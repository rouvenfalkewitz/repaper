/* Console API: everything a signed-in user does. Device-facing traffic lives in devices.ts. */
import type { FastifyInstance, FastifyRequest } from "fastify";
import {
  addEvent, claimDevice, deleteDevice, deviceEvents, findClaimable, getDevice, getOrg,
  getUserByEmail, orgDevices, renameDevice, type DeviceRow, type UserRow,
} from "./db.js";
import { COOKIE, endSession, loginAllowed, loginFailed, loginOk, requireUser, startSession, verifyPassword } from "./auth.js";
import { dropDevice, isOnline, sendToDevice } from "./devices.js";

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

  // everything below requires a session
  app.register(async (f) => {
    f.addHook("preHandler", requireUser);

    f.get("/api/fleet", async (req) => {
      const u = (req as Authed).user;
      const org = getOrg(u.org_id)!;
      return { org: org.name, user: { email: u.email, name: u.name }, devices: orgDevices(u.org_id).map(publicDevice) };
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
