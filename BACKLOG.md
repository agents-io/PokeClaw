# PokeClaw Backlog

Items go in, get prioritized, get done, get crossed out. Simple.

Priority: `P0` = blocks users, fix now. `P1` = next up. `P2` = when we get to it. `P3` = nice to have.

---

## Bugs

- [ ] **P2** K3-a: Auto-return fires on every service connect, not just user-initiated permission enable
- [ ] **P2** B2-a: No auto-return to PokeClaw after task completes in another app (e.g., stuck in YouTube)

## Features

- [ ] **P1** NLP Playbooks (Layer 2): natural language step-by-step instructions for common tasks (Send WhatsApp, Compose Email, etc.) — injected into system prompt so LLM follows a reliable path
- [ ] **P1** In-chat task auto-return: when task completes in another app, navigate back to PokeClaw chatroom
- [ ] **P2** Monitor should NOT jump to homepage: test if ClawNotificationListener catches notifications without pressing Home
- [ ] **P2** Unified task registry: monitor + agent tasks tracked in same system (top bar, floating button, etc.)
- [ ] **P3** Rename chat session (H6): pencil icon in sidebar → InputDialog → update title in DB + markdown
- [ ] **P3** Floating button: use PokeClaw icon instead of "AI" text
- [ ] **P3** ChatViewModel extraction: move business logic out of ComposeChatActivity god class

## QA Gaps

- [ ] **P1** C2: Auto-reply trigger E2E — needs 2nd device to send WhatsApp message to Girlfriend
- [ ] **P2** K6: Verify each Settings permission row leads to correct system settings page

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
