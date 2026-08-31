# Community Issue Reply Templates (v0.7.0 cycle)

> Ready-to-paste replies for the 6 OEM-specific open issues PokeClaw cannot
> reproduce in-house (Pixel + one Xiaomi Redmi only).
>
> These are **drafts** — Nicole's call to actually post. Each one is tuned for
> the issue body / OEM / Android version on the GitHub thread (cross-checked
> 2026-05-26). Read once before posting; tweak for tone if anything feels off.

---

## #42 — Xiaomi Poco F7 (HyperOS 3) — Accessibility / Notification Access disconnect

```
Quick update for HyperOS 3 watchers.

v0.7.0 ships this cycle and includes a couple of items relevant to this
class of disconnect:

1. New GPU/OpenCL + RAM + ABI diagnostics inside the debug-report.zip.
   When Accessibility disconnects on HyperOS, the next debug-report will
   tell us whether the kill correlates with a backend GPU failure on
   inference, which is a separate bug we have been tracking under #41.
2. The foreground service hardening from v0.6.8 / v0.6.9 stays in place,
   plus a small TaskOrchestrator hotfix lined up for the next signed
   release.

What I cannot yet confirm without a fresh HyperOS 3 reproduction:
- whether the Accessibility kill is the foreground-service-not-started
  race we fixed in v0.6.9, or
- whether HyperOS is killing the service even after the foreground
  notification is visible, which would require an Autostart / Battery
  whitelist policy change in MIUI Optimization.

Could you install v0.7.0 once it lands, repro the disconnect once, and
then immediately go Settings → About → Share Debug Report and attach
the zip? The new fields in summary.txt (OpenCL libraries, Backend
health) make HyperOS triage a lot faster on my side.

Thanks for your patience — this is one of the highest-priority bugs
in the backlog right now.
```

---

## #48 — Xiaomi 23013RK75C (Android 15) — empty body

```
Hi — thanks for opening this. The issue body is currently empty, so I
do not yet know what failed on your Xiaomi 23013RK75C.

Could you do two things:

1. Reinstall the latest signed build (v0.7.0 lands this cycle) and try
   to reproduce whatever you originally hit.
2. Generate a fresh debug-report.zip from Settings → About → Share Debug
   Report and attach it here, along with a one-line description of what
   you tapped, what you expected to happen, and what actually happened.

v0.7.0 adds OpenCL / RAM / Accessibility diagnostics directly into the
debug-report summary, so Xiaomi-specific service issues are a lot
easier to triage now without a back-and-forth.
```

---

## #23 — Xiaomi Redmi 14 Pro — local LLM crash / system UI restart

```
Update on this — I have not been able to reproduce the system-UI restart
on the Pixel 8 Pro I use day-to-day, which is consistent with this being
a Redmi-side LiteRT GPU path issue rather than a generic crash.

Two things shipped in the current cycle that should help:

1. v0.6.9 added a conservative CPU-first policy on devices where the
   GPU path has crashed before. After the first GPU init failure on
   Redmi 14 Pro, subsequent runs should pin to CPU and not restart
   the system UI.
2. v0.7.0 (this cycle) extends the debug-report with the actual OpenCL
   library presence check and a Backend health summary. If your device
   reports "OpenCL libraries found: (none)" we know the GPU path will
   never work and we can pin CPU-mode permanently.

Could you reinstall v0.7.0 once it is signed and tagged, reproduce the
local-model crash once, and attach a fresh debug-report.zip?

If the new summary.txt shows OpenCL libraries present but inference
still crashes, that points to a different LiteRT-LM runtime bug and we
will pursue that separately.
```

---

## #17 + #16 — Samsung Galaxy A52 5G (downloading fails / cannot open)

```
Bundling the response since both threads are the same device.

I do not have a Galaxy A52 5G in-house, but Samsung Remote Test Lab
gives me free time-bounded access to real Samsung hardware and I plan
to reproduce both there in the next cycle (target: v0.7.x).

For now, two checks that would help me triage faster:

1. Are these reproducing on a current signed build (v0.6.12 or
   v0.7.0 once it ships)? Earlier 0.5.x builds had a model-download
   resume bug that we believed fixed in v0.6.x.
2. If you can attach a debug-report.zip from Settings → About → Share
   Debug Report, the v0.7.0 build will include device ABI + RAM +
   storage info. For Samsung A52 specifically, RAM and storage
   headroom both matter for the E2B model (~2.6 GB).

I will post back when Remote Test Lab confirms the repro.
```

---

## #29 — Crash when using local model (device unknown)

```
Hi — the report does not specify which device this happened on. The
v0.7.0 build adds device manufacturer / model / ABI / RAM / OpenCL
library presence directly to the debug-report summary, which is the
most reliable way to triage local-model crashes across OEMs.

Could you install v0.7.0 once it lands, reproduce the crash once, and
then immediately attach a fresh debug-report.zip from Settings → About
→ Share Debug Report?

If the crash is OEM-specific I will route it to the appropriate
existing thread (#23 Xiaomi Redmi, #14 OpenCL, #42 HyperOS) and close
this one as a duplicate.
```

---

## #2 — Xiaomi Redmi Note 14 Pro — WhatsApp tool drift

```
Re-reading this with fresh eyes after the v0.7.0 cycle.

The behavior you described — "forgot how to operate WhatsApp and tapped
on profile image" — sounds like the LLM lost the conversation context
mid-task and re-grounded against the screen it could see, which on
WhatsApp is often the contact's profile picture if the chat header is
in the visual frame.

Two relevant changes this cycle:

1. v0.7.0 ships Persistent Global Instructions (Settings → Models →
   Global instructions). For WhatsApp-heavy users, putting something
   like "When sending WhatsApp messages, always use the send button at
   the bottom of the conversation, not the contact profile" in that
   field would harden against this regression specifically.
2. v0.7.0 adds Voice Input (mic icon in the chat composer), which
   removes a class of typing-related context loss from the prompt
   path on small-screen devices.

For Redmi Note 14 Pro specifically: I still need a fresh debug-report
to confirm the LiteRT backend is GPU-verified on your unit, since CPU
fallback adds enough latency that some app interactions can race the
LLM's planning step.

Could you install v0.7.0 (once tagged), set a Global instruction for
WhatsApp, and try the failing flow once? If it still fails, attach the
debug-report.zip.
```

---

## Posting hygiene

- Replies cite specific versions (v0.6.x → v0.7.0). Do **not** post until
  v0.7.0 is actually tagged + the GitHub release page is live, otherwise
  reporters will look for an APK that does not exist yet.
- Same-day reply policy (per `ARCHITECTURE_DECISIONS.md` D8) — these
  drafts are sized to be posted within 24 hours of v0.7.0 going live.
- For follow-up rounds after the reporter attaches a debug-report.zip:
  - if OpenCL is missing → set `KEY_LOCAL_BACKEND_PREFERENCE=CPU` for
    that device class in a future build.
  - if Accessibility is the issue → check `recoverPendingGpuCrashIfNeeded`
    fired correctly.
  - if both look healthy but the bug persists → escalate to a real
    device via Firebase Test Lab or Samsung Remote Test Lab.
