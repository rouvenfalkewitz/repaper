0.2.23 · 2026-09-14 · Discarding a waiting Print2Go job now cancels it for everyone (the Dock and every other phone), not just this device. Also: the discard button no longer stretches too tall when only one job is waiting; and the waiting-list state is now thread-safe.

0.2.22 · 2026-09-14 · Print2Go reliability + fixes: the waiting list is rebuilt from the cloud on every reconnect, so a job already printed or expired elsewhere no longer lingers as a ghost; a failed Print2Go print no longer leaves a duplicate that could reprint; swipe-to-discard rebuilt (the delete button now actually deletes and sits cleanly at the edge); the queue shows a clear paused state while a print runs; signing out clears the queue and inherited labels.

0.2.21 · 2026-09-14 · Fixes: tapping a sheet now prints a waiting Print2Go (inherited-label) job too — no more "nothing waiting to print" when a job is clearly there. Rebuilt swipe-to-discard so it tracks the finger, no longer fights the scroll, and shows a clean red panel.

0.2.20 · 2026-09-14 · Auto-print now happens only when "Cycle through sheets" is on. With it off, every job waits for a tap — even with a single sheet (before, one sheet always printed automatically).

0.2.19 · 2026-09-14 · Print2Go: prints onto a Dock sheet even when it was added without a QR link (the key now comes through). Swipe a waiting job left to discard it, and the queue is fully locked while a print runs.

0.2.18 (12 Sep 2026) — Fix: the waiting jobs can't be tapped or re-routed while a print is already running.
0.2.17 (12 Sep 2026) — Pick which Dock to print from right in Settings, with live online dots — no pop-up.
0.2.16 (12 Sep 2026) — Settings now matches the iPhone: an icon on every row and the right typography, and the how-to shows the real app mark.
0.2.15 (12 Sep 2026) — A closer match to the iPhone app: a two-step sign-in, a glowing GO ring, drawers you can swipe down to close, and the how-to told in pictures.
0.2.14 (12 Sep 2026) — Push notifications wake the app for Dock (Print2Go) jobs even when it's closed. Final polish to match the iPhone app.
0.2.13 (11 Sep 2026) — A whole new look to match the iPhone: a floating bottom bar with the GO ring, redesigned Sheets and Settings, scan-or-paste to add a sheet, and inherited Dock-Labels shown alongside your own.
0.2.12 (11 Sep 2026) — Print2Go: print the jobs sent to a Dock on this phone (choose the Dock in Settings). Signing in now joins the fleet directly.
0.2.11 (10 Sep 2026) — Receives jobs mirrored from a RePaper Dock Light and prints them like any shared page. Ready-screen how-to reads above the pictures.
0.2.10 (10 Sep 2026) — Tap-to-print reads even mangled NFC tags; unreadable tags show what they contain; sheet adding reports whether the tag was programmed.
0.2.9 (10 Sep 2026) — Sheets program their own NFC tag over Bluetooth when added (and can be re-programmed from the sheet options) — tapping a sheet now always works; a fresh tap-to-print look.
0.2.8 (10 Sep 2026) — The real brand lockup and icon; Settings reorganized (Printer · Sheets · Cloud); sheets’ NFC tags are programmed when added, so tap-to-print always works.
0.2.7 (10 Sep 2026) — Sign out in Settings: removes this phone from the fleet; sheets stay.
0.2.6 (8 Sep 2026) — New option: cycle through sheets — jobs print on each in turn.
0.2.5 (8 Sep 2026) — With a single sheet, jobs print without asking.
0.2.4 (8 Sep 2026) — Tap a sheet with your phone to print on it (NFC).
0.2.3 (8 Sep 2026) — New sheets appear on the main screen immediately; notifications on newer Android.
0.2.2 (8 Sep 2026) — Sheets register correctly on every panel type; remote diagnostics for support.
0.2.1 (8 Sep 2026) — Scan the QR to add a sheet; all panel colours supported — six-colour, seven-colour and grayscale.
0.2.0 (7 Sep 2026) — Sign in with your RePaper account — the phone joins your fleet by itself.
0.1.0 (7 Sep 2026) — The first RePaper Go: shows up in every app's print dialog and prints to sheets over Bluetooth.
