/* Shared console runtime: helpers + the app shell (sidebar, user chip).
   Pages declare <body data-page="…"> and put content inside .main; the shell
   renders itself around it from /api/me. */
"use strict";
const el = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const ICON = {
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
  qr: `<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h3v3h-3zM20 14v1M17 20h4M14 20h1"/></svg>`,
  warn: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 9v4m0 4h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/></svg>`,
};
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

/* ── the shell ── */
const LOGO = `<svg class="logo" viewBox="0 0 6629.1 2338.6" role="img" aria-label="RePaper"><path class="ring" fill-rule="evenodd" d="M0 1169.3a1169.3 1169.3 0 1 0 2338.6 0a1169.3 1169.3 0 1 0 -2338.6 0ZM203.6 1169.3a965.7 965.7 0 1 0 1931.3 0a965.7 965.7 0 1 0 -1931.3 0Z"/><path class="letters" d="M355.9 1512.3V824.7H928.2Q1003.3 824.7 1052.6 855.6Q1101.8 886.5 1126.4 936.9Q1151 987.3 1151 1046.8Q1151 1112.2 1120.1 1165.7Q1089.1 1219.2 1026.9 1250.2L1167.5 1512.3H937.8L823.8 1283.6H559.5V1512.3ZM559.5 1133.1H861.7Q898.4 1133.1 920.4 1111.1Q942.3 1089 942.3 1052.5Q942.3 1027.9 932.4 1010.6Q922.5 993.4 904.6 984.5Q886.7 975.7 861.7 975.7H559.5Z M1257.9 1512.3V824.7H1974.7V975.7H1462.1V1091.1H1910.8V1237.2H1462.1V1361.4H1982.7V1512.3Z"/><path class="letters" d="M2489.5 1512.3V824.7H3020.1Q3087.7 824.7 3138.5 854.6Q3189.4 884.4 3217.7 937Q3246 989.7 3246 1058.4Q3246 1127.8 3217.2 1181.2Q3188.4 1234.6 3137.1 1264.1Q3085.7 1293.7 3018.3 1293.7H2693.1V1512.3ZM2693.1 1142.8H2961.6Q3006.8 1142.8 3030.2 1121Q3053.5 1099.3 3053.5 1059.4Q3053.5 1032.2 3042.9 1013.7Q3032.3 995.2 3012 985.4Q2991.6 975.7 2961.6 975.7H2693.1Z M3181.3 1512.3 3519.3 824.7H3745.7L4083.7 1512.3H3863.4L3811.5 1402.1H3442.5L3391.1 1512.3ZM3510.7 1253H3743.3L3680.7 1113.3Q3676.8 1103.9 3669.7 1086.8Q3662.7 1069.6 3655.1 1050.1Q3647.5 1030.6 3641.1 1013.8Q3634.7 997 3631.5 989H3624Q3616.7 1007.3 3607.1 1031.2Q3597.5 1055 3588.6 1077.4Q3579.7 1099.7 3573.3 1113.7Z M4153.5 1512.3V824.7H4684.1Q4751.7 824.7 4802.5 854.6Q4853.4 884.4 4881.7 937Q4910 989.7 4910 1058.4Q4910 1127.8 4881.2 1181.2Q4852.4 1234.6 4801.1 1264.1Q4749.7 1293.7 4682.3 1293.7H4357.1V1512.3ZM4357.1 1142.8H4625.6Q4670.8 1142.8 4694.2 1121Q4717.5 1099.3 4717.5 1059.4Q4717.5 1032.2 4706.9 1013.7Q4696.3 995.2 4676 985.4Q4655.6 975.7 4625.6 975.7H4357.1Z M4984.5 1512.3V824.7H5701.2V975.7H5188.7V1091.1H5637.4V1237.2H5188.7V1361.4H5709.2V1512.3Z M5817.5 1512.3V824.7H6389.7Q6464.9 824.7 6514.1 855.6Q6563.4 886.5 6588 936.9Q6612.6 987.3 6612.6 1046.8Q6612.6 1112.2 6581.7 1165.7Q6550.7 1219.2 6488.5 1250.2L6629.1 1512.3H6399.4L6285.3 1283.6H6021.1V1512.3ZM6021.1 1133.1H6323.3Q6360 1133.1 6381.9 1111.1Q6403.9 1089 6403.9 1052.5Q6403.9 1027.9 6394 1010.6Q6384.1 993.4 6366.1 984.5Q6348.2 975.7 6323.3 975.7H6021.1Z"/></svg>`;

const NAV = [
  { href: "/", key: "fleet", label: "Fleet", icon: "dock" },
  { href: "/team", key: "team", label: "Team", icon: "people" },
  { href: "/account", key: "account", label: "Account", icon: "user" },
  { href: "/billing", key: "billing", label: "Billing", icon: "card", tag: "preview" },
  { href: "/shop", key: "shop", label: "Shop", icon: "bag", tag: "preview" },
];

let ME = null;
const MEP = api("/api/me").then((m) => (ME = m)).catch(() => null);

function renderShell() {
  const side = el("side"); if (!side) return;
  const active = document.body.dataset.page;
  side.innerHTML = `<a href="/" class="sidelogo">${LOGO}</a>
    <nav class="nav">${NAV.map((n) => `<a href="${n.href}" class="${n.key === active ? "active" : ""}">${ICON[n.icon]}<span>${n.label}</span>${n.tag ? `<span class="tag">${n.tag}</span>` : ""}</a>`).join("")}</nav>
    <div class="foot"><a class="userchip" href="/account" id="userchip"></a></div>`;
  MEP.then(() => { if (!ME) return;
    el("userchip").innerHTML = `${avatarHtml(ME, 32)}<span class="who3"><b>${esc(ME.name || ME.email)}</b><span>${esc(ME.org)}</span></span>
      <button class="iconbtn" title="Sign out" aria-label="Sign out" onclick="event.preventDefault();signOut()">${ICON.out}</button>`;
    document.dispatchEvent(new CustomEvent("me-ready"));
  });
}
renderShell();
document.addEventListener("click", (e) => { const d = el("edDlg"); if (d && e.target === d) closeModal(); const t = el("detDlg"); if (t && e.target === t) t.close(); });
