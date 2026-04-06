# PokeClaw Skill File Specification v1.0

## Overview

Skills are predefined playbooks that tell the on-device LLM exactly what steps to follow. Instead of the model guessing which tools to use, a skill provides a recipe. The model follows it.

Skills are written in natural language (Markdown). What you write is what the model sees.

## File Format

**Naming:** `{skill-name}.skill.md`
**Location:** `assets/skills/` (built-in), `/sdcard/PokeClaw/skills/` (user-created)
**Encoding:** UTF-8

## Frontmatter

Delimited by `---` lines. Flat key-value pairs only.

```
---
description: <required> One sentence. Include a trigger example. Shown to the router LLM for skill selection.
tools: <required> Comma-separated list of PokeClaw tool identifiers this skill uses.
author: <optional> Name or handle.
version: <optional> Semver.
---
```

- `description` is what the router LLM sees. Write it to maximize matching accuracy. Include an example trigger phrase after a comma.
- `tools` must use exact PokeClaw tool identifiers. Unknown tools produce a load-time warning; the skill still loads with valid tools. Zero valid tools = skill skipped.

## Body

Everything after the closing `---` is injected verbatim into the LLM prompt when this skill is selected.

### Structure

```markdown
# Skill Name

One sentence summary.

## Steps

1. From the user's request, identify:
   - **param1**: description (default: value)
   - **param2**: description
2. If any required information is unclear, ask the user.
3. [Action step with inline error handling if critical]
4. [Action step]
...
N. Confirm completion to the user.

## Example

User: "natural language request"
→ tool_call(args)
→ tool_call(args)
→ "Confirmation message"

## If something goes wrong

- If [condition]: [response]
- If [condition]: [response]
```

### Rules

- Target 250-350 tokens. Warn at 400.
- Steps are numbered and imperative.
- Step 1 is always parameter extraction. Include defaults.
- Step 2 is always "ask if unclear."
- Use natural language references ("the contact," "the specified app"), not template syntax like `{contact}`.
- Put critical error handling inline with the step. The error section is supplementary.
- One example recommended. Two max.

## Routing

On each user message:

1. Runtime builds a routing prompt listing all loaded skills by `description`.
2. LLM outputs a skill name or "none."
3. Runtime normalizes output (lowercase, strip whitespace, hyphens = underscores) and matches against loaded skill filenames.
4. No match → "none" → general conversation mode.

The router handles any language. Gemma 4 is multilingual — a Chinese request matches an English skill description semantically.

### Router Prompt Template

```
Pick the best skill or "none":

1. send-message: Send a message to a contact, e.g. "text Mom hello on WhatsApp"
2. monitor-reply: Auto-reply to incoming messages, e.g. "reply to boss for me"
3. open-app: Open an app, e.g. "open Chrome"

User: "{user_input}"
Skill:
```

~250-300 tokens for 10 skills. Model outputs one word.

## Execution

1. Runtime reads the matched skill's `tools` field.
2. Runtime builds execution prompt: skill body (verbatim) + tool definitions for listed tools only.
3. Model follows steps using scoped tools.
4. Rigid step execution for MVP.

## Valid Tool Identifiers

| Tool | Description |
|------|-------------|
| `open_app` | Launch an app by name |
| `tap` | Tap a screen coordinate or element |
| `type_text` | Enter text into the focused field |
| `find_element` | Find a UI element by description |
| `scroll` | Scroll in a direction |
| `swipe` | Swipe gesture |
| `send_message` | Full messaging flow |
| `auto_reply` | Enable auto-reply monitoring |
| `send_reply` | Reply to a notification |
| `read_notification` | Read incoming notifications |
| `set_monitor` | Start monitoring for notifications |
| `list_skills` | Return list of installed skills |
| `press_back` | Press the back button |
| `screenshot` | Capture current screen |
| `get_screen_info` | Read current UI tree |
| `finish` | Signal task completion |

## Example Skills

### send-message.skill.md

```markdown
---
description: Send a message to a contact on a messaging app, e.g. "text Mom hello on WhatsApp"
tools: open_app, tap, type_text, find_element, scroll
---

# Send Message

Send a text message to a specific contact using a messaging app.

## Steps

1. From the user's request, identify:
   - **contact**: who to message
   - **app**: which app to use (default: WhatsApp)
   - **message**: what to send
2. If any of these are unclear, ask the user before proceeding.
3. Use open_app to launch the messaging app.
4. Find the contact in the chat list. If not found, tell the user.
5. Tap the contact to open the chat.
6. Tap the message input field.
7. Type the message content.
8. Tap the send button. If not found, tell the user.
9. Confirm: "Message sent to [contact] on [app]."

## Example

User: "Tell Mom I'll be late on WhatsApp"
→ open_app("WhatsApp")
→ find Mom → tap
→ tap message field → type "I'll be late"
→ tap send
→ "Message sent to Mom on WhatsApp."

## If something goes wrong

- If the app is not installed: tell the user.
- If the contact is not found: ask the user to check the name.
- If the send button can't be found: tell the user the app layout might have changed.
```

### monitor-reply.skill.md

```markdown
---
description: Watch for incoming messages and auto-reply, e.g. "auto-reply to my boss saying I'm in a meeting"
tools: auto_reply, finish
---

# Monitor and Auto-Reply

Watch for incoming messages from a contact and automatically reply using the on-device LLM.

## Steps

1. From the user's request, identify:
   - **contact**: who to watch for
   - **app**: which messaging app (default: WhatsApp)
2. If the contact is unclear, ask the user.
3. Use auto_reply to enable monitoring for the contact on the specified app.
4. Use finish to confirm: "Auto-reply is active for [contact] on [app]."

## Example

User: "Monitor Mom on WhatsApp and auto-reply for me"
→ auto_reply(contact="Mom", app="WhatsApp")
→ finish("Auto-reply enabled for Mom on WhatsApp")

## If something goes wrong

- If accessibility is not enabled: tell the user to enable it in Settings.
- If the contact name is ambiguous: ask which contact they mean.
```

### open-app.skill.md

```markdown
---
description: Open an app by name, e.g. "open Chrome" or "launch Settings"
tools: open_app, finish
---

# Open App

Open an application on the device.

## Steps

1. From the user's request, identify:
   - **app**: which app to open
2. Use open_app to launch it.
3. Confirm: "Opened [app]."

## Example

User: "Open YouTube"
→ open_app("YouTube")
→ "Opened YouTube."

## If something goes wrong

- If the app is not found: "I couldn't find an app called [name]. Could you check the name?"
```

### list-skills.skill.md

```markdown
---
description: Show what skills are available, e.g. "what can you do?" or "help"
tools: list_skills
---

# List Skills

Tell the user what skills are available.

## Steps

1. Use list_skills to get the current list of installed skills.
2. Present each skill with its name and a short description.
3. Ask if the user wants to try any of them.

## Example

User: "What can you do?"
→ list_skills()
→ "Here's what I can help with:
   - Send Message: text someone on WhatsApp, Telegram, etc.
   - Monitor Reply: auto-reply to incoming messages
   - Open App: launch any app
   Want to try any of these?"
```
