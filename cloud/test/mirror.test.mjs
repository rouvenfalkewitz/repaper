/* End-to-end test of Print2Go against a REAL server on a temp DB: a Dock and two
   phones; the phones mirror the Dock; the Dock forwards a job into the shared pool;
   exactly one device claims it (first wins), the rest are told it's taken; byte
   integrity; offline queue; seat-preserving sign-out.
   Run from cloud/:  npx tsc && node test/mirror.test.mjs */
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomBytes, createHash } from "node:crypto";
import Database from "better-sqlite3";

const PORT = 3215;
const BASE = `http://127.0.0.1:${PORT}`;
const dir = mkdtempSync(join(tmpdir(), "repaper-p2g-"));
const dbPath = join(dir, "cloud.db");

let failures = 0;
const ok = (cond, name) => { console.log(`  ${cond ? "✓" : "✗"} ${name}`); if (!cond) failures++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const server = spawn(process.execPath, ["dist/server.js"], {
  env: { ...process.env, DATABASE_PATH: dbPath, PORT: String(PORT), LOG_LEVEL: "silent", DOMAIN: "" },
  stdio: ["ignore", "pipe", "pipe"],
});
server.stderr.on("data", (d) => process.stderr.write(d));

for (let i = 0; i < 50; i++) {
  try { const r = await fetch(`${BASE}/api/health`); if (r.ok) break; } catch {}
  await sleep(200);
  if (i === 49) { console.error("server never came up"); process.exit(1); }
}

const db = new Database(dbPath);
const now = Math.floor(Date.now() / 1000);
const orgId = db.prepare("INSERT INTO org(name, created) VALUES('Test Org', ?)").run(now).lastInsertRowid;
const userId = db.prepare("INSERT INTO user(org_id, email, name, pass_hash, created, role) VALUES(?,?,?,?,?,'admin')")
  .run(orgId, "t@example.org", "Tester", "scrypt$00$00", now).lastInsertRowid;
const token = randomBytes(32).toString("hex");
db.prepare("INSERT INTO session(token, user_id, created, expires) VALUES(?,?,?,?)").run(token, userId, now, now + 3600);
db.close();
const cookie = { headers: { cookie: `rp_session=${token}`, "content-type": "application/json" } };

const api = async (path, body) => {
  const r = await fetch(BASE + path, body === undefined ? cookie : { method: "POST", ...cookie, body: JSON.stringify(body) });
  return { status: r.status, json: await r.json().catch(() => ({})) };
};

const device = (kind, claim, status = {}) => {
  const id = randomBytes(16).toString("hex");
  const secret = randomBytes(24).toString("hex");
  const inbox = [];
  let ws = null;
  const connect = () => new Promise((resolve, reject) => {
    ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws/device`);
    ws.onopen = () => ws.send(JSON.stringify({ t: "hello", id, secret, claim, kind, name: `${kind}-test`, version: "0.0.1" }));
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      inbox.push(m);
      if (m.t === "hello_ok") { if (Object.keys(status).length) ws.send(JSON.stringify({ t: "status", ...status })); resolve(m); }
    };
    ws.onerror = reject;
  });
  return { id, secret, inbox, connect, close: () => ws?.close(),
           auth: (extra = {}) => ({ id, secret, ...extra }),
           waitFor: async (t, ms = 3000) => {
             const end = Date.now() + ms;
             while (Date.now() < end) {
               const i = inbox.findIndex((x) => x.t === t);
               if (i >= 0) return inbox.splice(i, 1)[0];
               await sleep(40);
             }
             return null;
           } };
};

try {
  const dock = device("dock", "AAAA-BBBB", { printer: "Front Dock", print2go: true, state: "ready" });
  const p1 = device("go", "CCCC-DD11");
  const p2 = device("go", "CCCC-DD22");
  await dock.connect(); await p1.connect(); await p2.connect();
  ok(true, "a Print2Go Dock and two phones connect");

  ok((await api("/api/claim", { code: "AAAA-BBBB" })).json.ok === true, "dock claimed");
  ok((await api("/api/claim", { code: "CCCC-DD11" })).json.ok === true, "phone 1 claimed");
  ok((await api("/api/claim", { code: "CCCC-DD22" })).json.ok === true, "phone 2 claimed");

  // the Dock shows up in the phone's list of Print2Go Docks
  await sleep(200);
  const list = await api("/api/device/print2go-docks", p1.auth());
  ok(list.json.docks?.some((x) => x.id === dock.id), "phone sees the Dock in the Print2Go list");

  // both phones mirror the Dock
  ok((await api("/api/device/mirror-from", p1.auth({ dock_id: dock.id }))).json.ok === true, "phone 1 mirrors the Dock");
  ok((await api("/api/device/mirror-from", p2.auth({ dock_id: dock.id }))).json.ok === true, "phone 2 mirrors the Dock");

  // a Go device is refused as a mirror source
  const badSource = await api("/api/device/mirror-from", p1.auth({ dock_id: p2.id }));
  ok(badSource.status === 404, "a phone can't mirror another phone");

  // the Dock forwards a job into the shared pool
  const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47]), randomBytes(800)]);
  const fwd = await api("/api/device/forward-job", dock.auth({ name: "Weekly specials", type: "png", data: png.toString("base64") }));
  ok(fwd.json.ok === true && fwd.json.phones === 2, "job forwarded, offered to both phones");
  const jid = fwd.json.job_id;
  const o1 = await p1.waitFor("mirror_job"), o2 = await p2.waitFor("mirror_job"), od = await dock.waitFor("mirror_job");
  ok(o1?.job?.id === jid && o2?.job?.id === jid && od?.job?.id === jid, "Dock and both phones are offered the job");

  // phone 1 takes it; phone 2 and the Dock are told it's taken
  const take1 = await api(`/api/device/mirror-job/${jid}/take`, p1.auth());
  ok(take1.json.ok === true && take1.json.type === "png", "phone 1 claims the job and gets the page");
  ok(createHash("sha256").update(Buffer.from(take1.json.data, "base64")).digest("hex")
     === createHash("sha256").update(png).digest("hex"), "page bytes survive the relay unchanged");
  ok((await p2.waitFor("mirror_taken"))?.job?.id === jid, "phone 2 is told the job was taken");
  ok((await dock.waitFor("mirror_taken"))?.job?.id === jid, "the Dock is told the job was taken");

  // phone 2 loses the race
  const take2 = await api(`/api/device/mirror-job/${jid}/take`, p2.auth());
  ok(take2.status === 409, "phone 2's late claim is refused");

  // phone 1 finishes → the job is forgotten
  ok((await api(`/api/device/mirror-job/${jid}/done`, p1.auth())).json.ok === true, "phone 1 reports done");
  ok((await api(`/api/device/mirror-job/${jid}/take`, p2.auth())).status === 404, "the job is gone after done");

  // release path: a new job, claimed then released, is re-offered
  const fwd2 = await api("/api/device/forward-job", dock.auth({ name: "Retry me", type: "png", data: png.toString("base64") }));
  const jid2 = fwd2.json.job_id;
  await p1.waitFor("mirror_job"); await p2.waitFor("mirror_job");
  await api(`/api/device/mirror-job/${jid2}/take`, p1.auth());
  await p2.waitFor("mirror_taken");
  await api(`/api/device/mirror-job/${jid2}/release`, p1.auth());
  ok((await p2.waitFor("mirror_job"))?.job?.id === jid2, "a released job is re-offered to the pool");
  await api(`/api/device/mirror-job/${jid2}/take`, p2.auth());   // phone 2 now claims it
  await dock.waitFor("mirror_taken");
  await api(`/api/device/mirror-job/${jid2}/done`, p2.auth());   // and finishes it — pool is clean

  // offline queue: phone 2 away, a job waits, arrives on reconnect
  p2.close(); await sleep(300);
  const fwd3 = await api("/api/device/forward-job", dock.auth({ name: "While away", type: "png", data: png.toString("base64") }));
  await dock.waitFor("mirror_job");   // drain the Dock's copy
  ok(fwd3.json.phones === 1, "only the online phone is pushed to");
  p2.inbox.length = 0;
  await p2.connect();
  ok((await p2.waitFor("mirror_job"))?.job?.id === fwd3.json.job_id, "the waiting job arrives when the phone reconnects");
  await api(`/api/device/mirror-job/${fwd3.json.job_id}/done`, dock.auth());   // dock cleans it (it also could print it)

  // seat-preserving sign-out: the device stays in the fleet, marked dormant
  const before = (await api("/api/fleet")).json.devices.length;
  ok((await api("/api/device/unclaim", p1.auth())).json.ok === true, "phone 1 signs out");
  await sleep(150);
  const after = (await api("/api/fleet")).json.devices;
  ok(after.length === before, "the device keeps its seat (still in the fleet)");
  ok(after.find((x) => x.id === p1.id)?.dormant === true, "the signed-out device is marked dormant");
  ok(after.find((x) => x.id === p1.id)?.mirror_from == null, "sign-out drops the Print2Go source");

  // re-sign-in wakes the same device, no new seat
  ok((await api("/api/claim", { code: "CCCC-DD11" })).json.ok === true, "re-sign-in on the same device");
  await sleep(150);
  const after2 = (await api("/api/fleet")).json.devices;
  ok(after2.length === before, "still no new seat after re-sign-in");
  ok(after2.find((x) => x.id === p1.id)?.dormant === false, "the device is active again");

  console.log(failures ? `\n${failures} failure(s)` : "\nall Print2Go relay tests green");
} finally {
  server.kill();
  rmSync(dir, { recursive: true, force: true });
}
process.exit(failures ? 1 : 0);
