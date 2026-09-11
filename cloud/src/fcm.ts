/* Firebase Cloud Messaging — HTTP v1, service-account auth (RS256 JWT → OAuth token →
 * messages:send). Wakes a closed RePaper Go Android app when a Print2Go job is waiting.
 * Node built-ins only (crypto + global fetch), so no runtime dependency to add.
 *
 * Config (env):
 *   FCM_SERVICE_ACCOUNT   base64 of the Firebase service-account JSON (the whole file)
 * The JSON supplies project_id, client_email and private_key.
 */
import { createSign } from "node:crypto";

type ServiceAccount = { project_id: string; client_email: string; private_key: string };

let sa: ServiceAccount | null = null;
try {
  if (process.env.FCM_SERVICE_ACCOUNT) sa = JSON.parse(Buffer.from(process.env.FCM_SERVICE_ACCOUNT, "base64").toString("utf8"));
} catch { sa = null; }

export const fcmConfigured = (): boolean => !!(sa && sa.private_key && sa.client_email && sa.project_id);

const b64url = (b: Buffer | string) =>
  (Buffer.isBuffer(b) ? b : Buffer.from(b)).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

/* Google OAuth access tokens last an hour; mint one and reuse it. */
let token = "";
let tokenExp = 0;
const accessToken = async (): Promise<string> => {
  const now = Math.floor(Date.now() / 1000);
  if (token && now < tokenExp - 120) return token;
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(JSON.stringify({
    iss: sa!.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now, exp: now + 3600,
  }));
  const signer = createSign("RSA-SHA256");
  signer.update(`${header}.${claims}`);
  const jwt = `${header}.${claims}.${b64url(signer.sign(sa!.private_key))}`;
  const resp = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`,
  });
  const j = (await resp.json()) as { access_token?: string; expires_in?: number; error?: string };
  if (!j.access_token) throw new Error(`fcm oauth failed: ${j.error ?? resp.status}`);
  token = j.access_token; tokenExp = now + (j.expires_in ?? 3600);
  return token;
};

export type FcmResult = { ok: boolean; status: number; reason?: string };

/** Send one notification. A 404/UNREGISTERED means the token is dead (drop it). */
export const sendFcm = async (
  fcmToken: string,
  alert: { title: string; body: string },
  log?: { warn: (s: string) => void },
): Promise<FcmResult> => {
  if (!fcmConfigured()) return { ok: false, status: 0, reason: "unconfigured" };
  try {
    const at = await accessToken();
    const resp = await fetch(`https://fcm.googleapis.com/v1/projects/${sa!.project_id}/messages:send`, {
      method: "POST",
      headers: { authorization: `Bearer ${at}`, "content-type": "application/json" },
      body: JSON.stringify({
        message: {
          token: fcmToken,
          notification: { title: alert.title, body: alert.body },
          data: { action: "print" },
          android: { priority: "high" },
        },
      }),
    });
    if (resp.ok) return { ok: true, status: resp.status };
    let reason: string | undefined;
    try { reason = ((await resp.json()) as { error?: { status?: string } }).error?.status; } catch {}
    return { ok: false, status: resp.status, reason };
  } catch (e) {
    log?.warn(`fcm: ${e}`);
    return { ok: false, status: 0, reason: "request" };
  }
};
