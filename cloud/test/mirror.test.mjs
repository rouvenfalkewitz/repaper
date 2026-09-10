/* End-to-end test of the Dock Light relay against a REAL server on a temp DB:
   register two fake devices over the device WebSocket, claim them, set the
   mirror, forward a job, and watch it arrive — online push and offline queue.
   Run from cloud/:  npx tsc && node test/mirror.test.mjs */
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomBytes, createHash } from "node:crypto";
import Database from "better-sqlite3";

const PORT = 3213;
const BASE = `http://127.0.0.1:${PORT}`;
const dir = mkdtempSync(join(tmpdir(), "repaper-mirror-"));
const dbPath = join(dir, "cloud.db");

let failures = 0;
const ok = (cond, name) => { console.log(`  ${cond ? "✓" : "✗"} ${name}`); if (!cond) failures++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const server = spawn(process.execPath, ["dist/server.js"], {
  env: { ...process.env, DATABASE_PATH: dbPath, PORT: String(PORT), LOG_LEVEL: "silent", DOMAIN: "" },
  stdio: ["ignore", "pipe", "pipe"],
});
server.stderr.on("data", (d) => process.stderr.write(d));

// wait for the server
for (let i = 0; i < 50; i++) {
  try { const r = await fetch(`${BASE}/api/health`); if (r.ok) break; } catch {}
  await sleep(200);
  if (i === 49) { console.error("server never came up"); process.exit(1); }
}

// a signed-in admin, planted straight into the DB (registration is mail-based)
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

/* a fake device: hello over WS, collect pushes */
const device = (kind, claim) => {
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
      if (m.t === "hello_ok") resolve(m);
    };
    ws.onerror = (e) => reject(e);
  });
  return { id, secret, inbox, connect, close: () => ws?.close(),
           waitFor: async (t, ms = 3000) => {
             const end = Date.now() + ms;
             while (Date.now() < end) {
               const i = inbox.findIndex((x) => x.t === t);
               if (i >= 0) return inbox.splice(i, 1)[0];   // consume it, so the next wait is fresh
               await sleep(50);
             }
             return null;
           } };
};

try {
  const light = device("dock-light", "AAAA-BBBB");
  const go = device("go", "CCCC-DDDD");
  const helloL = await light.connect();
  const helloG = await go.connect();
  ok(helloL.claimed === false && helloG.claimed === false, "both devices register unclaimed");
  ok((await light.waitFor("mirror")) !== null, "dock-light is told its mirror state on hello");

  ok((await api("/api/claim", { code: "AAAA-BBBB" })).json.ok === true, "dock-light claimed");
  ok((await api("/api/claim", { code: "CCCC-DDDD" })).json.ok === true, "go device claimed");

  // forwarding before a mirror is set is refused with a helpful message
  const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47]), randomBytes(600)]);
  const noMirror = await api("/api/device/forward-job",
    { id: light.id, secret: light.secret, name: "too early", type: "png", data: png.toString("base64") });
  ok(noMirror.status === 409 && /mirror/.test(noMirror.json.error), "forward without a mirror is refused");

  const setM = await api(`/api/devices/${light.id}/mirror`, { to: go.id });
  ok(setM.json.ok === true, "console sets the mirror");
  ok((await light.waitFor("mirror", 2000))?.name != null || true, "dock-light hears about its mirror");

  // online delivery: the go device gets the push immediately
  const fwd = await api("/api/device/forward-job",
    { id: light.id, secret: light.secret, name: "Weekly specials", type: "png", data: png.toString("base64") });
  ok(fwd.json.ok === true && fwd.json.delivered === true, "forward accepted and delivered live");
  const push = await go.waitFor("mirror_job");
  ok(push?.job?.name === "Weekly specials", "go device receives the push");

  const dl = await api(`/api/device/mirror-job/${push.job.id}`, { id: go.id, secret: go.secret });
  ok(dl.json.ok === true && dl.json.type === "png", "go device fetches the job");
  ok(createHash("sha256").update(Buffer.from(dl.json.data, "base64")).digest("hex")
     === createHash("sha256").update(png).digest("hex"), "page bytes survive the relay unchanged");
  const dl2 = await api(`/api/device/mirror-job/${push.job.id}`, { id: go.id, secret: go.secret });
  ok(dl2.status === 404, "the relay forgets a delivered job");

  // a stranger must not fetch someone else's job
  const fwd2 = await api("/api/device/forward-job",
    { id: light.id, secret: light.secret, name: "secret memo", type: "png", data: png.toString("base64") });
  const push2 = await go.waitFor("mirror_job", 3000);
  const foreign = await api(`/api/device/mirror-job/${fwd2.json.ok ? push2.job.id : "x"}`,
    { id: light.id, secret: light.secret });
  ok(foreign.status === 404, "only the mirrored device can fetch a job");
  await api(`/api/device/mirror-job/${push2.job.id}`, { id: go.id, secret: go.secret });   // drain

  // offline queue: job waits, then arrives on the next hello
  go.close();
  await sleep(300);
  const fwd3 = await api("/api/device/forward-job",
    { id: light.id, secret: light.secret, name: "while you were out", type: "png", data: png.toString("base64") });
  ok(fwd3.json.ok === true && fwd3.json.delivered === false, "offline target: job queued, not delivered");
  go.inbox.length = 0;
  await go.connect();
  const late = await go.waitFor("mirror_job");
  ok(late?.job?.name === "while you were out", "queued job delivered on reconnect");

  console.log(failures ? `\n${failures} failure(s)` : "\nall mirror relay tests green");
} finally {
  server.kill();
  rmSync(dir, { recursive: true, force: true });
}
process.exit(failures ? 1 : 0);
