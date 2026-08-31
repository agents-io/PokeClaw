# PokeClaw Backlog

Items go in, get prioritized, get done, get crossed out. Simple.

Priority: `P0` = blocks users, fix now. `P1` = next up. `P2` = when we get to it. `P3` = nice to have.

---

## Bugs

- [~] **P1** AppCapabilityCoordinator process-young grace (partial fix shipped 2026-05-28): `bindingState` now returns `CONNECTING` for the first 30s after process start regardless of stale `lastHealthyAt` in MMKV. Addresses the case where OS keeps the AccessibilityService enabled across an app process restart and rebinds within seconds — previously the in-app coordinator would see `running=false`+`lastHealthyAt` from minutes ago and immediately return `DEGRADED`, forcing the user back to Settings. Limitation: cannot help the Pixel-specific behavior where `force-stop` revokes the secure setting `accessibility_enabled` (true `DISABLED` state, requires user re-enable). Production behavior on Xiaomi/Samsung task-killers may or may not preserve the secure setting — needs telemetry to confirm. Runtime QA blocked on Pixel by the force-stop auto-disable; code-review verified. **2026-05-28 commit `pending` adds 30s process-start grace.**
- [ ] **P1** Historical upgrade gap: users on the older public debug signing path still need a one-time uninstall + reinstall because the original public signing key is already lost
- [ ] **P1** Autonomous `send_message` reliability: when the agent launches WhatsApp from background, OEM chain-launch intercepts / wrong-entry screens can trap `prepareForContactLookup` in reopen/back recovery and fail before contact tap; unify launch handling and harden lookup state transitions
- [ ] **P2** K3-a: Auto-return fires on every service connect, not just user-initiated permission enable
- [ ] **P2** B2-a: No auto-return to PokeClaw after task completes in another app (e.g., stuck in YouTube)
- [ ] **P1** Investigate MediaTek/Samsung local-engine bring-up failures that still report OpenCL/LiteRT engine creation errors on some devices even after GPU→CPU fallback
- [ ] **P1** Investigate Snapdragon 8s Gen 3 local-runtime performance: Gemma 4 E2B task rounds are taking ~50-60s and often run on CPU path instead of expected LiteRT GPU acceleration; verify backend selection, delegate health state, and model/engine compatibility
- [ ] **P2** Settings screen: active model row breaks layout when the model name is long; keep the label/value aligned and truncate or wrap cleanly without shoving the left label into a narrow column

## Features

- [~] ~~**P0** Missed-call auto follow-up~~ — **dropped 2026-05-26**. Missed-call response is the dedicated scope of the separate revenue product `missed-call-ai-chatbot-lab` (web prototype, Android port planned there). PokeClaw stays the generic mobile-agent harness per ARCHITECTURE_DECISIONS.md D2 — no per-vertical workflows in core. If a future need arises for a generic "phone state" tool (any task that depends on call state), add it as a tool primitive, NOT a workflow.
- [x] ~~**P0** Production external automation intent: promote the debug-only task/chat broadcast into a user-enabled production API for Tasker, MacroDroid, Locale, and ADB-style callers. It should accept explicit package/component broadcasts with `task` / `chat` / base64 extras, preserve harness safety rules, and optionally return a result callback intent.~~ — implemented 2026-04-30; callback contract exists, Tasker/MacroDroid callback E2E remains a QA gap
- [ ] **P1** Persistent global instructions: add a user-editable local instructions layer that applies to new tasks/conversations without becoming a prompt dump. It must be short, inspectable, removable, local-first, and separate from hard safety/tool rules.
- [ ] **P1** Scoped app/channel rules: support rules scoped to apps or channels such as WhatsApp, Telegram, Gmail, Browser, and Phone so the harness loads only relevant guidance instead of stuffing every rule into every local-model context.
- [ ] **P1** Explicit user-approved memory: add manual "remember this" style memory first, then optional suggested memories only after user approval. Memory must be deletable/exportable and must not store secrets, bot tokens, API keys, or recovery codes.
- [ ] **P1** Telegram remote-control channel hardening: treat the Telegram bot token path as a first-class remote-control channel with clear setup, token validation, polling status, and E2E QA gates. Current QA can configure a bot token, but live send-to-bot E2E is blocked on the QA Telegram account being frozen/read-only.
- [ ] **P2** Voice input: add a prompt microphone button as an input method, preferably using an available cloud transcription path when the user has a cloud API key and a local/on-device option later. Wake-word/background listening is a separate higher-risk permission/battery design, not the MVP.
- [ ] **P1** Local model import UX: keep shared-storage `.litertlm` import easy and explain clearly why other apps' `Android/data/...` sandboxes (for example Edge Gallery) are not directly readable
- [ ] **P1** More small local model options: add 1B / 1.5B-class local models so lower-RAM phones can still run a useful on-device agent
- [ ] **P1** Custom local model sources: let users point PokeClaw at user-defined model URLs / hosted downloads instead of only the built-in catalog
- [ ] **P2** Google AI Core integration research: evaluate Android's official on-device AI / system model APIs as an optional local runtime path
- [ ] **P1** Structured monitor identifiers: let monitor setup keep a user-facing nickname while using a more stable identifier where possible (phone number / app-stable id / aliases) so WhatsApp/Telegram display-name drift stops breaking setup
- [ ] **P2** Chat keyboard dismissal polish: tapping non-button chatroom space should reliably clear focus and hide IME in both empty and non-empty conversations
- [ ] **P1** Structure-first UI matching: remove remaining language-specific text heuristics where the platform exposes a stable structural hook first (dialog positive buttons, send affordances, standard action widgets)
- [ ] **P1** Tinder automation: auto swipe + monitor matches + auto-reply using same monitor architecture as WhatsApp
- [x] ~~**P1** NLP Playbooks (Layer 2): 5 playbooks in system prompt (Search in App, Navigate Settings, Compose Email, Read Screen, Read Notifications)~~ — done 2026-04-08
- [x] ~~**P1** In-chat task auto-return~~ — done 2026-04-08
- [x] ~~**P2** Monitor stays in app~~ — done 2026-04-08, removed GLOBAL_ACTION_HOME
- [ ] **P2** Unified task registry: monitor + agent tasks tracked in same system (top bar, floating button, etc.)
- [ ] **P3** Rename chat session (H6): pencil icon in sidebar → InputDialog → update title in DB + markdown
- [ ] **P3** Floating button: use PokeClaw icon instead of "AI" text
- [ ] **P3** ChatViewModel extraction: move business logic out of ComposeChatActivity god class
- [ ] **P1** Full i18n refactor for ChatScreen.kt — 75+ user-facing hardcoded English strings (quick task templates, intro text, monitor labels, send/cancel labels) need extracting to strings.xml + zh + ja translation. Surfaced by realme RMX3823 user (#50) wanting "thorough Chinese version". Voice input strings (4 strings) already i18n'd in v0.7.0 as a first slice.

## QA Gaps

- [ ] **P0** Missed-call follow-up E2E: missed-call notification / phone-state trigger reaches PokeClaw, follow-up message is sent to the caller, and the result/status is visible in the same chatroom
- [ ] **P0** Production intent E2E: Tasker/MacroDroid-style explicit broadcast reaches PokeClaw in a release build, starts the requested task/chat, and never bypasses safety/global rules
- [ ] **P1** Production intent callback E2E: when an external automation request includes `request_id` and `return_action`, PokeClaw broadcasts a completion/failure result that Tasker/MacroDroid can consume
- [ ] **P1** Telegram bot channel E2E: token configured -> polling connected -> user sends `/start` and a task to the bot -> PokeClaw receives the update -> returns a visible bot reply. Current QA is blocked by the handset Telegram account being frozen/read-only.
- [ ] **P1** C2: Auto-reply trigger E2E — needs 2nd device to send WhatsApp message to Girlfriend
- [x] ~~**P1** Release QA: verify locally signed `0.5.1+` public APK can upgrade in-place over the next signed public build once the stable key is installed in GitHub Actions~~ — done 2026-05-26: v0.6.12 signed → v0.7.0 signed in-place upgrade PASS on Pixel 8 Pro (same keystore, no uninstall needed)
- [ ] **P0** PROCESS GATE — full QA on signed-release APK BEFORE pushing the version tag. v0.7.0 was tagged first and QA'd second; CLAUDE.md says full QA triggers on "before any release/version bump". Next release: pull the locally-built signed `assembleRelease` APK or the CI artifact from a draft release, run V/W/X/Y + J + K + P sections, only then push the tag.
- [ ] **P1** v0.7.x follow-up — re-run W7/W8 with a configured cloud LLM to verify PromptUtils.applyGlobalPrompt logcat trace fires (or AppLogStore captures it in debug-report.zip)
- [ ] **P1** v0.7.x follow-up — re-run V3/V4/V7-V9 with actual human voice to verify transcript appends to text field correctly on signed release
- [x] ~~**P2** Emulator Matrix CI workflow debug~~ — fixed 2026-05-26. ROOT CAUSE: `reactivecircus/android-emulator-runner@v2` runs each LINE of the `script:` block as a separate `sh -c <line>` call, breaking variable persistence and if/then/fi continuation. Fix: extract logic to `scripts/emulator-smoke.sh` and invoke as a single command. All 5 API levels (29/31/33/34/35) now GREEN. ClawApplication boot + ComposeChatActivity launch + zero FATAL verified per API.
- [x] ~~**P1** M1-M12 QA: Cloud LLM complex tasks~~ — done 2026-04-08, 10/12 PASS
- [x] ~~**P2** K6: Verify each Settings permission row leads to correct system settings page~~ — done 2026-05-26 on Pixel 8 Pro v0.7.0 signed: all 6 rows route to the correct system Activity (Accessibility, Task Notifications via permissioncontroller, Notification Access, System Window via SpaActivity, Battery Whitelist via RequestIgnoreBatteryOptimizations, File Access via SpaActivity). Recorded in QA Debug Changelog 2026-05-26.
- [ ] **P2** Settings layout QA: verify long local/cloud model names render cleanly on the Settings screen across Pixel/Samsung widths
- [ ] **P2** Download free space check — done 2026-04-08 (StatFs before download)
- [ ] **P1** Local vague-task UX: in Local Task mode, prompt-only behavior is correct, but vague requests like `Copy that token to the clipboard` currently hang instead of failing fast with a clear request for the missing content/details

## Ideas / Research

- Monetization: two-tier (dev=free open source, consumer=China APK + premium features)
- YC application showcase
- Layer 2 NLP Playbooks as "App Cards" like DroidRun
- On-device LLM as competitive moat (first to ship with Gemma 4)
- Positioning: cloud/desktop-driven mobile-agent frameworks already exist; PokeClaw should own the phone-resident, local-first, model-slot harness that can run on a user's own Android device without a PC/cloud phone fleet.

---

## Done

_Move completed items here with date._

- [x] ~~2026-04-30: Production External Automation API~~ — user-enabled `RUN_TASK` / `RUN_CHAT` broadcasts, base64 extras, safety opt-in, and task terminal callback contract
- [x] ~~2026-04-08: Fix "Accessibility starting..." on every chat (A1-b)~~
- [x] ~~2026-04-08: Floating button IDLE→RUNNING in other apps (F3-b)~~
- [x] ~~2026-04-08: LiteRT-LM session conflict + GPU→CPU fallback (D1-a, D1-b)~~
- [x] ~~2026-04-08: Monitor permission check + auto-return after grant~~
- [x] ~~2026-04-08: Settings page: Notification Access row~~
- [x] ~~2026-04-08: Full QA pass 49/50 cases~~
- [x] ~~2026-04-08: Download free space check (StatFs before download)~~
- [x] ~~2026-04-08: Task detection keywords fix (check, compose, find, screen, notification, read my)~~
- [x] ~~2026-04-08: Compound task routing fix (skip Tier 1 for "and"/"then"/"after")~~
- [x] ~~2026-04-08: M1-M12 QA: 10/12 PASS, 2 PARTIAL (M9 camera, M12 system dialog)~~
- [x] ~~2026-04-08: NLP Playbooks Layer 2: 5 playbooks (Search, Settings, Email, Screen, Notifications)~~
- [x] ~~2026-04-08: Tinder research: UI structure documented, workflow designed, needs login~~
- [x] ~~2026-04-10: Chat bubble timestamps~~ — IG-style per-message footer landed for user + assistant bubbles, with hidden timestamp metadata persisted in markdown history so relaunch/reload keeps stable times
- [x] ~~2026-04-28: Release publishing stable signing path~~ — `v0.6.9` tag workflow produced a signed release APK and `SHA256SUMS.txt` through GitHub Actions
- [x] ~~2026-05-26: Voice Input (#44)~~ — system RecognizerIntent wired into chat composer between TextField and Send FAB. V1/V2/V5/V10 ADB-verified PASS on Pixel 8 Pro v0.7.0; V3/V4/V7/V8/V9 need human voice verify, code path structurally verified
- [x] ~~2026-05-26: Persistent Global Prompt (#45)~~ — MMKV-backed user-defined instructions, prepended to system prompt at ChatSessionController.buildConversationConfig and AgentConfig.Builder.build. Settings row under Model group, InputDialog editor, max 2000 chars, empty string = disabled. W1-W6 ADB-verified PASS Pixel 8 Pro v0.7.0 (MMKV persistence via run-as confirmed); W7/W8 logcat injection trace needs configured LLM to fire
- [x] ~~2026-05-26: Custom Local Model URL (#36)~~ — MMKV-backed advanced setting. Adds synthetic ModelInfo (isCustom=true) appended to LocalModelManager.catalog(); LlmConfigActivity now iterates catalog so custom row renders. Relaxed file validation (≥1MB) for unknown-size custom models. http(s) prefix validator + Android auto-cap normalization. X1-X8 ADB-verified PASS Pixel 8 Pro v0.7.0 (custom model appears in Available Models list with displayName "Custom: my-model.litertlm")
- [x] ~~2026-05-26: Floating button icon swap (BACKLOG P3)~~ — replaced "AI" text label with PokeClaw small icon in `layout_floating_circle.xml`'s `cardIdle` view. Layout compiles + inflates without error, content-desc localised in en/zh/ja. Tracked `tvFloatTextIdle` -> `ivFloatIconIdle`; old `floating_ai_label` string left in place but unused, can be cleaned up later
- [x] ~~2026-05-26: GPU/OpenCL diagnostics in debug-report (#41 + #14)~~ — DebugReportManager summary.txt now includes Supported ABIs, RAM, OpenCL library presence check (probes 6 well-known driver paths), and LocalBackendHealth.debugStateSummary() output. Y1-Y4 ADB-verified PASS Pixel 8 Pro (OpenCL found at /system/vendor/lib64/libOpenCL.so confirms why Pixel GPU fallback works; non-Pixel reporters will see "(none)" if drivers missing — enables instant community triage)
- [x] ~~2026-05-26: Rename chat session — pencil icon (BACKLOG P3)~~ — pencil edit icon added next to each conversation row in sidebar's Recent list, alongside the existing long-press menu (kept for delete + power users). Tap pencil → existing rename AlertDialog opens pre-filled with current title. Long-press still works for both Rename and Delete. ADB-installed cleanly on Pixel 8 Pro; structural verification only (need an actual saved conversation to dump the icon in uiautomator, deferred to human verify or LLM-enabled QA session)
