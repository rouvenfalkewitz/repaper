# Dock-Labels & Sheet Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A phone paired to a Dock inherits that Dock's sheets as printable, tagged "Dock-Labels"; the same physical sheet in both places collapses to one row; NFC tag info is shared both ways.

**Architecture:** New cloud `dock_sheet` table (keyed by `dock_id` + `sheet_id`) holds each Dock's sheets. The Dock publishes a full snapshot over the existing device WebSocket; the cloud diffs it and relays the set only to phones paired to that Dock (`mirror_from`). NFC tag info is the one field any device can write; the cloud fans it out to the Dock and paired phones. The app merges inherited sheets with its local ones, de-duped by landing name.

**Tech Stack:** Cloud = Fastify + better-sqlite3 + `ws` (TypeScript, ESM, Node 22). Dock = Python 3 (`repaper_dock`, websockets, urllib). App = SwiftUI (iOS).

**Spec:** `docs/superpowers/specs/2026-09-11-dock-labels-and-sheet-sync-design.md`

## Global Constraints

- Sheet identity is the **landing name** everywhere; all matching/de-dupe keys off it. Cloud key is `(dock_id, sheet_id)` where `sheet_id` = landing name.
- Definition zone (`link`, `model`, `address`, `name`) is **Dock → phone one-way**; the cloud ignores definition fields from a phone. NFC zone (`tag_uid`, `tag_programmed`) is **any-writer, last-writer-wins**.
- `dock_sheet` rows are relayed **only to phones with `mirror_from == dock_id`** and **never exposed in the console UI / any `/api` fleet payload**.
- Phone-local sheets are **not** synced upward. NFC is the sole write-back.
- Cloud: TypeScript ESM, imports end `.js`; DB migrations follow the `PRAGMA table_info` guard pattern in `db.ts`. Run cloud tests with Node 22 (`node --test` / the existing `test/*.mjs`).
- Commit after each task.

---

### Task 1: Cloud `dock_sheet` table + db accessors

**Files:**
- Modify: `cloud/src/db.ts` (add table create, row type, accessors)
- Test: `cloud/test/sheets.test.mjs` (new)

**Interfaces:**
- Produces:
  - type `DockSheetRow = { dock_id: string; sheet_id: string; name: string; address: string; link: string; model: string; tag_uid: string | null; tag_programmed: number }`
  - `syncDockSheets(dockId: string, sheets: {id,name,address,link,model,tag_uid?,tag_programmed?}[]): void` — snapshot upsert: inserts/updates the listed sheets and **deletes rows for that dock not in the list**. Preserves an existing `tag_uid`/`tag_programmed` when the incoming sheet omits them (so a Dock snapshot without NFC doesn't wipe a phone-learned tag).
  - `dockSheets(dockId: string): DockSheetRow[]`
  - `setDockSheetNfc(dockId: string, sheetId: string, uid: string | null, programmed: boolean): DockSheetRow | undefined` — updates only the NFC zone; returns the row (or undefined if unknown).
  - `model` stored as a JSON string (the app/dock already treat model as `{width,height,palette,inset}`); pass through verbatim.

- [ ] **Step 1: Write the failing test**

```js
// cloud/test/sheets.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
process.env.DATABASE_PATH = ":memory:";
const db = await import("../dist/db.js");

test("syncDockSheets upserts and diffs; NFC preserved across snapshots", () => {
  db.syncDockSheets("dockA", [
    { id: "kitchen", name: "Kitchen", address: "A1", link: "https://x/kitchen", model: '{"width":296,"height":128,"palette":"BWR"}' },
    { id: "desk", name: "Desk", address: "D4", link: "https://x/desk", model: '{"width":400,"height":300,"palette":"BW"}' },
  ]);
  assert.equal(db.dockSheets("dockA").length, 2);

  // a phone learns the tag for kitchen
  db.setDockSheetNfc("dockA", "kitchen", "0455AA", true);
  assert.equal(db.dockSheets("dockA").find(s => s.sheet_id === "kitchen").tag_uid, "0455AA");

  // Dock re-publishes a snapshot WITHOUT desk and WITHOUT nfc fields → desk removed, kitchen keeps its tag
  db.syncDockSheets("dockA", [
    { id: "kitchen", name: "Kitchen door", address: "A1", link: "https://x/kitchen", model: '{"width":296,"height":128,"palette":"BWR"}' },
  ]);
  const rows = db.dockSheets("dockA");
  assert.equal(rows.length, 1);
  assert.equal(rows[0].sheet_id, "kitchen");
  assert.equal(rows[0].name, "Kitchen door");   // definition updated
  assert.equal(rows[0].tag_uid, "0455AA");        // NFC preserved
});
```

- [ ] **Step 2: Run it, verify it fails**

Run: `cd cloud && npm run build && node --test test/sheets.test.mjs`
Expected: FAIL (`db.syncDockSheets is not a function`).

- [ ] **Step 3: Implement the table + accessors**

In `db.ts`, after the other `CREATE TABLE` blocks add:

```ts
db.exec(`
CREATE TABLE IF NOT EXISTS dock_sheet (
  dock_id TEXT NOT NULL,
  sheet_id TEXT NOT NULL,
  name TEXT NOT NULL DEFAULT '',
  address TEXT NOT NULL DEFAULT '',
  link TEXT NOT NULL DEFAULT '',
  model TEXT NOT NULL DEFAULT '{}',
  tag_uid TEXT,
  tag_programmed INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (dock_id, sheet_id)
)`);
```

Add near the other device accessors:

```ts
export type DockSheetRow = {
  dock_id: string; sheet_id: string; name: string; address: string;
  link: string; model: string; tag_uid: string | null; tag_programmed: number;
};
export const dockSheets = (dockId: string): DockSheetRow[] =>
  db.prepare("SELECT * FROM dock_sheet WHERE dock_id=? ORDER BY sheet_id").all(dockId) as DockSheetRow[];

type IncomingSheet = { id: string; name?: string; address?: string; link?: string; model?: string; tag_uid?: string | null; tag_programmed?: boolean };
export const syncDockSheets = (dockId: string, sheets: IncomingSheet[]): void => {
  const keep = new Set(sheets.map((s) => s.id));
  const existing = new Map(dockSheets(dockId).map((r) => [r.sheet_id, r]));
  const del = db.prepare("DELETE FROM dock_sheet WHERE dock_id=? AND sheet_id=?");
  for (const r of existing.keys()) if (!keep.has(r)) del.run(dockId, r);
  const up = db.prepare(`INSERT INTO dock_sheet(dock_id,sheet_id,name,address,link,model,tag_uid,tag_programmed)
    VALUES(@dock_id,@sheet_id,@name,@address,@link,@model,@tag_uid,@tag_programmed)
    ON CONFLICT(dock_id,sheet_id) DO UPDATE SET name=@name,address=@address,link=@link,model=@model,
      tag_uid=COALESCE(@tag_uid,tag_uid), tag_programmed=CASE WHEN @tag_uid IS NULL THEN tag_programmed ELSE @tag_programmed END`);
  for (const s of sheets) {
    const prev = existing.get(s.id);
    up.run({
      dock_id: dockId, sheet_id: s.id, name: s.name ?? "", address: s.address ?? "",
      link: s.link ?? "", model: s.model ?? "{}",
      tag_uid: s.tag_uid ?? null,
      tag_programmed: s.tag_programmed ? 1 : (prev?.tag_programmed ?? 0),
    });
  }
};
export const setDockSheetNfc = (dockId: string, sheetId: string, uid: string | null, programmed: boolean): DockSheetRow | undefined => {
  db.prepare("UPDATE dock_sheet SET tag_uid=?, tag_programmed=? WHERE dock_id=? AND sheet_id=?")
    .run(uid, programmed ? 1 : 0, dockId, sheetId);
  return db.prepare("SELECT * FROM dock_sheet WHERE dock_id=? AND sheet_id=?").get(dockId, sheetId) as DockSheetRow | undefined;
};
```

- [ ] **Step 4: Run tests, verify pass**

Run: `cd cloud && npm run build && node --test test/sheets.test.mjs`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add cloud/src/db.ts cloud/test/sheets.test.mjs
git commit -m "cloud: dock_sheet table + snapshot-diff accessors"
```

---

### Task 2: Cloud device-socket — ingest Dock snapshot, relay to phones, NFC fan-out

**Files:**
- Modify: `cloud/src/devices.ts` (message handlers + push helper)
- Test: `cloud/test/sheets.test.mjs` (extend — exercised via the running server like `mirror.test.mjs`)

**Interfaces:**
- Consumes: `syncDockSheets`, `dockSheets`, `setDockSheetNfc`, `mirrorPhones`, `getDevice`, `sendToDevice`, `deviceLabel` (all existing/Task 1).
- Produces:
  - export `pushDockSheets(phoneId: string, dockId: string): void` — sends `{ t:"dock_sheets", dock, dock_name, sheets:[{id,name,address,link,model,tag_uid,tag_programmed}] }` to `phoneId`. Sends an empty `sheets` array when `dockId` is falsy (used on unpair).
  - Socket now handles inbound `{ t:"sheets", sheets:[...] }` (from a dock/dock-light) and `{ t:"sheet_nfc", sheet_id, uid, programmed }` (from any device).

- [ ] **Step 1: Write the failing test** (server-level, mirroring `mirror.test.mjs` harness)

```js
// append to cloud/test/sheets.test.mjs — uses the same spawn-server helper style as mirror.test.mjs
// Pseudocode of the check (fill with the mirror.test.mjs harness utilities):
// 1. connect a dock socket (kind dock), send hello, then {t:"sheets", sheets:[kitchen]}
// 2. connect a phone socket (kind go) that is paired (mirror_from = dock) and claimed
// 3. assert the phone receives {t:"dock_sheets", sheets:[kitchen]} with the link present
// 4. phone sends {t:"sheet_nfc", sheet_id:"kitchen", uid:"0455AA", programmed:false}
// 5. assert the DOCK receives {t:"sheet_nfc", sheet_id:"kitchen", uid:"0455AA"}
```

Implement it with the same WebSocket + `waitFor` helpers `mirror.test.mjs` uses (import/share them; if they are inline in that file, copy the minimal helper into `sheets.test.mjs`). Assert on the messages each side receives.

- [ ] **Step 2: Run it, verify it fails**

Run: `cd cloud && npm run build && node --test test/sheets.test.mjs`
Expected: FAIL (phone never receives `dock_sheets`).

- [ ] **Step 3: Implement handlers in `devices.ts`**

Add the import: `syncDockSheets, dockSheets, setDockSheetNfc` to the `./db.js` import list.

Add the push helper near `notifyDockPeers`:

```ts
export const pushDockSheets = (phoneId: string, dockId: string): void => {
  const dock = dockId ? getDevice(dockId) : undefined;
  const sheets = dockId ? dockSheets(dockId).map((r) => ({
    id: r.sheet_id, name: r.name, address: r.address, link: r.link, model: r.model,
    tag_uid: r.tag_uid, tag_programmed: !!r.tag_programmed,
  })) : [];
  sendToDevice(phoneId, { t: "dock_sheets", dock: dockId || null, dock_name: dock ? deviceLabel(dock) : null, sheets });
};
```

In the message loop (after the `push_token` handler), add:

```ts
if (msg.t === "sheets" && (getDevice(deviceId)?.kind === "dock" || getDevice(deviceId)?.kind === "dock-light")) {
  const incoming = Array.isArray(msg.sheets) ? msg.sheets : [];
  syncDockSheets(deviceId, incoming.filter((s: { id?: unknown }) => typeof s.id === "string"));
  for (const p of mirrorPhones(deviceId)) pushDockSheets(p.id, deviceId);
}
if (msg.t === "sheet_nfc") {
  const sheetId = String(msg.sheet_id ?? "");
  const d = getDevice(deviceId);
  // resolve which dock owns this sheet: a dock edits its own; a phone edits its paired dock's
  const dockId = (d?.kind === "dock" || d?.kind === "dock-light") ? deviceId : d?.mirror_from ?? "";
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
```

- [ ] **Step 4: Run tests, verify pass**

Run: `cd cloud && npm run build && node --test test/sheets.test.mjs`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add cloud/src/devices.ts cloud/test/sheets.test.mjs
git commit -m "cloud: relay Dock sheet snapshots to paired phones + NFC fan-out"
```

---

### Task 3: Cloud — push sheets on connect, pair, and unpair

**Files:**
- Modify: `cloud/src/devices.ts` (hello: push to a phone with `mirror_from`)
- Modify: `cloud/src/api.ts` (the `mirror-from` endpoint: push current set on pair, empty on unpair)
- Test: `cloud/test/sheets.test.mjs` (extend: a phone connecting after the dock already published gets the set on hello; setting mirror-from to null clears)

**Interfaces:**
- Consumes: `pushDockSheets` (Task 2), existing `setMirrorFrom` + `mirror-from` route.

- [ ] **Step 1: Write the failing test**

Extend the harness: dock publishes `sheets`; THEN a phone connects with `mirror_from` already set → assert it receives `dock_sheets` right after `hello_ok`. Then POST `mirror-from` with `dock_id:null` → assert the phone receives `dock_sheets` with an empty `sheets` array.

- [ ] **Step 2: Run it, verify it fails**

Run: `cd cloud && npm run build && node --test test/sheets.test.mjs`
Expected: FAIL (no `dock_sheets` on hello).

- [ ] **Step 3: Implement**

In `devices.ts` hello, alongside the existing `if (d.kind === "go" && d.mirror_from) notifyDockPeers(d.mirror_from);`, add:

```ts
if (claimed && d.kind === "go" && d.mirror_from) pushDockSheets(d.id, d.mirror_from);
```

In `api.ts`, find the `mirror-from` POST handler (it calls `setMirrorFrom`). After it sets the value, add (using the phone's id `d.id` and the new/old dock):

```ts
pushDockSheets(d.id, dockId ?? "");   // dockId is the new mirror_from (null/"" clears)
```

Import `pushDockSheets` from `./devices.js` in `api.ts`.

- [ ] **Step 4: Run tests, verify pass**

Run: `cd cloud && npm run build && node --test test/sheets.test.mjs`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add cloud/src/devices.ts cloud/src/api.ts cloud/test/sheets.test.mjs
git commit -m "cloud: push Dock sheets on connect/pair, clear on unpair"
```

---

### Task 4: Dock — publish snapshot on connect + change, apply NFC write-back

**Files:**
- Modify: `dock/sidecar/repaper_dock/cloud.py` (send `sheets` snapshot; handle inbound `sheet_nfc`)
- Modify: `dock/sidecar/repaper_dock/dockd.py` (call publish on connect + after any registry change)
- Modify: `dock/sidecar/repaper_dock/sheets.py` (a `snapshot()` that returns the list of dicts, and a `set_tag(sheet_id, uid, programmed)`)
- Test: `dock/sidecar/tests/test_sheet_sync.py` (new) — `snapshot()` shape + `set_tag` persistence

**Interfaces:**
- Produces:
  - `SheetRegistry.snapshot() -> list[dict]` with keys `id,name,address,link,model,tag_uid,tag_programmed` (`model` is the JSON string of `{width,height,palette,inset}`; `link` is the stored landing link).
  - `SheetRegistry.set_tag(sheet_id: str, uid: str | None, programmed: bool) -> bool`
  - `Cloud.publish_sheets()` — sends `{ "t":"sheets", "sheets": registry.snapshot() }` if connected.

- [ ] **Step 1: Write the failing test**

```python
# dock/sidecar/tests/test_sheet_sync.py
from repaper_dock.sheets import SheetRegistry
def test_snapshot_and_set_tag(tmp_path):
    reg = SheetRegistry(tmp_path / "sheets.json")
    reg.add(...)   # use the registry's existing add API for one sheet with a link + model
    snap = reg.snapshot()
    assert snap and set(snap[0]) >= {"id","name","address","link","model","tag_uid","tag_programmed"}
    assert reg.set_tag(snap[0]["id"], "0455AA", False) is True
    assert reg.snapshot()[0]["tag_uid"] == "0455AA"
```

(Read `sheets.py` first for the real `add` signature and fill the `...`.)

- [ ] **Step 2: Run it, verify it fails**

Run: `cd dock/sidecar && .venv/bin/python -m pytest tests/test_sheet_sync.py -q`
Expected: FAIL (`snapshot`/`set_tag` missing).

- [ ] **Step 3: Implement**

Add `snapshot()` and `set_tag()` to `SheetRegistry` (serialize each entry's stored fields; `model` via the existing model-to-dict then `json.dumps`). In `cloud.py`, add `publish_sheets()` and call it right after the socket connects (where it currently logs "connected"), and handle inbound `{"t":"sheet_nfc"}` by calling `self.dock.registry.set_tag(...)` then `self.dock.request_publish_sheets()` is NOT needed (the cloud already has it) — just persist. In `dockd.py`, call `self.cloud.publish_sheets()` after any registry mutation (add/remove/set_tag) and on startup once connected.

- [ ] **Step 4: Run tests, verify pass**

Run: `cd dock/sidecar && .venv/bin/python -m pytest tests/test_sheet_sync.py -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add dock/sidecar/repaper_dock/sheets.py dock/sidecar/repaper_dock/cloud.py dock/sidecar/repaper_dock/dockd.py dock/sidecar/tests/test_sheet_sync.py
git commit -m "dock: publish sheet snapshot to cloud + apply NFC write-back"
```

---

### Task 5: iOS — data model: Sheet origin, inherited store, merge/de-dupe

**Files:**
- Modify: `ios/App/Sources/Store.swift` (`Sheet` gains `dockName: String?`; `SheetStore` gains `dockSheets` + a pure `merge` + published merged list)

**Interfaces:**
- Produces:
  - `Sheet.dockName: String?` — non-nil ⇒ it's a Dock-Label from that Dock. (`origin` is expressible as `dockName != nil`.)
  - `SheetStore.dockSheets: [Sheet]` (in-memory, set from the cloud), `SheetStore.setDockSheets(_ sheets: [Sheet])`.
  - `static func mergeSheets(local: [Sheet], dock: [Sheet]) -> [Sheet]` — union by `id` (case-insensitive); when an id is in both, the **dock** entry wins (carries `dockName`), NFC prefers whichever has a `tagUid`. Dock entries sort with locals by name.
  - `SheetStore.sheets` becomes the merged list; `find`/`findByUid` operate on it.

- [ ] **Step 1: Write the failing test** (pure function — put it in the RePaperKit test target if `Sheet` can be referenced there; otherwise assert manually and mark this step "manual" — see note). Preferred: extract `mergeSheets` logic to operate on a small `SheetLike` protocol in RePaperKit so it is unit-testable. If that is too invasive, keep `mergeSheets` in the app and verify by the screenshot harness in Task 7.

```swift
// If testable: RePaperKit test
func testMergeDedupesByIdDockWins() {
    let local = [SheetLike(id: "kitchen", dockName: nil, tagUid: nil),
                 SheetLike(id: "home", dockName: nil, tagUid: "AA")]
    let dock  = [SheetLike(id: "kitchen", dockName: "Pilot", tagUid: "BB")]
    let m = mergeSheets(local: local, dock: dock)
    XCTAssertEqual(m.count, 2)                       // kitchen collapsed
    XCTAssertEqual(m.first { $0.id == "kitchen" }?.dockName, "Pilot")   // dock wins
}
```

- [ ] **Step 2: Run it, verify it fails** — `cd ios/RePaperKit && swift test` (or note manual).

- [ ] **Step 3: Implement** the `dockName` field, `dockSheets` store, `setDockSheets` (assigns + recomputes merged + `objectWillChange`), and `mergeSheets`. Keep `save()`/`load()` writing only the **local** sheets (never persist inherited ones).

- [ ] **Step 4: Run tests / build** — `swift test` green (or `xcodebuild … build` succeeds).

- [ ] **Step 5: Commit**

```bash
git commit -am "iOS: Sheet.dockName + inherited store + merge/de-dupe by landing name"
```

---

### Task 6: iOS — CloudAgent: receive dock_sheets, apply sheet_nfc, send NFC write-back

**Files:**
- Modify: `ios/App/Sources/CloudAgent.swift` (handle `dock_sheets` + `sheet_nfc`; add `sendSheetNfc`)
- Modify: `ios/App/Sources/SheetsView.swift` (on NFC learn of any sheet, call `cloud.sendSheetNfc`)

**Interfaces:**
- Consumes: `SheetStore.setDockSheets`, `SheetStore.setTagUid`, `Sheet.dockName` (Task 5).
- Produces: `CloudAgent.sendSheetNfc(sheetId: String, uid: String, programmed: Bool)` — sends `{t:"sheet_nfc", sheet_id, uid, programmed}` over the socket.

- [ ] **Step 1: Write the failing test** — no unit harness for the socket layer; this is verified in the field test. Write the handler and build.

- [ ] **Step 2: Implement** in `handle(_:)`:

```swift
case "dock_sheets":
    let arr = (obj["sheets"] as? [[String: Any]]) ?? []
    let dockName = obj["dock_name"] as? String
    SheetStore.shared.setDockSheets(arr.compactMap { Sheet(fromCloud: $0, dockName: dockName) })
case "sheet_nfc":
    if let id = obj["sheet_id"] as? String, let uid = obj["uid"] as? String {
        SheetStore.shared.setTagUid(id, uid)   // applies to whichever sheet matches
    }
```

Add `Sheet(fromCloud:dockName:)` (parses id/name/address/link/model/tag_uid). Add `sendSheetNfc`. In `SheetsView.learnTag`, after `setTagUid`, also `Task { await cloud.sendSheetNfc(sheetId: id, uid: read.uid, programmed: false) }`.

- [ ] **Step 3: Build** — `xcodebuild … build` succeeds.

- [ ] **Step 4: Commit**

```bash
git commit -am "iOS: receive inherited sheets, apply + send NFC over the socket"
```

---

### Task 7: iOS — UI: Dock-Label tag + read-only configure

**Files:**
- Modify: `ios/App/Sources/SheetsView.swift` (row tag; configure panel variant)

**Interfaces:**
- Consumes: `Sheet.dockName` (Task 5).

- [ ] **Step 1: Implement the row tag** — in `sheetRow`, when `s.dockName != nil` render a `minitag`-style chip **"Dock · \(name)"** beside the palette dot / NFC badge.

- [ ] **Step 2: Implement the configure variant** — in `configureOverlay`, when `s.dockName != nil`: replace the Rename/Remove rows with a line **"This label lives on \(dockName)"**; keep the NFC "Set up / Re-learn" row (which now also writes back via Task 6). Local-only sheets keep the full panel.

- [ ] **Step 3: Build + screenshot** — build for the simulator with a `--demo-sheets` hook that seeds one local + one dock sheet (same debug pattern used earlier), screenshot the rows + a Dock-Label configure panel to confirm the tag and the lighter panel. Strip the debug hook before committing.

- [ ] **Step 4: Commit**

```bash
git commit -am "iOS Sheets: Dock-Label tag + read-only configure for inherited sheets"
```

---

## Self-Review

**Spec coverage:** two-zone record → Task 1; snapshot sync + relay + NFC fan-out → Tasks 2, 4; connect/pair/unpair triggers → Task 3; app merge/de-dupe + tag + read-only + NFC write-back → Tasks 5–7; "only paired phones / never in console" → Global Constraints + Task 2 push helper (no `/api` change). Dock Light empty list → falls out (registry empty). Field-test caveat → noted in spec; hardware step outside the plan.

**Placeholder scan:** Task 2/6 have "verified in the field test" where no unit harness exists (socket layer) — acceptable, not a code placeholder. Task 4/5 say "read the file first" for real signatures (`SheetRegistry.add`, whether `Sheet` is testable in RePaperKit) — these are genuine unknowns to resolve at implementation, not skipped work.

**Type consistency:** `sheet_id`/`id` = landing name throughout; `dock_sheets` message shape identical in Task 2 (producer) and Task 6 (consumer); `tag_uid`/`tag_programmed` names consistent cloud↔dock↔app; `dockName` consistent Task 5→7.
