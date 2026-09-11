# Dock-Labels & Sheet Sync — Design

Date: 2026-09-11
Status: approved (brainstorm), ready for implementation plan

## Problem

A phone paired to a Dock via Print2Go can print the Dock's jobs, but it can
only print them on sheets it added locally. The Dock's own sheets ("labels")
are invisible to the phone, so a person can't grab a Dock job and print it on
one of the Dock's sheets. Separately, the same physical sheet added in both
places shows up twice with no relationship between the copies.

## Goal

A phone paired to a Dock **inherits that Dock's sheets** as first-class,
printable targets, shown tagged as **Dock-Labels**. Locally-added sheets stay.
The same physical sheet in both places collapses to one row. NFC tag info is
shared so tap-to-print works everywhere and only ever has to be learned once.

## Identity

A sheet's canonical identity is its **landing name** — the token in its QR/NFC
link. The app already sets `Sheet.id = landing.name`, and the Dock derives the
same. All matching and de-duping keys off the landing name. No fuzzy matching.

## Data model — two zones, different ownership

One logical sheet record, two zones:

- **Definition zone — Dock-owned, one-way (Dock → cloud → paired phones):**
  `landing link` (carries the BLE key), `model`, `address`, display `name`.
  Phones treat these as read-only.
- **NFC zone — shared, any-writer (any device → cloud → everyone),
  last-writer-wins:** `tag_uid`, `tag_programmed`.

New cloud table **`dock_sheet`**, keyed by `(dock_id, sheet_id)`, holding both
zones. It persists. It is **only ever relayed to phones paired to that Dock**
(`mirror_from == dock_id`) and is **never shown in the console UI**. The landing
link lives in this table at rest — the accepted "possession = access"
trade-off (a QR anyone can scan), scoped to trusted paired devices.

**Phone-local sheets** (scanned directly, not on any Dock) remain in the phone's
existing local `SheetStore`, private to that phone. They are **not** synced up
to the Dock or sideways to other phones. NFC is the *only* write-back path.

## Sync transport

Rides the existing device WebSocket, mirroring the `mirror_job`/peers pattern.
No new connection.

- **Dock → cloud (snapshot):** on connect and after any `SheetRegistry` change,
  the Dock sends its full current list
  `{ t: "sheets", sheets: [{ id, name, address, link, model, tag_uid, tag_programmed }] }`.
  The cloud upserts into `dock_sheet` for that `dock_id` and **deletes any row it
  no longer lists** — adds/renames/removals all propagate by diffing snapshots.
  Definition fields in a message from a *phone* are ignored (Dock is
  authoritative there).
- **Cloud → phone:** `{ t: "dock_sheets", dock, dock_name, sheets: [...] }` sent
  when the phone connects (if it has `mirror_from`), the Dock's sheets change, or
  the phone pairs/unpairs. Unpair sends an **empty set** to clear. The phone
  replaces its inherited set for that Dock wholesale.
- **NFC write-back (only bidirectional path):**
  `{ t: "sheet_nfc", sheet_id, uid, programmed }` from any device. The cloud
  updates just the NFC zone of that `dock_sheet` row, then fans the new value out
  to the Dock **and** every paired phone (`mirrorPhones` + `sendToDevice`).

Persistence means a Dock going offline doesn't wipe a phone's Dock-Labels, and a
reconnecting phone gets the current set on hello. A Dock Light publishes an
empty list, so its phones inherit nothing — correct by construction.

## App behavior (iOS)

- **Storage:** `SheetStore` keeps persisted local sheets as today, and gains an
  in-memory `dockSheets` set populated from `dock_sheets` (replaced wholesale;
  empty when unpaired). The displayed list is a **merge of both, de-duped by
  landing name.** A sheet present in `dockSheets` is a **Dock-Label** whether or
  not it's also local (the de-dupe rule). `find()`/`findByUid()` span the merged
  set, so inherited sheets are valid for tap-matching, auto-print, and Print2Go
  sheet choice.
- **Row UI:** a Dock-Label shows a small **"Dock · <DockName>"** tag beside the
  palette dot and NFC badge. Local-only sheets stay untagged.
- **Configure panel:** a Dock-Label is lighter — name/size/palette/NFC status
  plus **"This label lives on <DockName>"**, and **no Rename / no Remove** (it's
  a live mirror). A purely-local sheet keeps the full panel.
- **NFC:** "Set up tapping / Re-learn" stays available on every sheet. On a
  Dock-Label, learning the UID sends `sheet_nfc` up so cloud + Dock converge; if
  the Dock already published a `tag_uid`, the phone inherits it and tapping works
  with no learning step.
- **Printing:** unchanged and identical for both kinds — the phone renders from
  the landing link and sends over BLE. A Dock-Label is a sheet whose definition
  arrived from the Dock instead of a QR scan.

## Dock behavior

`dockd` publishes its `SheetRegistry` snapshot on connect and after any change,
and handles the `sheet_nfc` write-back by updating the tag info in its registry
(useful because the Dock's RC522 identifies sheets by UID). A Dock Light
publishes an empty list.

## Edge cases

- **Dock offline:** rows persist; Labels stay; printing is phone→sheet over BLE,
  so a Dock-Label still prints with the Dock powered off.
- **Unpair:** empty set clears Dock-Labels; a sheet also scanned locally survives
  in the persisted local store and reappears untagged — no data loss.
- **Sheet removed on Dock while phone offline:** reconnect delivers the smaller
  snapshot → phone drops it.
- **Same sheet on two Docks / NFC race:** de-dupe by landing name collapses to
  one row; NFC is last-writer-wins on a trivial field.

## Testing

- **Cloud:** extend the existing suite (`mirror.test.mjs` style) — snapshot
  upsert/diff, `dock_sheets` push on connect/change/pair, `sheet_nfc` fan-out to
  Dock + peers, unpair clears.
- **Dock:** unit-test the snapshot builder and the `sheet_nfc` registry update
  (there is a `tests/` dir).
- **App:** keep the merge/de-dupe as a small pure function so it is checkable;
  the rest is manual.
- **Field test caveat:** the pilot is a Dock Light with no sheets, so end-to-end
  inheritance needs a Dock that has sheets (the Mac Dock or a full Dock).

## Out of scope (YAGNI)

No console UI for sheets; no syncing phone-local sheets upward; no incremental
sheet events (snapshot-diff only); NFC stays the sole write-back path.
