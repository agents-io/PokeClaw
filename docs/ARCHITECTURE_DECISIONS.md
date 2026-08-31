# PokeClaw — Architecture Decisions

> External-reader-friendly summary of the load-bearing choices in PokeClaw's design.
> The goal of this document: someone unfamiliar with the codebase can understand the
> *why* of each major decision in 15 minutes — without reading the source.
>
> For internal refactor guidance see `ARCHITECTURE_RECONSTRUCTION.md`.
> For the protocol-level skill format see `skill-spec.md`.

---

## D1. On-device LiteRT-LM (Gemma) as the default, cloud LLMs as optional

**Decision.** The default local runtime is Google's LiteRT-LM with Gemma 4 weights
(`gemma-4-E2B-it.litertlm` ≈ 2.6 GB for 8 GB-RAM devices, `gemma-4-E4B-it.litertlm` ≈ 3.6 GB
for 10 GB+ devices). Cloud LLMs (OpenAI-compatible, Anthropic, Gemini, Groq, etc.) are
opt-in for users who provide their own API keys.

**Why.**

- *Privacy as a category-defining differentiator.* Phone automation tools that touch
  Accessibility, notifications, and message content are exactly the things users do not
  want sent to a third party. Local-first by default is the only way to credibly make
  that promise.
- *Cost ceiling for free users.* Every cloud LLM round-trip costs money. An on-device
  default lets users explore the product indefinitely without a paid backend.
- *Hardware-acceleration headroom.* Modern Android devices ship with a competent LLM
  inference stack (Tensor on Pixel, MediaTek APU, Qualcomm Hexagon). LiteRT-LM is the
  layer that abstracts those.

**Trade-offs.**

- Smaller models (E2B/E4B) lag GPT-4-class cloud models on hard reasoning. The product
  is designed to *route* — chat stays local, complex tasks can opt into cloud.
- Model files are ~3 GB. The download UX is mandatory; we ship a Settings flow that
  picks the right size per device RAM (`LocalModelManager.recommendedModel`).
- GPU path is fragile across OEMs (#41, #14). The runtime now uses a conservative
  CPU-first policy on chipsets where OpenCL or GPU init has failed; see D6.

---

## D2. 21 generic Tools × 13 Core Rules — separating what the LLM can do from how it should behave

**Decision.** All on-device actions go through a small, fixed set of generic tools
(`app/src/main/java/io/agents/pokeclaw/tool/impl/`) — tap, scroll, input_text, open_app,
read_screen, get_notifications, send_message, etc. — *not* per-app tools. App-specific
behavior is encoded in 13 *Core Rules* injected into the system prompt.

**Why.**

- *Tools are an interface boundary.* A small, generic surface is easier to QA, easier to
  audit, and harder for an LLM to misuse. Adding "WhatsApp tool" / "Instagram tool" /
  "Gmail tool" balloons the attack surface and creates per-app brittleness.
- *Rules carry intent without expanding code.* If WhatsApp's "send" affordance moves,
  we update one rule string, not a tool implementation.
- *Generalizes to any Android app.* The same 21 tools work across WhatsApp, Telegram,
  YouTube, Settings, etc. — proven by the M-section QA suite.

**Trade-offs.**

- LLM has to reason more (decide *which* tool, *what* arguments) instead of calling a
  high-level action. Tasks are slower than they would be with bespoke app tools.
- For deeply structured workflows (Tinder swipe loops, scheduled monitors) we have
  added a parallel **Playbook** layer (D3) that constrains the LLM into a deterministic
  shape.

---

## D3. Java Playbooks closed-source, Markdown Playbooks open-source — the business model edge

**Decision.** Two playbook tiers:

- **MD Playbooks** (open-source, in-repo) are LLM prompt fragments — "here is how to
  Search in App / Navigate Settings / Compose Email / Read Screen / Read Notifications."
- **Java Playbooks** (Plus/Pro tier, closed-source) are background-automation
  state machines with LLM "slots" — e.g. an auto-reply monitor that watches a contact,
  classifies an incoming message, and triggers a deterministic action.

**Why.**

- *Distribution requires an open core.* PokeClaw's GitHub stars, fork traffic, and
  acquirer optionality all depend on the codebase being credibly open.
- *Sustainable revenue requires a paid surface.* Java Playbooks are the engineering
  effort that justifies a Plus/Pro tier. They are also the surface that benefits most
  from deterministic engineering (state machine, retry, idempotency) — the parts users
  pay for in commercial automation tools (Tasker, MacroDroid).
- *Acqui-hire alignment.* Both surfaces are owned by the same author. A future acquirer
  takes the codebase + author + paid-tier IP as a single package.

**Trade-offs.**

- Adds a dual licensing burden. The repo's LICENSE is Apache-2.0; closed components
  ship as a separate signed artifact and are not in the public source tree.
- Some open users will fork and re-implement the closed pieces. The bet is that the
  vast majority will not, because the engineering for production-grade automation is
  non-trivial.

---

## D4. Accessibility Service + NotificationListenerService, not root and not ADB pairing

**Decision.** All on-device actions are mediated by two Android system services that
the user grants once: AccessibilityService (for UI traversal + tap synthesis) and
NotificationListenerService (for monitor + auto-reply flows).

**Why.**

- *No root requirement.* Root locks PokeClaw out of >99% of consumer Android phones.
- *No ADB pairing requirement.* Pairing-based automation tools work but require a PC
  during setup and re-pair after every reboot on some OEMs — far worse UX than a
  one-time Accessibility toggle.
- *Cross-OEM coverage.* AccessibilityService works on every Android since API 28, with
  vendor variations that are observable (and reportable — see D6).

**Trade-offs.**

- *OEM service-lifecycle policy is the #1 source of bugs.* Xiaomi HyperOS, Samsung One
  UI, and a few realme/Infinix builds aggressively kill Accessibility services in
  background. We mitigate with a foreground notification (hardened in v0.5.1 / v0.6.8 /
  v0.6.9) but cannot fully prevent vendor kills without per-vendor allowlisting.
- *Google's evolving Play policy.* Late-January 2026 policy explicitly limits
  Accessibility-driven "autonomous AI actions." We are tracking this; sideload remains
  the distribution path until further notice.

---

## D5. MMKV for state, markdown frontmatter for chat history

**Decision.** Persistent app state lives in MMKV (`utils.KVUtils`). Chat / conversation
history lives as `.md` files with YAML frontmatter on disk, indexed in a small SQLite
table.

**Why.**

- *MMKV is fast and cross-process safe.* AccessibilityService and the main app process
  share state; MMKV's memory-mapped design handles concurrent reads cleanly.
- *Chat history as markdown is user-readable, scriptable, and bug-portable.* Users can
  open the file, edit it, search it with grep, share it as a bug-report. SQLite alone
  would make the data invisible to users.
- *Hybrid migration story.* If we ever need full-text search across history, we can
  reindex the markdown into SQLite FTS5 without changing the source of truth.

**Trade-offs.**

- Two systems instead of one — slight cognitive overhead.
- Sync edge cases between SQLite index and on-disk markdown. We have `ChatHistoryManager`
  helpers that always re-derive the index from disk when in doubt.

---

## D6. Community-delegated cross-OEM coverage, not in-house device farm

**Decision.** PokeClaw owns Pixel + one Xiaomi Redmi as in-house QA. All other OEM
coverage (Samsung, OPPO, realme, Infinix, vivo, Honor, etc.) is delegated to community
reporters via a structured `debug-report.zip` flow.

**Why.**

- *A one-person + AI team cannot maintain a 20-device test farm.* Even a 5-OEM farm
  costs $1-2k upfront and weekly maintenance time.
- *Real OEM bugs only appear on real OEMs.* Emulators do not reproduce HyperOS service
  kills or Samsung's Knox-side battery optimization.
- *Reporters self-select the right device coverage.* If a Xiaomi user files a bug,
  they have the device.

**Mechanism (the "debug-report.zip" contract).**

- Settings → About → Share Debug Report builds a deterministic zip with: device
  fingerprint, supported ABIs, RAM, OpenCL library probe (added 2026-05-26 for #41 / #14),
  Accessibility / Notification / Overlay state, recent logcat for backend tags, and
  recent HTTP logs.
- Issues created via the PokeClaw template are required to attach this zip.
- The author triages, replies same-day (visible-commit signal), and ships a hotfix
  the next minor release.

**Trade-offs.**

- Round-trip time per OEM bug is longer than first-party device farm. Mitigated by the
  diagnostic dump being self-describing enough to fix most issues without a follow-up.
- Some users will not file debug-reports. Those issues stay open as "needs more info."

---

## D7. Provider-agnostic OpenAI-compatible cloud LLM layer

**Decision.** All cloud LLM access goes through an OpenAI-compatible base URL. Built-in
providers (OpenAI, Anthropic via OpenRouter, Gemini, Groq, DeepSeek, Cerebras, etc.) are
just preset (baseUrl, modelName) pairs in `LlmConfigActivity`. Users can also enter
custom baseUrl + apiKey directly.

**Why.**

- *Vendor lock-in is the single biggest risk for an AI infrastructure project.* The
  abstraction layer means we can drop a provider without code changes.
- *Lowers integration cost.* Adding a new provider is a config change, not a code
  release.
- *Aligns with the user's existing API keys.* Most power users already have OpenAI or
  Groq credentials; we accept what they have.

**Trade-offs.**

- Providers that depart from OpenAI's API shape (Anthropic's prompt-caching, Gemini's
  multimodal extensions) require provider-specific adapters. We have a small
  `AnthropicLlmClient` for the cases where the OpenAI-compat shim is insufficient.

---

## D8. Same-day reply policy on every open issue

**Decision.** Every new issue gets a visible author reply within 24 hours, even if the
reply is "thanks, can you attach the debug-report.zip from this build."

**Why.**

- *Visible activity is the strongest acquirer signal.* GitHub's Insights → Pulse view
  shows commit cadence and issue response time. Both are read as proxies for "is this
  project alive."
- *Engagement compounds.* Reporters who get a fast reply are more likely to file the
  next bug they hit, fork to fix it, or recommend the project.
- *Filters trivia from real bugs.* Asking for the debug-report.zip is a low-friction
  test of how committed the reporter is.

**Trade-offs.**

- Author bandwidth is the bottleneck. Mitigated by templates: "thanks, please attach
  Settings → About → Share Debug Report"; "fixed in vX.Y.Z, please retest."

---

## North star (re-anchor)

> PokeClaw is the open-source mobile agent harness. On every Android phone that runs
> an AI assistant, this is the layer that translates "do this for me" into
> AccessibilityService taps + notification reads, regardless of which LLM the user
> picked.

If a decision in this doc starts to drift from that north star, the decision is
out-of-date and should be revisited explicitly, not silently.
