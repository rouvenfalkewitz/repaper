/* Apple Push Notification service — token-based (.p8 key, ES256 JWT).
 * Wakes a closed RePaper Go app when a Print2Go job is waiting. Uses Node's
 * built-in http2 + crypto, so there is no runtime dependency to add.
 *
 * Config (env):
 *   APNS_KEY_P8    base64 of the .p8 auth key file contents (the whole PEM)
 *   APNS_KEY_ID    the key's 10-char Key ID
 *   APNS_TEAM_ID   Apple Team ID (default 8DPLCLHB27)
 *   APNS_TOPIC     the app's bundle id (default net.repaper.go.ios)
 *   APNS_HOST      override the host; otherwise chosen per-device from its env
 *                  (sandbox for development builds, production otherwise)
 */
import { connect } from "node:http2";
import { createPrivateKey, sign as cryptoSign } from "node:crypto";

const KEY_P8 = process.env.APNS_KEY_P8 ? Buffer.from(process.env.APNS_KEY_P8, "base64").toString("utf8") : "";
const KEY_ID = process.env.APNS_KEY_ID || "";
const TEAM_ID = process.env.APNS_TEAM_ID || "8DPLCLHB27";
const TOPIC = process.env.APNS_TOPIC || "net.repaper.go.ios";
const HOST_OVERRIDE = process.env.APNS_HOST || "";

export const apnsConfigured = (): boolean => !!(KEY_P8 && KEY_ID);

const b64url = (b: Buffer) => b.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

/* the provider JWT is valid up to an hour; Apple wants it reused, not minted per push */
let cachedJwt = "";
let cachedAt = 0;
const providerJwt = (): string => {
  const now = Math.floor(Date.now() / 1000);
  if (cachedJwt && now - cachedAt < 50 * 60) return cachedJwt;
  const header = b64url(Buffer.from(JSON.stringify({ alg: "ES256", kid: KEY_ID })));
  const claims = b64url(Buffer.from(JSON.stringify({ iss: TEAM_ID, iat: now })));
  const signingInput = `${header}.${claims}`;
  const key = createPrivateKey(KEY_P8);
  // ES256 wants raw r||s (JOSE), not DER — dsaEncoding "ieee-p1363" gives that
  const sig = cryptoSign("sha256", Buffer.from(signingInput), { key, dsaEncoding: "ieee-p1363" });
  cachedJwt = `${signingInput}.${b64url(sig)}`;
  cachedAt = now;
  return cachedJwt;
};

const hostFor = (env: string | null): string =>
  HOST_OVERRIDE || (env === "production" ? "api.push.apple.com" : "api.sandbox.push.apple.com");

export type PushResult = { ok: boolean; status: number; reason?: string };

/** Send one alert push. Resolves with the APNs status (410 ⇒ token is dead). */
export const sendPush = (
  token: string,
  env: string | null,
  alert: { title: string; body: string },
  log?: { warn: (s: string) => void },
): Promise<PushResult> =>
  new Promise((resolve) => {
    if (!apnsConfigured()) return resolve({ ok: false, status: 0, reason: "unconfigured" });
    let jwt: string;
    try { jwt = providerJwt(); }
    catch (e) { log?.warn(`apns: bad key — ${e}`); return resolve({ ok: false, status: 0, reason: "bad-key" }); }

    const client = connect(`https://${hostFor(env)}`);
    const done = (r: PushResult) => { try { client.close(); } catch {} resolve(r); };
    client.on("error", (e) => { log?.warn(`apns: connect — ${e}`); done({ ok: false, status: 0, reason: "connect" }); });

    const body = JSON.stringify({ aps: { alert, sound: "default" }, action: "print" });
    const req = client.request({
      ":method": "POST",
      ":path": `/3/device/${token}`,
      "authorization": `bearer ${jwt}`,
      "apns-topic": TOPIC,
      "apns-push-type": "alert",
      "apns-priority": "10",
    });
    req.setTimeout(8000, () => done({ ok: false, status: 0, reason: "timeout" }));
    let status = 0, data = "";
    req.on("response", (h) => { status = Number(h[":status"]) || 0; });
    req.on("data", (c) => (data += c));
    req.on("end", () => {
      let reason: string | undefined;
      if (status !== 200) { try { reason = JSON.parse(data).reason; } catch {} }
      done({ ok: status === 200, status, reason });
    });
    req.on("error", (e) => { log?.warn(`apns: request — ${e}`); done({ ok: false, status: 0, reason: "request" }); });
    req.end(body);
  });
