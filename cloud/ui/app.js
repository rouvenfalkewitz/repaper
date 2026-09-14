/* Shared console runtime: helpers + the app shell (sidebar, user chip).
   Pages declare <body data-page="…"> and put content inside .main; the shell
   renders itself around it from /api/me. */
"use strict";
const el = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const ICON = {
  android: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6.5 10.5a5.5 5.5 0 0 1 11 0V17h-11z"/><path d="m8 6 -1.3-2M16 6l1.3-2M6.5 13.5H4.8M19.2 13.5h-1.7"/><circle cx="9.7" cy="9.3" r=".4" fill="currentColor"/><circle cx="14.3" cy="9.3" r=".4" fill="currentColor"/></svg>`,
  apple: `<svg viewBox="0 0 24 24" aria-hidden="true" style="fill:currentColor;stroke:none"><path d="M16.4 12.7c0-2 1.6-3 1.7-3.1-.9-1.4-2.4-1.6-2.9-1.6-1.2-.1-2.4.7-3 .7-.6 0-1.6-.7-2.6-.7-1.3 0-2.6.8-3.3 2-1.4 2.5-.4 6.1 1 8.1.7 1 1.5 2.1 2.5 2.1 1 0 1.4-.7 2.6-.7 1.2 0 1.5.7 2.6.7s1.8-1 2.4-2c.8-1.1 1.1-2.2 1.1-2.3 0 0-2.1-.8-2.1-3.2zM14.5 6.7c.5-.7.9-1.6.8-2.6-.8 0-1.8.6-2.3 1.2-.5.6-1 1.6-.8 2.5.9.1 1.8-.4 2.3-1.1z"/></svg>`,
  home: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7h-6v7H4a1 1 0 0 1-1-1z"/></svg>`,
  check: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 5 5L20 7"/></svg>`,
  x: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg>`,
  spin: `<svg class="spin" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a9 9 0 1 0 9 9"/></svg>`,
  pencil: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 20h4L19 9l-4-4L4 16v4z"/><path d="m13 7 4 4"/></svg>`,
  info: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 8h.01M11 12h1v4h1"/></svg>`,
  trash: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14"/></svg>`,
  plus: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>`,
  dock: `<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="9" width="18" height="9" rx="3"/><circle cx="12" cy="13.5" r="2"/><path d="M8 9V7a4 4 0 0 1 8 0v2"/></svg>`,
  phone: `<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.5h2"/></svg>`,
  ping: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 18.2h.01"/><path d="M10 15.5a3 3 0 0 1 4 0"/><path d="M7.5 12.5a7 7 0 0 1 9 0"/><path d="M4.5 9.5a11 11 0 0 1 15 0"/></svg>`,
  people: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="9" cy="8" r="3.2"/><path d="M3.5 19a5.5 5.5 0 0 1 11 0"/><path d="M15.5 5.4a3.2 3.2 0 0 1 0 5.2M17.5 13.6a5.5 5.5 0 0 1 3 5.4"/></svg>`,
  user: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3.5"/><path d="M5.5 20a6.5 6.5 0 0 1 13 0"/></svg>`,
  card: `<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="6" width="18" height="13" rx="2.5"/><path d="M3 10.5h18M6.5 15h4"/></svg>`,
  bag: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 8h14l-1 12a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2z"/><path d="M9 10V6a3 3 0 0 1 6 0v4"/></svg>`,
  out: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5M21 12H9"/></svg>`,
  shield: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 5 6v5c0 4.5 3 8.2 7 10 4-1.8 7-5.5 7-10V6z"/><path d="m9.5 12 2 2 3.5-4"/></svg>`,
  camera: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 8h3l2-2.5h6L17 8h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z"/><circle cx="12" cy="14" r="3.5"/></svg>`,
  sheet: `<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3.5" y="6" width="17" height="12" rx="2"/><path d="M7 10h6M7 13.5h4"/></svg>`,
  pulse: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 12h4l2.5-6 4 12L16 12h5"/></svg>`,
  download: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v11m0 0 4.5-4.5M12 15l-4.5-4.5"/><path d="M4 19h16"/></svg>`,
  gear: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8V15a1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>`,
  qr: `<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h3v3h-3zM20 14v1M17 20h4M14 20h1"/></svg>`,
  warn: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 9v4m0 4h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/></svg>`,
  search: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/></svg>`,
  key: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="8" cy="15.5" r="4.5"/><path d="m11.5 12 8.5-8.5M17 6.5 19.5 9M14.5 9 17 11.5"/></svg>`,
  copy: `<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/></svg>`,
  clock: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>`,
  mail: `<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="5.5" width="18" height="13" rx="2"/><path d="m3.5 7 8.5 6 8.5-6"/></svg>`,
  sparkle: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5 13.8 9l5.5 1.8L13.8 12.6 12 18.1 10.2 12.6 4.7 10.8 10.2 9z"/><path d="M18.5 3.5v3M20 5h-3"/></svg>`,
  printer: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 8V4h10v4"/><rect x="4" y="8" width="16" height="8" rx="2"/><path d="M7 14h10v6H7z"/><circle cx="17" cy="11" r=".5" fill="currentColor"/></svg>`,
  grid: `<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/></svg>`,
  list: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01"/></svg>`,
  corner: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6 3 12l6 6M3 12h12a4 4 0 0 0 4-4V6"/></svg>`,
  chevrons: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 9 4-4 4 4M8 15l4 4 4-4"/></svg>`,
  chevright: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6"/></svg>`,
  external: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 4h6v6M20 4l-8.5 8.5M18 13v5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h5"/></svg>`,
  globe: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3.2 12h17.6M12 3a15 15 0 0 1 0 18M12 3a15 15 0 0 0 0 18"/></svg>`,
};
/* a standard copy-button: icon + label, flips to ✓ Copied for a moment */
function copyBtnHtml(id, label = "Copy link") { return `<button class="btn outline" type="button" id="${id}">${ICON.copy}<span>${label}</span></button>`; }
function wireCopyBtn(id, text) {
  const b = el(id); if (!b) return;
  b.addEventListener("click", async () => {
    try { await navigator.clipboard.writeText(text); } catch { toast("Copy failed — select it by hand.", "err"); return; }
    b.classList.add("ok"); b.innerHTML = ICON.check + "<span>Copied</span>";
    setTimeout(() => { b.classList.remove("ok"); b.innerHTML = ICON.copy + "<span>Copy link</span>"; }, 1600);
  });
}
let toastT;
function toast(t, kind) { const x = el("toast"); if (!x) return; x.querySelector(".tx").textContent = t; x.querySelector(".ic").innerHTML = kind === "err" ? ICON.x : ICON.check; x.className = "toast show " + (kind || ""); clearTimeout(toastT); toastT = setTimeout(() => x.classList.remove("show"), 2800); }
async function api(path, body) {
  const r = await fetch(path, body !== undefined ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) } : { cache: "no-store" });
  if (r.status === 401 && !path.startsWith("/api/login")) { location.href = "/login"; throw new Error("signed out"); }
  const j = await r.json();
  if (!r.ok || j.error) throw new Error(j.error || r.statusText);
  return j;
}
function busy(b, label) {
  b.disabled = true; b.dataset.html = b.dataset.html || b.innerHTML; b.classList.add("busy"); b.innerHTML = ICON.spin + `<span>${label}</span>`;
  return (result, failed) => { b.classList.remove("busy"); if (!result) { b.innerHTML = b.dataset.html; b.disabled = false; return; }
    b.classList.add(failed ? "fail" : "ok"); b.innerHTML = (failed ? ICON.x : ICON.check) + `<span>${result}</span>`;
    setTimeout(() => { b.classList.remove("ok", "fail"); b.innerHTML = b.dataset.html; b.disabled = false; }, 1600); };
}
function shake(i) { i.classList.remove("shake"); void i.offsetWidth; i.classList.add("shake"); i.focus(); setTimeout(() => i.classList.remove("shake"), 400); }
const fact = (k, v) => v ? `<div class="fact"><i>${esc(k)}</i><b>${esc(v)}</b></div>` : "";
function ago(ts) {
  if (!ts) return "never";
  const m = Math.floor((Date.now() / 1000 - ts) / 60);
  if (m < 1) return "just now"; if (m < 60) return `${m} min ago`;
  if (m < 48 * 60) return `${Math.floor(m / 60)} h ago`;
  return new Date(ts * 1000).toLocaleDateString();
}
/* avatar: image if set, initials on a deterministic tint otherwise */
const AV_TINTS = [["var(--accent-tint)", "var(--accent-text)"], ["var(--blue-tint)", "var(--blue)"], ["var(--amber-tint)", "var(--amber)"], ["var(--red-tint)", "var(--red)"]];
function avatarHtml(u, size = 32) {
  if (u.avatar) return `<img class="av" src="${esc(u.avatar)}" width="${size}" height="${size}" alt="" style="width:${size}px;height:${size}px">`;
  const base = u.name || u.email || "?";
  const initials = base.trim().split(/\s+/).map((w) => w[0]).slice(0, 2).join("").toUpperCase();
  let h = 0; for (const c of u.email || base) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  const [bg, fg] = AV_TINTS[h % AV_TINTS.length];
  return `<span class="av" style="width:${size}px;height:${size}px;background:${bg};color:${fg};font-size:${Math.round(size * 0.38)}px">${esc(initials)}</span>`;
}
/* generic modal (pages include <dialog class="modal" id="edDlg">) */
function setModal(title, html) {
  el("edTitle").textContent = title; el("edBody").innerHTML = html; el("edDlg").showModal();
  const i = el("edBody").querySelector("input"); if (i) { i.focus(); i.select && i.select(); }
}
function closeModal() { const d = el("edDlg"); if (d) d.close(); }
async function signOut() { try { await api("/api/logout", {}); } catch {} location.href = "/login"; }
function toggleUserMenu(e) { if (e) e.stopPropagation(); const m = el("userMenu"), t = el("userTrigger"); if (!m) return; const willOpen = m.hidden; m.hidden = !willOpen; if (t) t.setAttribute("aria-expanded", String(willOpen)); }
function closeUserMenu() { const m = el("userMenu"), t = el("userTrigger"); if (m && !m.hidden) { m.hidden = true; if (t) t.setAttribute("aria-expanded", "false"); } }
document.addEventListener("click", (e) => { const bar = el("userbar"); if (bar && !bar.contains(e.target)) closeUserMenu(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeUserMenu(); });

/* ── the shell ── */
const LOGO = `<svg class="logo" viewBox="0 0 6629.1 2338.6" role="img" aria-label="RePaper"><path class="ring" fill-rule="evenodd" d="M0 1169.3a1169.3 1169.3 0 1 0 2338.6 0a1169.3 1169.3 0 1 0 -2338.6 0ZM203.6 1169.3a965.7 965.7 0 1 0 1931.3 0a965.7 965.7 0 1 0 -1931.3 0Z"/><path class="letters" d="M355.9 1512.3V824.7H928.2Q1003.3 824.7 1052.6 855.6Q1101.8 886.5 1126.4 936.9Q1151 987.3 1151 1046.8Q1151 1112.2 1120.1 1165.7Q1089.1 1219.2 1026.9 1250.2L1167.5 1512.3H937.8L823.8 1283.6H559.5V1512.3ZM559.5 1133.1H861.7Q898.4 1133.1 920.4 1111.1Q942.3 1089 942.3 1052.5Q942.3 1027.9 932.4 1010.6Q922.5 993.4 904.6 984.5Q886.7 975.7 861.7 975.7H559.5Z M1257.9 1512.3V824.7H1974.7V975.7H1462.1V1091.1H1910.8V1237.2H1462.1V1361.4H1982.7V1512.3Z"/><path class="letters" d="M2489.5 1512.3V824.7H3020.1Q3087.7 824.7 3138.5 854.6Q3189.4 884.4 3217.7 937Q3246 989.7 3246 1058.4Q3246 1127.8 3217.2 1181.2Q3188.4 1234.6 3137.1 1264.1Q3085.7 1293.7 3018.3 1293.7H2693.1V1512.3ZM2693.1 1142.8H2961.6Q3006.8 1142.8 3030.2 1121Q3053.5 1099.3 3053.5 1059.4Q3053.5 1032.2 3042.9 1013.7Q3032.3 995.2 3012 985.4Q2991.6 975.7 2961.6 975.7H2693.1Z M3181.3 1512.3 3519.3 824.7H3745.7L4083.7 1512.3H3863.4L3811.5 1402.1H3442.5L3391.1 1512.3ZM3510.7 1253H3743.3L3680.7 1113.3Q3676.8 1103.9 3669.7 1086.8Q3662.7 1069.6 3655.1 1050.1Q3647.5 1030.6 3641.1 1013.8Q3634.7 997 3631.5 989H3624Q3616.7 1007.3 3607.1 1031.2Q3597.5 1055 3588.6 1077.4Q3579.7 1099.7 3573.3 1113.7Z M4153.5 1512.3V824.7H4684.1Q4751.7 824.7 4802.5 854.6Q4853.4 884.4 4881.7 937Q4910 989.7 4910 1058.4Q4910 1127.8 4881.2 1181.2Q4852.4 1234.6 4801.1 1264.1Q4749.7 1293.7 4682.3 1293.7H4357.1V1512.3ZM4357.1 1142.8H4625.6Q4670.8 1142.8 4694.2 1121Q4717.5 1099.3 4717.5 1059.4Q4717.5 1032.2 4706.9 1013.7Q4696.3 995.2 4676 985.4Q4655.6 975.7 4625.6 975.7H4357.1Z M4984.5 1512.3V824.7H5701.2V975.7H5188.7V1091.1H5637.4V1237.2H5188.7V1361.4H5709.2V1512.3Z M5817.5 1512.3V824.7H6389.7Q6464.9 824.7 6514.1 855.6Q6563.4 886.5 6588 936.9Q6612.6 987.3 6612.6 1046.8Q6612.6 1112.2 6581.7 1165.7Q6550.7 1219.2 6488.5 1250.2L6629.1 1512.3H6399.4L6285.3 1283.6H6021.1V1512.3ZM6021.1 1133.1H6323.3Q6360 1133.1 6381.9 1111.1Q6403.9 1089 6403.9 1052.5Q6403.9 1027.9 6394 1010.6Q6384.1 993.4 6366.1 984.5Q6348.2 975.7 6323.3 975.7H6021.1Z"/></svg>`;

const NAV = [
  { href: "/", key: "dashboard", label: "Dashboard", icon: "home", group: "Overview" },
  { href: "/fleet", key: "fleet", label: "Fleet", icon: "dock", group: "Manage" },
  { href: "/sheets", key: "sheets", label: "Sheets", icon: "sheet", group: "Manage" },
  { href: "/activity", key: "activity", label: "Activity", icon: "pulse", group: "Manage" },
  { href: "/org", key: "org", label: "Organisation", icon: "people", group: "Manage" },
  { href: "/updates", key: "updates", label: "Updates", icon: "download", group: "Manage" },
  { href: "/products", key: "products", label: "How it works", icon: "sparkle", tag: "tour", group: "Explore" },
  { href: "/billing", key: "billing", label: "Billing", icon: "card", tag: "preview", group: "Explore" },
  { href: "/shop", key: "shop", label: "Shop", icon: "bag", tag: "preview", group: "Explore" },
];

let ME = null;
const MEP = api("/api/me").then((m) => (ME = m)).catch(() => null);

function renderShell() {
  const side = el("side"); if (!side) return;
  const active = document.body.dataset.page;
  let lastGroup = "";
  const navItems = NAV.map((n) => {
    const head = n.group && n.group !== lastGroup ? (lastGroup = n.group, `<div class="navlabel">${n.group}</div>`) : "";
    return `${head}<a href="${n.href}" class="${n.key === active ? "active" : ""}">${ICON[n.icon]}<span>${n.label}</span>${n.tag ? `<span class="tag">${n.tag}</span>` : ""}</a>`;
  }).join("");
  side.innerHTML = `<div class="sidetop"><a href="/" class="sidelogo">${LOGO}</a><button class="cmdk-trigger" type="button" onclick="openCmdK()" title="Search — ⌘K" aria-label="Search (Command K)">${ICON.search}</button></div>
    <nav class="nav">${navItems}</nav>
    <div class="foot"><div class="userbar" id="userbar"></div></div>`;
  initCmdK();
  MEP.then(() => { if (!ME) return;
    const defaultName = ME.org === (ME.name || "") || ME.org === ME.email.split("@")[0];
    const orgLine = ME.personal && defaultName ? "Personal"
      : `${ME.org_logo ? `<img class="orgmini" src="${esc(ME.org_logo)}" alt="">` : ""}${esc(ME.org)}`;
    el("userbar").innerHTML = `
      <div class="usermenu" id="userMenu" role="menu" hidden>
        <a class="umi" role="menuitem" href="/account">${ICON.gear}<span>Account settings</span></a>
        <button class="umi" role="menuitem" type="button" onclick="signOut()">${ICON.out}<span>Sign out</span></button>
      </div>
      <button class="userchip ${active === "account" ? "active" : ""}" type="button" id="userTrigger" aria-haspopup="menu" aria-expanded="false" onclick="toggleUserMenu(event)">
        <span class="uav">${avatarHtml(ME, 34)}<span class="uonline"></span></span>
        <span class="who3"><b>${esc(ME.name || ME.email)}</b><span>${orgLine}</span></span>
        <span class="uchev">${ICON.chevrons}</span>
      </button>`;
    document.dispatchEvent(new CustomEvent("me-ready"));
  });
}
/* ── ⌘K command palette ── jump to any page, device or action from anywhere */
const CMDK = { built: false, open: false, sel: 0, items: [], filtered: [], devices: null, devicesTried: false };
function cmdkBaseItems() {
  const pages = NAV.map((n) => ({ kind: "page", label: n.label, sub: "Page", icon: n.icon, run: () => (location.href = n.href) }));
  pages.push({ kind: "page", label: "Account", sub: "Page", icon: "user", run: () => (location.href = "/account") });
  const actions = [
    { kind: "action", label: "Claim a device", sub: "Action", icon: "plus", run: () => (location.href = "/fleet?claim=1") },
    { kind: "action", label: "Sign out", sub: "Action", icon: "out", run: () => signOut() },
  ];
  const devs = (CMDK.devices || []).map((d) => ({
    kind: "device", label: d.name || (d.status || {}).printer || d.kind, sub: d.site ? `Device · ${d.site}` : "Device",
    icon: d.kind === "go" ? "phone" : "dock", run: () => (location.href = `/fleet?device=${encodeURIComponent(d.id)}`),
  }));
  return [...pages, ...devs, ...actions];
}
function cmdkFilter(q) {
  const all = CMDK.items;
  q = q.trim().toLowerCase();
  if (!q) return all.slice(0, 12);
  const toks = q.split(/\s+/);
  const scored = [];
  for (const it of all) {
    const hay = (it.label + " " + it.sub).toLowerCase();
    if (toks.every((t) => hay.includes(t))) scored.push([it.label.toLowerCase().startsWith(q) ? 0 : hay.indexOf(q) < 0 ? 2 : 1, it]);
  }
  return scored.sort((a, b) => a[0] - b[0]).map((x) => x[1]).slice(0, 12);
}
function cmdkRender() {
  const list = el("cmdkList"); if (!list) return;
  if (!CMDK.filtered.length) { list.innerHTML = `<div class="cmdk-empty">No matches.</div>`; return; }
  list.innerHTML = CMDK.filtered.map((it, i) => `<button class="cmdk-item ${i === CMDK.sel ? "sel" : ""}" data-i="${i}" role="option">
    <span class="ci-ic">${ICON[it.icon] || ICON.search}</span><span class="ci-l">${esc(it.label)}</span><span class="ci-s">${esc(it.sub)}</span></button>`).join("");
  const sel = list.querySelector(".cmdk-item.sel"); if (sel) sel.scrollIntoView({ block: "nearest" });
}
function cmdkSetQuery(q) { CMDK.filtered = cmdkFilter(q); CMDK.sel = 0; cmdkRender(); }
function openCmdK() {
  if (!CMDK.built) return;
  CMDK.open = true; el("cmdk").hidden = false; document.body.style.overflow = "hidden";
  CMDK.items = cmdkBaseItems(); const inp = el("cmdkInput"); inp.value = ""; cmdkSetQuery("");
  requestAnimationFrame(() => inp.focus());
  if (!CMDK.devicesTried) { CMDK.devicesTried = true; api("/api/fleet").then((f) => { CMDK.devices = f.devices || []; if (CMDK.open) { CMDK.items = cmdkBaseItems(); cmdkSetQuery(el("cmdkInput").value); } }).catch(() => {}); }
}
function closeCmdK() { CMDK.open = false; const d = el("cmdk"); if (d) d.hidden = true; document.body.style.overflow = ""; }
function initCmdK() {
  if (CMDK.built) return; CMDK.built = true;
  const w = document.createElement("div");
  w.className = "cmdk"; w.id = "cmdk"; w.hidden = true;
  w.innerHTML = `<div class="cmdk-back" onclick="closeCmdK()"></div>
    <div class="cmdk-panel" role="dialog" aria-modal="true" aria-label="Command palette">
      <div class="cmdk-inp"><span class="ic">${ICON.search}</span><input id="cmdkInput" placeholder="Search pages, devices, actions…" autocomplete="off" spellcheck="false"><kbd>esc</kbd></div>
      <div class="cmdk-list" id="cmdkList" role="listbox"></div>
    </div>`;
  document.body.appendChild(w);
  el("cmdkInput").addEventListener("input", (e) => cmdkSetQuery(e.target.value));
  el("cmdkList").addEventListener("click", (e) => { const b = e.target.closest(".cmdk-item"); if (!b) return; const it = CMDK.filtered[+b.dataset.i]; if (it) { closeCmdK(); it.run(); } });
  el("cmdkList").addEventListener("mousemove", (e) => { const b = e.target.closest(".cmdk-item"); if (!b) return; const i = +b.dataset.i; if (i !== CMDK.sel) { CMDK.sel = i; cmdkRender(); } });
}
document.addEventListener("keydown", (e) => {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") { e.preventDefault(); CMDK.open ? closeCmdK() : openCmdK(); return; }
  if (!CMDK.open) return;
  if (e.key === "Escape") { e.preventDefault(); closeCmdK(); }
  else if (e.key === "ArrowDown") { e.preventDefault(); CMDK.sel = Math.min(CMDK.filtered.length - 1, CMDK.sel + 1); cmdkRender(); }
  else if (e.key === "ArrowUp") { e.preventDefault(); CMDK.sel = Math.max(0, CMDK.sel - 1); cmdkRender(); }
  else if (e.key === "Enter") { e.preventDefault(); const it = CMDK.filtered[CMDK.sel]; if (it) { closeCmdK(); it.run(); } }
});

/* flicker killer: only touch the DOM when the content actually changed */
const _lastHTML = new Map();
function setHTML(id, html) {
  if (_lastHTML.get(id) === html) return;
  _lastHTML.set(id, html);
  const n = el(id); if (n) n.innerHTML = html;
}
/* initial-load skeletons */
function skeletons(kind, n = 3) {
  if (kind === "cards") return Array.from({ length: n }, () => `<div class="dev skel"><div class="skl w40"></div><div class="skl w70 tall"></div><div class="skl w55"></div><div class="skl w100 thin"></div></div>`).join("");
  if (kind === "rows") return Array.from({ length: n }, () => `<div class="member"><span class="mrow"><span class="skl av"></span><span style="flex:1;display:grid;gap:6px"><span class="skl w40"></span><span class="skl w60 thin"></span></span></span></div>`).join("");
  return `<div class="skl w70"></div>`;
}

/* tiny sparkline: jobs per day, last two weeks */
function spark(stats, w = 64, h = 18) {
  if (!stats || stats.length < 2) return "";
  const max = Math.max(1, ...stats.map((s) => s.jobs));
  const pt = (s, i) => [(i / (stats.length - 1)) * (w - 4) + 2, h - 3 - (s.jobs / max) * (h - 6)];
  const pts = stats.map((s, i) => pt(s, i).map((n) => n.toFixed(1)).join(",")).join(" ");
  const [lx, ly] = pt(stats[stats.length - 1], stats.length - 1);
  return `<svg class="spark" viewBox="0 0 ${w} ${h}" width="${w}" height="${h}" role="img" aria-label="jobs per day, last ${stats.length} days"><polyline points="${pts}"/><circle cx="${lx.toFixed(1)}" cy="${ly.toFixed(1)}" r="2"/></svg>`;
}

renderShell();
document.addEventListener("click", (e) => { const d = el("edDlg"); if (d && e.target === d) closeModal(); const t = el("detDlg"); if (t && e.target === t) t.close(); });
