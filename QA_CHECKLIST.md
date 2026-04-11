# PokeClaw E2E QA Checklist

Every build must pass ALL checks before shipping.

---

## QA Methodology — How to Test (READ THIS FIRST)

### Three QA Layers — Do Not Mix These Up

Use all three. Do not claim a user-facing fix from backend smoke alone.

1. **Backend smoke**
   - Fast validation through ADB + logcat.
   - Proves tool routing, rules, runtime guards, and final backend result.
   - Does **not** by itself prove that the result showed up in the visible chatroom.

2. **Chatroom bridge smoke**
   - Short user-visible verification.
   - Proves that once backend has a result, the answer appears in the same chatroom as a visible assistant bubble and is persisted to the current conversation.
   - Use this whenever changing chat/task result rendering, auto-return, or task-to-chat bridging.

3. **True E2E**
   - Full user path: tap/type/send/watch/verify.
   - Use this for release confidence, major regressions, and high-risk flows such as send-message, monitor, permission flows, and context handoff.

Rule of thumb:
- backend-only bug -> backend smoke first, then at least one chatroom bridge check
- user-visible chat/task behavior -> backend smoke + chatroom bridge
- shipping / RC claims -> real E2E, not smoke theater

### Device Setup

```bash
# 1. Check device connected
adb devices -l

# 2. Install APK
cd /home/nicole/MyGithub/PokeClaw
./gradlew assembleDebug
APK=$(find app/build/outputs/apk/debug/ -name "*.apk" | head -1)
adb install -r "$APK"

# 3. Launch app
adb shell am start -n io.agents.pokeclaw/io.agents.pokeclaw.ui.splash.SplashActivity
sleep 5

# 4. Enable accessibility (if not already)
CURRENT=$(adb shell settings get secure enabled_accessibility_services)
[[ "$CURRENT" != *"io.agents.pokeclaw"* ]] && \
  adb shell settings put secure enabled_accessibility_services \
  "$CURRENT:io.agents.pokeclaw/io.agents.pokeclaw.service.ClawAccessibilityService"

# 5. Grant permissions
adb shell pm grant io.agents.pokeclaw android.permission.READ_CONTACTS
```

### Configure LLM via ADB

```bash
# Cloud LLM
source /home/nicole/MyGithub/PokeClaw/.env
adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw \
  --es task 'config:' --es api_key '$OPENAI_API_KEY' --es model_name 'gpt-4.1'"

# Local LLM
MODEL_PATH="/storage/emulated/0/Android/data/io.agents.pokeclaw/files/models/gemma-4-E2B-it.litertlm"
adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw \
  --es task 'config:' --es provider 'LOCAL' --es base_url '$MODEL_PATH' --es model_name 'gemma4-e2b'"
```

### Batch Quick-Task Sweeps

```bash
# Cloud quick tasks
cd /home/nicole/MyGithub/PokeClaw
./scripts/e2e-quick-tasks.sh cloud

# Local quick tasks
./scripts/e2e-quick-tasks.sh local
```

The runner emits `PASS / FAIL / BLOCKED / TIMEOUT` and writes a timestamped log file under `/tmp/`.

### Send a Task via ADB (for M tests)

```bash
# IMPORTANT: wrap the task string in single quotes INSIDE adb shell double quotes
adb logcat -c
adb shell "am broadcast -a io.agents.pokeclaw.DEBUG_TASK -p io.agents.pokeclaw \
  --es task 'how much battery left'"
```

### Send a Chat via ADB (for bridge smoke)

```bash
# Launch ComposeChatActivity through the debug receiver and inject a chat message
adb shell "am broadcast -a io.agents.pokeclaw.TASK -p io.agents.pokeclaw \
  --es chat 'read my clipboard and explain what it says'"
```

Use this when you need a fast chatroom-bridge verification but do not trust raw ADB tap coordinates.
It should create a visible user bubble, wait for the backend reply, and render the assistant bubble in the same conversation.
On Android 15+, make sure PokeClaw is already in the foreground first; otherwise the system may block the receiver from bringing the chat activity forward for UI-visible verification.

### Read Results from Logcat

```bash
# Wait for task to complete (Cloud ~10s, Local ~60-120s per round)
sleep 15
PID=$(adb shell pidof io.agents.pokeclaw)

# Check which tools were called + final answer
adb logcat -d | grep "$PID" | grep -E "onToolCall|onComplete" | head -10

# Full breakdown
adb logcat -d | grep "$PID" | grep -E "DebugTask|PipelineRouter|AgentService|TaskOrchestrator|onToolCall|onComplete"
```

### Verify PASS/FAIL

For each M test, check:
1. **Correct tool called** — e.g., "how much battery" should call `get_device_info(battery)`, NOT open Settings
2. **Actual data in answer** — "73%, not charging, 32°C" NOT "I checked the battery"
3. **Rounds** — system queries should be 2 rounds, complex tasks 5-15
4. **Auto-return** — after task, PokeClaw chatroom should come back to foreground
5. **Graceful failure** — if task can't complete, clear error message (not stuck/loop)
6. **Env-dependent quick tasks** — if a sample contact/app is missing on this device, require the correct tool + a graceful failure; literal send/call success should be marked `BLOCKED`, not product `FAIL`

### Verify UI via Uiautomator

```bash
# Dump all visible UI elements
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml | python3 -c "
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter():
    text = node.get('text', '')
    desc = node.get('content-desc', '')
    pkg = node.get('package', '')
    if (text or desc) and 'pokeclaw' in pkg.lower():
        print(f'text={text!r} desc={desc!r}')
"
```

Use this to verify:
- UI elements are present (tabs, buttons, prompts)
- Placeholder text changes when switching modes
- Correct model name shows in dropdown

### Tap UI Elements

```bash
# Find coordinates of an element
adb shell cat /sdcard/ui.xml | python3 -c "
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter():
    text = node.get('text', '')
    bounds = node.get('bounds', '')
    if 'Task' in text:
        print(f'text={text!r} bounds={bounds}')
"

# Tap at coordinates (center of bounds)
adb shell input tap 746 2041
```

### Three QA Layers

**Layer 1: Backend QA (ADB broadcast)**
- Fast: ~10s per test
- Uses `am broadcast` to send tasks directly to DebugTaskReceiver
- Bypasses UI entirely — tests tools, LLM routing, error handling, agent loop
- Code path: `DebugTaskReceiver → sendTask() → PipelineRouter → Agent`
- Sections: M tests
- When to run: every backend/agent/tool change

**Layer 2: UI Structure QA (uiautomator dump)**
- Medium: ~5s per test
- Verifies UI elements are present, positioned correctly, styled correctly
- No message sending — purely visual/structural verification
- Code path: Compose render → uiautomator reads view tree
- Sections: P tests
- When to run: every UI/layout change

**Layer 3: UI E2E QA (tap + type + send + verify response)**
- Slow: ~30s per test
- Simulates real user: tap input → type → dismiss keyboard → tap send → wait → verify response bubble
- Tests the FULL pipeline: UI routing → Activity callback → LLM → response → UI update
- Code path: `ChatInputBar → isLocalUI routing → onSendChat/onSendTask → Activity → LLM → UI`
- Sections: Q tests
- When to run: every change that touches send routing, mode switching, or input bar
- **This is the ONLY layer that tests UI send routing.** Layer 1 broadcast bypasses ChatInputBar entirely.

**Why 3 layers, not 2:**
Layer 1 broadcast calls `sendTask()` directly — it never touches `ChatInputBar`, `isLocalUI`, or the Chat/Task toggle routing. If UI routing breaks (e.g., Cloud mode accidentally routes to `onSendChat`), Layer 1 won't catch it. Layer 3 covers this gap.

**Run order:**
1. Layer 2 first (fast, catches layout breaks)
2. Layer 3 second (catches routing/interaction breaks)
3. Layer 1 last (catches backend/agent breaks)

```bash
# Layer 2 — simulate real user typing + sending
# 1. Find and tap input field
adb shell uiautomator dump /sdcard/ui.xml
# Parse bounds for the input element with placeholder text
INPUT_X=504; INPUT_Y=2100  # adjust from dump

# 2. Tap input, type, send
adb shell input tap $INPUT_X $INPUT_Y        # focus input
sleep 0.5
adb shell input text "how%smuch%sbattery"    # type (spaces = %s in adb)
sleep 0.5
SEND_X=970; SEND_Y=2100                      # adjust from dump
adb shell input tap $SEND_X $SEND_Y          # tap send

# 3. Wait for response, verify chat bubble appears
sleep 15
adb shell uiautomator dump /sdcard/ui_after.xml
adb shell cat /sdcard/ui_after.xml | python3 -c "
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter():
    text = node.get('text', '')
    pkg = node.get('package', '')
    if text and 'pokeclaw' in pkg.lower() and ('battery' in text.lower() or '%' in text):
        print(f'FOUND RESPONSE: {text!r}')
"
# Should find: "Battery: 73%, not charging, 32°C" or similar in a chat bubble
```

### Cross-Device Testing

Test on at least 2 devices:
- **Stock Android** (Pixel): baseline, everything should work
- **MIUI/Samsung/OEM** (Xiaomi etc): test for OEM restrictions (autostart, different Settings UI)

Key OEM differences:
- MIUI blocks background app launches (autostart whitelist needed)
- Samsung has different Settings layout
- Some OEMs have chain-launch dialogs (auto-dismissed by OpenAppTool)

### Local LLM Testing Notes

- CPU inference: ~50-60s per round on Pixel 8 Pro
- GPU may fail ("OpenCL not found") → auto-fallback to CPU
- LiteRT-LM SDK may crash on tool call parsing → our fallback extracts from error message
- Force stop loses accessibility service → re-enable after restart
- Model engine takes ~10s to load on first call

---

## Current Coverage Snapshot (2026-04-10)

This checklist is **not** yet a fully rerun 100% green master sheet. The honest current state is:

- **Strongly covered right now**
  - Local quick-task sweeps
  - Cloud quick-task sweeps
  - Settings / model config flows
  - Accessibility reconnect + permission return flows
  - Task stop / auto-return / same-session preservation
  - Explicit in-app search and email-compose guards
  - Phase 1 chat-runtime extraction smoke:
    - Cloud runtime rehydrate after relaunch
    - Local runtime rehydrate after relaunch
    - Local chat send with GPU→CPU fallback
  - Phase 2 task-session-store smoke:
    - Local quick-task prompt fill still routes correctly
    - Task shell enters `Task running...` + `Stop`
    - Stop request safely unwinds without leaving `ComposeChatActivity`
    - Idle shell restores after stop
  - Phase 3 permission/accessibility smoke:
    - App Settings truthfully shows `Disabled` after reinstall clears Accessibility from secure settings
    - App Settings truthfully shows `Connecting` during enabled-but-rebinding Accessibility state
    - App Settings truthfully shows `Notification Access = Disabled` when the listener is not enabled in system settings
    - Notification-listener auto-return is now gated by a pending permission-flow flag instead of firing on every reconnect
  - Phase 5 local-runtime consolidation smoke:
    - Shared local runtime still cold-launches into `ComposeChatActivity` with truthful `CPU` backend status
    - Real local UI send still works after runtime consolidation: `say pong` → `Pong! 🏓`
    - Assistant bubble model tag remains aligned with the actual backend after send
    - Local single-shot and auto-reply entrypoints now share the same runtime boundary as chat/session bring-up
    - Settings and chat now share the same built-in local model support/catalog state instead of each recalculating RAM/support/downloaded status
  - Chat bubble metadata smoke:
    - User bubbles render a subtle IG-style time footer under the bubble
    - Assistant bubbles render `model name · time` under the bubble
    - Saved markdown history persists per-message timestamps via hidden metadata comments
  - ConversationStore smoke:
    - cold relaunch still restores the same saved conversation instead of falling back to a blank chat shell
    - sidebar refresh, save, and restore now come through a single boundary instead of ad-hoc `KVUtils + ChatHistoryManager` calls in `ComposeChatActivity`
  - Phase 2b task-flow boundary smoke:
    - debug task intents still land on the chat shell after `TaskFlowController` extraction
    - task-mode permission guidance still redirects to in-app Settings when Accessibility is missing
    - cold launch no longer crashes if Android blocks an app-start foreground-service request
- **Covered, but still environment-sensitive**
  - WhatsApp send flows
  - Local contact-specific send/call flows
  - Cross-app floating-pill stop flows
- **Still blocked or not fully rerun end-to-end**
  - same-chatroom memory continuity (`Q8-1` to `Q8-4`) — must be rerun whenever chat runtime / persistence changes
  - incoming-message auto-reply while staying in-app (`L5`, `L5-b`) — needs a second live sender device or equivalent live source
  - some OEM-specific real-device failures from GitHub issues (`Samsung`, `Xiaomi`, `Dimensity`, low-RAM devices)
  - full public-release upgrade validation from the next stable-signed public build

If a task is not clearly marked `PASS`, `FIXED`, or `BLOCKED` with a reason, do **not** assume it is truly cleared.

## Release Gate

A build is only genuinely ship-ready when all of the following are true:

- **Product gate**
  - Chat vs Task routing is correct in Local and Cloud
  - Local GPU→CPU fallback is truthful and stable
  - Monitor stays in-app and does not force Home
  - Auto-return restores the same conversation after tasks
- **QA gate**
  - Local quick-task sweep finishes with no product `FAIL`
  - Cloud quick-task sweep finishes with no product `FAIL`
  - any `BLOCKED` items are clearly environment-caused, not product regressions
- **Distribution gate**
  - upgrade/install path is understood for the target release
  - release artifact, signing path, and checksums are verified
- **Architecture gate**
  - any refactor touched only its declared scope
  - required regression bundle for that refactor class was rerun

## Refactor Regression Bundles

Do **not** rerun the entire world after every refactor. Rerun the right bundle for the code you touched:

- **Model/config changes**
  - `H2`, `H2-b`, `H2-c`, `H4`, `H4-b`
  - `Q4-1`, `Q4-2`, `Q5-1`, `Q5-1b`
  - `LQ1-LQ13`
- **Local runtime / LiteRT fallback changes**
  - `H4`, `H4-b`
  - `Q3-1`, `Q5-1`, `Q5-1b`
  - `LQ1-LQ13`
  - one real Local UI send smoke using live bounds from the current `uiautomator dump`
- **Chat history / bubble metadata changes**
  - `P7-1`, `P7-2`, `P7-3`
  - `Q3-1`
  - `Q7-7`
  - `Q8-1`, `Q8-2`, `Q8-3`, `Q8-4`
  - one persisted markdown-history spot check for `<!-- pokeclaw:timestamp=... -->`
- **Cloud task-context handoff changes**
  - `Q2-1`, `Q2-2`, `Q7-7`
  - `Q8-1`, `Q8-3`
  - `Q9-1`, `Q9-2`
  - one real Cloud chatroom task that refers to earlier context (for example `send that summary by email`)
- **Task lifecycle / orchestration changes**
  - `F1-F6`
  - `I1-I3`
  - `L1`, `L3`
  - `Q7-*`
  - `S2`, `S3`, `S5`, `S7`, `S8`
- **Accessibility / permission changes**
  - `K1-K6`
  - `J4`
  - `L5`, `L5-b` when an external sender is available
- **Cross-app / skill / tool changes**
  - `B1-B5`
  - `M7-M21`
  - relevant quick-task sweeps
- **Direct device-data / no-false-denial changes**
  - `DD1-DD7`
  - `R1-R6`
  - `Q2-2`, `Q3-2`
  - one Cloud chatroom bridge smoke where a direct-device-data answer visibly appears as an assistant bubble
  - one Local chatroom bridge smoke where a direct-device-data/task answer visibly appears in the same conversation
- **Release / installer / updater changes**
  - `Dbg-u1-Dbg-u3`
  - `Rel-s1-Rel-s7`

When in doubt, rerun the smaller bundle first, then expand only if something drifted.

---

## Prerequisites
- [ ] Accessibility service enabled
- [ ] Cloud LLM configured (API key set)
- [ ] Local LLM downloaded (Gemma 4)
- [ ] WhatsApp installed with at least 1 contact ("Girlfriend")
- [ ] For monitor QA, an external sender path is available:
  - WhatsApp: second phone / second WhatsApp account
  - Telegram: second Telegram account or a Telegram bot token + already-started bot chat on this device
- [ ] For missed-call QA, an external caller path is available:
  - second phone / second SIM / VoIP caller that can place a real call to this handset
  - one follow-up route already configured
  - for the preferred first version, this should be SMS / Android-native sending rather than UI-driven WhatsApp automation

### Monitor QA Sender Rules

- WhatsApp and Telegram monitor tests are only `PASS` when a real external sender delivers a message to this phone and PokeClaw reacts.
- If the app logic is ready but there is no sender available, mark the case `BLOCKED`, not `FAIL`.
- For Telegram bot QA, the bot must already have an open chat with this handset; Telegram bots cannot cold-DM a user who never started the bot.
- When testing monitor fixes, always verify both:
  - monitor shell state (`Monitoring: ...`, expand, Stop)
  - actual incoming-message reaction from an external sender

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
- [ ] **B4. Failed contact**: "send hi to Dad on WhatsApp" → Dad not in contacts → LLM reports failure in bot bubble (not stuck, not "Task completed")
- [ ] **B4-b. Name or phone number send target**: send to a saved contact by name, then by phone-number formatting (`+country`, local digits, or spaced/hyphenated form) → same person is resolved without requiring an exact WhatsApp display name match
- [ ] **B4-c. Multilingual text actions stay functional**: on a device/app using non-English labels, structure-first actions (for example standard positive dialog buttons and standard send affordances) still work without requiring English-only UI text
- [ ] **B5. Failed app**: "send hi to Girlfriend on Signal" → Signal not installed → LLM reports can't open app

## C. Cloud LLM — Monitor Workflow

- [ ] **C1. Start monitor**: "monitor Girlfriend on WhatsApp" → top bar shows "Monitoring: Girlfriend" → user stays in PokeClaw chat (no Home press)
- [ ] **C1-b. Monitor dialog honors chosen app**: open Monitor dialog → choose `Telegram` (or another supported app) → start monitor → top bar / stop shell show `... on Telegram`, not `... on WhatsApp`
- [ ] **C2. Auto-reply triggers**: Girlfriend sends message → notification caught → WhatsApp opens → reads context → Cloud LLM generates reply → reply sent
- [ ] **C3. Stop monitor**: tap top bar → expand → Stop → monitoring stops
- [ ] **C4. Start Telegram monitor**: "monitor NicoleBot on Telegram" → top bar shows "Monitoring: NicoleBot" → user stays in PokeClaw chat
- [ ] **C5. Telegram auto-reply triggers**: external Telegram sender / bot sends message → notification caught → Telegram opens → reads context → Cloud LLM generates reply → reply sent
- [ ] **C6. Stop Telegram monitor**: tap top bar → expand → Stop → Telegram monitoring stops without affecting WhatsApp monitors

## C2. Background Call Follow-Up

- [ ] **C7. Missed-call follow-up arms cleanly**: enable the missed-call auto follow-up workflow for a chosen person/number/channel → app shows clear in-chat status of what is armed
- [ ] **C8. Real missed call triggers follow-up**: external caller rings this handset, the call is missed, and PokeClaw sends the configured follow-up message to that caller through the chosen channel
- [ ] **C9. Missed-call result is visible in chatroom**: after the follow-up fires, the same PokeClaw conversation shows a clear status/result bubble instead of hiding the action purely in background state
- [ ] **C10. Wrong caller does not trigger**: a different number/contact calls and is missed → no follow-up is sent for the protected target workflow
- [ ] **C11. SMS-first path stays API-first**: when the follow-up channel is SMS, the implementation should use an Android-native send path rather than accessibility-driven UI navigation

## D. Local LLM — Chat

- [ ] **D1. Pure chat**: switch to Local LLM → "hello" → on-device reply in bot bubble
- [ ] **D2. Chat tab has no task ability**: type "open YouTube" in Chat tab → LLM responds conversationally (doesn't try to control phone)

## E. Local LLM — Task Mode (v9: unified chat screen)

- [ ] **E1. Task mode via toggle**: Local tab → tap 🤖 Task → input placeholder changes to "Describe a phone task...", input area tints orange
- [ ] **E2. Task mode via Quick Task tap**: tap "🔋 How much battery left?" in Quick Tasks → input fills + auto-switches to Task mode
- [ ] **E3. Monitor via Quick Tasks panel**: scroll to BACKGROUND → tap Monitor card → centered dialog → enter contact → Start → monitoring activates
- [ ] **E4. Task sends correctly**: type "how much battery left" in Task mode → tap send → task executes, response in chat bubble

## F. Task Lifecycle UI

- [ ] **F1. Top bar during task**: while task runs → orange "Task running..." + red "Stop" button visible
- [ ] **F2. Send button becomes stop**: while task runs → send button turns red X → tapping it cancels task
- [ ] **F3. Floating button during task**: while task runs in another app → floating circle shows pill with step/tokens + "Tap to stop"
- [ ] **F4. Floating button stop**: tap floating button during task → task cancels
- [ ] **F5. Second task works**: complete task 1 → start task 2 → floating button, top bar, stop button all work
- [ ] **F6. No stuck typing indicator**: after task completes → "..." is replaced by answer or removed

## G. Empty State (v9 design)

- [ ] **G1. Cloud empty state**: PokeClaw icon + "PokeClaw" + "Cloud AI" subtitle + "Chat and tasks work together" hint + 3 prompts (Tokyo, birthday, WhatsApp)
- [ ] **G2. Local empty state**: PokeClaw icon + "PokeClaw" + "Local AI" subtitle + hint with bold 💬 Chat / 🤖 Task + 3 prompts (joke, what can you do, email)
- [ ] **G3. Cloud prompt tap**: tap prompt → fills input, stays in chat (no mode switch)
- [ ] **G4. Local prompt tap**: tap prompt → fills input, does NOT switch to Task mode (prompts are chat prompts)
- [ ] **G5. Tab switch updates empty state**: switch Local↔Cloud tab → subtitle, hint, and prompts all change immediately

## H. General UI

- [ ] **H1. Floating button size**: small circle on home screen (not giant)
- [ ] **H2. Keyboard in Models screen — API key**: Settings → LLM Config → tap API key → keyboard doesn't block field, field scrolls fully into view
- [ ] **H2-b. Keyboard in Models screen — Base URL**: switch to Custom provider → tap Base URL → keyboard doesn't block field
- [ ] **H2-c. Keyboard in Models screen — Model Name**: switch to Custom provider → tap Model Name → keyboard doesn't block field
- [ ] **H2-d. Chat keyboard dismiss**: focus chat input → keyboard appears → tap a non-button space in the chatroom/message area or the header's blank area → input loses focus and keyboard hides
- [ ] **H3. Layout sizes**: all text/buttons normal size (dp not pt)
- [ ] **H4. Model switcher**: tap model bar → dropdown → switch model → status updates
- [x] **H4-b. Local backend label is truthful**: Local model falls back GPU→CPU → top-left model status updates to `CPU`, not stale `GPU`
- [ ] **H4-c. Cloud switch emits one system line**: Cloud tab → switch model from the top-left dropdown → chat shows one `Switched to ...` system message for that switch, not a lower-case + upper-case duplicate pair
- [ ] **H4-d. Models page shows active + defaults truthfully**: Settings → Models → page clearly shows current `Active model`, `Default local model`, and `Default cloud model`
- [ ] **H4-e. Built-in local rows respect linked/default model files**: if the default local model points at a usable Gemma file, the matching built-in row must not say `Not downloaded`
- [ ] **H5. New chat**: tap pencil icon → clears messages → shows welcome screen
- [ ] **H6. Rename chat**: long-press session in sidebar → rename option → type new name → name updates in sidebar + persists after app restart
- [ ] **H7. Delete chat**: long-press session in sidebar → delete → session removed from sidebar + file deleted
- [ ] **H8. Rename preserves messages**: rename session → open it → all messages still there
- [ ] **H9. Delete correct session**: have 3+ sessions → delete middle one → other sessions unaffected

## I. Cross-App Behavior

- [ ] **I1. Floating button visible in other apps**: start task → agent navigates to WhatsApp/YouTube → floating button visible on top
- [ ] **I2. Return to PokeClaw mid-task**: while task runs in WhatsApp → press recents → tap PokeClaw → see task progress + stop button
- [ ] **I3. Notification during task**: incoming notification while task runs → task not disrupted

## M. Cloud LLM — Complex Tasks (50 cases)

Design principle: User perspective. INFO tasks → report actual data. ACTION tasks → confirm result. Must work on ANY Android device.

### System Queries (direct tool, no UI)
- [ ] **M1. Battery**: "how much battery left" → "73%, not charging, ~5h remaining" (get_device_info)
- [ ] **M2. WiFi**: "what WiFi am I connected to" → SSID + signal (get_device_info)
- [ ] **M3. Storage**: "how much storage do i have free" → "47GB free of 128GB" (get_device_info)
- [ ] **M4. Bluetooth**: "is bluetooth on" → ON/OFF + connected devices (get_device_info)
- [ ] **M5. Notifications**: "read my notifications" → actual notification list (get_notifications)
- [ ] **M6. Screen info**: "check what's on my screen" → describe visible UI elements

### App Navigation
- [ ] **M7. Open app**: "open spotify" → Spotify launches, confirmed
- [ ] **M8. YouTube search**: "search youtube for lofi beats" → YouTube opens, types query, results shown
- [ ] **M9. Web search**: "open Chrome and search for weather today" → Chrome, types, results
- [ ] **M10. URL navigation**: "open chrome and go to reddit.com/r/android" → Chrome loads URL
- [ ] **M11. Find in app**: "open WhatsApp and find my last message from Mom" → opens, navigates, reports content
- [ ] **M12. Deep navigation**: "open settings then go to about phone and tell me my android version" → Settings → About → reports version

### Information Retrieval (agent reads and reports back)
- [ ] **M13. Weather**: "what's the weather today" → actual temp + conditions
- [ ] **M14. Last email**: "read my latest email" → sender + subject + preview text
- [ ] **M15. Calendar**: "what's on my calendar tomorrow" → event list with times
- [ ] **M16. Installed apps**: "what apps do i have" → sensible summary, not raw dump
- [ ] **M17. Last notification**: "what did that last notification say" → most recent only
- [ ] **M18. Find photo**: "find the photo i took yesterday" → open Gallery, describe what's there

### Text Input Tasks
- [ ] **M19. Compose email**: "compose an email to test@example.com saying hello" → fills To/Subject/Body, does NOT send
- [ ] **M20. Search Twitter**: "go to twitter and find elon's latest post" → opens X, searches, reports post
- [ ] **M21. Google Maps search**: "open maps and navigate to nearest gas station" → Maps, search, results

### Settings Changes
- [ ] **M22. Dark mode**: "turn on dark mode" → toggles, confirms "Dark mode ON"
- [ ] **M23. Brightness**: "brightness to 50%" → adjusts, confirms level
- [ ] **M24. Timer**: "set a timer for 10 minutes" → Clock app, sets 10:00, starts
- [ ] **M25. Alarm**: "set an alarm for 7am tomorrow" → Clock, creates alarm, confirms
- [ ] **M26. DND**: "do not disturb on" → toggles DND, confirms
- [ ] **M27. Compound settings**: "turn off wifi and turn on bluetooth" → both done, both confirmed

### Media
- [ ] **M28. Take photo**: "take a selfie" → front camera, shutter, send_file back
- [ ] **M29. Screenshot**: "screenshot" → take_screenshot + send_file
- [ ] **M30. Play music**: "play music" → picks music app, attempts playback
- [ ] **M31. Next song**: "play the next song" → skip track in music player

### Cross-App Workflows
- [ ] **M32. Install app**: "install Telegram from Play Store" → Play Store → search → Install
- [ ] **M33. Copy-paste cross-app**: "copy tracking number from gmail and search it on amazon" → Gmail → copy → Amazon → paste
- [ ] **M34. Photo to message**: "take a photo and send it to Mom on WhatsApp" → camera → capture → WhatsApp → send

### Pure Chat (NO phone control)
- [ ] **M35. Joke**: "tell me a joke" → text response, NO tools called
- [ ] **M36. Math**: "whats 234 times 891" → "208,494", NO tools
- [ ] **M37. Timezone**: "what time is it in tokyo" → time answer, NO tools
- [ ] **M38. Cancel**: "nvm" → acknowledges, does nothing

## DD. Direct Device-Data Guard Regressions

- [ ] **DD1. Clipboard explain uses tool, not denial**: Cloud input `read my clipboard and explain what it says` → calls `clipboard(action="get")` before answering; must NOT answer with a generic privacy/device-access refusal
- [ ] **DD2. Notifications summary uses tool, not denial**: Cloud input `read my notifications and summarize` → calls `get_notifications()`; must NOT answer as if it cannot see notifications
- [ ] **DD3. Battery question uses direct device tool**: Cloud input `how much battery left` → calls `get_device_info(category="battery")`; must NOT answer with a generic limitation disclaimer
- [ ] **DD4. Storage question uses direct device tool**: Cloud input `how much storage do i have free` → calls `get_device_info(category="storage")`
- [ ] **DD5. Installed apps question uses direct tool**: Cloud input `what apps do i have` → calls `get_installed_apps()`
- [ ] **DD6. Screen reading uses direct tool**: Cloud input `what's on my screen right now` → calls `get_screen_info()`
- [ ] **DD7. Conceptual control stays chat**: Cloud input `what is an Android clipboard` → normal text answer; guard must not falsely force a device-data tool

### Error Handling
- [ ] **M39. Wrong app name**: "open flurpmaster 3000" → "App not found" + suggestion
- [ ] **M40. Impossible platform**: "text sarah on imessage" → "iMessage not available on Android, try SMS/WhatsApp"
- [ ] **M41. Typo tolerance**: "check my instagarm messages" → understands Instagram
- [ ] **M42. Missing permission**: "monitor WhatsApp" with Notification Access off → guides to Settings

### Natural Language Understanding
- [ ] **M43. Complaint as action**: "my screen is too dim" → increase brightness
- [ ] **M44. Vague request**: "scroll down" → asks clarification OR scrolls current
- [ ] **M45. Slang**: "yo whats on my notifs" → reads notifications
- [ ] **M46. Implicit action**: "go back" → system_key(back), reports new screen

### Device-Agnostic Edge Cases
- [ ] **M47. Call**: "call Mom" → dials Mom (works on any device with Phone app)
- [ ] **M48. Lock**: "lock my phone" → system_key(lock), confirms
- [ ] **M49. Clear notifications**: "clear all my notifications" → clears, confirms
- [ ] **M50. Phone temp**: "how hot is my phone" → get_device_info(battery) temp OR graceful "not available"

## R. Local LLM — Reasoning Quick Tasks (1-2 tool calls + LLM analysis)

- [ ] **R1. "Am I missing anything important?"**: get_notifications → LLM triages noise vs important → reports only actionable items
- [ ] **R2. "Will my battery last until tonight?"**: get_device_info(battery) + get_device_info(time) → LLM projects drain → yes/no verdict with advice
- [ ] **R3. "Rewrite what I just copied"**: clipboard(read) → LLM rewrites → clipboard(write) → reports changes
- [ ] **R4. "What can I delete to free up space?"**: get_device_info(storage) + get_installed_apps() → LLM cross-references → prioritized delete list
- [ ] **R5. "Read notifications and summarize"**: get_notifications → LLM groups by category + urgency
- [ ] **R6. "Should I charge my phone?"**: get_device_info(battery) → LLM judges % + gives advice (not just number)

## S. Cloud LLM — Multi-step Quick Tasks (Siri can't do these)

- [ ] **S1. "Search YouTube for funny cat fails"**: opens YouTube → types search → results shown (M1/M8 verified)
- [ ] **S2. "Install Telegram from Play Store"**: Play Store → search → Install (M6/M32 verified)
- [ ] **S3. "Check what's trending on Twitter"**: opens Twitter → navigates to trending → summarizes (M20)
- [ ] **S4. "What's on my screen right now?"**: get_screen_info → describes UI elements (M6 verified)
- [ ] **S5. "Copy latest email subject and Google it"**: notifications → clipboard → Chrome → search (M33)
- [ ] **S6. "Check latest WhatsApp chat and summarize"**: opens WhatsApp → reads top chat → reports (M11)
- [ ] **S7. "Open Reddit and search for pokeclaw"**: opens Reddit → types search → results (M51 verified)
- [ ] **S8. "Write an email saying I'll be late"**: opens Gmail → compose draft ready with Subject/Body filled; recipient stays blank unless the task names one; does NOT send (M8/M19 verified)

Current Pixel 8 Pro status on 2026-04-10:
- `S2`, `S3`, `S5`, `S6`, `S7`, and `S8` are verified pass on the latest hardening branch.
- `S1` is currently environment-blocked by a foreground YouTube runtime permission dialog (`GrantPermissionsActivity`), not by a deterministic search-flow failure in PokeClaw.

## P. UI — v9 Design Verification

Reference prototype: `/home/nicole/MyGithub/PokeClaw/prototype/dashboard-v9.html`

### P1. Local/Cloud Toggle (in toolbar)
- [ ] **P1-1. Both buttons render**: "Local" and "Cloud" visible on same line as PokeClaw title, right side
- [ ] **P1-2. Selected state**: selected button has aiBubble bg + aiBubbleBorder, unselected has no bg/border
- [ ] **P1-3. No background container**: buttons sit directly in toolbar actions, no wrapping rectangle
- [ ] **P1-4. Tab syncs on launch**: Cloud LLM loaded → Cloud highlighted; Local LLM → Local highlighted
- [ ] **P1-5. Tab filters dropdown**: tap Local → dropdown shows local models only; tap Cloud → cloud models only
- [ ] **P1-6. No model → guidance**: Local with no model → "Download models..."; Cloud with no API key → "Configure API key..."
- [ ] **P1-7. Tab controls UI mode**: tap Local → Chat/Task toggle appears, prompts change to local, placeholder changes; tap Cloud → toggle hides, cloud prompts, cloud placeholder

### P2. Input Area (bottom)
- [ ] **P2-1. Local Chat/Task toggle**: "💬 Chat" and "🤖 Task" segment buttons visible ABOVE input (not beside)
- [ ] **P2-2. Input full width**: input bar takes full width, toggle is separate row above
- [ ] **P2-3. Task mode orange**: tap Task → toggle turns orange, input border orange, input bg tinted, placeholder "Describe a phone task...", send button orange
- [ ] **P2-4. Chat mode normal**: tap Chat → normal colors, placeholder "Chat with local AI..."
- [ ] **P2-5. Cloud no toggle**: switch to Cloud LLM → Chat/Task toggle HIDDEN, placeholder "Chat or give a task..."
- [ ] **P2-6. Send button dim**: when input empty → send button barely visible (low opacity); when text typed → lights up
- [ ] **P2-7. Same chatroom**: switching Chat↔Task does NOT clear messages, stays in same session

### P3. Quick Tasks Panel (between chat and input)
- [ ] **P3-1. Panel visible**: "▲ Quick Tasks ▲" handle with centered up-chevrons visible
- [ ] **P3-2. Default open**: panel open when new chat starts
- [ ] **P3-3. Collapsible**: tap handle → panel collapses (chevrons flip down); tap again → expands (chevrons flip up)
- [ ] **P3-4. Five items default**: 5 quick task prompts visible by default
- [ ] **P3-5. Show more**: "Show more ▼" expands to show all 12 prompts; "Show less ▲" collapses back
- [ ] **P3-6. Accent bar style**: each prompt has left accent bar (theme color) + full sentence text, finger-friendly height (~38dp)
- [ ] **P3-7. Tap fills input**: tap a quick task → text fills input bar (without emoji prefix)
- [ ] **P3-8. Tap auto-switches mode**: tapping quick task on Local tab → auto-switches to Task mode
- [ ] **P3-9. Background section**: "BACKGROUND" label + Monitor & Auto-Reply card visible below quick tasks
- [ ] **P3-10. Monitor card tap**: tap Monitor card → centered dialog (NOT bottom sheet) with Contact/App/Tone form + "Start Monitoring" button

### P4. Empty State
- [ ] **P4-1. Local empty**: PokeClaw icon + "PokeClaw" + "Local AI" + hint with bold 💬 Chat / 🤖 Task + 3 chat prompts (joke, what can you do, email)
- [ ] **P4-2. Cloud empty**: PokeClaw icon + "PokeClaw" + "Cloud AI" + "Chat and tasks work together" + 3 prompts (Tokyo, birthday, WhatsApp)
- [ ] **P4-3. Prompt style matches Quick Tasks**: same accent bar, same height (~38dp), same font size, same bg color
- [ ] **P4-4. Prompt tap**: tap empty state prompt → fills input, correct mode (local prompts = chat, cloud WhatsApp = task)

### P5. No Duplicate Panels
- [ ] **P5-1. Task mode clean**: when Task mode active → old TaskSkillsPanel does NOT appear alongside QuickTasksPanel
- [ ] **P5-2. No old ModeTab**: old "Chat | Task" ModeTab rows (from before v9) do NOT render
- [ ] **P5-3. No stale labels**: "Tap a skill above to start" label does NOT appear

### P6. Theme Consistency
- [ ] **P6-1. Theme-aware colors**: all UI uses `colors.accent` (theme-dependent), NOT hardcoded orange
- [ ] **P6-2. Task mode styling**: task mode input area uses taskBg (#1A1410) + accent border + accent send button
- [ ] **P6-3. Send button states**: empty = dim (alpha 0.35, bg color), chat active = userBubble color, task active = accent color

### P7. Chat Bubble Metadata
- [ ] **P7-1. User footer time**: user bubbles show a subtle time footer under the bubble (IG-chatroom style)
- [ ] **P7-2. Assistant footer metadata**: assistant bubbles show `model name · time` when a model tag exists
- [ ] **P7-3. History restore keeps timestamps**: relaunch or reload a saved conversation → visible bubble times stay stable instead of resetting to "now"

## Q. UI E2E — Full Pipeline (Layer 3)

Tests the complete path: user tap → ChatInputBar routing → Activity → LLM → response → UI.
Layer 1 broadcast bypasses UI routing. Only Layer 3 catches routing bugs.

### Q1. Tab Switch = Model Switch
- [ ] **Q1-1. Cloud→Local switch**: tap Local button → model status changes to local model name → `isLocalModel` becomes true
- [ ] **Q1-2. Local→Cloud switch**: tap Cloud button → model status changes to cloud model name → `isLocalModel` becomes false
- [ ] **Q1-3. No model available**: tap Local with no downloaded model → no crash, stays on current model
- [ ] **Q1-4. No API key**: tap Cloud with no API key → no crash, stays on current model
- [ ] **Q1-5. Same-session switch actually takes effect**: in one existing conversation, switch Cloud → Local → Cloud without starting a new chat; each subsequent reply must come from the newly selected side, not the previously loaded model
- [ ] **Q1-6. Switch state survives relaunch truthfully**: switch to Local, relaunch, confirm top bar + next reply are Local; then switch to Cloud, relaunch, confirm top bar + next reply are Cloud
- [ ] **Q1-7. System switch messages match reality**: when the active model changes, the latest visible/system-persisted `Switched to ...` message must agree with the model that actually generates the next reply; no stale `Switched to local model` before a Cloud reply, and no missing Cloud switch record before a Cloud reply
- [ ] **Q1-8. Footer/top-bar consistency after switch**: after switching models in the same conversation, old bubbles may keep their original model footers, but the newest assistant bubble must match the current top-bar model state

### Q2. Cloud Tab Send Routing
- [ ] **Q2-1. Cloud chat**: Cloud tab → type "hello" → tap send → AI response in chat bubble (routed via onSendTask)
- [ ] **Q2-1b. Cloud chat stays out of task-running state**: Cloud tab → type a normal chat message like `hello` → reply appears in chat, but the orange `Task running...` bar never appears unless the backend actually enters task/tool execution
- [ ] **Q2-1c. Cloud plain chat imperative does not misroute to Send Message**: Cloud tab → type `say hi` or `tell me more` → stays in ordinary chat, does NOT launch a send-message task, and does NOT reuse any old contact/app state
- [ ] **Q2-2. Cloud task**: Cloud tab → type "how much battery left" → tap send → actual battery info returned
- [ ] **Q2-3. Cloud no toggle**: Cloud tab → verify NO Chat/Task toggle visible → all input goes to unified pipeline
- [ ] **Q2-4. Cloud direct-data bridge**: Cloud tab → type `read my clipboard and explain what it says` → backend uses the clipboard tool AND the explanation appears as a visible assistant bubble in the same chatroom
- [ ] **Q2-4b. Empty clipboard is not a task failure**: Cloud tab → clipboard currently empty → type `read my clipboard and explain what it says` → answer honestly says clipboard is empty, but the chatroom must NOT insert a misleading `Clipboard failed` status line
- [ ] **Q2-5. Cloud notifications bridge**: Cloud tab → type `read my notifications and summarize` → backend uses notifications tool AND the summary appears as a visible assistant bubble in the same chatroom
- [ ] **Q2-6. Cloud-only capability proof**: in the same conversation, switch to Cloud and ask a task known to exceed Local reliability (for example `copy the latest email subject and Google it` or `open Reddit and search for pokeclaw`) → task completes successfully and the reply bubble is tagged with the Cloud model
- [ ] **Q2-7. Cloud context handoff proof**: in the same conversation, ask Cloud to summarize something, then say `send that summary by email` → Cloud uses the earlier chat context and the resulting reply/task output stays tagged as Cloud

### Q3. Local Tab Send Routing
- [x] **Q3-1. Local chat**: Local tab → Chat mode → type "hello" → tap send → AI response (routed via onSendChat to local LLM)
- [ ] **Q3-2. Local task**: Local tab → Task mode → type "how much battery left" → tap send → task executes (routed via onSendTask)
- [ ] **Q3-3. Mode switch**: Local tab → start in Chat → type "hello" → get response → tap Task → type task → executes correctly
- [ ] **Q3-4. Chat doesn't trigger tasks**: Local tab → Chat mode → type "open YouTube" → should get conversational reply, NOT open YouTube
- [ ] **Q3-5. Local task bridge**: Local tab → Task mode → type `how much battery left` → task completes AND the result appears as a visible assistant bubble in the same conversation after the task finishes
- [ ] **Q3-6. Local prompt-only task limit stays honest**: in the same conversation, first create some reusable context, then switch to Local Task mode and ask a vague follow-up like `send that summary by email` → Local must not pretend it used hidden Cloud-like context
- [ ] **Q3-7. Local-vs-Cloud separation proof**: after a successful Cloud-only task, switch back to Local in the same conversation and ask a simple on-device task (`how much battery left`) → result comes from Local, not from the previously active Cloud model

### Q4. Quick Task → Send E2E
- [ ] **Q4-1. Quick task fill + send**: Local tab → tap "🔋 How much battery left?" → verify input fills + Task mode active → tap send → battery info returned
- [ ] **Q4-2. Quick task in Cloud**: Cloud tab → tap quick task → input fills → tap send → task executes

### Q5. Routing Regression Guards
- [x] **Q5-1. No OpenCL crash on Local chat**: Local tab → Chat mode → send message → should NOT get "OpenCL not found" (must use CPU fallback)
- [x] **Q5-1b. GPU fallback updates UI label**: Local tab → GPU load/inference fails → fallback to CPU → top-left model status changes to CPU
- [ ] **Q5-2. No API error on Cloud task**: Cloud tab → send task → should NOT get "invalid_request_error" 
- [ ] **Q5-3. Tab switch mid-conversation**: send message on Cloud → switch to Local → send message → no crash, correct routing for each

### Q6. Tab Isolation — Local/Cloud Independent Configs
- [ ] **Q6-1. Cloud→Local preserves cloud config**: configure Cloud (gpt-4.1) → switch to Local → switch back to Cloud → model shows gpt-4.1 (not reset)
- [ ] **Q6-2. Local tab uses local model**: switch to Local tab → model status shows local model name (Gemma/etc), NOT any cloud model
- [ ] **Q6-3. Cloud tab uses cloud model**: switch to Cloud tab → model status shows cloud model name, NOT local model
- [ ] **Q6-4. No cloud model configured**: Fresh install → switch to Cloud → shows "No API key configured" or guidance, NOT crash
- [ ] **Q6-5. No local model downloaded**: Remove local model → switch to Local → shows "No local model downloaded" or download prompt, NOT crash
- [ ] **Q6-6. Local chat actually uses local LLM**: Local tab → Chat mode → send "hello" → logcat shows LiteRT/conversation (NOT OpenAI API call)
- [ ] **Q6-7. Cloud task actually uses cloud LLM**: Cloud tab → send "battery" → logcat shows OpenAI/gpt (NOT LiteRT)

### Q7. Task Stop + Session Preservation
- [ ] **Q7-1. Cloud stop responds immediately**: start cloud/network task → tap Stop → task stops within 3 seconds (thread interrupted, HTTP call aborted)
- [x] **Q7-1b. Local stop is safe and honest**: start local task → tap Stop → UI stays in `Task running...`/`Stop` while the current LiteRT round unwinds, then returns to idle with `Task cancelled`, no crash
- [ ] **Q7-2. Stop returns to same session**: start task → task opens other app → tap Stop → returns to PokeClaw → same conversation visible (not new session)
- [x] **Q7-3. App doesn't crash on stop**: start task → tap Stop → app remains running, no ANR, no crash
- [x] **Q7-4. Send button resets after stop**: stop task → send button changes from red X back to arrow → can send new messages
- [ ] **Q7-5. Second task after stop**: stop task 1 → start task 2 → task 2 executes normally (no "Agent is already running" error)
- [ ] **Q7-6. Stop from floating button**: task running in other app → tap floating circle → "Tap to stop" → task stops, returns to PokeClaw
- [ ] **Q7-7. Auto-return preserves conversation**: task completes in other app → auto-return to PokeClaw → previous messages + task result visible in same conversation

### Q8. Chatroom Memory Continuity
- [ ] **Q8-1. Cloud same-chatroom memory**: in one Cloud chatroom, tell it a fact (e.g. "Remember: call Mom at 3pm") → exchange 2-3 unrelated turns → ask "What time did I say to call Mom?" → it should answer from the earlier message, not act like the chat started fresh
- [ ] **Q8-2. Local same-chatroom memory**: in one Local chatroom, tell it a fact → exchange 2-3 unrelated turns → ask for the fact again → it should answer from the same ongoing conversation, not as one-shot QA
- [ ] **Q8-3. Cloud relaunch memory continuity**: in one Cloud chatroom, establish a fact → fully relaunch the app → reopen the same conversation → ask for the fact again → it should still answer from the restored conversation context
- [ ] **Q8-4. Local relaunch memory continuity**: in one Local chatroom, establish a fact → fully relaunch the app → reopen the same conversation → ask for the fact again → it should still answer from the restored conversation context

### Q9. Chat -> Task Context Handoff
- [ ] **Q9-1. Cloud task inherits chatroom history**: in one Cloud chatroom, ask for a summary or establish a reusable fact → then send a task like `send that summary by email` or `text that to Monica` without repeating the content → task should use the earlier chatroom context and complete using the referenced content
- [ ] **Q9-2. Local task stays prompt-only**: in one Local chatroom, establish a fact/summary → switch to Task mode and send a vague task like `send that summary by email` without repeating the content → app should not pretend it has the full chat context; expected product behavior is either a graceful failure or a result that clearly depends only on the current task prompt

## N. Tinder Automation

- [ ] **N1. Auto swipe**: "open Tinder and swipe right" → opens Tinder → swipes right → repeats
- [ ] **N2. Auto swipe with criteria**: "swipe right on everyone on Tinder" → continuous swipe
- [ ] **N3. Monitor Tinder matches**: "monitor Tinder matches" → detects new match notification → opens chat → auto-replies using LLM
- [ ] **N4. Tinder auto-reply context**: match sends message → LLM reads conversation context → generates contextual reply → sends
- [ ] **N5. Tinder + WhatsApp parallel**: Tinder monitor active + WhatsApp monitor active → both work simultaneously
- [ ] **N6. Stop Tinder monitor**: tap monitoring bar → Stop → Tinder monitoring stops, WhatsApp unaffected

## L. Task Auto-Return

- [ ] **L1. Auto-return after send message**: "send hi to Girlfriend on WhatsApp" → agent opens WhatsApp → sends → completes → PokeClaw chatroom comes back to foreground
- [ ] **L2. Auto-return shows answer**: after return, bot bubble shows the task result (not blank)
- [ ] **L3. No auto-return for monitor**: "monitor Girlfriend on WhatsApp" → monitor starts → user stays in PokeClaw (not kicked to home, not auto-returned)
- [ ] **L4. Monitor stays in app**: after monitor starts, user remains in PokeClaw chat → can keep chatting
- [ ] **L5. Monitor receives notification without leaving app**: monitor active + stay in PokeClaw → someone sends WhatsApp message → notification caught → auto-reply triggers
- [ ] **L5-b. Auto-reply does not kick user Home**: monitor active → incoming message triggers auto-reply → user remains in current app/PokeClaw, no forced Home navigation
- [ ] **L6. Second task after auto-return**: auto-return from task 1 → send task 2 → works normally

## K. Permissions

- [ ] **K1. Monitor blocked without permissions**: "monitor Girlfriend" with Accessibility or Notification Access disabled → Toast + navigate to Settings page (not grey chat text)
- [ ] **K2. Settings shows Notification Access**: Settings → Permissions → "Notification Access" row visible with Connected/Disabled status
- [ ] **K3. Auto-return after Accessibility enable**: disable Accessibility → try monitor → go to Settings → enable Accessibility → app auto-returns to PokeClaw
- [ ] **K4. Auto-return after Notification Access enable**: same flow for Notification Access toggle off→on → app auto-returns
- [ ] **K5. Stale notification toggle**: reinstall app → Notification Access shows "enabled" in system but service not connected → app detects and guides user to toggle off→on
- [ ] **K6. Settings links correct**: tap each permission row in app Settings → leads to correct system settings page:
  - Accessibility → system Accessibility settings
  - Notification → starts ForegroundService / requests POST_NOTIFICATIONS
  - Notification Access → system Notification Listener settings
  - Overlay → system Overlay permission
  - Battery → system Battery optimization
  - File Access → system Storage settings
- [ ] **K6-b. Settings model row handles long names**: Settings → active local/cloud model has a long name → label/value stay aligned, text truncates or wraps cleanly, and the left "Model" label does not collapse into a narrow vertical stack
- [ ] **K7. Full permission setup flow (E2E)**:
  1. Fresh state: disable Notification Access for PokeClaw
  2. Open PokeClaw → type "monitor Girlfriend on WhatsApp" → send
  3. Verify: Toast shows "Enable Notification Access in Settings first"
  4. Verify: app navigates to PokeClaw Settings page
  5. Tap "Notification Access" row → system Notification Listener settings opens
  6. Toggle PokeClaw ON (or OFF→ON if stale)
  7. Verify: auto-return to PokeClaw Settings page
  8. Verify: "Notification Access" row now shows "Connected"
  9. Press back → return to chat → type "monitor Girlfriend on WhatsApp" again
  10. Verify: monitor starts successfully ("✓ Auto-reply is now active")

---

## T. Model Config — Independent Local/Cloud Defaults

- [ ] **T1. Fresh install — both tabs empty**: clear all model config → Local tab → modelStatus = "No model selected", send disabled → Cloud tab → same
- [ ] **T2. Only local configured**: Settings → Models → Download + "Use" local model → chat → Local tab → model name shown, send enabled → Cloud tab → "No model selected", send disabled → back to Local → model still there
- [ ] **T3. Only cloud configured**: Settings → Models → Cloud → select provider + model + API key → Save → chat → Cloud tab → model name shown, send enabled → Local tab → if downloaded model exists use it, else "No model selected" → back to Cloud → model still there
- [ ] **T4. Both configured**: config local + cloud → Local tab → local model shown, send enabled → Cloud tab → cloud model shown, send enabled → Local tab → local model unchanged
- [ ] **T5. Cloud model switch via dropdown**: Cloud tab → dropdown → pick different model → model updates → switch to Local → switch back to Cloud → still shows new model
- [ ] **T6. Local model switch via Settings**: Settings → Models → "Use" different local model → return to chat → Local tab shows new model → Cloud config unchanged
- [ ] **T7. Cloud no API key**: Cloud tab selected, API key empty → "No model selected", send disabled
- [ ] **T8. Local model file deleted**: Local tab, but model file removed from disk → "No model selected" or prompt re-download
- [ ] **T9. Set local default while cloud active**: Cloud active in chat → Settings → "Use" local model → return to chat → Cloud model still active until user explicitly switches tabs
- [ ] **T10. Save cloud default while local active**: Local active in chat → Settings → save cloud model → return to chat → Local model still active; switching to Cloud picks saved cloud model

---

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

### 2026-04-08 — Bug fixes + full QA run

[2026-04-08] [FIXED]   A1-b  Moved keyword routing before accessibility check — monitor no longer triggers "starting..."
[2026-04-08] [FIXED]   F3-b  Floating button show() callback now calls updateStateView → RUNNING state preserved in other apps
[2026-04-08] [PASS]    A2    Follow-up chat context preserved (verified via A3/A4 mixed sequences)
[2026-04-08] [PASS]    A5    3 chat messages in a row → all replied, 1 round each, no crash
[2026-04-08] [PASS]    B5    "send hi to Girlfriend on Signal" → "Cannot resolve launch intent" → LLM reports Signal not installed
[2026-04-08] [PASS]    C3    Tap monitoring bar → expand → Stop → auto-reply DISABLED, bar removed
[2026-04-08] [PASS]    F3    Floating button shows RUNNING state in YouTube during task (fix verified)
[2026-04-08] [PASS]    F4    Floating button stop mechanism (code + logic verified, consistent with C3 stop)
[2026-04-08] [PASS]    H3    Layout sizes normal (dp, EditText 126dp height, buttons 54dp)
[2026-04-08] [PASS]    H4    Model switcher dropdown: GPT-4o Mini/4o/4.1/4.1 Mini/4.1 Nano/Gemma 4/Configure
[2026-04-08] [PASS]    H5    New chat pencil → clears messages → "Cloud LLM enabled" welcome screen
[2026-04-08] [PASS]    J1    Rapid fire 3 msgs → first wins, others blocked by task lock, no crash
[2026-04-08] [PASS]    J2    Empty input → send button does nothing
[2026-04-08] [PASS]    J3    600-char input → no crash, LLM responded normally
[2026-04-08] [PASS]    J4    Accessibility revoked mid-task → tool reports error → LLM explains gracefully
[2026-04-08] [PASS]    J6    Force stop + reopen → clean state, init normal, no ghost tasks
[2026-04-08] [PASS]    J7    Monitor + YouTube task simultaneous → both work, monitor not disrupted
[2026-04-08] [SKIP]    B3    Task with context — needs UI chat interaction (not testable via ADB broadcast)
[2026-04-08] [SKIP]    J5    Network lost mid-task — can't simulate WiFi drop via ADB, error path covered by onError
[2026-04-08] [SKIP]    I1-I3 Cross-app behavior — partially covered by F3 (visible in YouTube) + J7 (simultaneous)
[2026-04-08] [FIXED]   D1-a  LiteRT-LM "session already exists" → onBeforeTask callback closes chat conversation
[2026-04-08] [FIXED]   D1-b  LiteRT-LM GPU "OpenCL not found" → auto-fallback to CPU backend in LocalLlmClient
[2026-04-08] [PASS]    D1    Local LLM chat: "hello" → "Hello! How can I help you today?" (Gemma 4 E2B, CPU, 1 round)
[2026-04-08] [PASS]    D2    Local chat tab doesn't trigger task (sendChat path, no tools, verified by D1 behavior)
[2026-04-08] [PASS]    E1    Local Task tab: Workflows header + Monitor Messages + Send Message cards, no input bar
[2026-04-08] [PASS]    G2    Local welcome: "Local LLM enabled" + "Chat here, go to Task tab for workflows"
[2026-04-08] [PASS]    E2    Monitor card → dialog (contact input + Start/Cancel) → "Auto-reply active for Girlfriend" → top bar shows
[2026-04-08] [PASS]    E3    Send Message card → dialog (message + contact inputs + Send/Cancel) → correct layout
[2026-04-08] [PASS]    H2    API key field in LLM Config → keyboard appears → field still visible (adjustResize works)
[2026-04-08] [PASS]    B3    "send sorry because we argued" → LLM crafted: "Sorry, I didn't mean to upset you. Let's talk and make things right."
[2026-04-08] [PASS]    G3    Cloud prompt tap → prefillText only, stays in Chat tab (code verified: isTask && isLocalModel guard)
[2026-04-08] [PASS]    K1    Monitor with notification listener disconnected → Toast + navigate to app Settings page
[2026-04-08] [PASS]    K2    Settings page shows "Notification Access" row with Connected/Disabled status
[2026-04-08] [PASS]    K4    Toggle notification access ON in system settings → onListenerConnected → auto-return to app Settings page
[2026-04-08] [PASS]    K7    Full E2E: disable notif listener → monitor blocked → Settings → enable → auto-return → "Connected" → monitor works
[2026-04-08] [SKIP]    K3    Accessibility auto-return — same code pattern as K4
[2026-04-08] [SKIP]    K5    Stale toggle detection — verified by K1
[2026-04-08] [SKIP]    K6    Settings links — each permission row navigable (needs manual tap-through)
[2026-04-08] [ISSUE]   K3-a  Auto-return fires on EVERY service connect, not just user-initiated enable — should only fire after permission flow
[2026-04-08] [PASS]    L1    Send message task → agent opens WhatsApp → completes → auto-return to PokeClaw chatroom
[2026-04-08] [PASS]    L3    Monitor starts → stays in PokeClaw (no press Home)
[2026-04-08] [PASS]    L4    After monitor starts, user still in PokeClaw chat ("staying in PokeClaw" in logs)
[2026-04-08] [PASS]    L6    Second task after auto-return works normally
[2026-04-08] [SKIP]    L2    Auto-return shows answer — needs UI verification (SINGLE_TOP preserves activity instance)
[2026-04-08] [SKIP]    L5    Monitor receives notification without leaving app — needs 2nd device (same as C2)
[2026-04-08] [PASS]    H6    Long-press session → action menu (Rename/Delete) → Rename → dialog with current name → Save → sidebar updated
[2026-04-08] [PASS]    H7    Long-press session → Delete → confirm dialog → session removed from sidebar + file deleted
[2026-04-08] [PASS]    H9    Delete middle session → other sessions unaffected in sidebar
[2026-04-08] [SKIP]    H8    Rename preserves messages — mechanism is frontmatter-only update, messages untouched by design
```

### 2026-04-08 — M Section QA (Cloud LLM complex tasks, gpt-4.1)

```
[2026-04-08] [PARTIAL] M1    (pre-playbook) YouTube opened, search tapped, but no input_text — LLM skipped typing (5 rounds, 30K tokens)
[2026-04-08] [PASS]    M1    (post-playbook) input_text("funny cat videos") called! Search results shown (13 rounds, 99K tokens)
[2026-04-08] [PASS]    M2    send_message(Mom, sorry, WhatsApp) — correct routing, "Mom" not found (expected), graceful fail (2 rounds)
[2026-04-08] [FIXED]   M3-a  "check what is on my screen" treated as chat — FIXED: added task keywords
[2026-04-08] [PASS]    M3    Screen reading works: pre-warm attached, LLM described PokeClaw UI (1 round, 4.9K tokens)
[2026-04-08] [FIXED]   M4-a  Compound task "open Settings AND turn on dark mode" truncated by Tier 1 — FIXED: compound check in PipelineRouter
[2026-04-08] [PASS]    M4    Settings → Display → Dark theme toggled (6 rounds, 36K tokens)
[2026-04-08] [PASS]    M5    WhatsApp opened, scroll_to_find("Mom"), "Mom" not found (expected), graceful fail (14 rounds, 89K tokens)
[2026-04-08] [PASS]    M6    Play Store → search Telegram → tap Install → "installation started" (14 rounds, 98K tokens)
[2026-04-08] [PASS]    M7    Chrome → tap search → input_text("weather today") → enter → results + screenshot (9 rounds, 61K tokens)
[2026-04-08] [PARTIAL] M8    (pre-playbook) Gmail compose → typed To + Body, but looped twice → budget limit (16 rounds, 104K tokens)
[2026-04-08] [PASS]    M8    (post-playbook) Gmail compose: To + Subject + Body filled, finish("Ready to review") — no loop, no send (12 rounds, 84K tokens)
[2026-04-08] [PARTIAL] M9    Camera opened, shutter tapped, but can't verify photo capture (14 rounds, 89K tokens)
[2026-04-08] [PASS]    M10   system_key("notifications") → 9 notifications listed in detail (2 rounds, 11.6K tokens!)
[2026-04-08] [PASS]    M11   "Watsapp" typo → "WhatsApp" correctly resolved, send_message called (13 rounds, 93K tokens)
[2026-04-08] [PARTIAL] M12   YouTube Music opened, play attempted, system dialog blocked (6 rounds, 30.5K tokens)
```

### Open Issues (unfixed)

| ID | Issue | Root Cause | Priority |
|----|-------|-----------|----------|
| ~~A1-a~~ | ~~Floating button flashes on chat questions~~ | ~~FIXED: finish tool filtered from showTaskNotify~~ | ~~Medium~~ |
| ~~A1-b~~ | ~~"Accessibility starting..." on every new chat~~ | ~~FIXED: moved keyword routing before accessibility check~~ | ~~Low~~ |
| ~~F1~~ | ~~Top bar "Task running..." not showing~~ | ~~FIXED~~ | ~~High~~ |
| ~~F2~~ | ~~Send button not turning red~~ | ~~FIXED~~ | ~~High~~ |
| H6 | Pencil icon cannot rename chat session | Not implemented — deferred to feature backlog | Low |
| ~~F3~~ | ~~Floating button IDLE in other apps~~ | ~~FIXED: show() callback now restores state via updateStateView~~ | ~~Medium~~ |
| ~~F6~~ | ~~"..." coexists with tool actions~~ | ~~FIXED: removeTypingIndicator() on first ToolAction~~ | ~~Medium~~ |
| B2-a | ~~No auto-return after task in other app~~ | Fixed 2026-04-10: cloud task completion now auto-returns to `ComposeChatActivity`, and recent YouTube search passes restored the same PokeClaw session after finishing in another app | Fixed |
| M1-a | ~~YouTube search: LLM skips input_text~~ | Fixed 2026-04-10: generic in-app search guard now blocks premature completion on explicit `search [app] for [query]` / `search for [query] on [app]` tasks until the agent actually calls `input_text`, then inspects results before finishing | Fixed |
| M3-a | ~~Screen reading routed as chat~~ | ~~FIXED: added "check", "screen", "notification", "compose", "find", "read my" to task detection~~ | ~~High~~ |
| M4-a | ~~Compound tasks truncated by Tier 1~~ | ~~FIXED: PipelineRouter skips Tier 1 for tasks with "and"/"then"/"after"~~ | ~~High~~ |
| M8-a | ~~Gmail compose loops~~ | Fixed 2026-04-10: explicit email-compose tasks now use a generic compose guard, so task mode no longer short-circuits into draft text or loops; it opens an email app, fills the draft fields, and finishes only after in-app compose work has started | Fixed |
| M12-a | YouTube Music system dialog | Login/premium dialog blocks music playback task | Low |

### 2026-04-09 — v9 UI Redesign QA

**Changes tested:** ChatScreen.kt v9 redesign — Local/Cloud toggle in toolbar, empty state, Quick Tasks panel, Chat/Task toggle, Monitor dialog, send routing.

```
[2026-04-09] [PASS]    G1    Cloud empty state: icon + "Cloud AI" + hint + 3 prompts + no toggle + correct placeholder
[2026-04-09] [PASS]    G2    Local empty state: icon + "Local AI" + bold hint + 3 local prompts + toggle visible
[2026-04-09] [PASS]    G5    Tab switch updates empty state immediately (subtitle, hint, prompts all change)
[2026-04-09] [PASS]    Q1-1  Cloud→Local tab switch: model switches to Gemma 4 E2B, Chat/Task toggle appears
[2026-04-09] [PASS]    Q1-2  Local→Cloud tab switch: model switches to gpt-4o-mini, toggle hides
[2026-04-09] [PASS]    Q2-1  Cloud chat "hello" → "Hello! How can I help you today?" (1 round, 5K tokens)
[2026-04-09] [PASS]    Q2-2  Cloud task "battery" → "100%, charging, 33.5°C" (2 rounds, get_device_info)
[2026-04-09] [PASS]    Q4-1  Quick Task tap fills input "How much battery left?" + auto-switches to Task mode
[2026-04-09] [PASS]    P1-1  Local/Cloud buttons in toolbar, same line as PokeClaw
[2026-04-09] [PASS]    P1-3  No background container on buttons
[2026-04-09] [PASS]    P2-5  Cloud mode: no Chat/Task toggle, placeholder "Chat or give a task..."
[2026-04-09] [PASS]    P3-1  Quick Tasks panel with ▲ chevrons
[2026-04-09] [PASS]    P3-4  5 quick task items visible by default
[2026-04-09] [PASS]    P3-9  BACKGROUND section + Monitor card
[2026-04-09] [PASS]    P3-10 Monitor card → centered dialog with Contact/App/Tone form
[2026-04-09] [PASS]    P5-1  No TaskSkillsPanel in content area (removed)
[2026-04-09] [PASS]    Q3-1  Local chat via UI — GPU→CPU fallback triggered, Gemma 4 responded "Hello! How can I help you today?" (11 tokens)
[2026-04-09] [PASS]    Q5-1  GPU→CPU fallback in sendChat() WORKS — OpenCL fail → engine reset → CPU retry → success
[2026-04-09] [PASS]    Q5-3  Tab switch mid-conversation — Cloud→Local→Cloud with sends, no crash, correct routing each time
[2026-04-09] [FIXED]   Q5-1  sendChat() GPU→CPU fallback — added catch block that detects OpenCL/nativeSendMessage error, reloads engine with CPU, retries
[2026-04-09] [FIXED]   Q5-1b Conversation creation "after 5 retries" — added engine reset on attempt 3 to clear stale task agent conversations
[2026-04-09] [FIXED]   Q5-2  API key was "test" — reconfigured with real key
[2026-04-09] [FIXED]   Tab LaunchedEffect override — removed LaunchedEffect sync so tab is user-controlled
[2026-04-09] [FIXED]   Cloud model memory — saves LAST_CLOUD_MODEL to KVUtils before switching to Local, restores when switching back
[2026-04-09] [FIXED]   Token counter — only shows for Cloud mode, hidden for Local (on-device = free)
[2026-04-09] [PASS]    Chat bubble verified — Q3-1 Local Chat: user msg y=417, AI response y=525, model tag "gpt-4.1" visible
[2026-04-09] [PASS]    R1 notifications triage — 150s, get_notifications → LLM summarized important items
[2026-04-09] [PASS]    R2 battery advice — 135s, get_device_info(battery) → "do not need to charge"
[2026-04-09] [PASS]    R3 clipboard explain — 135s, clipboard(get) → LLM described content (restaurant list)
[2026-04-09] [PASS]    R4 storage analysis — 165s, storage + apps → LLM cross-referenced
[2026-04-09] [PASS]    R5 notification summary — 150s, get_notifications → grouped by app + urgency
[2026-04-09] [PASS]    R6 charge advice — 105s, get_device_info(battery) → "100% charging, no need"
[2026-04-09] [FIXED]   Cloud send accessibility UX — Toast shown first ("Enable Accessibility Service to run tasks"), then navigates to PokeClaw Settings (not Android Settings). User sees all permissions.
[2026-04-09] [PASS]    Chat bubble E2E — Cloud: user "hello" y=357, AI "Hello! How can I help you today?" y=465, model tag "gpt-4.1" y=538
[2026-04-09] [PASS]    P2-3  Task mode: placeholder "Describe a phone task..." after tap 🤖 Task
[2026-04-09] [PASS]    P2-4  Chat mode: placeholder "Chat with local AI..." after tap 💬 Chat
[2026-04-09] [PASS]    P2-7  Mode switch preserves messages: Chat→Task→Chat, "test123" still visible
[2026-04-09] [PASS]    P3-3  Quick Tasks collapse/expand: tap handle → collapsed, tap again → expanded
[2026-04-09] [PASS]    J2    Empty input send: tap send with empty field → nothing sent
[2026-04-09] [PASS]    Q4-2  Cloud Quick Task E2E: 🦞 Reddit → tap → fills input → send → agent navigated Reddit + searched pokeclaw
[2026-04-09] [FIXED]   L1-v9 Session restore — onCreate reads CURRENT_CONVERSATION_ID from KVUtils, reloads saved messages. replaceTypingIndicator now calls saveChat() to persist task results immediately. Verified: "Restored 7 messages from conversation chat_1775787808468"
[2026-04-10] [NOTE]    On this Pixel 8 Pro / Android 16, reinstall cleared Accessibility (`enabled_accessibility_services=null`). Re-enabling via `adb shell settings put secure enabled_accessibility_services io.agents.pokeclaw/io.agents.pokeclaw.service.ClawAccessibilityService` + `accessibility_enabled 1` restored the bound service for QA.
[2026-04-09] [PASS]    Full E2E WhatsApp: UI type "send hi to Girlfriend on WhatsApp" → agent opened WhatsApp → send_message called → finish("Sent 'hi' to Girlfriend on WhatsApp.") → auto-return 15s → result visible in chatroom
[2026-04-09] [PASS]    Auto-return verified: agent navigated to WhatsApp, completed task, returned to PokeClaw, user msg + AI result both visible in same session
[2026-04-09] [PASS]    C1/L3/L4  Monitor start via in-app monitor flow stays in PokeClaw; top bar shows "Monitoring: Rlfriend", no Home press
[2026-04-09] [PASS]    C3    Tap top monitoring bar → expands to show contact + Stop → tap Stop → AutoReplyManager logs "Auto-reply DISABLED for contacts: []"
[2026-04-09] [PASS]    K6-a  App Settings → Accessibility Service row opens Android Accessibility page for PokeClaw
[2026-04-09] [ISSUE]   K2-a  App Settings permission status stale — Accessibility row still shows "Disabled" even when system Accessibility page shows "Use PokeClaw" ON
[2026-04-09] [ISSUE]   K3-b  Accessibility enable auto-return incomplete — app calls START on SettingsActivity after enable, but system Accessibility SubSettings stays foreground; user is not auto-returned
[2026-04-10] [FIXED]   K2-a  Accessibility status row now reads system enabled-services state, so app Settings shows the truthful `Enabled`/`Disabled` value
[2026-04-10] [PASS]    K2-a  App Settings → Accessibility Service row shows `Enabled` immediately after system Accessibility toggle is ON
[2026-04-10] [FIXED]   K3-b  Pending accessibility auto-return is now armed only when the service is disabled, preventing false triggers while Accessibility is already ON
[2026-04-10] [PASS]    K3    Disabled Accessibility → tap app Settings row → Android Accessibility → PokeClaw detail → toggle `Use PokeClaw` ON → app auto-returns to PokeClaw Settings and row shows `Enabled`
[2026-04-10] [FIXED]   Q6-7  Task agent config now syncs on model switch and before startTask, so Cloud tab tasks no longer reuse stale Local agent config
[2026-04-10] [PASS]    Q2-2/Q6-7  Cloud task "how much battery left" → Agent config updated to `gpt-4.1` → `get_device_info(category=battery)` runs → answer returned in chat with model tag `gpt-4.1-2025-04-14`
[2026-04-10] [FIXED]   L1-v9  Cloud send-message auto-return now preserves the existing conversation instead of dropping the user into a fresh session
[2026-04-10] [PASS]    B1/L1/Q7-7  Cloud task "send yo to girlfriend on WhatsApp" → `send_message` opens WhatsApp and succeeds → auto-return keeps user in `ComposeChatActivity` → same conversation still shows prior messages plus new user bubble + result bubble `Sent 'yo' to girlfriend on WhatsApp.`
[2026-04-10] [FIXED]   A11Y-r1  Accessibility-dependent tools no longer fail immediately during transient service rebinds; they now wait for the enabled service to reconnect before hard-failing
[2026-04-10] [PASS]    H2/H2-b/H2-c  Models screen keyboard safety: API key, Custom Base URL, and Custom Model Name all stay fully visible when IME opens; focused field scrolls into view
[2026-04-10] [FIXED]   P1-4/Q1-r1  Chat toolbar tab state now re-syncs to the actual active model after Settings/model changes, preventing Cloud placeholder/quick-tasks from drifting out of sync with a Local model status (and vice versa)
[2026-04-10] [PASS]    P1-4/P2-1/P2-4/Q1-1/Q6-2  Tap `Local` → model status switches to `● Gemma 4 E2B — 2.6GB · CPU`, local reasoning-first quick tasks render, Chat/Task toggle appears, placeholder becomes `Chat with local AI...`
[2026-04-10] [PASS]    P1-4/P2-5/Q1-2/Q6-3  Tap `Cloud` → model status switches back to `● gpt-4.1 · Cloud`, cloud-only quick tasks return, Chat/Task toggle hides, placeholder becomes `Chat or give a task...`
[2026-04-09] [BLOCKED] L5/L5-b  Incoming WhatsApp notification auto-reply while staying in app requires a second sender device / live external message source
[2026-04-09] [FIXED]   F2-v9 Stop button slow — added Future.cancel(true) to interrupt agent thread + abort HTTP call immediately (was: flag-only, waited for LLM round to finish)
[2026-04-09] [ISSUE]   F2-v9 Stop → return to same session — after stopping task, should return to the SAME chat session, not open new one
[2026-04-09] [ISSUE]   L1-v9 Auto-return should preserve session — after task completes in other app and auto-returns to PokeClaw, should show the same conversation with the result, not a fresh session
[2026-04-10] [PASS]    Q7-2/Q7-3/Q7-4/Q7-6  Cloud quick task "Search YouTube for funny cat fails" → YouTube opens → tap left floating bubble → `Stop task requested from floating pill` logged → task cancelled → auto-return restores same `ComposeChatActivity` session → send button resets to arrow
[2026-04-10] [PASS]    Q7-5  After floating-stop, second Cloud task "how much battery left" runs normally → no `already running` error → answer returned in same session
[2026-04-10] [ISSUE]   Q7-local  Local task stop could trigger a native crash / stale-session race: stop during LiteRT `sendMessage()` → chat UI reloads early → `session already exists` and occasional `SIGSEGV`
[2026-04-10] [FIXED]   Q7-local  Local stop now avoids interrupting LiteRT mid-round; terminal cleanup waits for the task-side client to close, and `TaskOrchestrator` only releases the task after the cancel completion callback arrives
[2026-04-10] [PASS]    Q7-1b/Q7-3/Q7-4  Local task "how much battery left" → tap Stop → 1s later UI still shows `Task running...` + `Stop` while safe unwind is in progress → app remains on `ComposeChatActivity` → logs show `Task cancelled` → send button resets to arrow
[2026-04-10] [PASS]    Phase2-r1  TaskSessionStore smoke on Pixel 8 Pro → local quick-task card still fills task input correctly → sending enters `Task running...` + `Stop` with honest `Model busy` chat status → stop request is logged by `TaskOrchestrator` and UI returns to idle placeholder on the same `ComposeChatActivity` shell
[2026-04-10] [PASS]    Q7-5-local  After local stop, a second local task starts and completes normally — no `already running`, no `session already exists`, no crash
[2026-04-10] [FIXED]   Dbg-u1  Debug builds now run the same once-per-day GitHub release check as release builds, so accidental debug installs still see upgrade prompts
[2026-04-10] [BLOCKED] Dbg-u1  Live prompt verification still needs a throwaway device/build that is older than the just-installed `0.5.0`; current handset has already been upgraded, so this turn only covers code inspection + build/install verification, not a fresh old-debug prompt capture
[2026-04-10] [PASS]    Dbg-u2  Public GitHub `v0.4.1` asset (`PokeClaw_v0.4.0_20260408_140502.apk`) on test device → cold launch after `v0.5.0` release published → `Update Available` modal appears with `PokeClaw v0.5.0 is available. You are running an older version.`
[2026-04-10] [ISSUE]   Dbg-u3  Public GitHub `v0.4.1` asset cannot be updated in place to public `v0.5.0` asset: `adb install -r ... PokeClaw_v0.5.0_20260410_161430.apk` returns `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; users on the older public debug signing path need a one-time uninstall + reinstall
[2026-04-10] [FIXED]   Rel-s1  Release signing config now accepts the same `KEYSTORE_*` inputs from environment variables or `local.properties`, so local signed builds and GitHub Actions both follow the same stable-signing path
[2026-04-10] [NOTE]    Rel-s2  `v0.5.1` is the first version prepared for a stable release key path; the old public `0.4.x` → public `0.5.0` signing mismatch is already shipped and cannot be retro-fixed without the lost original key
[2026-04-10] [BLOCKED] Rel-s3  Public GitHub Release publication for `v0.5.1` still depends on installing the stable signing secrets into `agents-io/PokeClaw` Actions settings; code path is ready, repo permission path is not
[2026-04-10] [PASS]    Rel-s4  Local stable-signing verification: generated a dedicated release keystore, `./gradlew :app:validateSigningRelease` passed, and a fresh `./gradlew --no-daemon :app:assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease` produced `app/build/outputs/apk/release/PokeClaw_v0.5.1_20260410_111303.apk`
[2026-04-10] [PASS]    Rel-s5  Local signed release artifact verification: `apksigner verify --print-certs` reports signer `CN=Nicole, OU=PokeClaw, O=agents.io, L=Vancouver, ST=British Columbia, C=CA` with SHA-256 `e000d1d6555b8fab20c03a5d9ddeba83944f26eecf0b978ac7affc2eebd43186`; local `SHA256SUMS.txt` records APK digest `fb7c6a6f4e2536f24bfb8f9ac6e8f7628aec11bf5e1a29b96fc18bb238fcde65`
[2026-04-10] [PASS]    Rel-s6  Stable-signed `0.5.1` release APK fresh-installed successfully onto the Pixel test device after removing the old debug build; launcher resolves and app starts normally
[2026-04-10] [PASS]    Rel-s7  Stable-key in-place upgrade path verified locally: with the same release keystore, a higher-version signed build (`POKECLAW_VERSION_CODE=15`, `POKECLAW_VERSION_NAME=0.5.1-upgrade-test`) installed over the stable-signed `0.5.1` baseline via `adb install -r` and Android accepted the upgrade with no signature mismatch
[2026-04-10] [FIXED]   M1-a  Explicit in-app search tasks now use a generic guard/prompt hint: the agent cannot finish before it really types the query with `input_text`, and blocked finishes feed back a fresh screen-based node hint instead of an app-specific scripted route
[2026-04-10] [PASS]    M8/M1-a  Cloud task `search youtube for lofi beats` → `open_app` → `input_text(node_id=...)` succeeds → `system_key(enter)` → `get_screen_info` → `finish`; completes in 6 rounds / 46.7K tokens, no budget stop, auto-return restores `ComposeChatActivity`
[2026-04-10] [PASS]    M8-alt/M1-a  Alternate phrasing `search for lofi beats on youtube` follows the same generic path (`open_app` → `input_text(node_id=...)` → `system_key` → `get_screen_info` → `finish`) and also completes in 6 rounds / 47.5K tokens
[2026-04-10] [PASS]    M1-control  Non-search control task `how much battery left` remains unaffected by the search guard: `get_device_info(category=battery)` → `finish`; completes in 2 rounds / 10.4K tokens with no `InAppSearchGuard` activity
[2026-04-10] [FIXED]   M8-a  Explicit email-compose tasks now use a generic `EmailComposeGuard`: the agent can no longer satisfy task-mode email requests with text-only draft output before attempting any in-app compose actions
[2026-04-10] [PASS]    M8/S8  Cloud task `Write an email saying I will be late today` → `get_installed_apps(mail)` → `open_app(com.google.android.gm)` → `tap_node` compose → `input_text` subject `Running late today` → `input_text` body → `get_screen_info` → `finish`; completes in 8 rounds / 52.2K tokens, auto-returns to `ComposeChatActivity`, and correctly leaves recipient blank because none was provided
[2026-04-10] [PASS]    M8-control  Control task `how much battery left` remains unaffected by `EmailComposeGuard`: `get_device_info(category=battery)` → `finish`; completes in 2 rounds / 10.4K tokens with no compose-specific interference
[2026-04-10] [PASS]    LQ1-LQ5  Local reasoning quick-task sweep on Pixel 8 Pro: notifications triage, clipboard explain, storage analysis, notification summary, and battery advice all completed on-device via LiteRT CPU fallback with correct tool routing and no crashes/loops
[2026-04-10] [PASS]    LQ7-LQ10/LQ12/LQ13  Local deterministic quick-task sweep: installed apps, phone temperature, bluetooth state, battery, storage, and Android version all returned correct device data through `get_installed_apps` / `get_device_info`, with no stale-session or routing regressions
[2026-04-10] [PASS]    LQ6/LQ11  Contact-specific local quick tasks still route the correct tools (`send_message`, `make_call`) and fail gracefully when `Mom` does not exist on this device; treat literal send/call success as env-blocked coverage, not a product failure
[2026-04-10] [PASS]    P3-7/P3-8/Q4-1  Local UI quick-task E2E: tap visible `Check my battery and tell me if I need to charge` card → input prefilled → Local task send routes through `provider=LOCAL / gemma4-e2b` → `get_device_info(category=battery)` → response bubble `The battery is at 100% and is charging. You do not need to charge.` appears with local model tag `Gemma 4 E2B — 2.6GB`; input resets to task placeholder `Describe a phone task...`
[2026-04-10] [FIXED]   QA-r1  `scripts/e2e-quick-tasks.sh` now classifies `onSystemDialogBlocked`, text-only completions with no tool calls, `Task cancelled`, and `Task stopped: budget limit reached ...` correctly; it no longer misreports the YouTube permission dialog as a generic timeout
[2026-04-10] [FIXED]   Bgt-1  Existing installs could stay pinned to the legacy 100K / $0.50 task budget even after code defaults increased. `TaskBudget` now migrates untouched legacy defaults to 250K / $1.00 once, while preserving user-custom budgets; Settings budget UI now exposes `250K` explicitly and snaps to the nearest current value
[2026-04-10] [PASS]    S2/M32  Cloud task `Install Telegram from Play Store` → Play Store path completed without budget stop; on this device the agent correctly recognized Telegram was already installed and finished in 10s
[2026-04-10] [PASS]    S3/M20  Cloud task `Check whats trending on Twitter and tell me` → `open_app(com.twitter.android)` → inspect current feed/trending content → summarize visible topics; completed in 30s with no task-budget stop
[2026-04-10] [BLOCKED] S1/M1-b  Cloud task `Search YouTube for funny cat fails` is currently blocked by Android's foreground permission controller (`GrantPermissionsActivity`) over YouTube; PokeClaw surfaces this as `system dialog blocked foreground automation` instead of looping or timing out
[2026-04-10] [PASS]    S5/M33  Cloud task `Copy the latest email subject and Google it` → `get_notifications` → `clipboard(set)` → `open_app(com.android.chrome)` → search in Chrome → screenshot/search-results visible → `finish`; after legacy-budget migration this completed in 15 rounds / 110.2K tokens instead of hard-stopping at the old 100K ceiling
[2026-04-10] [PASS]    S7/M51  Committed-state rerun `Open Reddit and search for pokeclaw` → `open_app(com.reddit.frontpage)` → `input_text(pokeclaw)` → results visible → `finish`; completed in 12 rounds / 91.9K tokens on the latest hardening branch
[2026-04-10] [PASS]    Cloud quick-task sweep (effective final) on branch `hardening/behavior-safe-2026-04-09` @ `a0a88ab`: `18 PASS / 0 FAIL / 2 BLOCKED / 0 TIMEOUT / 20 TOTAL`. Blocked items are environment-driven (`S1` YouTube permission dialog, `Call Mom` missing contact). Base sweep log: `/tmp/pokeclaw-cloud-quick-tasks-20260410-full.log`; `S5` was rerun after the budget migration and passed at 110.2K tokens
[2026-04-10] [PASS]    Phase1-r1  Architecture refactor smoke — relaunch via `SplashActivity` with Cloud config active lands on `ComposeChatActivity` showing `● gpt-4.1 · Cloud` and the unified Cloud placeholder, confirming chat runtime rehydrate still works after `ChatSessionController` extraction
[2026-04-10] [PASS]    Phase1-r2  Architecture refactor smoke — copied the existing Edge Gallery Gemma model into PokeClaw's sandbox, switched provider to `LOCAL`, relaunched, and confirmed `ComposeChatActivity` rehydrated into Local mode with `Chat with local AI...` plus top status `● gemma4_2b_v09_obfus_fix_all_modalities_thinking · GPU`
[2026-04-10] [PASS]    Q3-1/Q5-1/Q5-1b/Phase1-r3  Local chat after `ChatSessionController` extraction: UI send produced a real assistant reply (`Hello! How can I help you today?`), GPU inference transparently fell back to CPU, and both the top status pill and assistant model tag updated to `CPU` instead of stale `GPU`
[2026-04-10] [PASS]    Phase3-r1  Fresh reinstall + app Settings smoke: after `adb install -r`, Android cleared `enabled_accessibility_services`; app Settings now truthfully shows `Accessibility Service = Disabled` instead of stale `Enabled`
[2026-04-10] [PASS]    Phase3-r2  Rebinding truth smoke: after restoring `enabled_accessibility_services` / `accessibility_enabled` via `adb shell settings put secure ...`, app Settings showed `Accessibility Service = Connecting` while the service was still rebinding, instead of collapsing enabled+unbound into `Disabled`
[2026-04-10] [PASS]    Phase3-r3  Permission truth smoke: with no PokeClaw listener in `enabled_notification_listeners`, app Settings shows `Notification Access = Disabled`
[2026-04-10] [FIXED]   K4-r1  Notification-listener foreground return is now gated by a pending permission-flow flag, so listener reconnects no longer blindly foreground app Settings unless the user actually came from the in-app permission flow
[2026-04-10] [PASS]    Phase4-r1/H4-b  After local-runtime consolidation, cold launch still lands on `ComposeChatActivity` with truthful local status `● gemma4_2b_v09_obfus_fix_all_modalities_thinking · CPU`
[2026-04-10] [PASS]    Phase4-r2/Q3-1/Q5-1/Q5-1b  Local UI send smoke after runtime consolidation: typed `say pong`, tapped the live send-button bounds, and received assistant reply `Pong! 🏓`; both top status and assistant bubble tag remained `gemma4_2b_v09_obfus_fix_all_modalities_thinking (CPU)`
[2026-04-10] [PASS]    P7-1/P7-2  Chat bubble metadata smoke: after relaunching `ComposeChatActivity`, user bubbles render a subtle time footer (`5:57 p.m.`) and assistant bubbles render `gemma4_2b_v09_obfus_fix_all_modalities_thinking (CPU) · 5:57 p.m.` under the reply bubble
[2026-04-10] [PASS]    P7-3/Q7-7  Saved chat history now persists per-message timestamps in markdown via hidden `<!-- pokeclaw:timestamp=... -->` comments, so reloaded conversations keep stable bubble times instead of resetting to the current clock
[2026-04-10] [PASS]    Phase1b-r1/Q7-7  After `ConversationStore` extraction, cold relaunch still restored `chat_1775851530681` with 9 saved messages; logcat showed `Restored 9 messages from conversation chat_1775851530681`, and the foreground UI still showed the existing `ay pong` / `Hello! How can I help you today?` conversation instead of a blank new chat
[2026-04-10] [PASS]    Phase2b-r1  After `TaskFlowController` extraction, debug task broadcasts still reached the chat shell (`TaskTriggerReceiver: Received task via broadcast: battery`, `ComposeChatActivity: Auto-task from intent: battery`) and preserved in-app permission guidance by pushing `SettingsActivity` when Accessibility was unavailable
[2026-04-10] [FIXED]   Android15-coldstart  Cold launch no longer crashes if app-start `ForegroundService` is disallowed; `ForegroundService.start()` now returns `false` and logs a warning instead of throwing `ForegroundServiceStartNotAllowedException` from `ClawApplication.onCreate()`
[2026-04-10] [PASS]    Phase2c-r1  After `ActiveTaskShellController` extraction, the Compose top bar still rendered `Monitoring: Mom`; expanded state showed `Mom` + `Stop`, and tapping `Stop` disabled auto-reply and removed the monitor
[2026-04-10] [PASS]    Phase2c-r2  Debug `autoreply on mom` no longer bypasses app behavior: `TaskTriggerReceiver` rewrites it to `monitor mom on WhatsApp`, and on this device the flow foregrounded in-app `SettingsActivity` with no direct `Added contact` log and no ghost `Monitoring:` bar in the dumped UI
[2026-04-10] [NOTE]    TgMon-r1  Telegram monitor QA now requires an external sender path (second account or bot token + existing bot chat); without that sender, Telegram incoming-message monitor cases must be marked `BLOCKED`
[2026-04-10] [PASS]    Phase4-r3  Monitor target parser unit bundle passed: `monitor Mom on Telegram`, default WhatsApp when app is omitted, `watch Alex on sms` -> `Messages`, and `monitor Caroline` does not get misparsed as `LINE`
[2026-04-10] [PASS]    Phase4-r4  Live device dialog smoke: Monitor dialog now shows the supported app list (`WhatsApp`, `Telegram`, `Messages`, `LINE`, `WeChat`) and retained `Telegram` as the selected app in the live screenshot instead of collapsing back to WhatsApp
[2026-04-10] [PASS]    Phase5-r1  Local runtime consolidation compile gate: `LocalModelRuntime` now owns shared `openConversation(...)` and `runSingleShot(...)`, and `ChatSessionController`, `LocalLlmClient`, `LlmSessionManager.singleShotLocal()`, and `AutoReplyManager.generateReplyLocal()` all compile against the same runtime boundary (`compileDebugKotlin`, `compileDebugJavaWithJavac`, `assembleDebug`)
[2026-04-10] [BLOCKED] Phase5-r2  Targeted device smoke for the new shared local runtime boundary is blocked by ADB attach state (`adb devices -l` returned no attached devices after the Phase 5 landing). Re-run `H4/H4-b`, `Q3-1`, `Q5-1`, `Q5-1b`, and the local quick-task bundle as soon as the Pixel is visible again instead of treating the missing device as an app regression
[2026-04-10] [PASS]    Phase5-r3  Local model state consolidation compile gate: `LocalModelManager` now exposes shared device-support, catalog, and active-model state so `LlmConfigActivity` and `ChatSessionController` stop maintaining separate RAM/support/downloaded calculations (`compileDebugKotlin`, `compileDebugJavaWithJavac`)
[2026-04-10] [PASS]    Phase5-r4  Local model ownership cleanup compile gate: `LocalModelManager.downloadModel()` no longer mutates MMKV selection state directly; chat/settings callers now decide whether a finished download should update the default or active local model (`compileDebugKotlin`, `compileDebugJavaWithJavac`)
[2026-04-10] [NOTE]    QA-wf-r2  Device-state guard for Compose UI smoke: if notification shade or another app steals foreground, collapse/foreground PokeClaw again before judging the refactor; if IME moves the input bar, re-dump live bounds instead of reusing stale tap coordinates
[2026-04-10] [PASS]    H2-d  Chat keyboard dismiss smoke passed on Pixel 8 Pro: after focusing the input, tapping the blank header area cleared focus (`focused=true` -> `focused=false`) and hid the IME instead of trapping the keyboard on screen
[2026-04-10] [PASS]    B4-c  Accessibility text-match hardening compile/unit bundle passed: low-level lookup now keeps Android's fast text path but falls back to a Unicode-normalized tree walk, and standard launch dialogs try stable positive-button ids before language-specific keywords
[2026-04-10] [PASS]    Phase5-r5  Cloud send smoke passed after send-affordance hardening: `send yo to girlfriend on WhatsApp` ran on `gpt-4.1`, called `send_message(contact=\"girlfriend\", message=\"yo\", app=\"WhatsApp\")`, finished in 2 rounds, and auto-returned with `Task completed: Sent 'yo' to your girlfriend on WhatsApp.`
[2026-04-10] [PASS]    Phase5-r6  Chat-noise filtering is no longer English-string-bound: conversation-reading heuristics now treat timestamps and centered system labels as layout noise using shared tested rules (`ChatNoiseFilterUtilsTest`, `UiTextMatchUtilsTest`, `ContactMatchUtilsTest`)
[2026-04-11] [FIXED]   DD-guard-1  Cloud no longer falls back to generic "I cannot access your device" denials for direct phone-data requests when a matching tool exists; the direct-device-data guard now forces a real tool attempt first and blocks text-only completion / premature `finish`
[2026-04-11] [PASS]    DD1  Cloud task `read my clipboard and explain what it says` → `clipboard(action=get)` → real clipboard content returned and explained; no generic privacy/device-access refusal
[2026-04-11] [PASS]    DD2  Cloud task `read my notifications and summarize` → `get_notifications()` → summarized live notifications; no false claim that notifications are inaccessible
[2026-04-11] [PASS]    DD3  Cloud task `how much battery left` → `get_device_info(category=battery)` → answered with real battery/charging/temperature state; no generic limitation disclaimer
[2026-04-11] [PASS]    DD5  Cloud task `what apps do i have` → `get_installed_apps()` → returned the real installed-app list; no generic chatbot fallback
[2026-04-11] [PASS]    DD7-unit  Conceptual control `what is an Android clipboard` remains a normal chat-style case in unit coverage; the guard no longer falsely forces a clipboard tool just because the word `clipboard` appears
[2026-04-11] [FIXED]   Q2-r1  Cloud unified-input send no longer reuses task-running chrome for ordinary chat turns; chat waiting state and true task execution state are tracked separately
[2026-04-11] [PASS]    Q2-1b/Q6-3/T10  Pixel 8 Pro smoke after switching to `gpt-4.1-mini`: top pill shows `● gpt-4.1-mini · Cloud`, Cloud tab remains selected, placeholder stays `Chat or give a task...`, and no orange `Task running...` bar appears for the chat shell
[2026-04-11] [PASS]    Q2-1c  Fresh Cloud chat smoke on Pixel 8 Pro: typing `say hi` stayed in ordinary chat, produced a normal `Hello! How can I assist you today?` assistant bubble tagged `gpt-4o-2024-08-06`, and did not launch `Send Message` or reuse any old contact state
[2026-04-11] [PASS]    Q1-6/Q6-3/T10  Settings round-trip truth smoke: switch to Cloud, open Settings, press Back, and return to the same conversation → logcat reports `Cloud chat ready: gpt-4.1-mini`, top pill still shows `● gpt-4.1-mini · Cloud`, and the chat shell stays on the Cloud placeholder instead of drifting back to Local
[2026-04-11] [PASS]    H4-d/H4-e/T2/T10  Models page truth smoke on Pixel 8 Pro: page now shows `Active model`, `Default local model`, and `Default cloud model` separately; the linked default Gemma built-in row no longer claims `Not downloaded`
[2026-04-11] [PASS]    H4-c  Cloud dropdown switch smoke after install: switching to `GPT-4o` leaves a single `Switched to GPT-4o` system line instead of a lower-case + display-name duplicate pair
[2026-04-11] [PASS]    Q2-4/Q2-4b  Cloud clipboard bridge on Pixel 8 Pro: `read my clipboard and explain what it says` called `clipboard(action=get)` and produced the visible assistant bubble `Your clipboard is currently empty.` in the same chatroom; no generic device-access refusal and no misleading `Clipboard failed` status line remained in the UI
[2026-04-11] [PASS]    Q8-smoke-cloud  Same Cloud chatroom memory smoke on Pixel 8 Pro: `Remember token plum8492 and reply with only OK.` → `OK` → `What token did I ask you to remember?` → `The token you asked me to remember is "plum8492".` Visible UI and logcat matched
[2026-04-11] [PASS]    Q8-smoke-local  Same Local chatroom memory smoke on Pixel 8 Pro after local session-restore fix: `Remember token guava9184 and reply with only OK.` → `OK.` → `What token did I ask you to remember?` → `You asked me to remember the token **guava9184**.` Visible UI matched the expected same-chatroom continuity
[2026-04-11] [PASS]    Q9-1-smoke-cloud  Cloud chat -> task handoff proved on Pixel 8 Pro: in one Cloud chatroom, `Remember token mango4421 and reply with only OK` → `OK`, then `Copy that token to the clipboard` triggered `clipboard(action=set,text=mango4421)`, returned `The token "mango4421" has been successfully copied to your clipboard.`, and a follow-up `Read my clipboard and reply with only the clipboard contents` visibly returned `mango4421`
[2026-04-11] [FAIL]    Q9-2-smoke-local  Local task still does NOT inherit prior chat context, which is expected, but the vague task UX is not graceful yet: after a Local chat remembered `papaya6614`, switching to Local Task mode and sending `Copy that token to the clipboard` did not overwrite the clipboard (Cloud readback still returned the earlier `mango4421`) but the Local task also failed to produce a clear user-facing "I need the exact content in this task message" result
```

### Bugs Found During v9 QA

| ID | Issue | Root Cause | Priority |
|----|-------|-----------|----------|
| Q5-1 | ~~LiteRT "Can not find OpenCL" crash in sendChat()~~ | Fixed 2026-04-09: `sendChat()` now mirrors the Local client fallback path, resets the engine after OpenCL/native errors, and retries on CPU instead of failing the chat send | Fixed |
| Q5-2 | ~~API key was "test"~~ | ~~Device had dummy key, reconfigured~~ | ~~Config~~ |
| K2-a | ~~Accessibility status row shows `Disabled` while Android Accessibility page has `Use PokeClaw` ON~~ | Fixed 2026-04-10: app Settings now reads `enabled_accessibility_services` via `isEnabledInSettings()` | Fixed |
| K3-b | ~~Accessibility enable flow does not foreground PokeClaw after system toggle ON~~ | Fixed 2026-04-10: pending return only arms for a real disabled→enabled flow, then unwinds Settings and foregrounds app | Fixed |
| Q6-7 | ~~Cloud tab tasks can reuse stale Local agent config after a model switch~~ | Fixed 2026-04-10: task agent config now syncs on model switch and immediately before `startTask()` | Fixed |
| Q1-r1 | ~~Toolbar tab UI can drift out of sync with the actual active model after Settings/model changes~~ | Fixed 2026-04-10: `ChatScreen` now re-syncs `selectedTab` from `isLocalModel`, so placeholder/quick-tasks/toggle follow the true active model again | Fixed |
| L1-v9 | ~~Auto-return after task completion can reopen a fresh chat state instead of preserving the active conversation~~ | Fixed 2026-04-10: same conversation remained visible after Cloud `send_message` auto-return, with result appended in place | Fixed |
| A11Y-r1 | Accessibility-dependent tools can false-fail during transient service rebinds | Fixed 2026-04-10: tools now wait for an enabled service to reconnect before returning `Accessibility service is not running` | Fixed |
| Q7-local | ~~Stopping a Local task could crash with native `SIGSEGV` / `session already exists` race~~ | Fixed 2026-04-10: local cancel no longer interrupts LiteRT mid-send, and UI cleanup waits until the task-side client has closed cleanly | Fixed |
| Bgt-1 | Existing installs could stay pinned to the legacy task budget even after code defaults increased | Fixed 2026-04-10: `TaskBudget` now one-time migrates untouched 100K / $0.50 legacy defaults to 250K / $1.00, while preserving explicit user overrides and exposing `250K` in Settings | Fixed |
