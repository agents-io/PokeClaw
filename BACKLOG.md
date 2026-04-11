# PokeClaw Backlog

Items go in, get prioritized, get done, get crossed out. Simple.

Priority: `P0` = blocks users, fix now. `P1` = next up. `P2` = when we get to it. `P3` = nice to have.

---

## Bugs

- [ ] **P0** Release publishing: install stable signing secrets on `agents-io/PokeClaw` so tag-based GitHub releases can ship a signed `release` APK instead of failing closed
- [ ] **P1** Historical upgrade gap: users on the older public debug signing path still need a one-time uninstall + reinstall because the original public signing key is already lost
- [ ] **P2** K3-a: Auto-return fires on every service connect, not just user-initiated permission enable
- [ ] **P2** B2-a: No auto-return to PokeClaw after task completes in another app (e.g., stuck in YouTube)
- [ ] **P1** Investigate MediaTek/Samsung local-engine bring-up failures that still report OpenCL/LiteRT engine creation errors on some devices even after GPU→CPU fallback
- [ ] **P2** Settings screen: active model row breaks layout when the model name is long; keep the label/value aligned and truncate or wrap cleanly without shoving the left label into a narrow column

## Features

- [ ] **P0** Missed-call auto follow-up: when a call is missed, let PokeClaw detect the missed-call event, send a follow-up message to that caller automatically, and keep the status visible in the same chatroom instead of as a hidden background-only action
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

## QA Gaps

- [ ] **P0** Missed-call follow-up E2E: missed-call notification / phone-state trigger reaches PokeClaw, follow-up message is sent to the caller, and the result/status is visible in the same chatroom
- [ ] **P1** C2: Auto-reply trigger E2E — needs 2nd device to send WhatsApp message to Girlfriend
- [ ] **P1** Release QA: verify locally signed `0.5.1+` public APK can upgrade in-place over the next signed public build once the stable key is installed in GitHub Actions
- [x] ~~**P1** M1-M12 QA: Cloud LLM complex tasks~~ — done 2026-04-08, 10/12 PASS
- [ ] **P2** K6: Verify each Settings permission row leads to correct system settings page
- [ ] **P2** Settings layout QA: verify long local/cloud model names render cleanly on the Settings screen across Pixel/Samsung widths
- [ ] **P2** Download free space check — done 2026-04-08 (StatFs before download)
- [ ] **P1** Local vague-task UX: in Local Task mode, prompt-only behavior is correct, but vague requests like `Copy that token to the clipboard` currently hang instead of failing fast with a clear request for the missing content/details

## Ideas / Research

- Monetization: two-tier (dev=free open source, consumer=China APK + premium features)
- YC application showcase
- Layer 2 NLP Playbooks as "App Cards" like DroidRun
- On-device LLM as competitive moat (first to ship with Gemma 4)

---

## Done

_Move completed items here with date._

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
