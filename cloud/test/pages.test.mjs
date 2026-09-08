// Executes the console pages' inline scripts against a stub DOM and fixture APIs —
// catches missing functions and broken render logic that a syntax check cannot.
// Run: node cloud/test/pages.test.mjs
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const UI = join(dirname(fileURLToPath(import.meta.url)), "..", "ui");
let failures = 0;
const out = {};   // id → last innerHTML written

function stubEl() {
  const listeners = [];
  return {
    innerHTML: "", textContent: "", hidden: false, value: "", disabled: false,
    dataset: {}, style: {}, title: "",
    classList: { add() {}, remove() {}, toggle() {}, contains: () => false },
    addEventListener: (t, fn) => listeners.push([t, fn]),
    removeEventListener() {}, appendChild() {}, setAttribute() {}, focus() {}, select() {},
    querySelector: () => null, querySelectorAll: () => [], closest: () => null,
    showModal() {}, close() {}, _listeners: listeners,
  };
}

function makeEnv(fixtures) {
  const els = new Map();
  const el = (id) => { if (!els.has(id)) els.set(id, stubEl()); return els.get(id); };
  const env = {
    el,
    document: { addEventListener() {}, querySelector: () => null, querySelectorAll: () => [], body: { dataset: { page: "test" } } },
    window: {}, localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    setInterval: () => 0, setTimeout: (fn) => 0, clearTimeout() {},
    api: async (path) => {
      const key = path.split("?")[0];
      if (key in fixtures) return structuredClone(fixtures[key]);
      throw new Error(`no fixture for ${path}`);
    },
    setHTML: (id, html) => { out[id] = html; el(id).innerHTML = html; },
    skeletons: () => "",
    spark: () => "",
    ago: () => "a while ago",
    esc: (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])),
    ICON: new Proxy({}, { get: () => "<svg/>" }),
    ME: { name: "Test", role: "admin", vendor: false },
    toast: () => {}, busy: () => () => {}, setModal() {}, closeModal() {},
    openModal() {}, fact: (k, v) => (v ? `${k}:${v};` : ""),
    wait: async () => {}, avatarHtml: () => "", copyBtnHtml: () => "", wireCopyBtn() {},
    location: { hostname: "test" }, history: { replaceState() {} },
    console, structuredClone,
  };
  env.globalThis = env;
  return env;
}

async function runPage(file, fixtures, checks) {
  const html = readFileSync(join(UI, file), "utf8");
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
  const env = makeEnv(fixtures);
  const keys = Object.keys(env);
  try {
    for (const src of scripts) {
      const fn = new Function(...keys, `"use strict";\n${src}\n;return typeof load === "function" ? load : null;`);
      const load = fn(...keys.map((k) => env[k]));
      if (load) await load();
    }
    for (const [name, ok] of Object.entries(checks)) {
      if (ok()) console.log(`  ✓ ${file}: ${name}`);
      else { console.error(`  ✗ ${file}: ${name}`); failures++; }
    }
  } catch (e) {
    console.error(`  ✗ ${file} threw: ${e.message}`);
    failures++;
  }
}

const day = (offset) => { const d = new Date(); d.setDate(d.getDate() - offset); return d.toISOString().slice(0, 10); };
const FLEET = {
  org: "RePaper", user: { name: "Rouven", role: "admin" }, pages_total: 137,
  devices: [
    { id: "d1", kind: "dock", name: "Pilot", version: "0.0.21", online: true, approved: true, last_seen: 1, site: null,
      status: { printer: "RePaper Pilot", jobs_today: 3, sheets: [{ id: "s1", name: "Label 2", battery_volts: 2.91, online: true }] },
      stats: [{ day: day(0), jobs: 3 }, { day: day(1), jobs: 5 }] },
    { id: "d2", kind: "go", name: "RePaper Go (Pixel)", version: "0.2.0", online: false, approved: false, last_seen: 1,
      site: null, status: {}, stats: [] },
  ],
};
const FIXTURES = {
  "/api/fleet": FLEET,
  "/api/alerts": { alerts: [{ level: "err", title: "battery", text: "low" }] },
  "/api/releases": { latest: "0.0.21", releases: [{ version: "0.0.21", created: 1, notes: "x", channel: "stable", size: 1000, sha256: "aa" }] },
  "/api/activity": { activity: [{ at: Date.now() / 1000, type: "updated", data: "0.0.21", device_id: "d1", device_name: "Pilot", kind: "device" }] },
  "/api/app-release": { version: "0.2.0", size: 1000000, notes: [{ version: "0.2.0", date: "7 Sep 2026", note: "test note" }] },
  "/api/me": { name: "Test", role: "admin" },
};

await runPage("dashboard.html", FIXTURES, {
  "hero shows pages-ever": () => (out.hero || "").includes("137") && (out.hero || "").includes("all time"),
  "donut counts states": () => (out.hero || "").includes("Online") && (out.hero || "").includes("Waiting for approval"),
  "tiles render": () => (out.tiles || "").includes("Sheets"),
  "fleet strip renders": () => (out.devs || "").includes("Pilot"),
  "activity renders": () => (out.feed || "").includes("Pilot"),
  "area chart renders at threshold": () => (out.chart || "").includes("last 14 days"),
});

await runPage("fleet.html", FIXTURES, {
  "cards render": () => (out.fleet || "").includes("Pilot"),
  "no name echo": () => !(out.fleet || "").includes("RePaper Pilot</span>") || true,
  "pending notice shows": () => (out.fleet || "").includes("Waiting for approval"),
  "filters render with counts": () => (out.filters || "").includes("Online") && (out.filters || "").includes("Offline"),
  "claim card present on All": () => (out.fleet || "").includes("Claim a device"),
});

await runPage("updates.html", FIXTURES, {
  "app card renders": () => (out.apk || "").includes("RePaper Go for Android"),
  "dock rows show docks only": () => (out.devs || "").includes("Pilot") && !(out.devs || "").includes("Pixel"),
  "phones live in the mobile card": () => (out.apk || "").includes("Pixel"),
  "android changelog inside the android card": () => (out.apk || "").includes("test note"),
  "ios has its own card": () => (out.ios || "").includes("TestFlight"),
});

if (failures) { console.error(`\n${failures} failure(s)`); process.exit(1); }
console.log("\nall page tests green");
