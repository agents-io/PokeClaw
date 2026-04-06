# PokeClaw

Your phone, on autopilot. No cloud, no API keys, no data leaving your device.

PokeClaw runs Gemma 4 (2.3B) entirely on your Android phone and controls it through accessibility. Tell it what to do in plain language, it figures out the taps, swipes, and typing.

## What it does

**Task mode** - give it a job, watch it work:

https://github.com/agents-io/PokeClaw/raw/main/demo/hi-demo.mp4

**Auto-reply** - monitors your messages and replies on your behalf:

https://github.com/agents-io/PokeClaw/raw/main/demo/monitor-demo.mp4

The model picks the right tool, fills in the parameters, and executes. You don't configure anything per-app. It just reads the screen and acts.

## How it works

PokeClaw gives a small on-device LLM a set of tools (tap, swipe, type, open app, send message, enable auto-reply, etc.) and lets it decide what to do. The LLM sees a text representation of the current screen, picks an action, sees the result, picks the next action, until the task is done.

Everything runs locally via [LiteRT-LM](https://ai.google.dev/edge/litert/llm/overview) with native tool calling. The model never phones home.

## Tools

The LLM has access to these tools and picks them autonomously:

| Tool | What it does |
|------|-------------|
| `tap` / `swipe` / `long_press` | Touch the screen |
| `input_text` | Type into any text field |
| `open_app` | Launch any installed app |
| `send_message` | Full messaging flow: open app, find contact, type, send |
| `auto_reply` | Monitor a contact and reply automatically using LLM |
| `get_screen_info` | Read current UI tree |
| `take_screenshot` | Capture screen |
| `finish` | Signal task completion |

## Quick start

1. Install the APK (Android 9+, arm64)
2. Grant accessibility permission when prompted
3. The model downloads automatically on first launch (~2.6 GB)
4. Switch to Task mode, type what you want

No API keys. No cloud config. No account.

## Build from source

```bash
git clone https://github.com/agents-io/PokeClaw.git
cd PokeClaw
./gradlew assembleDebug
```

Requires Java 17+ and Android SDK 36.

## License

Apache 2.0
