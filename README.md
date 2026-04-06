
<p align="center">
  <img src="banna.png" width="600" />
</p>
<p align="center">
  <img src="option.png" width="600" />
</p>

# PokeClaw (PocketClaw) - A Pocket Versoin Inspired By OpenClaw

Gemma 4 launched 4 days ago.  

I wanted to know if it could actually drive a phone.

So I pulled two all-nighters and built it.

As far as I know, this is the first working app built on Gemma 4 that can autonomously control an Android phone.

The entire pipeline is a closed loop inside your device. No Wifi needed,No monthly billing for the API keys.



```
Everyone else:  Phone → Internet → Cloud API → Internet → Phone
                       💳Credit card needed, API key required. Monthly bill attached.

PokeClaw:       Phone → LLM → Phone
                       That's it. No internet. No API key. No bill.
```
**AI controls your phone. And it never leaves your phone.**

This is a 2-day open-source prototype, not a polished consumer app.
If it works on your device, amazing. If it breaks, Issues and fix proposals are welcome.

Monitor Girlfriend's whatsapp and auto reply:


https://github.com/user-attachments/assets/4cb4c2bf-90e1-4391-8e08-9d6113634a41

Monitor Mom's whatsapp and auto reply:

https://github.com/user-attachments/assets/5a43d4d5-458a-4eea-a0a5-58d113255741

https://github.com/user-attachments/assets/5c2966c5-04e6-4b22-8d66-11915ae62096

> **☝️ Auto-reply demo:** PokeClaw monitors messages from Mom, reads what she said, and replies based on context using the on-device LLM. [Watch in higher resolution on YouTube](https://youtube.com/shorts/Vxpf474chm0)

> **☝️ Context demo:** Mom asks "what did I tell you to bring?" — the AI opens the chat, reads the full conversation on screen, sees the earlier message about wine, and replies correctly. This is the difference between context-aware and context-free replies.

https://github.com/user-attachments/assets/89999dd8-a1be-49ad-9419-60c2b38f6374


> **Why is the "hi" demo slow?** Recorded on a budget Android phone (I'm literally too broke to buy a proper one, got this just to demo the app lol) with CPU-only inference, no GPU, no NPU. Running Gemma 4 E2B on pure CPU takes about 45 seconds to warm up — started at several minutes, we optimized the engine initialization and session handoff to squeeze it down this far. If your phone actually has a decent chip it's **way faster**:
> - **Google Tensor G3/G4** (Pixel 8, Pixel 9)
> - **Snapdragon 8 Gen 2/3** (Galaxy S24, OnePlus 12)
> - **Dimensity 9200/9300** (recent MediaTek flagships)
> - **Snapdragon 7+ Gen 2+** (mid-range with GPU)
>
> On these devices, warmup drops to seconds. Same model, better hardware.
>
> That said, the fact that a 2.3B model can autonomously control a phone running purely on CPU is already pretty impressive. GPU just makes it faster.








## The Story

I'm a solo developer. When Gemma 4 dropped on April 2nd with native tool calling on LiteRT-LM, I pulled two all-nighters and built this from scratch. This is the first local LLM that can run on a phone and is capable enough to handle genuinely complex tasks — having conversations, auto-replying to your mom based on context, navigating apps autonomously. That's exciting to me.

It's not perfect. Local LLMs are not as smart as cloud models, and there are plenty of rough edges. Hardware is what it is — we can't make your CPU faster. But on the software side, we're actively improving the architecture, the tool system, and the overall design. Cloud LLM support is coming as an optional feature for people who want more power.

And it's **completely free**. No API keys that bill you every month. No subscription. No usage limits. The model runs on your hardware and costs you nothing.

We're living through a historic shift. Local LLMs are now smart enough to actually do useful work on a phone. That wasn't true 6 months ago. On-device models are getting smarter fast, and we're hoping it won't be long before they close the gap with cloud models entirely. When that happens, PokeClaw is ready.

**This project has a lot of issues. That's expected. Please [open them](https://github.com/agents-io/PokeClaw/issues).** Every bug report makes this better.

## Screenshots

<p align="center">
  <img src="screenshots/chat.png" width="200" />
  <img src="screenshots/task.png" width="200" />
  <img src="screenshots/settings.png" width="200" />
  <img src="screenshots/model.png" width="200" />
</p>

## What it does

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

These tools are generic — they work with any app, any contact, any language. The LLM picks the right tool and fills in the parameters from your request.

## Tools + Skills

A 2.3B on-device model is not GPT-4. It follows instructions well, but it's not great at figuring out *which* tools to use on its own. So we give it a playbook.

The auto-reply feature is a good example. It doesn't work by magic — there's a predefined workflow behind it: open the chat → read all visible messages on screen → generate a context-aware reply → send it → go back to home. The model follows this recipe step by step. Every tool in that chain is generic: `open_app` works with any app, `read_screen` works on any screen, `send_message` works with any contact. The workflow just tells the model which tools to use and in what order.

This is what we're calling **Skills** — reusable workflows built from generic tools. We're actively designing a skill system inspired by [Claude Code's skill architecture](https://docs.anthropic.com/en/docs/claude-code/skills). The idea: anyone can write a skill as a simple text file that describes the steps, and the LLM follows it.

Some examples of what skills can do:

- **Auto-reply**: monitor notifications → open chat → read conversation → generate reply → send
- **Morning briefing**: open weather app → read temperature → open calendar → read today's events → open email → count unread → summarize everything
- **Smart forward**: catch a notification → open the message → read it → forward to another contact with a summary
- **Auto-booking**: open a booking app → search for a time slot → fill in details → confirm

Each skill is just a combination of the same generic tools (`open_app`, `tap`, `type`, `read_screen`, `send_message`, etc.) arranged in a specific order. The tools are the building blocks, the skills are the recipes.

Both are designed to be extensible. We're building the first 8-10 skills as built-in defaults. If the system works well, we'll open it up for the community to create and share their own tools and skills. You know your phone better than we do — you should be able to teach it new tricks.

As on-device models get smarter, the skills become less necessary. A future 7B or 13B model might figure out the right workflow on its own. Until then, skills are how we get reliable automation out of a small local model. Think of it as training wheels that the model will eventually outgrow.

🌐 **Landing Page:** https://agents-io.github.io/PokeClaw/

## Download

[**Download APK (v0.2.0)**](https://github.com/agents-io/PokeClaw/releases/latest)

### Requirements

| | Minimum | Recommended |
|---|---|---|
| **Android** | 9+ | 12+ |
| **Architecture** | arm64 | arm64 |
| **RAM** | 8 GB | 12 GB+ |
| **Storage** | 3 GB free (model download) | 5 GB+ |
| **GPU** | Not required (CPU works) | Tensor G3/G4, Snapdragon 8 Gen 2+, Dimensity 9200+ |
| **Root** | Not required | Not required |

> ⚠️ 8 GB is the minimum but may still crash on some devices depending on what else is running. 12 GB+ is comfortable. If the app crashes during model loading, close other apps and try again. If it still crashes, your phone doesn't have enough free RAM for a 2.3B model. Hardware limitation, not a bug.

## Quick start

1. Install the APK
2. Grant accessibility permission when prompted
3. The model downloads automatically on first launch (~2.6 GB)
4. Switch to Task mode, type what you want

No API keys. No cloud config. No account.

## Help Wanted

This is the first local LLM that can autonomously control a phone. Built in two all-nighters on a model that dropped days ago. It's already pretty impressive that this works at all, and it's only going to get better as on-device models improve.

That said, there are a LOT of issues. That's expected for something this new. If you run into bugs, weird behavior, or have ideas:

- ⭐ **[Star this repo](https://github.com/agents-io/PokeClaw)** if you think local AI phone control matters
- 🐛 **[Open an issue](https://github.com/agents-io/PokeClaw/issues)** when something breaks (it will)
- 🍴 **[Fork it](https://github.com/agents-io/PokeClaw/fork)** and build on it

Every issue makes this better. Every star helps more people find it.

## Changelog

### v0.2.0 (2026-04-06)
- **Auto-reply now reads conversation context.** Before replying, the AI opens the chatroom and reads all visible messages on screen. It no longer forgets what was said 3 messages ago.
- **In-app update checker.** The app checks GitHub Releases once per day and prompts you to download if a newer version exists. No more manually checking.

### v0.1.0 (2026-04-06)
- Initial release. On-device Gemma 4 E2B with tool calling, accessibility-based phone control, auto-reply, task mode.

## Acknowledgments

PokeClaw exists because of [Gemma 4](https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/) by [Google DeepMind](https://github.com/google-deepmind). Thank you to [Clément Farabet](https://github.com/clementfarabet), [Olivier Lacombe](https://github.com/olivierlacombe), and the entire Gemma team for shipping an open model with native tool calling under Apache 2.0. You made it possible for a solo developer to build a working phone agent in two nights. The [LiteRT-LM](https://ai.google.dev/edge/litert/llm/overview) runtime is what makes on-device inference practical.

Also inspired by the [OpenClaw](https://github.com/openclaw/openclaw) community 🦞 for proving that AI agents that actually do things are what people want.

And thank you to [Claude Code](https://claude.ai/code) by Anthropic. I'm a CS dropout with zero Android development experience. Claude Code made it possible for me to go from nothing to a working app in two nights. The future is wild.

## License

Apache 2.0

Contributors sign our CLA before their first PR is merged.
