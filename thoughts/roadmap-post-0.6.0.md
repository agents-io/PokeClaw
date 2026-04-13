# Post-0.6.0 Roadmap

Created: 2026-04-12

This document records the approved roadmap after shipping `0.6.0`.
Scope is intentionally narrow:

- harden the phone-side runtime
- improve QA discipline
- build the remote runtime moat in the right order
- keep vertical product logic and channel explosion out of core

## Status checkpoint (2026-04-13)

- `0.1 QA harness v2`: done
  - replaced the fixed-sleep bash runner with `scripts/qa_harness_v2.py`
  - per-trial lifecycle ownership
  - logcat-based completion detection
  - explicit `PASS / FAIL / CANCELLED / BLOCKED / TIMEOUT / INFRA_ERROR`
  - repeated-trial success-rate output
- `0.3 State/trace cleanup slice 1`: in progress
  - introducing a runtime-only execution event log
  - task / monitor / model/system events now have their own structured truth
  - chatroom behavior stays unchanged for now; it remains a projection layer
- `0.2 Local/OEM hardening`: active in parallel on the `0.6.2` hardening line
  - CPU-safe mode / GPU failure memory shipped
  - accessibility disconnect truthfulness shipped
- `0.4 GitHub CI automation`: not started yet

## Product thesis

PokeClaw should be treated as a **mobile-native phone harness with a product shell**, not just a chat app with tools.

The real moat is:

- phone-side execution
- reliable runtime state
- repeatable QA/eval discipline
- channel-independent remote control foundation

Cloud and Local are just brains plugged into the same harness.

## What the research showed

### Where competitors are ahead

- **Andclaw**
  - stronger remote-bridge architecture
  - explicit remote session model
  - per-channel bridge health/status state

- **ApkClaw**
  - broader channel matrix
  - more platform-specific routing quirks already handled
  - stronger proactive messaging support across more platforms

- **droidclaw**
  - stronger web/device pairing story
  - stronger remote-dashboard framing
  - less relevant as a direct in-app channel benchmark

### Where PokeClaw is already strong

- one APK with:
  - local LLM
  - cloud LLM
  - phone execution
  - channel ingress
  - user-facing product shell
- real task execution on a real Android device
- strong WeChat implementation depth
- quick-task/checklist culture that is already harness-like

### Main architectural gap

Today the channel layer is still shaped mostly like:

- remote message in
- start a task
- push result back

That is usable, but it is still **task ingress**, not yet a true **remote phone runtime**.

The core missing pieces are:

- unified remote session model
- channel-independent bridge interface
- persistent remote session store
- execution trace separated from chatroom

## Approved sequence

## Phase 0 — Foundation

### 0.1 QA harness v2

Replace the broken bash runner with a real host-side harness.

Required properties:

- per-trial polling
- logcat-based completion detection
- no fixed sleep between trials
- one trial owns one full lifecycle
- force-stop / relaunch support
- timeout / retry / cancelled / blocked classification
- repeated-trial success-rate reporting

Reason:

- the old runner broke because it fired new trials before the previous task had actually unwound
- Cloud tasks must be judged by success rate, not a single run
- manual adb remains the final signoff path, but not the only path

### 0.2 Local/OEM hardening

Focus:

- GPU/OpenCL failure memory per device
- safer fallback defaults
- service health detection for Accessibility / Notification Listener
- truthful degraded-state handling

Reason:

- expected crash/support pain is still concentrated in Local runtime and OEM behavior

### 0.3 State/trace cleanup slice 1

Start separating:

- execution trace
- task/session state
- user chatroom projection

Reason:

- current chatroom still mixes user conversation, debug/system events, and task trace
- remote runtime work should not be built on top of that coupling

### 0.4 GitHub CI automation

Install signing secrets and dry-run release workflow.

Reason:

- low effort
- recurring operational payoff
- already mostly implemented in code

## Phase 1 — Remote runtime architecture

This is the real moat. Do not start channel explosion before this exists.

### 1.1 Unified remote session model

Build shared models for:

- remote channel
- incoming message
- reply target
- remote session
- remote attachments/outbound payloads

### 1.2 Channel-independent bridge interface

Each channel should plug into one shared remote bridge contract:

- start/stop/reconnect
- health/status
- send text/media/progress
- inbound callback

### 1.3 Remote session store

Persist routing/session truth separately from chatroom text.

Must stop relying on "last sender" as the main truth.

### 1.4 Execution trace / chatroom separation

Execution events should become first-class records.
Chatroom should be one projection, not the only storage layer.

## Phase 2 — Product polish after foundation

Only start after Phase 1 is solid.

### 2.1 Remote phone shell behavior

Examples:

- remote status
- remote stop
- remote progress updates
- remote result/media echo

### 2.2 Remote conversation continuity

Per-channel remote continuity should be explicit and durable.

### 2.3 Channel-specific UX polish

Only after the shared bridge/runtime model is in place.

Examples:

- better progress/typing UX
- clearer degraded/auth-expired states
- channel-specific reply polish

## Keep out of core

Do not couple these into the core runtime yet:

- WhatsApp as a remote control channel
- booking/calendar/product-specific assistant logic
- web dashboard / pairing platform
- new channel explosion before the bridge model is finished

## Dependency map

- `0.1 QA harness v2` supports every later phase
- `0.2 Local/OEM hardening` can run in parallel with `0.1`
- `0.3 State/trace cleanup` should begin before Phase 1.4
- `1.1`, `1.2`, `1.3` are the minimum viable remote foundation
- `1.4` should land before major remote UX work
- Phase 2 should not start until Phase 1 is stable

## Immediate next step

Continue **Phase 0.3 State/trace cleanup slice 1**.

Reason:

- remote runtime work still should not be built on top of chatroom/debug coupling
- `0.1` is already in place, so the next leverage point is separating execution truth from user conversation
