# PokeClaw Project State Snapshot

_Last updated: 2026-04-20 (local workspace session)_

This file is a living handoff for the current workspace state: architecture, runtime flows, dependencies, and active work.

## 1) Workspace + Build Baseline

- Repo path: `C:\Users\phane\StudioProjects\saarthi`
- Branch: `main`
- App module: `:app`
- Android SDK config (`app/build.gradle.kts`):
  - `compileSdk = 36`
  - `targetSdk = 36`
  - `minSdk = 28`
  - Java 17
  - Compose enabled
- App defaults (`app/build.gradle.kts`):
  - `versionCode = 20`
  - `versionName = 0.6.5`

## 2) Top-Level Architecture (Current)

Primary package root: `app/src/main/java/io/agents/pokeclaw`

- `ui/`
  - Chat + task UI shell (`ComposeChatActivity`, `ChatScreen`, `ChatSessionController`)
- `agent/`
  - LLM abstraction (`AgentService`, cloud/local clients, routing, tools bridging)
- `tool/`
  - Generic device/app action tools (`open_app`, `send_message`, screen/data actions)
- `service/`
  - Accessibility, notification listener, foreground service
- `floating/`
  - Floating circle/pill task status and stop affordance
- `debug/`
  - ADB-triggered task/config/backend debug receiver
- `utils/`
  - MMKV config/state, UI matching helpers, contact matching, logging helpers
- `TaskOrchestrator` + `TaskSessionStore`
  - Central task lifecycle, lock/state transitions, callback fan-out

## 3) Key Runtime Flows

### 3.1 Chat Send Path

`ChatScreen` -> `ComposeChatActivity` callback -> `ChatSessionController.sendChat(...)`

- Cloud mode: uses `LlmSessionManager.createCloudClient(...)` + chat history buffer
- Local mode: uses LiteRT conversation from shared runtime (`LocalModelRuntime`)
- Reply replacement: typing bubble `...` replaced in same conversation

### 3.2 Task Execution Path

`AppViewModel.startTask(...)` -> `TaskOrchestrator.startNewTask(...)`

Tiered routing in orchestrator:

1. Tier 1 deterministic route (`DirectIntent` / `DirectTool`)
2. Tier 2 skill route (`SkillExecutor`)
3. Tier 3 full agent loop (`AgentService.executeTask(...)`)

Task state and lifecycle:

- Lock and state: `TaskSessionStore`
- Status propagation: `TaskEvent`, `ForegroundService`, `FloatingCircleManager`, channel callbacks
- Cancellation: orchestrator `cancelCurrentTask()` + service cancel + UI status unwind

### 3.3 WhatsApp Send Tool Path

`send_message` (`SendMessageTool`) high-level steps:

1. Resolve/open app via `OpenAppTool.openAppWithInterceptHandling(...)`
2. Wait for active app window
3. Stabilize chat list/contact lookup (`ContactListUiUtils.prepareForContactLookup(...)`)
4. Search/scroll contact and tap
5. Find message input and type
6. Find send action or fallback enter

Recent stability hardening exists around:

- OEM chain-launch intercept dialog handling
- Search/list readiness recovery logic
- Overlay close heuristics (avoid top-bar false taps like camera)

### 3.4 Local LiteRT Runtime Path

Main files:

- `agent/llm/LocalModelRuntime.kt`
- `agent/llm/EngineHolder.kt`
- `agent/llm/LocalBackendHealth.kt`
- `agent/llm/LocalLlmClient.kt`

Behavior summary:

- Shared singleton engine (`EngineHolder`) reused across chat/tasks
- Conversation open retries with session-conflict guard
- Accelerator-aware failure handling with CPU fallback
- Backend label surfaced to UI status

## 4) LiteRT Backend Status (Current)

### 4.1 Manifest / Native Libs

`app/src/main/AndroidManifest.xml` includes optional GPU native lib declarations under `<application>`:

- `libvndksupport.so`
- `libOpenCL.so`

### 4.2 Backend Selection + Fallback

Implemented in `LocalModelRuntime.kt`:

- Backend candidates derived from preference (`KVUtils.getLocalBackendPreference()`):
  - `NPU` preference: `NPU -> GPU -> CPU`
  - `GPU` preference: `GPU -> CPU`
  - `CPU` preference: `CPU`
  - default: `GPU -> CPU`
- NPU config uses:
  - `Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)`

### 4.3 Accelerator Failure Handling

- `isGpuBackendFailure(...)`
- `isNpuBackendFailure(...)`
- `isAcceleratorBackendFailure(...)`

Used by chat/runtime fallback logic to degrade safely to CPU without crashing task/chat flows.

### 4.4 Debug Controls (ADB)

`DebugTaskReceiver` backend actions include:

- `status`
- `force_cpu_safe`
- `clear_cpu_safe`
- `mark_pending_gpu_init`
- `clear_pending_gpu_init`
- `force_gpu_retry`
- `force_npu_retry`
- `recover_pending_gpu_crash`

## 5) Dependency Map (Current)

From `gradle/libs.versions.toml` + `app/build.gradle.kts`:

- On-device LLM:
  - `com.google.ai.edge.litertlm:litertlm-android:0.10.0`
- Agent framework:
  - `dev.langchain4j:langchain4j-core:1.12.2`
  - `dev.langchain4j:langchain4j-open-ai:1.12.2`
  - `dev.langchain4j:langchain4j-anthropic:1.12.2`
- Networking:
  - OkHttp `4.12.0`
  - Retrofit `2.11.0`
  - Gson `2.13.2`
- Android / UI:
  - AppCompat, Material, Lifecycle
  - Compose BOM `2025.05.00`
- Storage + infra:
  - MMKV
  - NanoHTTPD
- Utility libs:
  - Glide, EasyFloat, MultiType, ZXing, utilcodex

## 6) Active Working-Tree Focus (As Seen This Session)

Current modified/hot files are concentrated in:

- Runtime/backend:
  - `LocalModelRuntime.kt`
  - `EngineHolder.kt`
  - `LocalBackendHealth.kt`
  - `LocalLlmClient.kt`
  - `ChatSessionController.kt`
  - `DebugTaskReceiver.kt`
- WhatsApp send reliability:
  - `SendMessageTool.java`
  - `OpenAppTool.java`
  - `ContactListUiUtils.java`
- Project/process docs:
  - `QA_CHECKLIST.md`
  - `BACKLOG.md`
  - `CODE_FLOW_SEND_MESSAGE.md`
  - `WHATSAPP_NAVIGATION_DETAILS.md`

## 7) QA + Process Contract (Project Rules)

Per `CLAUDE.md`, the project enforces:

- QA-first development with ADB-driven E2E tests
- Every change tracked in `QA_CHECKLIST.md` changelog
- Architecture issues should be surfaced before feature layering
- No silent failures; user-visible error path required
- Detailed logcat traceability for entry/branch/state/external call/error

## 8) Known Near-Term Priorities (Backlog-Driven)

From `BACKLOG.md` top priorities:

- Stable release publishing/signing pipeline completion
- Further autonomous `send_message` reliability hardening
- Local runtime performance/backend reliability across chipsets
- Missed-call auto follow-up feature (P0)
- Better low-RAM local model coverage and import UX

## 9) Quick Reference Snippets

### NPU backend config

```kotlin
Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
```

### Shared launch handling for send_message/open_app

```java
boolean opened = OpenAppTool.openAppWithInterceptHandling(service, packageName);
```

### Local fallback chain

```kotlin
"NPU" -> listOf(NPU, GPU, CPU)
"GPU" -> listOf(GPU, CPU)
"CPU" -> listOf(CPU)
```

---

If this file is kept current each session, it can replace ad-hoc handoff messages and reduce repeated rediscovery work.
