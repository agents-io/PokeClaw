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
