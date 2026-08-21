# PokeClaw — Project Rules

## Read This First

Before changing prompts, skills, task routing, local runtime, QA, or release behavior, read `README.md` sections `Product Direction`, `Roadmap`, and `Known platform constraints`.

The short version: PokeClaw is a generic Android mobile-agent harness, not a collection of hardcoded demo tasks. Prioritize runtime, hardware, storage, accessibility, foreground-service, install/signing, and QA-harness correctness before narrow prompt or workflow tuning.

## Project Files

| File | What | When to update |
|------|------|----------------|
| `README.md` | Public product direction, roadmap, changelog | When product direction, roadmap, release notes, or public claims change |
| `AI_INDEX.md` | Repo map for coding agents | When files or directories move |
| `CLAUDE.md` | Project rules | When workflow/rules change |
| `QA_CHECKLIST.md` | E2E test cases + debug changelog | Every code change |
| `RELEASING.md` | Stable signing and release workflow | When the release process changes |
| `BACKLOG.md` | Features, bugs, ideas with priority | When new items come in or items get done |
| `ARCHITECTURE_RECONSTRUCTION.md` | Historical architecture/refactor guardrails | When reconstruction phases change |
| `CLAUDE.local.md` | Current session state | Every session |

When Nicole mentions a feature idea, bug, or "之後要做" item → write it into `BACKLOG.md` immediately. Don't rely on session memory.

## QA-First Development (MANDATORY)

Every code change MUST include E2E QA. No exceptions. This is the highest-priority practice in this project.

QA must reflect the product direction in `README.md`: do not turn stochastic model performance into one-off prompt drilling. Fix and gate deterministic harness failures first, then measure exploratory model tasks by repeated-trial success rate.

### Per-Change QA (every commit)

1. **Design QA tests FIRST** — before writing code, define what the E2E test looks like
2. **Add tests to `QA_CHECKLIST.md`** — permanent, under the relevant section (A-K or new section)
3. **Tests must be E2E via ADB** — simulate real user behavior: `adb shell input tap/text`, `adb shell am broadcast`, uiautomator dump, logcat verification. No unit tests, no mocks. Control the phone like a user would.
4. **Cover edge cases** — happy path + error path + boundary conditions. For every feature, ask: "what if permission is missing?", "what if network drops?", "what if user taps twice?", "what if another task is running?"
5. **Run the new tests** — execute them yourself, verify PASS, record results in the QA Debug Changelog
6. **Run affected existing tests** — any section that could be impacted by the change, re-run those tests
7. **After a big feature** — run the FULL checklist top to bottom (all sections A-K+)

### QA Test Design

**Think like a human user, not an engineer.** The user doesn't know about TaskOrchestrator, AgentService, or LiteRT-LM. They tap buttons, type messages, and expect things to work. Design tests from their perspective:

- "I open the app for the first time" — not "Activity.onCreate fires"
- "I type hello and tap send" — not "sendChat() is called with text='hello'"
- "I switch to a different app and come back" — not "onPause/onResume lifecycle"
- "The app asks me to enable something, I do it, and come back" — not "startActivity(ACTION_ACCESSIBILITY_SETTINGS)"

Cover what real users actually do:
- Tap the wrong thing, tap twice, tap while something is loading
- Leave the app mid-task, get a phone call, rotate the screen
- Have bad internet, no permissions, wrong settings
- Use the app for the first time with zero setup vs returning user with everything configured

Each test has a unique ID (e.g., K7, B3, J4)
- Format: `- [ ] **ID. Short name**: step1 → expected1 → step2 → expected2`
- Tests that need a second device or manual interaction: mark clearly so QA tester knows
- Tests that can be automated via ADB: write the full adb command sequence
- Record results in changelog: `[date] [PASS/FAIL/ISSUE/SKIP] ID description`

### What triggers full QA

- Architecture refactor
- New LLM provider or model integration
- Changes to task lifecycle (TaskOrchestrator, AgentService, TaskEvent)
- Changes to accessibility service or notification listener
- UI layout changes that affect multiple screens
- Before any release/version bump

### What triggers partial QA

- Bug fix → run the specific test + related section
- New feature → run new tests + the section it belongs to
- UI tweak → run H section (General UI) + affected section

## Architecture Before Features (MANDATORY)

If you spot an architecture problem while working on a feature — **stop the feature and flag it**. Do not build on top of a broken foundation.

Examples of architecture problems:
- God class doing too many things (e.g., ComposeChatActivity handling chat + task + model loading + permissions)
- Duplicate code paths that should be unified (e.g., two ways to start a monitor)
- Missing abstraction layer (e.g., task agent and chat UI both directly managing LiteRT-LM sessions)
- Tight coupling that makes changes ripple everywhere
- State managed in multiple places with no single source of truth

When you see this:
1. Stop the current feature work
2. Tell Nicole: "I found an architecture issue — [description]. I want to refactor [X] before continuing. OK?"
3. Wait for approval
4. Refactor first, QA the refactor, then resume the feature

Never paper over architecture debt with workarounds. Today's shortcut is next week's 3-hour debug session.

## Android Patterns

- All errors must be user-visible (Toast, system message, or dialog) — never silent failures
- Permission checks before features that need them — guide user to the correct settings page

## Debug Logging (MANDATORY)

Every code path must be traceable through logcat alone. If a bug happens and there's no log, that's a code defect — not a mystery.

### What to log

- **Every entry point**: method called, with key parameters. `XLog.i(TAG, "sendTask: text='$text', isLocal=$isLocal")`
- **Every decision branch**: which path was taken and why. `XLog.d(TAG, "route: monitor keyword detected, skipping agent loop")`
- **Every state change**: before and after. `XLog.i(TAG, "setState: $oldState → $newState")`
- **Every external call**: API request, tool execution, accessibility action. `XLog.d(TAG, "onToolCall: $toolName($parameters)")`
- **Every error with context**: not just the exception, but what was happening. `XLog.e(TAG, "Failed to send message to $contact via $app", e)`
- **Every permission/service status check**: `XLog.d(TAG, "isConnected=$connected, isRunning=$running")`

### Log levels

- `XLog.e` — errors that affect user experience (task failed, model load failed)
- `XLog.w` — recoverable issues (GPU fallback to CPU, retry succeeded)
- `XLog.i` — key lifecycle events (task started/completed, service connected, model loaded)
- `XLog.d` — detailed flow tracing (tool calls, state transitions, routing decisions)

### The rule

When reading logcat for any user flow, you should be able to reconstruct exactly what happened, what decisions were made, and where it went wrong — without reading the source code. One session of `adb logcat --pid=$(pidof io.agents.pokeclaw)` should tell the full story.

---8<--- (由 ~/.claude/rules 搬入 2026-08-21) ---8<---

# Android Dev Workflow — Token-Efficient Mobile Development

## The Problem

Building, installing, and screenshotting Android apps is expensive in tokens. The biggest waste comes from:

1. **One-change-per-cycle**: editing one thing → build → install → screenshot → read image → repeat. Each cycle costs ~2000 tokens minimum (build output + screenshot read). Batching 5 changes saves ~8000 tokens.
2. **Re-reading files already in context**: Reading SettingsActivity.kt when the summary already has its full content.
3. **SDK/build errors from not checking setup first**: Missing local.properties, wrong SDK path — wasted build cycles.
4. **Trial-and-error tap coordinates**: Screenshots + uiautomator dumps to find buttons.
5. **Running background tasks then polling**: Using `run_in_background` then checking output wastes a turn.

## The Workflow

### Phase 1: Collect ALL changes first (NO builds)

- Read all files you need to edit (if not already in context)
- Make ALL code edits in sequence — no builds between edits
- If user sends multiple feedback items, batch them ALL before building

### Phase 2: Pre-flight check (1 command)

Before first build in a session:
```bash
# Verify SDK, device, and app package in one command
ls local.properties 2>/dev/null || echo "NEED_SDK"; adb devices -l 2>/dev/null | grep device; adb shell pm list packages | grep pokeclaw
```

### Phase 3: Build once

```bash
./gradlew assembleDebug 2>&1 | tail -5
```
- Only read last 5 lines — SUCCESS or the error
- If error, fix and rebuild (still only tail -5)

### Phase 4: Install + multi-screenshot in ONE command chain

```bash
APK=$(find app/build/outputs/apk/debug/ -name "*.apk" | head -1) && \
adb install -r "$APK" && sleep 1 && \
adb shell am start -n io.agents.pokeclaw/com.apk.claw.android.ui.splash.SplashActivity && sleep 3 && \
adb shell screencap -p /sdcard/s1.png && \
adb shell input tap <SETTINGS_X> <SETTINGS_Y> && sleep 2 && \
adb shell screencap -p /sdcard/s2.png && \
adb pull /sdcard/s1.png /tmp/s1.png && \
adb pull /sdcard/s2.png /tmp/s2.png
```

- Chain everything. Don't split into 5 separate Bash calls.
- Known coordinates: Settings gear = (1003, 191), Menu hamburger = (77, 191)

### Phase 5: Read screenshots in parallel

Read both /tmp/s1.png and /tmp/s2.png in one message with parallel Read calls.

## Rules

- **NEVER build after a single edit**. Collect all edits first.
- **NEVER re-read a file that's already in context** from the current session or summary.
- **NEVER use `run_in_background` for adb/gradle** — just run it foreground and read the tail.
- **NEVER use `adb shell screencap` or read screenshot PNGs for QA**. Screenshots cost ~10K tokens each and bloat the session. Use uiautomator2 `d(text=...).exists()`, `d(resourceId=...).info`, `dump_hierarchy()`, or `adb logcat` to verify UI state instead. The ONLY exception is if the user explicitly asks to see a screenshot.
- **Cache tap coordinates** in this rule after discovering them via uiautomator.
- **Max 2 build cycles per user request**. If it takes more, something is wrong — stop and think.

## Known App Coordinates (1080px width device)

- Settings gear icon: (1003, 191)
- Menu hamburger: (77, 191) 
- New chat pencil: (871, 191)
- Message input field: (480, 2130)
- Send button: (1003, 2136)
- Rocket button: (882, 2136)

# Android Error Handling — Mandatory Best Practices

Every piece of Android code MUST follow these rules. No exceptions. No laziness.

## Rules

1. **Every network call** — try-catch with timeout, retry logic, user-visible error message
2. **Every file operation** — check exists, check permissions, catch IOException
3. **Every model load** — catch all exceptions, show status to user, graceful fallback
4. **Every intent/activity launch** — catch ActivityNotFoundException
5. **Every JSON parse** — catch JSONException, log the raw input for debugging
6. **Every SharedPreferences/MMKV write** — verify write succeeded
7. **Every Accessibility Service call** — null check on rootInActiveWindow, catch DeadObjectException

## Pattern

```kotlin
try {
    // happy path
} catch (e: SpecificException) {
    XLog.e(TAG, "What failed and why", e)
    runOnUiThread { showUserError("Human readable message") }
    // graceful fallback
}
```

## Never Do

- `catch (_: Exception) {}` with empty body — ALWAYS log
- Silent failures — user MUST know something went wrong
- `!!` on nullable values — use `?.let {}` or explicit null check with error message
- Assume network is available — always handle offline
- Assume model file exists — always check before loading


---

## 由 Claude memory 搬入（2026-08-21）— `rule_android_qa_uiautomator2.md`

原本每個 session 都載入。呢啲係 project-specific，cd 入呢個 repo 先 load。

---
name: Android QA must use uiautomator2 not raw adb input
description: For any Android UI QA work via ADB, default to uiautomator2 Python library. Raw `adb shell input tap` and `adb shell input text` fail in predictable ways (IME-occluded taps, long-string truncation, special-char mangling)
type: feedback
originSessionId: 36074db4-c565-46db-84ff-1912e5b94138
---
For any Android UI QA work via ADB, default to **`uiautomator2`** (Python library) — not raw `adb shell input tap` / `adb shell input text`.

**Why:** Raw `adb shell input` fails in three predictable ways that block real-world QA:

1. **Long-string truncation** — `adb shell input text "$LONG_API_KEY"` drops bytes for anything over ~100 chars. 164-char `sk-proj-...` OpenAI keys arrive corrupted. uiautomator2 `d(...).set_text()` uses `AccessibilityNodeInfo.ACTION_SET_TEXT` and writes the full string directly to the node.
2. **IME-occluded taps** — once the keyboard is shown (auto-shown when EditText is focused), tapping coordinates in the bottom half of the screen hits keyboard keys instead of the underlying UI. uiautomator2 selector clicks resolve by node bounds at current layout state.
3. **Special-char mangling** — `&`, `'`, spaces, quotes need escape gymnastics with `adb input text`; uiautomator2 `set_text` passes the string cleanly.

**How to apply:**
- Setup: `pip install uiautomator2` + `python -m uiautomator2 init` (installs AdbKeyboard companion on device, one-time)
- Connect: `d = u2.connect()` (auto-picks ADB device)
- Text input: `d(className='android.widget.EditText').set_text(s)` — never use `adb shell input text` for EditText
- Click: `d(description='Send').click()` — better than coordinate tap (still tap-based under the hood but at current node bounds)
- Dump: `d.dump_hierarchy()` — replace `adb shell uiautomator dump`

**Caveats:**
- `d.click(x, y)` is still a coordinate tap with the same IME-occlusion problem
- `set_text` may still trigger IME visibility on focus — after typing, layout reflows and cached coords go stale; always re-dump before next tap
- Release builds disable `run-as` and `XLog.i/d` to logcat — uiautomator2 doesn't help with those; use AppLogStore + debug-report.zip via UI share intent, or switch to debug build for log-trace QA

**Full playbook with traps + patterns:** `~/MyGithub/agentic-journal/projects/3-ship/playbooks/android-qa-uiautomator2-playbook.md`

**Project-specific playbook for PokeClaw:** `~/MyGithub/PokeClaw/docs/AI_AGENT_PLAYBOOK.md`

**Verified usage:** 2026-05-26 PokeClaw QA — configured Groq Cloud LLM end-to-end via Custom-provider tab (3 EditText fields + Save & Activate button) on signed-release APK. Same flow had failed repeatedly with raw `adb input text` (164-char key truncated, Send FAB tap missed because keyboard occluded the y-coord).
