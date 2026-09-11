/* Dock-Labels & sheet sync. Task 1 covers the db accessors directly (in-memory);
   Tasks 2-3 exercise the running server (spawned, like mirror.test.mjs).
   Run from cloud/ with Node 22:  npx tsc && node --test test/sheets.test.mjs */
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
  assert.equal(db.dockSheets("dockA").find((s) => s.sheet_id === "kitchen").tag_uid, "0455AA");

  // Dock re-publishes a snapshot WITHOUT desk and WITHOUT nfc → desk removed, kitchen keeps its tag + new name
  db.syncDockSheets("dockA", [
    { id: "kitchen", name: "Kitchen door", address: "A1", link: "https://x/kitchen", model: '{"width":296,"height":128,"palette":"BWR"}' },
  ]);
  const rows = db.dockSheets("dockA");
  assert.equal(rows.length, 1);
  assert.equal(rows[0].sheet_id, "kitchen");
  assert.equal(rows[0].name, "Kitchen door");
  assert.equal(rows[0].tag_uid, "0455AA");
  assert.equal(rows[0].tag_programmed, 1);
});
