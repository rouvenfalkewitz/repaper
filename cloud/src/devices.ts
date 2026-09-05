/* The device channel: one outbound WebSocket per device (Dock or Go), speaking the
   small protocol from docs/10 — hello, status, identify, claimed. Devices register
   themselves on first hello (trust on first connect) and stay unclaimed until a
   signed-in user enters their claim code in the console. */
import type { WebSocket } from "ws";
import { hashSecret, secretMatches } from "./auth.js";
import { addEvent, getDevice, getOrg, registerDevice, saveDeviceStatus, saveDiag, touchDevice, upsertStat } from "./db.js";

const live = new Map<string, WebSocket>(); // device id → open socket

export const isOnline = (id: string) => live.has(id);
export const onlineCount = () => live.size;

/* Push a message to a connected device; false if it is offline. */
export const sendToDevice = (id: string, msg: object): boolean => {
  const ws = live.get(id);
  if (!ws || ws.readyState !== ws.OPEN) return false;
  ws.send(JSON.stringify(msg));
  return true;
};

export const dropDevice = (id: string) => live.get(id)?.close(4001, "removed");

type Hello = { t: "hello"; id: string; secret: string; claim: string; kind: string; name: string; version: string };

const ID_RE = /^[a-f0-9-]{8,64}$/;
const CLAIM_RE = /^[A-Z0-9]{4}-[A-Z0-9]{4}$/;

export const handleDeviceSocket = (ws: WebSocket, remote: string) => {
  let deviceId: string | null = null;

  const refuse = (error: string) => { ws.send(JSON.stringify({ t: "error", error })); ws.close(4000, error); };
  const helloDeadline = setTimeout(() => refuse("no hello"), 15_000);

  ws.on("message", (raw: Buffer) => {
    let msg: Record<string, unknown>;
    try { msg = JSON.parse(raw.toString()); } catch { return refuse("not json"); }

    if (!deviceId) {
      clearTimeout(helloDeadline);
      const h = msg as unknown as Hello;
      if (h.t !== "hello" || typeof h.id !== "string" || typeof h.secret !== "string") return refuse("hello first");
      if (!ID_RE.test(h.id) || !["dock", "go"].includes(h.kind)) return refuse("bad hello");
      const known = getDevice(h.id);
      if (known) {
        if (!secretMatches(h.secret, known.secret_hash)) {
          console.warn(`device ${h.id} from ${remote}: wrong secret`);
          return refuse("auth");
        }
        if (known.version && h.version && known.version !== String(h.version))
          addEvent(h.id, "updated", `${known.version} → ${h.version}`);
        touchDevice(h.id, String(h.version ?? ""));
      } else {
        if (!CLAIM_RE.test(String(h.claim ?? ""))) return refuse("bad claim code");
        registerDevice(h.id, h.kind, String(h.name ?? "").slice(0, 63), hashSecret(h.secret), h.claim, String(h.version ?? ""));
        addEvent(h.id, "registered", remote);
        console.log(`device ${h.id} (${h.kind}) registered from ${remote} — unclaimed, code ${h.claim}`);
      }
      deviceId = h.id;
      live.get(deviceId)?.close(4002, "replaced"); // a reconnect supersedes a stale socket
      live.set(deviceId, ws);
      const d = getDevice(deviceId)!;
      const org = d.org_id ? getOrg(d.org_id) : undefined;
      ws.send(JSON.stringify({ t: "hello_ok", claimed: !!d.org_id, org: org?.name ?? null }));
      addEvent(deviceId, "online");
      return;
    }

    if (msg.t === "status") {
      const { t: _t, ...status } = msg;
      saveDeviceStatus(deviceId, JSON.stringify(status).slice(0, 256 * 1024));
      if (typeof status.jobs_today === "number")
        upsertStat(deviceId, new Date().toISOString().slice(0, 10), status.jobs_today);
    }
    if (msg.t === "diag") {
      saveDiag(deviceId, String(msg.log ?? "").slice(0, 64 * 1024));
      addEvent(deviceId, "diagnostics");
    }
    if (msg.t === "updating") addEvent(deviceId, "update_started", String(msg.version ?? ""));
    if (msg.t === "update_failed") addEvent(deviceId, "update_failed", String(msg.error ?? "").slice(0, 200));
  });

  ws.on("close", () => {
    clearTimeout(helloDeadline);
    if (deviceId && live.get(deviceId) === ws) {
      live.delete(deviceId);
      touchDevice(deviceId);
      addEvent(deviceId, "offline");
    }
  });
  ws.on("error", () => { /* close follows */ });
};
