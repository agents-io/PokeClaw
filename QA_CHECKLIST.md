# PokeClaw E2E QA Checklist

Every build must pass ALL checks before shipping. Run on Pixel 8 Pro (or equivalent).

## Prerequisites
- [ ] Accessibility service enabled
- [ ] Cloud LLM configured (API key set)
- [ ] Local LLM downloaded (Gemma 4)
- [ ] WhatsApp installed with at least 1 contact ("Girlfriend")

---

## A. Cloud LLM — Chat

- [ ] **A1. Pure chat question**: "what is 2+2" → answer in bot bubble, 1 round, no tools, no rocket, no "Starting task...", no "Reading screen..."
- [ ] **A2. Follow-up chat**: after A1, ask "what about 3+3" → answer in bot bubble, context preserved
- [ ] **A3. Chat then task**: chat "hello" → get reply → then "send hi to Girlfriend on WhatsApp" → task executes correctly
- [ ] **A4. Task then chat**: "send hi to Girlfriend on WhatsApp" → completes → then "how are you" → chat reply (not task)
- [ ] **A5. Multiple chat messages**: send 3 chat messages in a row → all get bot bubble replies

## B. Cloud LLM — Tasks

- [ ] **B1. Send message**: "send hi to Girlfriend on WhatsApp" → send_message tool called → message sent → answer in bot bubble
- [ ] **B2. Complex task**: "open YouTube and search for funny cat videos" → opens YouTube → searches → multiple steps shown
- [ ] **B3. Task with context**: "I'm arguing with my girlfriend" → then "send sorry to Girlfriend on WhatsApp" → message content should reflect context
- [ ] **B4. Failed task**: "send hi to NonExistentPerson on WhatsApp" → error message in bot bubble (not stuck)

## C. Cloud LLM — Monitor Workflow

- [ ] **C1. Start monitor**: "monitor Girlfriend on WhatsApp" → top bar shows "Monitoring: Girlfriend" → presses Home
- [ ] **C2. Auto-reply triggers**: Girlfriend sends message → notification caught → WhatsApp opens → reads context → Cloud LLM generates reply → reply sent
- [ ] **C3. Stop monitor**: tap top bar → expand → Stop → monitoring stops

## D. Local LLM — Chat

- [ ] **D1. Pure chat**: switch to Local LLM → "hello" → on-device reply in bot bubble
- [ ] **D2. Chat tab has no task ability**: type "open YouTube" in Chat tab → LLM responds conversationally (doesn't try to control phone)

## E. Local LLM — Task Tab

- [ ] **E1. No input bar**: Task tab has workflow cards only, no text input
- [ ] **E2. Monitor workflow**: tap Monitor card → dialog → enter "Girlfriend" → Start → monitoring activates
- [ ] **E3. Send message card**: tap Send Message card → dialog → fills contact/message → sends

## F. Task Lifecycle UI

- [ ] **F1. Top bar during task**: while task runs → orange "Task running..." + red "Stop" button visible
- [ ] **F2. Send button becomes stop**: while task runs → send button turns red X → tapping it cancels task
- [ ] **F3. Floating button during task**: while task runs in another app → floating circle shows pill with step/tokens + "Tap to stop"
- [ ] **F4. Floating button stop**: tap floating button during task → task cancels
- [ ] **F5. Second task works**: complete task 1 → start task 2 → floating button, top bar, stop button all work
- [ ] **F6. No stuck typing indicator**: after task completes → "..." is replaced by answer or removed

## G. Welcome Screen

- [ ] **G1. Cloud welcome**: new chat → "Cloud LLM enabled" + task examples + subtitle about instructions
- [ ] **G2. Local welcome**: switch to Local → new chat → "Local LLM enabled" + chat examples + subtitle about Task tab
- [ ] **G3. Cloud prompt tap**: tap suggested prompt → stays in Chat tab (not jump to Task)

## H. General UI

- [ ] **H1. Floating button size**: small circle on home screen (not giant)
- [ ] **H2. Keyboard in LLM Config**: Settings → LLM Config → tap API key → keyboard doesn't block field
- [ ] **H3. Layout sizes**: all text/buttons normal size (dp not pt)
- [ ] **H4. Model switcher**: tap model bar → dropdown → switch model → status updates
- [ ] **H5. New chat**: tap pencil icon → clears messages → shows welcome screen
- [ ] **H6. Rename chat**: tap pencil icon (top right) → edit session name → name updates in sidebar

## I. Cross-App Behavior

- [ ] **I1. Floating button visible in other apps**: start task → agent navigates to WhatsApp/YouTube → floating button visible on top
- [ ] **I2. Return to PokeClaw mid-task**: while task runs in WhatsApp → press recents → tap PokeClaw → see task progress + stop button
- [ ] **I3. Notification during task**: incoming notification while task runs → task not disrupted

## J. Stress / Edge Cases

- [ ] **J1. Rapid fire**: send 3 messages quickly → no crash, messages queued or latest wins
- [ ] **J2. Empty input**: tap send with empty field → nothing happens
- [ ] **J3. Very long input**: paste 500+ character task → no crash, task starts normally
- [ ] **J4. Accessibility lost mid-task**: if accessibility revokes during task → graceful error, not stuck
- [ ] **J5. Network lost mid-task**: if WiFi drops during Cloud task → error message, not infinite loop
- [ ] **J6. App killed and reopened**: force stop → reopen → clean state, no ghost tasks
- [ ] **J7. Monitor + task simultaneous**: monitor Girlfriend active → send task "open YouTube" → both work, monitor not disrupted

---

## QA Debug Changelog

Format: `[date] [status] [test-id] description`

### 2026-04-08 — Initial QA run

```
[2026-04-08] [PASS]    A1  Chat question "what is 2+2" → answer in bot bubble, 1 round
[2026-04-08] [ISSUE]   A1  Floating button flashed briefly (TASK_NOTIFY → SUCCESS) on chat question
[2026-04-08] [ISSUE]   A1  "Accessibility service starting..." shows in every new chat
[2026-04-08] [PASS]    B1  Send message to Girlfriend → send_message tool called, 2 rounds
[2026-04-08] [PASS]    C1  Monitor Girlfriend → Java routing, top bar shows "Monitoring: Girlfriend"
[2026-04-08] [PASS]    C2  Auto-reply with Cloud LLM → GPT-4o-mini generated reply, sent successfully
[2026-04-08] [PASS]    F5  Second task works after first completes
[2026-04-08] [PASS]    H1  Floating button size normal (dp fix applied)
[2026-04-08] [ISSUE]   F1  Top bar "Task running..." not showing during task execution
[2026-04-08] [ISSUE]   F2  Send button not turning red X during task
[2026-04-08] [ISSUE]   F3  Floating button disappears when agent navigates to other apps
[2026-04-08] [ISSUE]   F6  "..." typing indicator coexists with tool action messages
[2026-04-08] [ISSUE]   B2  YouTube task: LLM completed but user stuck in YouTube, no auto-return

### 2026-04-08 — Post-fix QA run (after TaskEvent, LlmSessionManager, etc.)

[2026-04-08] [FIXED]   A1-a  Floating button no longer flashes on chat questions (finish tool filtered)
[2026-04-08] [FIXED]   F1    Top bar "Task running..." + Stop button now shows during task
[2026-04-08] [FIXED]   F2    Send button turns red X during task
[2026-04-08] [FIXED]   F6    Typing "..." removed when first ToolAction arrives
[2026-04-08] [PASS]    A3    Chat → Task mixed: "what is 2+2" → reply → "send hi to Girlfriend" → works
[2026-04-08] [PASS]    A4    Task → Chat: after send message completes → "how are you" → text-only reply
[2026-04-08] [PASS]    B1    Send message to Girlfriend → 2 rounds, answer in bot bubble
[2026-04-08] [PASS]    B2    YouTube search → agent navigated, typed query, showing suggestions
[2026-04-08] [PASS]    F3    Floating button visible in YouTube during task (IDLE state, not RUNNING)
[2026-04-08] [PASS]    F5    Second task works after first (chat → task sequence)
[2026-04-08] [PASS]    G1    Cloud welcome screen: correct text + prompts
[2026-04-08] [PASS]    G7    Cloud Task tab: Workflows header + cards + input bar
[2026-04-08] [ISSUE]   A1-b  "Accessibility service starting..." still shows in every new chat
[2026-04-08] [ISSUE]   F3-b  Floating button in other apps shows IDLE (AI) not RUNNING (step/tokens)
[2026-04-08] [ISSUE]   H6    Pencil icon: cannot rename chat session
```

### Open Issues (unfixed)

| ID | Issue | Root Cause | Priority |
|----|-------|-----------|----------|
| A1-a | Floating button flashes on chat questions | `onToolCall(finish)` triggers TASK_NOTIFY even for finish-only calls | Medium |
| A1-b | "Accessibility starting..." on every new chat | `sendTask()` accessibility check fires before task routing | Low |
| ~~F1~~ | ~~Top bar "Task running..." not showing~~ | ~~FIXED~~ | ~~High~~ |
| ~~F2~~ | ~~Send button not turning red~~ | ~~FIXED~~ | ~~High~~ |
| H6 | Pencil icon cannot rename chat session | Not implemented / broken | Medium |
| F3 | Floating button invisible in other apps | ~~FIXED: visible now~~ but shows IDLE not RUNNING state | Medium |
| F6 | "..." coexists with tool actions | Typing indicator not removed when first ToolAction arrives | Medium |
| B2-a | No auto-return after task in other app | Agent completes in YouTube but doesn't navigate back to PokeClaw | Low |
