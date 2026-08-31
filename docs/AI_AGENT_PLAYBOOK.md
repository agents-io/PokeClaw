# PokeClaw — Playbook for the Next AI Agent

> Things the previous agent (me, 2026-05-25 / 2026-05-26) wishes someone had told them.
> Read this BEFORE doing any QA or release work on PokeClaw. Saves ~3 hours per landmine.

---

## 🚨 Top 10 — read before touching anything

### 1. `adb shell input tap` and `adb input text` are BROKEN for chat composer work

The default Android touch-injection toolchain fails in two specific ways on this project:

| Failure | Why |
|---|---|
| `adb shell input tap` on the Send FAB → Settings opens instead of sending | When IME is shown (and it always is after EditText is focused), Send FAB position moves from `y=2111` to `y=1336`. The y-coord overlaps with a Compose hit-test region that triggers an unrelated handler. Tapping at the new coords still misroutes. |
| `adb shell input text "$LONG_API_KEY"` truncates / drops bytes | Anything over ~100 chars unreliable. The 164-char `sk-proj-...` OpenAI key gets corrupted on the wire. |

**Fix:** install `uiautomator2` and use it for ALL UI interaction.

```bash
pip install uiautomator2
python -m uiautomator2 init   # one-time installs the AdbKeyboard companion app
```

```python
import uiautomator2 as u2
d = u2.connect()

# Long text? set_text uses AccessibilityNodeInfo.ACTION_SET_TEXT — bypasses IME
d(className='android.widget.EditText').set_text(some_164_char_key)

# Click? d(selector).click() dispatches AccessibilityAction.CLICK
# (caveat: under the hood it still does an `input tap` on the node's center,
#  so if the IME is over that center, you still miss. Dismiss IME or scroll first.)
d(description='Send').click()
```

**The full Custom-provider Cloud LLM config flow works end-to-end via uiautomator2** — proven 2026-05-26 (Groq `llama-3.3-70b-versatile · Cloud` set via API key + Base URL + model name + Save & Activate).

### 2. Release build restrictions — your debug tricks DO NOT WORK on signed APKs

`BuildConfig.DEBUG=false` on signed release. Specifically:

- `adb shell run-as io.agents.pokeclaw ...` returns `package not debuggable`. **MMKV file inspection only works on debug builds.** For signed release, use force-stop + relaunch + Settings UI to verify persistence.
- `XLog.i` and `XLog.d` are suppressed from `adb logcat` because `ClawApplication.kt` calls `XLog.setDEBUG(BuildConfig.DEBUG)`. But `AppLogStore.log()` still captures `XLog.i` to the in-app log files — so the data lives in `debug-report.zip` even when not in logcat.
- `DebugTaskReceiver` at `io.agents.pokeclaw.debug.DebugTaskReceiver:39` has `if (!BuildConfig.DEBUG) return` as the FIRST line of `onReceive`. **Every debug broadcast is silently dropped on release.** This means: `am broadcast --es task config:` for cloud LLM config, `--es support_action build_debug_report` for debug report generation, all NOT available on signed release. You must use the actual UI flow.

### 3. Send FAB in Cloud+Send mode opens Settings — this is BY DESIGN, not a bug

```kotlin
// ChatScreen.kt input bar onClick
if (!isLocalModel || isTaskMode) {
    onSendTask(text.trim())
} else {
    onSendChat(text.trim())
}
```

`isLocalModel=false` (Cloud) → routes to `onSendTask`. The task path requires Accessibility / Notification / Overlay / Battery / File-Access permissions. On a fresh device with none granted, the task path opens `SettingsActivity` to prompt the user to fix permissions. **This is intentional**, but it looks identical to "the Send button is wired to the wrong handler" so you may waste an hour like I did. Three workarounds:

1. Grant the 6 permissions before sending. Some are `adb`-settable (`enabled_accessibility_services`), some are NOT (`SYSTEM_ALERT_WINDOW` needs user tap).
2. Use **Local LLM in Chat mode** — `onSendChat`, no permission gate. Local needs a downloaded `.litertlm` though (~2.6 GB).
3. Use **debug-build APK** + `DebugTaskReceiver` broadcast.

### 4. Pre-Tag QA is MANDATORY — see `QA_CHECKLIST.md` "Pre-Tag Release Smoke"

The v0.7.0 release went tag-first-QA-second and violated `CLAUDE.md`'s "full QA before any release/version bump" rule. The Release Gate Record for v0.7.0 explicitly notes this PROCESS violation. The `QA_CHECKLIST.md` now has an 8-step Pre-Tag Smoke section that MUST be followed. Do not push a `vX.Y.Z` tag before steps 1-6 pass.

### 5. APK upgrade story depends on signing keystore

- **Same keystore** (v0.6.12 signed → v0.7.0 signed): in-place upgrade works, no uninstall. Verified 2026-05-26.
- **Different keystore** (debug → signed, or signed-v1 → signed-v2): `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`. Must uninstall first, which clears user data.

If you're testing on a Pixel that already has a signed v0.6.x and you build debug locally, you'll need to uninstall before installing the debug. Conversely, if a release ever changes signing keys, every user must uninstall+reinstall and lose state.

### 6. Side-project repos use `github.com-personal` SSH host

Nicole's dev machine has dual GitHub identity:

```
~/.ssh/config:
  Host github.com          → ~/.ssh/id_ed25519           (company)
  Host github.com-personal → ~/.ssh/id_ed25519_personal  (personal — for agents-io/*)
```

If a side-project repo's `origin` is `git@github.com:agents-io/...`, push fails with `Permission denied (to nicole-alltrue)`. Fix:

```bash
git remote set-url origin git@github.com-personal:agents-io/REPO.git
```

PokeClaw is already configured correctly; other side-projects may not be. See `~/MyGithub/agentic-journal/projects/3-ship/playbooks/side-project-repo-setup-checklist-2026-05-25.md`.

### 7. CI signing secrets ARE installed — don't trust stale `CLAUDE.local.md`

`CLAUDE.local.md` until 2026-05-26 said "GitHub Actions 仲未有 stable signing secrets". This was OUTDATED. The secrets (`ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) were installed 2026-04-28 and the v0.6.12 release used them. Always verify with `gh secret list -R agents-io/PokeClaw` before assuming.

### 8. Emulator Matrix CI workflow has two known gotchas

`.github/workflows/emulator-matrix.yml` for Android emulator smoke tests:

- **Component name**: launch via `io.agents.pokeclaw/.ui.splash.SplashActivity` (not `com.apk.claw.android...` which is a pre-rename stale name).
- **APK artifact path**: `actions/upload-artifact@v4` with a path-glob preserves directory structure. The `.apk` may be at `apk/app/build/outputs/apk/debug/PokeClaw_*.apk` not `apk/*.apk`. Always use `find apk -type f -name '*.apk'` not assume a flat layout.

### 9. Component names changed during repo rename

The project was renamed from a `com.apk.claw.android.*` namespace to `io.agents.pokeclaw.*`. Old references may still appear in:
- Stale doc snippets / older session notes
- Earlier workflow files
- Some hardcoded strings

When in doubt, grep the source — `grep -rn "com.apk.claw"` should return zero hits in active code. If it returns hits, those are stale and likely broken.

### 9.5. Grant all 6 system permissions via ADB (no manual phone taps needed)

Android 13+ has "restricted settings" that block ADB from enabling Accessibility / Notification-listener style services. The escape hatch is the `ACCESS_RESTRICTED_SETTINGS` appop:

```bash
PKG=io.agents.pokeclaw

# 1. POST_NOTIFICATIONS (Task Notifications row)
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS

# 2. MANAGE_EXTERNAL_STORAGE (File Access row)
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow

# 3. SYSTEM_ALERT_WINDOW (System Window row)
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow

# 4. Battery Whitelist row
adb shell dumpsys deviceidle whitelist +$PKG

# 5. Notification Access row (NotificationListenerService)
adb shell cmd notification allow_listener $PKG/io.agents.pokeclaw.service.ClawNotificationListener

# 6. Accessibility Service — REQUIRES the appop bypass FIRST
adb shell appops set $PKG ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services $PKG/io.agents.pokeclaw.service.ClawAccessibilityService
adb shell settings put secure accessibility_enabled 1

# Verify
adb shell dumpsys accessibility | grep -E "Bound services|Enabled services"
# Want to see: Bound services:{Service[label=PokeClaw, eventTypes=TYPES_ALL_MASK, ...]}
```

**Trap:** if you `force-stop` PokeClaw after granting, the Accessibility binding may be lost. Re-run the bypass + `enabled_accessibility_services` write before the next chat-send test.

**Reverse:** clear all granted perms before user testing if you want a fresh-install QA.

### 9.7. Don't trust the "Builder pattern" to be the runtime path

v0.7.0 wired `PromptUtils.applyGlobalPrompt` into `AgentConfig.Builder.build()`. That looked like THE construction site if you read AgentConfig.kt. But the actual runtime constructor for AgentConfig is `ResolvedModelConfig.toAgentConfig()` in `ModelConfigRepository.kt`, which uses the data class constructor directly. Result: the global prompt was saved to MMKV but never injected into any LLM call. Verified via app_logs/pokeclaw-app.log — zero `PromptUtils:` entries on v0.7.0 even after MMKV showed the key present. Shipped as v0.7.1 hotfix.

**Lesson**: when you wire a helper into the "API of record" (Builder, factory method, DSL), grep for ALL construction sites of the target type and patch each one. Tests may only exercise the Builder, while production goes through the constructor.

```bash
grep -rn "AgentConfig\.\|AgentConfig(\|toAgentConfig" app/src/main/java
```

### 10. PokeClaw is a generic mobile-agent harness, NOT a missed-call product

There is a SEPARATE revenue product called `~/MyGithub/missed-call-ai-chatbot-lab`. Missed-call follow-up is its scope, not PokeClaw's. PokeClaw stays generic — 21 tools × 13 rules — per `ARCHITECTURE_DECISIONS.md` D2. Do NOT add per-vertical workflows (missed call, plumber, dentist...) to PokeClaw core. If a generic primitive is needed (e.g. "phone call state read tool"), add it as a tool, not a workflow.

---

## 🛠 Pixel 8 Pro QA device — current state (2026-05-26)

When you reconnect ADB to this device, expect:

- v0.7.0 signed release APK installed (versionCode 28)
- Cloud LLM configured: Groq `llama-3.3-70b-versatile` via Custom provider
- Global prompt set: `Always reply in Cantonese only. No English.` (62 chars)
- Custom local model URL set: `https://example.com/test.litertlm` (placeholder, not a real downloadable model)
- All 6 system permissions DISABLED — has not been used for actual chat-send yet
- AdbKeyboard installed (from uiautomator2 init); may be set as default IME, may need switch back

To reset to clean state:

```bash
adb uninstall io.agents.pokeclaw
# Or to keep app but clear data:
adb shell pm clear io.agents.pokeclaw
```

---

## 📋 Quick reference — common operations

### Build + install signed release locally

```bash
source ~/.config/pokeclaw/release-signing.env   # exports KEYSTORE_FILE etc.
cd ~/MyGithub/PokeClaw
POKECLAW_VERSION_CODE=29 POKECLAW_VERSION_NAME=0.7.1 ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/PokeClaw_v*.apk
```

### Generate debug-report on signed release

Must go via the actual UI (debug receiver is gated):

```python
import uiautomator2 as u2
d = u2.connect()
d.app_start('io.agents.pokeclaw')
# Navigate Settings → About → Share Debug Report — open share sheet → save to file
# Files live at /data/data/io.agents.pokeclaw/cache/debug_reports/*.zip
# But you can't pull them via run-as on release — use the share intent to a
# file manager or messaging app you control.
```

On debug build, much simpler:
```bash
adb shell am broadcast -p io.agents.pokeclaw -a io.agents.pokeclaw.DEBUG_TASK \
  --es support_action build_debug_report
adb shell run-as io.agents.pokeclaw ls -t cache/debug_reports/ | head -1
```

### Push a release (after pre-tag QA passes)

```bash
# Make sure default versionCode / versionName in app/build.gradle.kts are bumped
grep -E "versionCode|versionName" app/build.gradle.kts | head -2
# Should show your new version. If not, bump them, commit, push, THEN tag.
git tag -a vX.Y.Z -m "vX.Y.Z — short description"
git push pokeclaw vX.Y.Z
# CI workflow `Release APK` will sign + publish within ~10min. Watch:
gh run list -R agents-io/PokeClaw --workflow 'Release APK' --limit 1
```

### Reply on user-reported OEM issues

After release goes live, reply on every open OEM-specific issue:

```bash
gh issue comment $NUM -R agents-io/PokeClaw -b "$(cat reply-draft.md)"
```

Drafts for the current 6 open OEM threads live in `docs/community-issue-replies.md`. Each cites a specific version — don't post unless that version is actually published, or you'll send users hunting for an APK that doesn't exist yet.

---

## 🎯 Strategic frame — re-read every session

For the long-term-decision context (acquisition target, moat, exit shape), see in this order:

1. `STRATEGY.md` — exit shape ($50-100M strategic acquisition by 2027-Q2, NOT acqui-hire), why mobile-agent harness is Stainless-shape, AI capability compounds so today's bottlenecks shrink fast
2. `EXECUTION_PLAN.md` — Phase 1 (shipped v0.7.0) → Phase 6 (decision gate). Maps every open issue + backlog item to a phase
3. `ARCHITECTURE_DECISIONS.md` — D1-D8, the load-bearing design choices an acquirer's tech-DD engineer needs to understand in 15 min

Memory file `~/.claude/projects/-home-nicole/memory/MEMORY.md` has the user-level context (Nicole's background, preferences, related projects).
