# Open decisions

**How this file works:** every decision waiting for Rouven lives here, in plain
words, with what it means for users. When you decide, tell Claude — the entry is
then DELETED (the reasoning moves into the docs) and whatever you chose gets
built. **Empty file = nothing to decide.**

---

## 1 · Tapping the phone on today's labels (from 10 Sep)

**Plain words:** Your two e-paper labels run old firmware. They cannot fill
their own NFC sticker — that ability just isn't in them yet. Newer labels will
do it automatically the moment they're added (that part is already built and
tested; it silently does nothing on old labels).

**What it means for the user:** with today's labels, "tap the phone on the
label to print" can't work out of the box — one label's sticker is empty, the
other contains factory leftovers the app can't use.

**Your options:**

- **A — wait.** Tapping simply works on future labels only. Today's labels are
  chosen from the list instead. Zero extra work for the user.
- **B — "learn the sticker".** When adding a label, hold the phone to it once;
  the app remembers whatever is on the sticker (even junk) and recognizes it on
  later taps. No writing. Works for the junk label, NOT for the empty one
  (nothing there to remember).
- **C — the phone fills the sticker.** When adding a label, hold the phone to it
  once and the phone writes the correct content itself. Works for BOTH labels,
  including the empty one. (This is the flow you had me remove — but as one
  guided moment during setup, not an extra chore afterwards.)

**Recommendation:** B and C combined: while adding, the app holds one
"touch the label now" moment — it learns the sticker if it can, writes it if
it's empty. After that, tapping always works, on every label you own.

---

## 2 · Dock Light and page privacy (from 10 Sep, needed before Dock Light ships to strangers)

**Plain words:** everywhere else, RePaper Cloud only ever sees *metadata* —
names, sizes, states — never the pages themselves. Dock Light is different by
design: the page travels through the cloud to reach the mirrored phone.

**What it means for the user:** someone printing on a Dock Light sends the
page's content through our server (it is deleted right after delivery, but it
was there).

**Your options:**

- **A — honest label.** Say it plainly in the console and docs: "Dock Light
  jobs travel through RePaper Cloud." Simplest, fine for the pilot.
- **B — end-to-end encryption.** The Dock Light encrypts each job for the
  mirrored device; the cloud stores only ciphertext it cannot read. More work,
  strongest story.

**Recommendation:** A now (pilot), B before Dock Light reaches paying
customers. The current implementation already deletes jobs after delivery and
holds them at most one hour.
