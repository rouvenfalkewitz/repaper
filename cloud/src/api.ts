/* Console API: everything a signed-in user does. Device-facing traffic lives in devices.ts. */
import { createHash, randomBytes } from "node:crypto";
import { copyFileSync, createReadStream, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import QRCode from "qrcode";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { generateSecret, otpauthUrl, totpCheck } from "./totp.js";
import {
  addEvent, addOrgEvent, addRecoveryCodes, anyOrgAdmin, bumpLoginPending, claimDevice, createApiKey,
  createInvite, createLoginPending, createOrg, createReset, createUser, deleteDevice, deleteLoginPending,
  deleteOtherSessions, deleteSessionsFor, deleteUser, deviceEvents, deviceStats, disableTotp,
  enableTotp, findClaimable, firstAdmin, getDevice, getOrg, getOrgByName, getUser, getUserByEmail,
  inviteByTokenHash, loginPendingByToken, markInviteUsed, markResetUsed, orgActivity, orgEvents,
  orgDevices, orgInvites, orgUsers, pendingInviteFor, publicCounts, recentResetFor, renameDevice, renameOrg,
  resetByTokenHash, revokeApiKey, revokeInvite, setDeviceSite, setTotpPending, setUserAvatar,
  createRelease, getRelease, latestRelease, listReleases,
  setOrgLogo, setUserName, setUserRole, updateCompany, updatePassword, useRecoveryCode, userApiKeys,
  COMPANY_FIELDS, DATA_DIR, type DeviceRow, type UserRow,
} from "./db.js";
import { COOKIE, endSession, hashPassword, loginAllowed, loginFailed, loginOk, requireUser, startSession, verifyPassword } from "./auth.js";
import { mailEnabled, sendInviteMail, sendRegisterMail, sendResetMail } from "./mail/index.js";
import { dropDevice, isOnline, onlineCount, sendToDevice } from "./devices.js";

const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

type Authed = FastifyRequest & { user: UserRow };

const publicDevice = (d: DeviceRow) => ({
  id: d.id, kind: d.kind, name: d.name, version: d.version, site: d.site,
  online: isOnline(d.id), last_seen: d.last_seen, claimed_at: d.claimed_at,
  status: JSON.parse(d.status || "{}"),
  stats: deviceStats(d.id).reverse(),
});

const LOW_MV = 2700;
const orgAlerts = (orgId: number) => {
  const out: { level: string; title: string; text: string; device_id: string }[] = [];
  const now = Date.now() / 1000;
  for (const d of orgDevices(orgId)) {
    const st = JSON.parse(d.status || "{}");
    const name = d.name || st.printer || d.id;
    if (!isOnline(d.id) && d.last_seen && now - d.last_seen > 600)
      out.push({ level: "warn", title: `${name} is offline`, text: `Last heard ${Math.round((now - d.last_seen) / 60)} min ago. Printing on site still works — the cloud just can't see it.`, device_id: d.id });
    for (const s of st.sheets || [])
      if (s.battery_volts != null && s.battery_volts * 1000 < LOW_MV)
        out.push({ level: "err", title: `${s.name || s.id} on ${name}: battery low (${s.battery_volts.toFixed(2)} V)`, text: "The Dock refuses to print to it until the cell is replaced — a refresh on a weak cell can leave the sheet half-drawn.", device_id: d.id });
  }
  return out;
};

/* releases live as tarballs next to the database; devices fetch them with
   short-lived tokens minted when an update is pushed over the socket */
const RELEASES_DIR = join(DATA_DIR, "releases");
mkdirSync(RELEASES_DIR, { recursive: true });
const dlTokens = new Map<string, { version: string; expires: number }>();
const publicBase = () => (process.env.DOMAIN ? `https://${process.env.DOMAIN}` : `http://localhost:${process.env.PORT || 3000}`);
const VENDOR_ORG = 1;   // pilot: the RePaper org uploads releases; a proper vendor role comes later

/* Releases publish themselves: the Docker image bundles the Dock software at the
   version in the repo, and startup registers it if the store doesn't have it yet.
   The console upload stays as a manual fallback. */
export const publishBundledRelease = (log: { info: (s: string) => void; warn: (s: string) => void }) => {
  const bundled = join(dirname(fileURLToPath(import.meta.url)), "..", "bundled");
  try {
    if (!existsSync(join(bundled, "VERSION"))) return;
    const version = readFileSync(join(bundled, "VERSION"), "utf8").trim();
    if (!/^\d+\.\d+\.\d+$/.test(version) || getRelease(version)) return;
    const buf = readFileSync(join(bundled, "dock-release.tar.gz"));
    copyFileSync(join(bundled, "dock-release.tar.gz"), join(RELEASES_DIR, `${version}.tar.gz`));
    let notes = "Published automatically with the cloud deploy.";
    try { notes = readFileSync(join(bundled, "RELEASE_NOTES.md"), "utf8").split("\n")[0].replace(/^[\d.]+\s*[—-]\s*/, "").trim() || notes; } catch {}
    createRelease(version, "stable", notes, createHash("sha256").update(buf).digest("hex"), buf.length);
    log.info(`bundled Dock release ${version} published`);
  } catch (e) {
    log.warn(`bundled release publish failed: ${e}`);
  }
};

const pushUpdate = (deviceId: string, version: string): boolean => {
  const rel = getRelease(version);
  if (!rel) return false;
  const token = randomBytes(24).toString("hex");
  dlTokens.set(token, { version, expires: Date.now() + 10 * 60_000 });
  return sendToDevice(deviceId, { t: "update", version: rel.version, sha256: rel.sha256, url: `${publicBase()}/dl/${token}` });
};

/* a device row must belong to the caller's org */
const ownDevice = (req: Authed): DeviceRow | undefined => {
  const d = getDevice((req.params as { id: string }).id);
  return d && d.org_id === (req as Authed).user.org_id ? d : undefined;
};

export const registerApi = (app: FastifyInstance) => {
  app.get("/api/health", async () => ({ status: "ok" }));

  /* devices download release tarballs with a short-lived token (auth happened on the socket) */
  app.get("/dl/:token", async (req, reply) => {
    const t = dlTokens.get((req.params as { token: string }).token);
    if (!t || t.expires < Date.now()) return reply.code(404).send({ error: "expired" });
    const rel = getRelease(t.version)!;
    return reply.header("Content-Type", "application/gzip").header("Content-Length", String(rel.size))
      .send(createReadStream(join(RELEASES_DIR, `${rel.version}.tar.gz`)));
  });

  /* aggregate numbers for the public status page — never per-org detail */
  app.get("/api/public-status", async () => {
    const c = publicCounts();
    return { status: "operational", devices_online: onlineCount(), devices: c.devices, orgs: c.orgs };
  });

  app.post("/api/login", async (req, reply) => {
    const { email, password } = (req.body ?? {}) as { email?: string; password?: string };
    if (!loginAllowed(req.ip)) return reply.code(429).send({ error: "too many attempts — wait a few minutes" });
    const user = email && password ? getUserByEmail(email) : undefined;
    if (!user || !verifyPassword(password!, user.pass_hash)) {
      loginFailed(req.ip);
      return reply.code(401).send({ error: "wrong email or password" });
    }
    loginOk(req.ip);
    if (user.totp_secret) {
      // password verified — park the login until the authenticator code arrives
      const t = randomBytes(32).toString("hex");
      createLoginPending(t, user.id);
      reply.setCookie("rp_pending", t, { path: "/", httpOnly: true, sameSite: "lax", secure: req.protocol === "https", maxAge: 300 });
      return { twofa: true };
    }
    reply.setCookie(COOKIE, startSession(user.id), {
      path: "/", httpOnly: true, sameSite: "lax", secure: req.protocol === "https", maxAge: 30 * 86400,
    });
    return { ok: true };
  });

  app.post("/api/login/2fa", async (req, reply) => {
    const t = req.cookies?.rp_pending;
    const pending = t ? loginPendingByToken(t) : undefined;
    if (!pending) return reply.code(401).send({ error: "sign in again — the code window expired" });
    if (pending.attempts >= 6) { deleteLoginPending(pending.token); return reply.code(429).send({ error: "too many wrong codes — sign in again" }); }
    const user = getUser(pending.user_id)!;
    const code = String(((req.body ?? {}) as { code?: string }).code ?? "").trim();
    const recovery = code.replace(/-/g, "").toLowerCase();
    const ok = (user.totp_secret && totpCheck(user.totp_secret, code))
      || (/^[a-f0-9]{10}$/.test(recovery) && useRecoveryCode(user.id, sha256(recovery)));
    if (!ok) { bumpLoginPending(pending.token); return reply.code(401).send({ error: "that code didn't match" }); }
    deleteLoginPending(pending.token);
    reply.clearCookie("rp_pending", { path: "/" });
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

  // ── self-registration, whitelist-gated. The reply never reveals whether an
  //    address is on the list; non-listed addresses simply produce nothing. ──
  const WHITELIST = (process.env.REGISTER_WHITELIST || "").split(",").map((s) => s.trim().toLowerCase()).filter(Boolean);
  const whitelisted = (email: string) => WHITELIST.some((w) => (w.startsWith("@") ? email.endsWith(w) : email === w));
  const regHits = new Map<string, { n: number; t: number }>();

  app.post("/api/register", async (req, reply) => {
    const hit = regHits.get(req.ip);
    if (hit && Date.now() - hit.t < 15 * 60_000 && hit.n >= 5) return reply.code(429).send({ error: "too many attempts — try again in a few minutes" });
    regHits.set(req.ip, !hit || Date.now() - hit.t > 15 * 60_000 ? { n: 1, t: Date.now() } : { n: hit.n + 1, t: hit.t });
    const email = String(((req.body ?? {}) as { email?: string }).email ?? "").trim().toLowerCase();
    if (!EMAIL_RE.test(email)) return reply.code(400).send({ error: "that doesn't look like an email address" });
    const neutral = { ok: true };
    if (!whitelisted(email) || getUserByEmail(email)) return neutral;
    // registration starts a personal workspace of one's own; joining someone
    // else's org is what invitations are for. The placeholder org/inviter only
    // satisfy the FKs — the real org is created when the link is used.
    const anchor = anyOrgAdmin();
    if (!anchor) { req.log.warn("register: no admin exists to anchor self-registrations"); return neutral; }
    if (pendingInviteFor(anchor.org_id, email)) return neutral;
    const token = randomBytes(32).toString("hex");
    createInvite(anchor.org_id, email, "admin", sha256(token), anchor.id, 14, true);
    try { await sendRegisterMail(email, `${req.protocol}://${req.host}/join/${token}`); }
    catch (e) { req.log.warn({ err: e }, "register mail failed"); }
    return neutral;
  });

  // ── forgot password: neutral reply, one-time link, 2 h, old sessions die ─
  app.post("/api/reset-request", async (req, reply) => {
    const hit = regHits.get("r:" + req.ip);
    if (hit && Date.now() - hit.t < 15 * 60_000 && hit.n >= 5) return reply.code(429).send({ error: "too many attempts — try again in a few minutes" });
    regHits.set("r:" + req.ip, !hit || Date.now() - hit.t > 15 * 60_000 ? { n: 1, t: Date.now() } : { n: hit.n + 1, t: hit.t });
    const email = String(((req.body ?? {}) as { email?: string }).email ?? "").trim().toLowerCase();
    if (!EMAIL_RE.test(email)) return reply.code(400).send({ error: "that doesn't look like an email address" });
    const user = getUserByEmail(email);
    if (!user || recentResetFor(user.id)) return { ok: true };
    const token = randomBytes(32).toString("hex");
    createReset(user.id, sha256(token));
    try { await sendResetMail(email, `${req.protocol}://${req.host}/reset/${token}`); }
    catch (e) { req.log.warn({ err: e }, "reset mail failed"); }
    return { ok: true };
  });

  app.get("/api/reset/lookup", async (req, reply) => {
    const token = String((req.query as { token?: string }).token ?? "");
    const r = /^[a-f0-9]{64}$/.test(token) ? resetByTokenHash(sha256(token)) : undefined;
    if (!r) return reply.code(404).send({ error: "this reset link is no longer valid — request a fresh one" });
    return { email: getUser(r.user_id)!.email };
  });

  app.post("/api/reset", async (req, reply) => {
    const { token, password } = (req.body ?? {}) as { token?: string; password?: string };
    const r = token && /^[a-f0-9]{64}$/.test(token) ? resetByTokenHash(sha256(token)) : undefined;
    if (!r) return reply.code(404).send({ error: "this reset link is no longer valid — request a fresh one" });
    if (!password || password.length < 10) return reply.code(400).send({ error: "pick a password of at least 10 characters" });
    updatePassword(r.user_id, hashPassword(password));
    markResetUsed(r.id);
    deleteSessionsFor(r.user_id);   // whoever held the old password is out
    reply.setCookie(COOKIE, startSession(r.user_id), {
      path: "/", httpOnly: true, sameSite: "lax", secure: req.protocol === "https", maxAge: 30 * 86400,
    });
    return { ok: true };
  });

  // ── joining by invite (the only way in — there is no open signup) ────────
  app.get("/api/invites/lookup", async (req, reply) => {
    const token = String((req.query as { token?: string }).token ?? "");
    const inv = /^[a-f0-9]{64}$/.test(token) ? inviteByTokenHash(sha256(token)) : undefined;
    if (!inv) return reply.code(404).send({ error: "this invitation is no longer valid — ask for a fresh one" });
    return { email: inv.email, org: inv.personal ? null : getOrg(inv.org_id)!.name };
  });

  app.post("/api/join", async (req, reply) => {
    const { token, name, password } = (req.body ?? {}) as { token?: string; name?: string; password?: string };
    const inv = token && /^[a-f0-9]{64}$/.test(token) ? inviteByTokenHash(sha256(token)) : undefined;
    if (!inv) return reply.code(404).send({ error: "this invitation is no longer valid — ask for a fresh one" });
    if (!password || password.length < 10) return reply.code(400).send({ error: "pick a password of at least 10 characters" });
    if (getUserByEmail(inv.email)) return reply.code(409).send({ error: "an account for this email already exists — sign in instead" });
    // a personal registration gets its own fresh workspace, and its owner is its admin
    const orgId = inv.personal ? createOrg(String(name ?? "").trim() || inv.email.split("@")[0]).id : inv.org_id;
    const r = createUser(orgId, inv.email, String(name ?? "").trim().slice(0, 63), hashPassword(password), inv.personal ? "admin" : inv.role);
    markInviteUsed(inv.id);
    if (!inv.personal) addOrgEvent(orgId, "joined", "", inv.email);
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

    // ── account ────────────────────────────────────────────────────────────
    const isPersonal = (orgId: number) => orgUsers(orgId).length === 1 && orgInvites(orgId).length === 0;
    const me = (u: UserRow) => {
      const org = getOrg(u.org_id)!;
      return {
        email: u.email, name: u.name, role: u.role, org: org.name, org_logo: org.logo,
        personal: isPersonal(u.org_id), vendor: u.org_id === VENDOR_ORG && u.role === "admin",
        avatar: u.avatar, twofa: !!u.totp_secret, mail: mailEnabled,
      };
    };

    f.get("/api/me", async (req) => me((req as Authed).user));

    f.post("/api/account", async (req, reply) => {
      const u = (req as Authed).user;
      const name = String(((req.body ?? {}) as { name?: string }).name ?? "").trim();
      if (name.length > 63) return reply.code(400).send({ error: "name must be at most 63 characters" });
      setUserName(u.id, name);
      return { ok: true };
    });

    f.post("/api/account/avatar", async (req, reply) => {
      const u = (req as Authed).user;
      const avatar = ((req.body ?? {}) as { avatar?: string | null }).avatar ?? null;
      if (avatar !== null) {
        if (typeof avatar !== "string" || !/^data:image\/(png|jpeg);base64,[A-Za-z0-9+/=]+$/.test(avatar))
          return reply.code(400).send({ error: "avatar must be a PNG or JPEG" });
        if (avatar.length > 64 * 1024) return reply.code(400).send({ error: "avatar too large" });
      }
      setUserAvatar(u.id, avatar);
      return { ok: true };
    });

    f.post("/api/account/password", async (req, reply) => {
      const u = (req as Authed).user;
      const { current, next } = (req.body ?? {}) as { current?: string; next?: string };
      if (!current || !verifyPassword(current, u.pass_hash)) return reply.code(401).send({ error: "the current password is wrong" });
      if (!next || next.length < 10) return reply.code(400).send({ error: "pick a password of at least 10 characters" });
      updatePassword(u.id, hashPassword(next));
      deleteOtherSessions(u.id, req.cookies![COOKIE]!);   // every other device is signed out
      return { ok: true };
    });

    // ── two-factor auth (TOTP) ─────────────────────────────────────────────
    f.post("/api/2fa/setup", async (req, reply) => {
      const u = (req as Authed).user;
      if (u.totp_secret) return reply.code(409).send({ error: "two-factor auth is already on" });
      const secret = generateSecret();
      setTotpPending(u.id, secret);
      const url = otpauthUrl(secret, u.email);
      return { secret, qr: await QRCode.toDataURL(url, { margin: 1, width: 220 }) };
    });

    f.post("/api/2fa/enable", async (req, reply) => {
      const u = (req as Authed).user;
      const fresh = getUser(u.id)!;
      const code = String(((req.body ?? {}) as { code?: string }).code ?? "");
      if (!fresh.totp_pending) return reply.code(400).send({ error: "start the setup first" });
      if (!totpCheck(fresh.totp_pending, code)) return reply.code(401).send({ error: "that code didn't match — check the app and try again" });
      enableTotp(u.id, fresh.totp_pending);
      const codes = Array.from({ length: 8 }, () => randomBytes(5).toString("hex"));
      addRecoveryCodes(u.id, codes.map(sha256));
      return { ok: true, codes: codes.map((c) => `${c.slice(0, 5)}-${c.slice(5)}`) };   // shown exactly once
    });

    f.post("/api/2fa/disable", async (req, reply) => {
      const u = (req as Authed).user;
      const { password, code } = (req.body ?? {}) as { password?: string; code?: string };
      if (!u.totp_secret) return reply.code(400).send({ error: "two-factor auth is not on" });
      if (!password || !verifyPassword(password, u.pass_hash)) return reply.code(401).send({ error: "the password is wrong" });
      if (!code || !totpCheck(u.totp_secret, code)) return reply.code(401).send({ error: "that code didn't match" });
      disableTotp(u.id);
      return { ok: true };
    });

    // ── organisation ───────────────────────────────────────────────────────
    f.get("/api/org", async (req) => {
      const u = (req as Authed).user;
      const org = getOrg(u.org_id)!;
      const company: Record<string, string | null> = {};
      for (const fld of COMPANY_FIELDS) company[fld] = org[fld];
      return { name: org.name, created: org.created, logo: org.logo, members: orgUsers(u.org_id).length,
               devices: orgDevices(u.org_id).length, personal: isPersonal(u.org_id), company };
    });

    f.post("/api/org", async (req, reply) => {
      if (!requireAdmin(req, reply)) return;
      const u = (req as Authed).user;
      const body = (req.body ?? {}) as Record<string, unknown>;
      if (body.name !== undefined) {
        const name = String(body.name).trim();
        if (!name || name.length > 63) return reply.code(400).send({ error: "name must be 1–63 characters" });
        if (name !== getOrg(u.org_id)!.name) addOrgEvent(u.org_id, "org_renamed", u.name || u.email, name);
        renameOrg(u.org_id, name);
      }
      if (body.logo !== undefined) {
        const logo = body.logo as string | null;
        if (logo !== null) {
          if (typeof logo !== "string" || !/^data:image\/(png|jpeg);base64,[A-Za-z0-9+/=]+$/.test(logo))
            return reply.code(400).send({ error: "the logo must be a PNG or JPEG" });
          if (logo.length > 96 * 1024) return reply.code(400).send({ error: "logo too large" });
        }
        setOrgLogo(u.org_id, logo);
      }
      if (body.company && typeof body.company === "object") {
        const values: Record<string, string | null> = {};
        for (const fld of COMPANY_FIELDS) {
          const v = (body.company as Record<string, unknown>)[fld];
          if (v === undefined) continue;
          const s = String(v ?? "").trim().slice(0, 120);
          values[fld] = s || null;
        }
        if (values.billing_email && !EMAIL_RE.test(values.billing_email))
          return reply.code(400).send({ error: "the billing email doesn't look like an email address" });
        updateCompany(u.org_id, values);
      }
      return { ok: true };
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
      addOrgEvent(u.org_id, "invited", u.name || u.email, email);
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

    f.post("/api/team/:id/role", async (req, reply) => {
      if (!requireAdmin(req, reply)) return;
      const u = (req as Authed).user;
      const target = getUser(Number((req.params as { id: string }).id));
      const role = ((req.body ?? {}) as { role?: string }).role;
      if (!target || target.org_id !== u.org_id) return reply.code(404).send({ error: "no such member" });
      if (target.id === u.id) return reply.code(400).send({ error: "you can't change your own role" });
      if (role !== "admin" && role !== "member") return reply.code(400).send({ error: "role must be admin or member" });
      setUserRole(target.id, role);
      addOrgEvent(u.org_id, "role_changed", u.name || u.email, `${target.email} is now ${role}`);
      return { ok: true };
    });

    // ── API keys (personal: a key acts as its owner, read-only) ────────────
    f.get("/api/keys", async (req) => ({ keys: userApiKeys((req as Authed).user.id) }));

    f.post("/api/keys", async (req, reply) => {
      const u = (req as Authed).user;
      if (u.role === "api") return reply.code(403).send({ error: "a key can't mint keys" });
      const name = String(((req.body ?? {}) as { name?: string }).name ?? "").trim();
      if (!name || name.length > 40) return reply.code(400).send({ error: "give the key a short name (what will use it?)" });
      const token = "rpk_" + randomBytes(24).toString("hex");
      createApiKey(u.org_id, u.id, name, sha256(token));
      return { ok: true, token };   // shown exactly once — only the hash is stored
    });

    f.post("/api/keys/:id/revoke", async (req, reply) => {
      const r = revokeApiKey(Number((req.params as { id: string }).id), (req as Authed).user.id);
      if (!r.changes) return reply.code(404).send({ error: "no such key" });
      return { ok: true };
    });

    f.post("/api/team/:id/remove", async (req, reply) => {
      if (!requireAdmin(req, reply)) return;
      const u = (req as Authed).user;
      const target = getUser(Number((req.params as { id: string }).id));
      if (!target || target.org_id !== u.org_id) return reply.code(404).send({ error: "no such member" });
      if (target.id === u.id) return reply.code(400).send({ error: "you can't remove yourself" });
      deleteUser(target.id);
      addOrgEvent(u.org_id, "member_removed", u.name || u.email, target.email);
      return { ok: true };
    });

    f.get("/api/devices/:id", async (req, reply) => {
      const d = ownDevice(req as Authed);
      if (!d) return reply.code(404).send({ error: "unknown device" });
      return { ...publicDevice(d), created: d.created, events: deviceEvents(d.id, 12),
               diag: d.diag_at ? { at: d.diag_at, log: d.diag } : null };
    });

    f.post("/api/devices/:id", async (req, reply) => {
      const d = ownDevice(req as Authed);
      if (!d) return reply.code(404).send({ error: "unknown device" });
      const body = (req.body ?? {}) as { name?: string; site?: string };
      if (body.name !== undefined) {
        const name = String(body.name).trim();
        if (!name || name.length > 63) return reply.code(400).send({ error: "name must be 1–63 characters" });
        renameDevice(d.id, name);
        addEvent(d.id, "renamed", name);
      }
      if (body.site !== undefined) {
        const site = String(body.site).trim().slice(0, 40);
        setDeviceSite(d.id, site || null);
      }
      return { ok: true };
    });

    f.post("/api/devices/:id/diag", async (req, reply) => {
      const d = ownDevice(req as Authed);
      if (!d) return reply.code(404).send({ error: "unknown device" });
      if (!sendToDevice(d.id, { t: "diag" })) return reply.code(409).send({ error: "the device is offline right now" });
      return { ok: true };   // the bundle arrives asynchronously; details show it once it lands
    });

    f.get("/api/alerts", async (req) => ({ alerts: orgAlerts((req as Authed).user.org_id) }));

    // ── software releases & OTA ────────────────────────────────────────────
    f.get("/api/releases", async () => ({ releases: listReleases(), latest: latestRelease()?.version ?? null }));

    f.post("/api/releases", { bodyLimit: 80 * 1024 * 1024 }, async (req, reply) => {
      const u = (req as Authed).user;
      if (u.org_id !== VENDOR_ORG || u.role !== "admin") return reply.code(403).send({ error: "only the vendor publishes releases" });
      const { version, channel, notes, data } = (req.body ?? {}) as { version?: string; channel?: string; notes?: string; data?: string };
      if (!version || !/^\d+\.\d+\.\d+$/.test(version)) return reply.code(400).send({ error: "version must look like 0.0.2" });
      if (getRelease(version)) return reply.code(409).send({ error: "this version already exists — bump it" });
      if (!data) return reply.code(400).send({ error: "no file attached" });
      const buf = Buffer.from(data, "base64");
      if (buf.length < 1024) return reply.code(400).send({ error: "that file looks too small to be a release" });
      writeFileSync(join(RELEASES_DIR, `${version}.tar.gz`), buf);
      const hash = createHash("sha256").update(buf).digest("hex");
      createRelease(version, channel === "beta" ? "beta" : "stable", String(notes ?? "").slice(0, 2000), hash, buf.length);
      return { ok: true, version, size: buf.length };
    });

    f.post("/api/devices/:id/update", async (req, reply) => {
      const d = ownDevice(req as Authed);
      if (!d) return reply.code(404).send({ error: "unknown device" });
      const version = String(((req.body ?? {}) as { version?: string }).version ?? "") || latestRelease()?.version;
      if (!version || !getRelease(version)) return reply.code(404).send({ error: "no such release" });
      if (!pushUpdate(d.id, version)) return reply.code(409).send({ error: "the device is offline right now" });
      return { ok: true, version };
    });

    f.post("/api/fleet/update", async (req, reply) => {
      const u = (req as Authed).user;
      const version = String(((req.body ?? {}) as { version?: string }).version ?? "") || latestRelease()?.version;
      if (!version || !getRelease(version)) return reply.code(404).send({ error: "no such release" });
      let sent = 0;
      for (const d of orgDevices(u.org_id))
        if (d.kind === "dock" && isOnline(d.id) && d.version !== version && pushUpdate(d.id, version)) sent++;
      return { ok: true, version, sent };
    });

    f.get("/api/sheets", async (req) => {
      const out: object[] = [];
      for (const d of orgDevices((req as Authed).user.org_id)) {
        const st = JSON.parse(d.status || "{}");
        for (const s of st.sheets || [])
          out.push({ ...s, device: d.name || st.printer || d.id, device_id: d.id, device_online: isOnline(d.id) });
      }
      return { sheets: out };
    });

    f.get("/api/activity", async (req) => {
      const orgId = (req as Authed).user.org_id;
      const dev = orgActivity(orgId).map((e) => ({ ...e, kind: "device" as const }));
      const org = orgEvents(orgId).map((e) => ({ ...e, kind: "org" as const }));
      return { activity: [...dev, ...org].sort((a, b) => b.at - a.at).slice(0, 60) };
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
