# PokeClaw — Project Rules

## QA-First Development (MANDATORY)

Every code change MUST include E2E QA. No exceptions. This is the highest-priority practice in this project.

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

## Android Patterns

- All errors must be user-visible (Toast, system message, or dialog) — never silent failures
- Permission checks before features that need them — guide user to the correct settings page
- Use XLog for all key operations — if you can't see it in logcat, it doesn't exist for debugging
