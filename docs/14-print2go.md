# Print2Go — jobs on a Dock print on phones too

**Locked 11 Sep 2026.** The name is **Print2Go** (a Dock prints → a Go device).
This supersedes the v1 "console sets one mirror target on a Dock Light" model.

## The model

One relationship, two sides, **initiated from the phone**:

- A **Go device** can turn on **Print2Go** and pick one **Dock** to receive jobs
  from. It only ever mirrors Docks — never another Go app. It prints those jobs
  on **its own sheets** (added manually, as today). Auto-pulling the Dock's sheet
  list is a later feature (needs real syncing) — out of scope for v1.
- A **Dock** can turn on **Print2Go** ("Let phones print the jobs sent to this
  Dock"). One Dock feeds **many** phones. A **Dock Light** is a Dock with
  Print2Go permanently on and no sheets of its own.

## Where a job prints (shared sheet pool)

A job sent to a Print2Go Dock is claimable by the **whole pool**: the Dock's own
sheets *and* every connected phone. **First to start it wins**; the others drop
it. A Dock Light (no sheets) simply isn't a candidate itself, so only phones
claim. Coordination is the cloud's job — one atomic claim, so a job never prints
twice.

## Seats & sign-out

A claimed device is a **paid seat**. Signing out no longer removes the device —
it marks it **dormant** (signed-out) but keeps it in the fleet and on the books.
The "add this device?" confirmation therefore appears **once**, at the very first
claim, never again. (Consent decision "A": no repeated dialog; members flow
through the "Waiting for approval" screen; admins get an in-app **Approve**.)

## Settings surfaces

- **Phone (RePaper Go):** a Print2Go section — off by default; when on, a Dock
  picker (the org's Print2Go Docks). Offered once on first launch too. When on,
  the main screen prints jobs that arrive from that Dock like any shared page.
- **Dock:** a Print2Go toggle; when on, a live list of the Go devices printing
  from it. Dock Light shows it always-on.
- **Cloud console:** the device page shows the Print2Go relationships (a Dock's
  connected phones; a phone's source Dock).

## Data model (cloud)

- Dock reports `print2go: true|false` in its status payload; kind `dock-light`
  counts as always-on.
- A Go device row gets `mirror_from` = the Dock's device id (or null). Replaces
  the old per-Dock `mirror_to`.
- `mirror_job(id, dock_id, name, type, path, created, claimed_by, claimed_at)`:
  the shared claimable job. Recipients = the source Dock + all devices whose
  `mirror_from = dock_id`.
- Claim is atomic: `UPDATE mirror_job SET claimed_by=? WHERE id=? AND claimed_by
  IS NULL` — only the winner proceeds; losers drop it. Release on failure
  re-opens it. Deleted on print, purged after an hour.

## Build order

1. ✅ Cloud model + relay (phone-initiated pairing, fan-out, atomic claim) — 27-check test green, deployed.
2. ✅ Dock (0.0.27): Print2Go toggle, status flag, shared-pool claim in the run loop,
   connected-phones list. Local boot + render verified. **Pilot Pi still on 0.0.26 —
   push 0.0.27 when it's back on the network (was asleep at build time).**
4. ✅ Seat-preserving sign-out (cloud side): dormant device keeps its seat; re-sign-in
   reactivates. iOS app still needs the `signed_out` push handling + one-time consent (A).
3. ⏳ iOS Go: Print2Go setting + first-launch offer + Dock picker; **claim-on-print**
   (take → print → done/release, NOT claim-on-receive — first to actually print wins);
   handle the `signed_out` push; drop the repeated consent dialog (decision A).
5. Android parity — the whole thing, in the final Android pass.

**iOS claim semantics note:** the phone must NOT claim on receiving the push (an idle
open app would hog every job). It keeps a pending list from `mirror_job`, and only calls
`/take` (claim + page handover) when it actually starts printing (auto-print or user
choice); `/done` on success, `/release` on failure. `mirror_taken` removes a pending
entry another device grabbed.
