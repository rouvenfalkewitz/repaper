/* Console API: everything a signed-in user does. Device-facing traffic lives in devices.ts. */
import { createHash, randomBytes } from "node:crypto";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import {
  addEvent, claimDevice, createInvite, createUser, deleteDevice, deleteUser, deviceEvents,
  findClaimable, getDevice, getOrg, getUser, getUserByEmail, inviteByTokenHash, markInviteUsed,
  orgDevices, orgInvites, orgUsers, pendingInviteFor, renameDevice, revokeInvite,
  type DeviceRow, type UserRow,
} from "./db.js";
import { COOKIE, endSession, hashPassword, loginAllowed, loginFailed, loginOk, requireUser, startSession, verifyPassword } from "./auth.js";
import { mailEnabled, sendInviteMail } from "./mail.js";
import { dropDevice, isOnline, sendToDevice } from "./devices.js";

const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

type Authed = FastifyRequest & { user: UserRow };

const publicDevice = (d: DeviceRow) => ({
  id: d.id, kind: d.kind, name: d.name, version: d.version,
  online: isOnline(d.id), last_seen: d.last_seen, claimed_at: d.claimed_at,
  status: JSON.parse(d.status || "{}"),
});

/* a device row must belong to the caller's org */
const ownDevice = (req: Authed): DeviceRow | undefined => {
  const d = getDevice((req.params as { id: string }).id);
  return d && d.org_id === (req as Authed).user.org_id ? d : undefined;
};

export const registerApi = (app: FastifyInstance) => {
  app.get("/api/health", async () => ({ status: "ok" }));

  app.post("/api/login", async (req, reply) => {
    const { email, password } = (req.body ?? {}) as { email?: string; password?: string };
    if (!loginAllowed(req.ip)) return reply.code(429).send({ error: "too many attempts — wait a few minutes" });
    const user = email && password ? getUserByEmail(email) : undefined;
    if (!user || !verifyPassword(password!, user.pass_hash)) {
      loginFailed(req.ip);
      return reply.code(401).send({ error: "wrong email or password" });
    }
    loginOk(req.ip);
    reply.setCookie(COOKIE, startSession(user.id), {
      path: "/", httpOnly: true, sameSite: "lax", secure: req.protocol === "https", maxAge: 30 * 86400,
    });
    return { ok: true };
  });

  app.post("/api/logout", async (req, reply) => {
    const token = req.cookies?.[COOKIE];
    if (token) endSession(token);
    reply.clearCookie(COOKIE, { path: "/" });
    return { ok: true };
  });

  // ── joining by invite (the only way in — there is no open signup) ────────
  app.get("/api/invites/lookup", async (req, reply) => {
    const token = String((req.query as { token?: string }).token ?? "");
    const inv = /^[a-f0-9]{64}$/.test(token) ? inviteByTokenHash(sha256(token)) : undefined;
    if (!inv) return reply.code(404).send({ error: "this invitation is no longer valid — ask for a fresh one" });
    return { email: inv.email, org: getOrg(inv.org_id)!.name };
  });

  app.post("/api/join", async (req, reply) => {
    const { token, name, password } = (req.body ?? {}) as { token?: string; name?: string; password?: string };
    const inv = token && /^[a-f0-9]{64}$/.test(token) ? inviteByTokenHash(sha256(token)) : undefined;
    if (!inv) return reply.code(404).send({ error: "this invitation is no longer valid — ask for a fresh one" });
    if (!password || password.length < 10) return reply.code(400).send({ error: "pick a password of at least 10 characters" });
    if (getUserByEmail(inv.email)) return reply.code(409).send({ error: "an account for this email already exists — sign in instead" });
    const r = createUser(inv.org_id, inv.email, String(name ?? "").trim().slice(0, 63), hashPassword(password), inv.role);
    markInviteUsed(inv.id);
    reply.setCookie(COOKIE, startSession(Number(r.lastInsertRowid)), {
      path: "/", httpOnly: true, sameSite: "lax", secure: req.protocol === "https", maxAge: 30 * 86400,
    });
    return { ok: true };
  });

  // everything below requires a session
  app.register(async (f) => {
    f.addHook("preHandler", requireUser);

    f.get("/api/fleet", async (req) => {
      const u = (req as Authed).user;
      const org = getOrg(u.org_id)!;
      return { org: org.name, user: { email: u.email, name: u.name, role: u.role }, devices: orgDevices(u.org_id).map(publicDevice) };
    });

    // ── team ───────────────────────────────────────────────────────────────
    const requireAdmin = (req: FastifyRequest, reply: FastifyReply): boolean => {
      if ((req as Authed).user.role === "admin") return true;
      reply.code(403).send({ error: "only admins can manage the team" });
      return false;
    };

    f.get("/api/team", async (req) => {
      const u = (req as Authed).user;
      return {
        users: orgUsers(u.org_id).map((x) => ({ ...x, you: x.id === u.id })),
        invites: u.role === "admin" ? orgInvites(u.org_id) : [],
        mail: mailEnabled,
      };
    });

    f.post("/api/invites", async (req, reply) => {
      if (!requireAdmin(req, reply)) return;
      const u = (req as Authed).user;
      const email = String(((req.body ?? {}) as { email?: string }).email ?? "").trim().toLowerCase();
      const role = ((req.body ?? {}) as { role?: string }).role === "admin" ? "admin" : "member";
      if (!EMAIL_RE.test(email)) return reply.code(400).send({ error: "that doesn't look like an email address" });
      if (getUserByEmail(email)) return reply.code(409).send({ error: "this person already has an account" });
      if (pendingInviteFor(u.org_id, email)) return reply.code(409).send({ error: "an invitation for this email is already pending — revoke it first for a fresh link" });
      const token = randomBytes(32).toString("hex");
      createInvite(u.org_id, email, role, sha256(token), u.id);
      const link = `${req.protocol}://${req.host}/join/${token}`;
      const org = getOrg(u.org_id)!;
      let mailed = false;
      try { await sendInviteMail(email, link, org.name, u.name || u.email); mailed = mailEnabled; }
      catch (e) { req.log.warn({ err: e }, "invite mail failed"); }
      return { ok: true, link, mailed };   // the link is shown once — only its hash is stored
    });

    f.post("/api/invites/:id/revoke", async (req, reply) => {
      if (!requireAdmin(req, reply)) return;
      const u = (req as Authed).user;
      const r = revokeInvite(Number((req.params as { id: string }).id), u.org_id);
      if (!r.changes) return reply.code(404).send({ error: "no such pending invitation" });
      return { ok: true };
    });

    f.post("/api/team/:id/remove", async (req, reply) => {
      if (!requireAdmin(req, reply)) return;
      const u = (req as Authed).user;
      const target = getUser(Number((req.params as { id: string }).id));
      if (!target || target.org_id !== u.org_id) return reply.code(404).send({ error: "no such member" });
      if (target.id === u.id) return reply.code(400).send({ error: "you can't remove yourself" });
      deleteUser(target.id);
      return { ok: true };
    });

    f.get("/api/devices/:id", async (req, reply) => {
      const d = ownDevice(req as Authed);
      if (!d) return reply.code(404).send({ error: "unknown device" });
      return { ...publicDevice(d), created: d.created, events: deviceEvents(d.id, 12) };
    });

    f.post("/api/devices/:id", async (req, reply) => {
      const d = ownDevice(req as Authed);
      if (!d) return reply.code(404).send({ error: "unknown device" });
      const name = String(((req.body ?? {}) as { name?: string }).name ?? "").trim();
      if (!name || name.length > 63) return reply.code(400).send({ error: "name must be 1–63 characters" });
      renameDevice(d.id, name);
      addEvent(d.id, "renamed", name);
      return { ok: true };
    });

    f.post("/api/devices/:id/identify", async (req, reply) => {
      const d = ownDevice(req as Authed);
      if (!d) return reply.code(404).send({ error: "unknown device" });
      if (!sendToDevice(d.id, { t: "identify" })) return reply.code(409).send({ error: "the device is offline right now" });
      addEvent(d.id, "identify");
      return { ok: true };
    });

    f.post("/api/devices/:id/remove", async (req, reply) => {
      const d = ownDevice(req as Authed);
      if (!d) return reply.code(404).send({ error: "unknown device" });
      deleteDevice(d.id);       // it can be claimed again any time — next hello re-registers it
      dropDevice(d.id);
      return { ok: true };
    });

    f.post("/api/claim", async (req, reply) => {
      const u = (req as Authed).user;
      const code = String(((req.body ?? {}) as { code?: string }).code ?? "").trim().toUpperCase().replace(/\s+/g, "");
      if (!/^[A-Z0-9]{4}-?[A-Z0-9]{4}$/.test(code)) return reply.code(400).send({ error: "a claim code looks like 3F9A-B2C1" });
      const normalized = code.includes("-") ? code : `${code.slice(0, 4)}-${code.slice(4)}`;
      const d = findClaimable(normalized);
      if (!d) return reply.code(404).send({ error: "no device with this code is waiting — is it connected to the cloud?" });
      claimDevice(d.id, u.org_id);
      const org = getOrg(u.org_id)!;
      addEvent(d.id, "claimed", org.name);
      sendToDevice(d.id, { t: "claimed", org: org.name });
      return { ok: true, device: publicDevice(getDevice(d.id)!) };
    });
  });
};
