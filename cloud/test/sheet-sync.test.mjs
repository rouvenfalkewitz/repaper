/* Dock-Labels sync against a REAL server on a temp DB: a Dock publishes its sheet
   snapshot, paired phones inherit it; NFC writes back; connect/pair/unpair triggers.
   Run from cloud/ with Node 22:  npx tsc && node test/sheet-sync.test.mjs */
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomBytes } from "node:crypto";
import Database from "better-sqlite3";

const PORT = 3216;
const BASE = `http://127.0.0.1:${PORT}`;
const dir = mkdtempSync(join(tmpdir(), "repaper-sheets-"));
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

const now = Math.floor(Date.now() / 1000);
const db = new Database(dbPath);
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

const device = (kind, claim) => {
  const id = randomBytes(16).toString("hex");
  const secret = randomBytes(24).toString("hex");
  const inbox = [];
  let ws = null;
  const connect = () => new Promise((resolve, reject) => {
    ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws/device`);
    ws.onopen = () => ws.send(JSON.stringify({ t: "hello", id, secret, claim, kind, name: `${kind}-test`, version: "0.0.1" }));
    ws.onmessage = (ev) => { const m = JSON.parse(ev.data); inbox.push(m); if (m.t === "hello_ok") resolve(m); };
    ws.onerror = reject;
  });
  return {
    id, secret, inbox, connect, close: () => ws?.close(),
    auth: (extra = {}) => ({ id, secret, ...extra }),
    send: (obj) => ws.send(JSON.stringify(obj)),
    waitFor: async (t, ms = 3000) => {
      const end = Date.now() + ms;
      while (Date.now() < end) {
        const i = inbox.findIndex((x) => x.t === t);
        if (i >= 0) return inbox.splice(i, 1)[0];
        await sleep(40);
      }
      return null;
    },
  };
};

const KITCHEN = { id: "kitchen", name: "Kitchen door", address: "A1", link: "https://x/kitchen", model: '{"width":296,"height":128,"palette":"BWR"}' };

try {
  const dock = device("dock", "AAAA-BBBB");
  const phone = device("go", "CCCC-DD11");
  await dock.connect(); await phone.connect();
  dock.send({ t: "status", printer: "Front Dock", print2go: true, state: "ready" });   // a Print2Go source
  ok((await api("/api/claim", { code: "AAAA-BBBB" })).json.ok === true, "dock claimed");
  ok((await api("/api/claim", { code: "CCCC-DD11" })).json.ok === true, "phone claimed");
  await sleep(150);
  ok((await api("/api/device/mirror-from", phone.auth({ dock_id: dock.id }))).json.ok === true, "phone mirrors the Dock");
  await phone.waitFor("dock_sheets");   // drain the (empty) set pushed on pairing — nothing published yet

  // Task 2: the Dock publishes a snapshot → the paired phone inherits it
  await sleep(150);
  dock.send({ t: "sheets", sheets: [KITCHEN] });
  const ds = await phone.waitFor("dock_sheets");
  ok(ds?.sheets?.length === 1 && ds.sheets[0].id === "kitchen", "phone receives the Dock's sheet snapshot");
  ok(ds?.sheets?.[0]?.link === "https://x/kitchen", "the landing link is relayed to the paired phone");
  ok(ds?.dock_name != null, "the snapshot names the Dock");

  // Task 2: NFC write-back — the phone learns a tag, the Dock hears about it
  phone.send({ t: "sheet_nfc", sheet_id: "kitchen", uid: "0455AA", programmed: false });
  const nfc = await dock.waitFor("sheet_nfc");
  ok(nfc?.sheet_id === "kitchen" && nfc?.uid === "0455AA", "the Dock receives the phone's NFC write-back");

  // Task 3: a phone that mirrors AFTER the dock published gets the set on pairing
  const phone2 = device("go", "CCCC-DD22");
  await phone2.connect();
  ok((await api("/api/claim", { code: "CCCC-DD22" })).json.ok === true, "phone 2 claimed");
  ok((await api("/api/device/mirror-from", phone2.auth({ dock_id: dock.id }))).json.ok === true, "phone 2 mirrors the Dock");
  const ds2 = await phone2.waitFor("dock_sheets");
  ok(ds2?.sheets?.length === 1, "newly-paired phone gets the current sheet set");

  // Task 3: unpair clears the phone's Dock-Labels (empty snapshot)
  await api("/api/device/mirror-from", phone2.auth({ dock_id: null }));
  const cleared = await phone2.waitFor("dock_sheets");
  ok(cleared?.sheets?.length === 0, "unpair pushes an empty set to clear Dock-Labels");

  // Task 3: a phone reconnecting with mirror_from already set gets the set on hello
  phone.close(); await sleep(100);
  await phone.connect();
  const dsHello = await phone.waitFor("dock_sheets");
  ok(dsHello?.sheets?.length === 1, "reconnecting paired phone gets the set on hello");
} catch (e) {
  console.error(e); failures++;
} finally {
  server.kill(); rmSync(dir, { recursive: true, force: true });
}
console.log(failures ? `\n${failures} failure(s)` : "\nall sheet-sync checks green");
process.exit(failures ? 1 : 0);
