/* The device channel: one outbound WebSocket per device (Dock or Go), speaking the
   small protocol from docs/10 — hello, status, identify, claimed. Devices register
   themselves on first hello (trust on first connect) and stay unclaimed until a
   signed-in user enters their claim code in the console. */
import type { WebSocket } from "ws";
import { hashSecret, secretMatches } from "./auth.js";
import { addEvent, deviceLabel, dockSheets, getDevice, getOrg, mirrorJobsClaimedBy, mirrorPhones, openMirrorJobsFor, registerDevice, releaseMirrorJob, saveDeviceStatus, saveDiag, setDeviceKind, setDevicePlatform, setDockSheetNfc, setPushToken, setTargetVersion, syncDockSheets, touchDevice, upsertStat } from "./db.js";
import type { MirrorJobRow } from "./db.js";

const live = new Map<string, WebSocket>(); // device id → open socket
const alive = new WeakMap<WebSocket, boolean>();
const lastPong = new WeakMap<WebSocket, number>(); // when the socket last proved itself alive

/* the server pings every device socket; a missed pong means the link is dead
   and the map must not lie about it */
setInterval(() => {
  for (const [id, ws] of live) {
    if (alive.get(ws) === false) { live.delete(id); try { ws.terminate(); } catch {} continue; }
    alive.set(ws, false);
    try { ws.ping(); } catch {}
  }
}, 30_000);

/* update offers converge: whoever knows the device's target re-offers it until
   the device reports that version. Set by the API, re-sent on hello + status. */
export let offerUpdate: (deviceId: string) => void = () => {};
export const setUpdateOffer = (fn: (deviceId: string) => void) => { offerUpdate = fn; };

export const isOnline = (id: string) => live.has(id);
export const onlineCount = () => live.size;

/* "Online" is not the same as "recently proven alive": a backgrounded phone or a dead
   radio keeps a socket OPEN until a ping goes unanswered (~30-60s). A caller that must
   choose between a silent socket delivery and a push should treat only a FRESH socket as
   reliably reachable, and fall back to push otherwise. */
export const isFresh = (id: string): boolean => {
  const ws = live.get(id);
  return !!ws && ws.readyState === ws.OPEN && Date.now() - (lastPong.get(ws) ?? 0) < 40_000;
};

/* Re-offer a job to the pool — set by the API so the close handler can put a job a
   disconnecting device was holding back into play (H2). */
export let offerJobToPool: (j: MirrorJobRow) => void = () => {};
export const setJobOffer = (fn: (j: MirrorJobRow) => void) => { offerJobToPool = fn; };

/* Push a message to a connected device; false if it is offline. */
export const sendToDevice = (id: string, msg: object): boolean => {
  const ws = live.get(id);
  if (!ws || ws.readyState !== ws.OPEN) return false;
  ws.send(JSON.stringify(msg));
  return true;
};

/* Evict a device NOW: remove it from the live map synchronously (so a later async close
   event on this socket becomes a no-op), then close the socket. Removing the map entry
   up front is what stops a since-deleted device's close handler from touching a row that
   no longer exists (which would throw an uncaught FK error and take the process down). */
export const dropDevice = (id: string) => {
  const ws = live.get(id);
  live.delete(id);
  ws?.close(4001, "removed");
};

/** Tell a Dock the current list of phones printing from it (for its settings view). */
export const notifyDockPeers = (dockId: string) => {
  const peers = mirrorPhones(dockId).map((p) => ({ name: deviceLabel(p), online: live.has(p.id) }));
  sendToDevice(dockId, { t: "print2go_peers", peers });
};

/** Send a paired phone the Dock's sheet set (Dock-Labels). An empty/blank dockId
 *  sends an empty set — used to clear the phone's labels on unpair. */
export const pushDockSheets = (phoneId: string, dockId: string) => {
  const dock = dockId ? getDevice(dockId) : undefined;
  const sheets = dockId ? dockSheets(dockId).map((r) => ({
    id: r.sheet_id, name: r.name, address: r.address, link: r.link, key: r.key, model: r.model,
    tag_uid: r.tag_uid, tag_programmed: !!r.tag_programmed,
  })) : [];
  sendToDevice(phoneId, { t: "dock_sheets", dock: dockId || null, dock_name: dock ? deviceLabel(dock) : null, sheets });
};

type Hello = { t: "hello"; id: string; secret: string; claim: string; kind: string; name: string; version: string; platform?: string };

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
      if (!ID_RE.test(h.id) || !["dock", "go", "dock-light"].includes(h.kind)) return refuse("bad hello");
      const known = getDevice(h.id);
      if (known) {
        if (!secretMatches(h.secret, known.secret_hash)) {
          console.warn(`device ${h.id} from ${remote}: wrong secret`);
          return refuse("auth");
        }
        if (known.version && h.version && known.version !== String(h.version))
          addEvent(h.id, "updated", `${known.version} → ${h.version}`);
        if (known.kind !== h.kind) {
          setDeviceKind(h.id, h.kind);
          addEvent(h.id, "kind_changed", `${known.kind} → ${h.kind}`);
        }
        touchDevice(h.id, String(h.version ?? ""));
      } else {
        if (!CLAIM_RE.test(String(h.claim ?? ""))) return refuse("bad claim code");
        registerDevice(h.id, h.kind, String(h.name ?? "").slice(0, 63), hashSecret(h.secret), h.claim, String(h.version ?? ""));
        addEvent(h.id, "registered", remote);
        console.log(`device ${h.id} (${h.kind}) registered from ${remote} — unclaimed, code ${h.claim}`);
      }
      // a Go app reports its platform so the Updates page can tab it as iOS or Android
      if (h.kind === "go" && (h.platform === "ios" || h.platform === "android")) setDevicePlatform(h.id, h.platform);
      deviceId = h.id;
      live.get(deviceId)?.close(4002, "replaced"); // a reconnect supersedes a stale socket
      live.set(deviceId, ws);
      alive.set(ws, true);
      lastPong.set(ws, Date.now());   // a fresh connection is proven alive right now
      const d = getDevice(deviceId)!;
      const org = d.org_id ? getOrg(d.org_id) : undefined;
      // a dormant (signed-out) device keeps its seat but the app returns to its gate
      const claimed = !!d.org_id && !d.dormant;
      ws.send(JSON.stringify({ t: "hello_ok", claimed, org: claimed ? org?.name ?? null : null, approved: !!d.approved }));
      // Print2Go: unclaimed jobs waiting for this device (a phone's source Dock, or a Dock itself)
      if (claimed) for (const j of openMirrorJobsFor(deviceId)) {
        const dock = getDevice(j.dock_id);
        ws.send(JSON.stringify({ t: "mirror_job", job: { id: j.id, name: j.name, from: dock ? deviceLabel(dock) : "a Dock" } }));
      }
      addEvent(deviceId, "online");
      offerUpdate(deviceId);
      // Print2Go: a Dock gets its current peer list; a phone's Dock learns it came online
      if (d.kind === "dock" || d.kind === "dock-light") notifyDockPeers(d.id);
      else if (d.kind === "go" && d.mirror_from) notifyDockPeers(d.mirror_from);
      // a paired phone inherits its Dock's sheets (Dock-Labels) on connect
      if (claimed && d.kind === "go" && d.mirror_from) pushDockSheets(d.id, d.mirror_from);
      return;
    }

    // a superseded socket (the device reconnected on a newer one) must not keep writing
    // shared state from late buffered frames — the live map is the single source of truth
    if (live.get(deviceId) !== ws) return;

    if (msg.t === "status") {
      const { t: _t, ...status } = msg;
      saveDeviceStatus(deviceId, JSON.stringify(status).slice(0, 256 * 1024));
      if (typeof status.jobs_today === "number")
        upsertStat(deviceId, new Date().toISOString().slice(0, 10), status.jobs_today);
      const row = getDevice(deviceId);
      if (row?.target_version && typeof status.version === "string") {
        if (status.version === row.target_version) setTargetVersion(deviceId, null);   // converged
        else offerUpdate(deviceId);
      }
    }
    if (msg.t === "push_token") {
      // a Go app hands over its push token so the cloud can wake it for a waiting job
      const raw = String(msg.token ?? "");
      const env = msg.env === "fcm" ? "fcm" : msg.env === "production" ? "production" : "sandbox";
      // APNs device tokens are hex; FCM registration tokens are far longer and use a wider
      // alphabet (letters, digits, ':', '-', '_', '.') — only hex-sanitise the APNs case,
      // or the FCM token gets shredded and Android push silently never arrives.
      const token = env === "fcm"
        ? raw.replace(/[^A-Za-z0-9:_.-]/g, "").slice(0, 4096)
        : raw.replace(/[^0-9a-fA-F]/g, "").slice(0, 200);
      if (token) setPushToken(deviceId, token, env);
    }
    if (msg.t === "sheets") {
      // a Dock publishes its full sheet snapshot; the cloud diffs it and relays to paired phones
      const d = getDevice(deviceId);
      if (d && (d.kind === "dock" || d.kind === "dock-light")) {
        const incoming = (Array.isArray(msg.sheets) ? msg.sheets : [])
          .filter((s: { id?: unknown }) => typeof s.id === "string");
        syncDockSheets(deviceId, incoming);
        for (const p of mirrorPhones(deviceId)) pushDockSheets(p.id, deviceId);
      }
    }
    if (msg.t === "sheet_nfc") {
      // the one bidirectional field — a phone edits its paired Dock's sheet; a Dock edits its own
      const sheetId = String(msg.sheet_id ?? "");
      const d = getDevice(deviceId);
      const dockId = (d?.kind === "dock" || d?.kind === "dock-light") ? deviceId : (d?.mirror_from ?? "");
      if (dockId && sheetId) {
        const uid = msg.uid == null ? null : String(msg.uid).replace(/[^0-9a-fA-F]/g, "").slice(0, 64);
        const row = setDockSheetNfc(dockId, sheetId, uid, !!msg.programmed);
        if (row) {
          const note = { t: "sheet_nfc", sheet_id: sheetId, uid: row.tag_uid, programmed: !!row.tag_programmed };
          sendToDevice(dockId, note);
          for (const p of mirrorPhones(dockId)) if (p.id !== deviceId) sendToDevice(p.id, note);
        }
      }
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
      // release any Print2Go job this device was holding a claim on, back into the pool,
      // so a device that dies mid-claim doesn't orphan the page for an hour (H2)
      for (const j of mirrorJobsClaimedBy(deviceId)) { releaseMirrorJob(j.id); offerJobToPool(j); }
      // the device row may have been removed by an admin between the socket opening and
      // this close firing — never touch a row that's gone (would throw an uncaught FK
      // error and take the whole process down)
      const d = getDevice(deviceId);
      if (d) {
        touchDevice(deviceId);
        addEvent(deviceId, "offline");
        if (d.kind === "go" && d.mirror_from) notifyDockPeers(d.mirror_from);   // update its Dock's peer list
      }
    }
  });
  ws.on("error", () => { /* close follows */ });
  ws.on("pong", () => { alive.set(ws, true); lastPong.set(ws, Date.now()); });
};
