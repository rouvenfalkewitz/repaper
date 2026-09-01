import { randomBytes, scryptSync, timingSafeEqual, createHash } from "node:crypto";
import type { FastifyRequest, FastifyReply } from "fastify";
import { apiKeyByHash, createSession, deleteSession, getSession, getUser, type UserRow } from "./db.js";

export const hashPassword = (password: string): string => {
  const salt = randomBytes(16).toString("hex");
  return `scrypt$${salt}$${scryptSync(password, salt, 32).toString("hex")}`;
};

export const verifyPassword = (password: string, stored: string): boolean => {
  const [algo, salt, hash] = stored.split("$");
  if (algo !== "scrypt" || !salt || !hash) return false;
  return timingSafeEqual(scryptSync(password, salt, 32), Buffer.from(hash, "hex"));
};

/* Device secrets are 256-bit random strings, so a plain sha256 (not scrypt) is enough at rest. */
export const hashSecret = (secret: string) => createHash("sha256").update(secret).digest("hex");
export const secretMatches = (secret: string, storedHash: string) => {
  const a = createHash("sha256").update(secret).digest();
  const b = Buffer.from(storedHash, "hex");
  return a.length === b.length && timingSafeEqual(a, b);
};

export const COOKIE = "rp_session";

export const startSession = (userId: number): string => {
  const token = randomBytes(32).toString("hex");
  createSession(token, userId);
  return token;
};

export const endSession = (token: string) => deleteSession(token);

export const userFromRequest = (req: FastifyRequest): UserRow | undefined => {
  const token = req.cookies?.[COOKIE];
  if (!token) return undefined;
  const s = getSession(token);
  return s ? getUser(s.user_id) : undefined;
};

/* preHandler for console API routes. Sessions (cookies) get full access per their
   role; org API keys (Authorization: Bearer rpk_…) get read-only access. */
export const requireUser = (req: FastifyRequest, reply: FastifyReply, done: (err?: Error) => void) => {
  const bearer = req.headers.authorization;
  if (bearer?.startsWith("Bearer ")) {
    const k = apiKeyByHash(createHash("sha256").update(bearer.slice(7).trim()).digest("hex"));
    if (!k) { reply.code(401).send({ error: "unknown API key" }); return; }
    if (req.method !== "GET") { reply.code(403).send({ error: "API keys are read-only for now" }); return; }
    (req as FastifyRequest & { user: UserRow }).user = {
      id: -k.id, org_id: k.org_id, email: `key:${k.name}`, name: k.name, role: "api",
      pass_hash: "", created: k.created, avatar: null, totp_secret: null, totp_pending: null,
    };
    done(); return;
  }
  const user = userFromRequest(req);
  if (!user) { reply.code(401).send({ error: "not signed in" }); return; }
  (req as FastifyRequest & { user: UserRow }).user = user;
  done();
};

/* tiny in-memory brake on login attempts: 8 failures per IP per 15 minutes */
const fails = new Map<string, { n: number; t: number }>();
export const loginAllowed = (ip: string): boolean => {
  const f = fails.get(ip);
  if (!f || Date.now() - f.t > 15 * 60_000) return true;
  return f.n < 8;
};
export const loginFailed = (ip: string) => {
  const f = fails.get(ip);
  if (!f || Date.now() - f.t > 15 * 60_000) fails.set(ip, { n: 1, t: Date.now() });
  else f.n++;
};
export const loginOk = (ip: string) => fails.delete(ip);
