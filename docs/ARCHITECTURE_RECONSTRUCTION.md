# PokeClaw Architecture Reconstruction

This document exists to keep the next refactor wave behavior-safe.

The goal is not "rewrite everything." The goal is:

- fewer regressions
- clearer ownership boundaries
- better QA targeting
- easier future feature work
- a codebase that is closer to product-ready without drifting from current expected behavior

## Non-Negotiables

- Do **not** change product behavior unless the change is a confirmed bug fix.
- Keep the current persisted config/MMKV keys compatible unless a migration is explicitly planned and tested.
- Every refactor must declare:
  - its scope
  - its invariants
  - the QA bundle that must be rerun
- No broad rewrite across unrelated subsystems in one go.

## Task Context Contract

- The chatroom itself is multi-turn in both Cloud and Local.
- Background monitors stay isolated from chat history.
- Quick tasks are just task templates; they follow the same task pipeline as typed tasks.
- Cloud tasks launched from the main chatroom may inherit the active chatroom history.
- Local tasks stay prompt-only for now; they should not pretend to carry the full chat transcript into the task engine.
- Any future "explicit handoff" for Local must be added as a product feature, not as an accidental side effect.

## Current Hotspots

### 1. `ComposeChatActivity` is still too broad

It currently owns:

- chat history
- model loading
- local/cloud switching
- send routing
- task orchestration callbacks
- permission gating
- auto-return UI updates

This makes regressions easy because UI, runtime state, and task coordination are all coupled together.

### 2. Task state is split across too many places

Current task lifecycle information is spread across:

- `AppViewModel`
- `TaskOrchestrator`
- `ForegroundService`
- `FloatingCircleManager`
- `ComposeChatActivity`
- chat message state

This is why stop-flow, auto-return, and same-session restoration are historically fragile.

### 3. Accessibility and permission state is scattered

Permission truth currently crosses:

- `SettingsActivity`
- `ComposeChatActivity`
- `ClawAccessibilityService`
- `BaseTool`
- `AutoReplyManager`

This area is better than before, but it is still easy to reintroduce drift between:

- "enabled in system settings"
- "service is bound"
- "tool can safely run now"

### 4. Local model lifecycle still spans multiple layers

The current local model path touches:

- `LocalModelManager`
- `EngineHolder`
- `LocalLlmClient`
- `ComposeChatActivity`
- `LlmConfigActivity`

This is survivable, but not yet clean enough for aggressive device-compatibility work.

### 5. QA knowledge is rich but still too manual

`QA_CHECKLIST.md` has a lot of real value now, but the project still needs:

- clearer release gates
- smaller targeted rerun bundles
- stronger mapping from refactor class → required QA bundle

## Reconstruction Strategy

Use a **phased strangler approach**, not a rewrite.

Each phase should land as a small, reviewable set of commits with a matching regression bundle.

## Phase Overview

- **Phase 0 — QA Gate First**: freeze checklist discipline and rerun bundles so refactors stop happening against a fuzzy test baseline
- **Phase 1 — Chat Runtime Extraction**: move chat runtime/model lifecycle out of `ComposeChatActivity`
- **Phase 1b — Conversation Persistence Boundary**: move conversation save/restore and current-conversation identity into a dedicated store
- **Phase 2 — Task Session Store**: make live task/session truth authoritative in one place
- **Phase 2b — Task Flow UI Boundary**: move task-send/task-event glue out of `ComposeChatActivity`
- **Phase 2c — Active Task Shell Boundary**: move top-bar active-task/monitor shell state and stop actions into a dedicated controller
- **Phase 3 — Permission / Accessibility Truth Boundary**: keep system permission truth and app-visible state aligned
- **Phase 4 — Structured Monitor Targets**: replace raw contact-name-only monitor requests with a structured app-aware target model
- **Phase 5 — Local Model Lifecycle Cleanup**: isolate device compatibility, import, fallback, and engine bring-up from UI code
- **Phase 6 — Release / Distribution Surface**: make signed upgrades, release artifacts, and public update behavior boring and predictable
- **Phase 7 — RC QA Sweep**: rerun the full release checklist once the reconstruction phases above stop moving

## Phase 0 — QA Gate First

Before broad refactors, freeze the test rules.

### Deliverables

- `QA_CHECKLIST.md` clearly states:
  - current coverage state
  - release gates
  - refactor regression bundles
- blocked vs fixed vs unverified is always explicit

### Exit Criteria

- no more pretending the master sheet is 100% rerun when it is not
- every future refactor can name its rerun bundle up front

## Phase 1 — Chat Runtime Extraction

### Status

- Landed on `main` as `fc788d9`
- Compile-gated and device-smoked on Pixel 8 Pro

### Goal

Slim `ComposeChatActivity` down so it stops directly owning every runtime concern.

### New boundary

Extract a `ChatSessionController` that owns:

- local/cloud runtime client selection
- chat send pipeline
- local model load / unload
- chat-side session state
- model status updates that belong to runtime, not raw UI

### Keep in `ComposeChatActivity`

- lifecycle wiring
- Compose bindings
- navigation / Activity-level intents
- view-state observation

### Must Preserve

- current chat vs task routing
- same visible status labels
- same session restore behavior
- same model switch UX

### Mandatory QA bundle

- `H2`, `H2-b`, `H2-c`, `H4`, `H4-b`
- `Q4-1`, `Q4-2`, `Q5-1`, `Q5-1b`
- `Q7-*`
- `LQ1-LQ13`

## Phase 2 — Task Session Store

### Status

- Landed on `main`
- Current landing scope: `TaskSessionStore` now owns live task-session truth and `TaskOrchestrator` / `AppViewModel` / `ChannelSetup` read from it instead of duplicating ad-hoc task metadata

### Goal

Create one authoritative task-session state source.

### New boundary

Introduce a `TaskSessionStore` or equivalent state holder that owns:

- idle/running/stopping/completed state
- current task id
- current task text
- current task channel/message linkage
- stop requested / safe unwind state
- auto-return intent metadata

### Current dependents that should observe instead of ad-hoc syncing

- `TaskOrchestrator`
- `AppViewModel`
- `ForegroundService`
- `FloatingCircleManager`
- `ComposeChatActivity`

### Must Preserve

- floating pill behavior
- top-bar stop behavior
- auto-return semantics
- same conversation restoration

### Mandatory QA bundle

- `F1-F6`
- `I1-I3`
- `L1`, `L3`
- `Q7-*`
- `S2`, `S3`, `S5`, `S7`, `S8`

### Early smoke evidence

- Local quick-task fill still reaches task-mode input correctly
- Task shell still enters `Task running...` + `Stop`
- Stop request still safely unwinds and returns to idle shell on `ComposeChatActivity`
- Fresh reinstall testing needs Accessibility + `POST_NOTIFICATIONS` restored first, or the smoke gets polluted by permission prompts instead of task-session logic

## Phase 1b — Conversation Persistence Boundary

### Status

- Landed on `main`
- Current landing scope: `ConversationStore` now owns current conversation identity, markdown save/restore, and sidebar refresh flow instead of leaving `ComposeChatActivity` to stitch together `KVUtils + ChatHistoryManager` directly

### Goal

Pull conversation persistence glue out of the Activity so chat UI work stops being coupled to file/KV details.

### New boundary

`ConversationStore` owns:

- current conversation id
- restore-last-conversation lookup
- save-current-conversation persistence
- switch-conversation persistence handoff
- sidebar conversation summary refresh
- rename/delete wrappers over markdown history

### Keep in `ComposeChatActivity`

- message list state
- lifecycle hooks
- chat/task UI bindings
- controller wiring
- task-specific side effects

### Must Preserve

- same current-conversation restore behavior after relaunch
- same sidebar contents and ordering
- same new-chat semantics
- same visible chat history contents

### Mandatory QA bundle

- `P7-1`, `P7-2`, `P7-3`
- `Q7-7`
- one cold relaunch restore smoke

### Early smoke evidence

- Cold relaunch still restored `chat_1775851530681` with 9 saved messages
- logcat confirmed `Restored 9 messages from conversation chat_1775851530681`
- foreground UI still showed the existing `ay pong` / `Hello! How can I help you today?` conversation instead of a blank shell

## Phase 2b — Task Flow UI Boundary

### Status

- Landed on `main`
- Current landing scope: `TaskFlowController` now owns task-mode send flow, monitor start wiring, typed `TaskEvent` rendering, and task cleanup instead of leaving `ComposeChatActivity` to mix task orchestration glue with UI shell work

### Goal

Pull task-specific UI flow out of the Activity so task orchestration work stops being coupled to Compose bindings and chat persistence code.

### New boundary

`TaskFlowController` owns:

- task-mode send entry
- accessibility / notification gating for task-mode execution
- monitor-task start flow
- typed `TaskEvent` rendering into chat messages
- task cleanup and post-task local-runtime reload

### Keep in `ComposeChatActivity`

- lifecycle hooks
- Compose bindings
- sidebar / conversation selection
- model switch shell wiring
- top-level navigation intents

### Must Preserve

- same task-entry UX from chat/task surfaces
- same in-app Settings redirect when Accessibility is missing
- same monitor stay-in-app behavior
- same task result rendering and same-session preservation

### Mandatory QA bundle

- `F1-F3`
- `K1-K3`
- `Q7-2`, `Q7-7`
- one task-intent smoke through the debug receiver path

### Early smoke evidence

- Debug task broadcast `battery` still reached the chat shell after the extraction (`TaskTriggerReceiver: Received task via broadcast: battery`, `ComposeChatActivity: Auto-task from intent: battery`)
- Missing Accessibility still redirected task flow into in-app `SettingsActivity` instead of silently failing
- Cold start is no longer blocked by Android 15 foreground-service restrictions because app-start `ForegroundService.start()` now fails closed instead of crashing the process

## Phase 2c — Active Task Shell Boundary

### Status

- Landed locally, pending commit
- Current landing scope: `ActiveTaskShellController` now owns active monitor/task shell state, periodic `AutoReplyManager` polling, and Stop / Stop All actions instead of leaving `ComposeChatActivity` to poll and mutate task-shell state directly

### Goal

Pull the active-task top bar out of the Activity so shell-state polling and stop actions stop drifting from the rest of the task flow.

### New boundary

`ActiveTaskShellController` owns:

- active monitor/task shell polling cadence
- top-bar task list state exposed as `StateFlow`
- per-monitor stop action
- stop-all behavior for active task + monitor shells
- monitor display names as rendered shell state

### Keep in `ComposeChatActivity`

- lifecycle hooks (`onResume` / `onPause`)
- Compose bindings (`collectAsState`)
- toast display
- navigation / settings entry points

### Must Preserve

- same `Monitoring: ...` top bar
- same expand → `Stop` affordance
- same stop-all behavior
- same in-app permission guidance when debug/task entry hits monitor requirements

### Mandatory QA bundle

- `C1`, `C3`
- `L3`, `L4`
- one expand/stop UI dump
- one debug `autoreply on ...` receiver smoke proving it routes through the normal monitor flow

### Early smoke evidence

- UI dump after controller extraction still showed `Monitoring: Mom`; expanded state rendered `Mom` + `Stop`
- tapping `Stop` still disabled auto-reply and cleared the shell state
- debug `autoreply on mom` no longer directly injects a ghost monitor; it is rewritten to `monitor mom on WhatsApp` and follows the same in-app permission/task flow as the real product path

## Phase 3 — Permission / Accessibility Coordinator

### Status

- Landed on `main` as `4c4d49d`
- Current landed scope: `AppCapabilityCoordinator` now centralizes app capability truth, splits `Disabled` vs `Connecting` vs `Ready`, and gates notification-access auto-return behind an explicit pending-return flag

### Goal

Make permission truth explicit and shared.

### New boundary

Introduce a coordinator/repository that distinguishes:

- configured in system settings
- bound/connected right now
- safe to run tool right now
- pending return-to-app flow

### Must Preserve

- current permission prompts
- stay-in-app monitor start
- current Settings flows
- reconnect waiting behavior

### Mandatory QA bundle

- `K1-K6`
- `J4`
- `L5`, `L5-b` when external sender is available
- Telegram monitor incoming-message cases only count when an external sender path (second account or bot) is available

### Early smoke evidence

- Fresh reinstall after `adb install -r` can clear `enabled_accessibility_services`; app Settings now shows `Disabled` instead of stale `Enabled`
- Re-enabling Accessibility via secure settings reproduces the enabled-but-rebinding state; app Settings now shows `Connecting` instead of collapsing it into `Disabled`
- Notification Access row now derives from system listener settings and correctly shows `Disabled` when PokeClaw is absent from `enabled_notification_listeners`
- Notification-listener `onListenerConnected()` no longer drags SettingsActivity to foreground on every reconnect; return-to-app now only happens when the in-app permission flow explicitly armed a pending flag

## Phase 4 — Structured Monitor Targets

### Status

- In progress on `main`
- Current landing scope: monitor setup now carries `target + app` end-to-end instead of dropping app selection and collapsing everything back to WhatsApp

### Goal

Make monitor setup app-aware first, then stable-identifier-aware second.

### New boundary

Split monitor setup into a structured target model that carries:

- user-facing label
- selected app
- app package for notification matching
- future alias / stable-id expansion point

### Must Preserve

- existing `monitor Mom on WhatsApp` behavior still works
- monitor still stays in-app
- stop flow still works from the top shell
- visible UX stays the same apart from the chosen app actually being honored

### Mandatory QA bundle

- `C1`, `C4`, `C5`, `C6`
- `L3`, `L4`, `L5`, `L5-b`
- parser/unit coverage for free-text variants (`Telegram`, `Messages`, default WhatsApp)

### Early smoke evidence

- `MonitorDialog` now surfaces the same supported app list used by monitor routing: `WhatsApp`, `Telegram`, `Messages`, `LINE`, `WeChat`
- live device screenshot confirms the dialog keeps `Telegram` selected instead of collapsing back to WhatsApp
- `MonitorTargetParserTest` now guards:
  - `monitor Mom on Telegram` -> `Mom + Telegram`
  - `watch Alex on sms` -> `Alex + Messages`
  - `monitor Caroline` does **not** get misparsed as `LINE`
  - no explicit app still defaults to `WhatsApp`

## Phase 5 — Local Model Runtime Consolidation

### Status

- In progress on `main`
- Current landing scope: `LocalModelRuntime` now owns shared engine acquisition, conversation opening, and single-shot inference so chat, local agent, and auto-reply stop carrying three separate lifecycle implementations

### Goal

Separate model file management from model runtime management.

### New boundary

Split concerns so:

- `LocalModelManager` handles files, downloads, validation, compatibility metadata
- a dedicated runtime layer handles engine acquisition, conversation creation/retry, single-shot inference, backend fallback, and live session ownership

### Why

This is the phase that makes lower-RAM support and more local models safer to add.

### Must Preserve

- GPU-first / CPU-fallback behavior
- truthful backend label
- current model selection semantics
- no regression in task/chat session handoff

### Mandatory QA bundle

- `H4`, `H4-b`
- `Q3-1`
- `Q5-1`, `Q5-1b`
- `LQ1-LQ13`
- device-specific local model smoke tests

### Early smoke evidence

- Cold launch after the Phase 4 refactor still lands on `ComposeChatActivity` with truthful local backend status `● gemma4_2b_v09_obfus_fix_all_modalities_thinking · CPU`
- Real Local UI send still works after runtime consolidation:
  - typed `say pong`
  - tapped the live send-button bounds
  - assistant replied `Pong! 🏓`
  - top status and assistant model tag both remained `CPU`
- Phase 5 landing scope is now compile-gated:
  - `ChatSessionController` conversation bring-up goes through `LocalModelRuntime.openConversation(...)`
  - `LocalLlmClient` tool-call conversations go through the same runtime boundary
  - `LlmSessionManager.singleShotLocal()` and `AutoReplyManager.generateReplyLocal()` both route through `LocalModelRuntime.runSingleShot(...)`
- Phase 5 state cleanup also started:
  - `LocalModelManager` now exposes shared device-support, built-in catalog, and active-model summary state
  - `LlmConfigActivity` and `ChatSessionController` now read the same RAM/support/downloaded state instead of maintaining parallel calculations
  - `LocalModelManager.downloadModel()` no longer mutates MMKV model selection as a side effect; caller boundaries now own "downloaded file" vs "selected local model"
- Current limitation:
  - the Phase 5 device smoke rerun is temporarily blocked by ADB attach state (`adb devices -l` returned no device after the landing)
  - do not infer a product regression from that block; rerun `H4/H4-b`, `Q3-1`, `Q5-1`, `Q5-1b`, and `LQ1-LQ13` as soon as the Pixel is visible again
- Practical QA note:
  - stale absolute tap coordinates are not a valid regression signal once the IME shifts the input bar
  - for Compose chat smoke, collapse any notification shade / foreground interruption first, then re-dump live bounds before tapping send
- Brittle-path hardening now in flight:
  - `send_message` and monitor contact matching no longer depend purely on exact display-name text; name and phone-number formatting now share one deterministic matcher
  - chat keyboard dismissal now has an explicit focus-clear path for tapping back into the chatroom, but the final focused-device QA should still be tracked under `H2-d`

## Phase 6 — Release / Distribution Surface

### Goal

Make upgrade behavior and public release quality boring and predictable.

### Scope

- release signing path
- update checker expectations
- public upgrade documentation
- checksum / artifact consistency

### Must Preserve

- current in-app update prompt semantics
- current stable-signing direction

### Mandatory QA bundle

- `Dbg-u1-Dbg-u3`
- `Rel-s1-Rel-s7`

## Phase 7 — RC QA Sweep

### Goal

Run the full release-candidate sheet only after the reconstruction phases stop moving.

### Scope

- rerun full `QA_CHECKLIST.md`
- convert `BLOCKED` vs `FAIL` vs `PASS` into a real release decision
- verify device coverage across local/cloud/runtime/permissions/release-upgrade paths

## What Should Not Happen

- No mega-branch that rewrites chat, task, accessibility, and models at once.
- No "cleanup" commit that also changes visible task behavior.
- No undocumented storage migration.
- No issue-thread claims that a fix is public unless it is actually in a public release.

## Decision Rule

If a proposed refactor does not make one of these easier:

- QA targeting
- ownership clarity
- behavior preservation
- device compatibility work
- future feature velocity

then it is probably not worth landing yet.
