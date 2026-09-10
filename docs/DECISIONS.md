# Open decisions

**How this file works:** every decision waiting for Rouven lives here, in plain
words, with what it means for users. When you decide, tell Claude — the entry is
then DELETED (the reasoning moves into the docs) and whatever you chose gets
built. **Empty file = nothing to decide.**

---

## 1 · Dock Light and page privacy (from 10 Sep, needed before Dock Light ships to strangers)

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
