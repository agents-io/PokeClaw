# PokeClaw — Execution Plan to Acquisition Target

> Bridges STRATEGY.md (where + why) and BACKLOG.md (what).
> Target per STRATEGY.md: **$50-100M strategic acquisition by 2027-Q2**.
> This file = mapping all 22 open issues + BACKLOG into a phased execution order.

---

## North Star

> Each phase ships **one signal that an acquirer reads as buying value**.
> Signals stack — by end of plan, full acquirer narrative is documented + visible.

---

## Phase 0 — RIGHT NOW (setup, ~2 hours)

**Signal: Alive.** Break 30-day silence.

- [x] STRATEGY.md committed (2026-05-25)
- [x] EXECUTION_PLAN.md committed (this file)
- [x] GitHub Actions emulator matrix workflow committed
- [ ] Firebase Test Lab account signup (Nicole, 15 min)
- [ ] Samsung Remote Test Lab signup (Nicole, 15 min)
- [ ] Reply 1 visible comment on each of 6 OEM-specific issues (#42, #48, #23, #17, #16, #2) — "v0.7.0 hardening shipping this week, debug-report instructions"

---

## Phase 1 — This Weekend (~10 hours, Pixel-only)

**Signal: Capable solo shipping.** Four features, one v0.7.0 release.

### Tickets

| # | Title | Effort | QA on |
|---|---|---|---|
| 1 | **#44 Voice Input** | 2 hr | Pixel 8 Pro (RecognizerIntent) |
| 2 | **#50 Simplified Chinese strings** | 1 hr | Pixel via locale switch |
| 3 | **#45 Persistent global prompt** | 3 hr | Pixel — Settings UI + MMKV |
| 4 | **#36 Custom local model URLs** | 3 hr | Pixel — Settings + download |

### Per-ticket process (QA-first per CLAUDE.md)

1. Add E2E test cases to `QA_CHECKLIST.md` first
2. Implement
3. Build APK, install on Pixel, run uiautomator2 / logcat verification (NO screenshots per android-dev-workflow rule)
4. Mark ticket done in BACKLOG.md
5. Commit (one ticket = one commit, atomic)

### Release packaging

After 4 tickets done:
- Bump `versionCode=28`, `versionName=0.7.0`
- Update README changelog
- Tag `v0.7.0`, push, GitHub release auto-fires via `release.yml`
- Reply to each closed ticket: "v0.7.0 ships this — please retest"

---

## Phase 2 — Next 2 Weeks (~25 hours)

**Signal: Architecture depth.** Big feature + architecture docs.

### Tickets

| # | Title | Effort | Notes |
|---|---|---|---|
| 5 | **BACKLOG P0 Missed-call follow-up** | 12 hr | Major feature, Pixel-testable |
| 6 | **#3 Accessibility reconnect hardening** | 4 hr | Pixel may repro, expand v0.5.1 hardening |
| 7 | **#41 + #14 GPU/OpenCL diagnostics** | 3 hr | Add structured logging, expose in debug-report |
| 8 | **BACKLOG P3 Rename chat session** | 1 hr | Quick UX win |
| 9 | **BACKLOG P3 Floating button icon** | 30 min | Quick UX win |
| 10 | **BACKLOG P1 1B/1.5B local model options** | 4 hr | Model registry expand |

### Parallel architecture-doc work (~6 hr)

Acquirer reads these to understand what they'd be buying:

- [x] **ARCHITECTURE_DECISIONS.md** (2026-05-26): single doc covering D1–D8 — on-device LiteRT default, 21 tools × 13 rules, Java vs MD playbooks, AccessibilityService choice, MMKV+markdown data layer, community-delegated cross-OEM, provider-agnostic cloud, same-day issue reply. Linked from STRATEGY.md.

### Release packaging

After Phase 2: `v0.8.0` — "Missed-call automation + architecture docs"

---

## Phase 3 — Weeks 3-4 (~20 hours)

**Signal: Cross-OEM coverage you can prove.**

### Cross-OEM QA infrastructure

- [ ] Wire Firebase Test Lab into emulator-matrix.yml workflow
  - Free tier: 15 virtual + 10 physical runs / day
  - Covers Samsung S24/S23, Xiaomi 13, Pixel real devices
- [ ] Samsung Remote Test Lab session for #17 + #16 reproduction
- [ ] BrowserStack App Live $40/mo trial month for Xiaomi reproduction
- [ ] eBay 二手 Xiaomi Redmi Note 12 ~$120 — permanent vendor coverage
- [ ] OEM Compatibility Matrix doc — `docs/oem-coverage.md`
  - Columns: Pixel / Samsung / Xiaomi / OPPO / realme / Infinix
  - Rows: critical flows (chat, task, monitor, model download, accessibility, notifications)
  - PASS / PARTIAL / FAIL per cell with evidence link

### Tickets unblocked by Phase 3 infrastructure

| # | Title | Where reproduced |
|---|---|---|
| 11 | **#42 Xiaomi HyperOS Accessibility kill** | BrowserStack / 二手 Xiaomi |
| 12 | **#23 Xiaomi Redmi 14 Pro UI crash** | BrowserStack / 二手 Xiaomi |
| 13 | **#48 Xiaomi 23013RK75C** | BrowserStack / 二手 Xiaomi |
| 14 | **#17 + #16 Samsung A52 issues** | Samsung Remote Test Lab |
| 15 | **#29 + #2 Xiaomi crashes** | BrowserStack / 二手 Xiaomi |

### Release packaging

After Phase 3: `v0.9.0` — "Multi-OEM hardening"

---

## Phase 4 — Month 2 (~30 hours)

**Signal: Protocol attempt + scope.**

### Major work

- [ ] **Mobile Agent Protocol Spec draft** (`docs/MAP-spec.md`)
  - Define `[INTENT:...]` markers, tool envelope, monitor lifecycle
  - Even one paper = protocol-owner narrative for acquirer
- [ ] **BACKLOG P1 Tinder automation** — proves "same monitor architecture, any app"
- [ ] **BACKLOG P1 Structured monitor identifiers** — refactor monitoring layer
- [ ] **BACKLOG P1 Structure-first UI matching** — reduce language heuristics

### Tickets

| # | Title | Effort |
|---|---|---|
| 16 | **BACKLOG P1 Tinder automation** | 8 hr |
| 17 | **BACKLOG P1 Structured monitor IDs** | 6 hr |
| 18 | **BACKLOG P1 Structure-first matching** | 6 hr |
| 19 | **#38 HuggingFace model import docs** | 1 hr |
| 20 | **BACKLOG P2 Unified task registry** | 5 hr |

### Release packaging

After Phase 4: `v1.0.0` — API stable + MAP spec draft.

---

## Phase 5 — Month 3 (outreach + applications)

**Signal: Acquisition radar active.**

- [ ] **YC W27 application** — Use Phase 1-4 narrative
  - Even if rejected, pitch deck sharpens story
- [ ] **2-3 inbound conversations**:
  - Anthropic DevRel (mobile Claude gap)
  - Samsung Mobile (Galaxy AI Google-independent angle)
  - Xiaomi (HyperOS AI layer)
- [ ] **HN "Show HN" post** for v1.0.0
- [ ] **Droidcon / AndroidMakers talk submission**
- [ ] **Sponsorship setup** — GitHub Sponsors, in case OSS-sustained becomes the path

---

## Phase 6 — Months 4-6 (compound + decide)

Decision gate per STRATEGY.md §10:
- Hit all 3 milestones (active push, 2000+ stars, 1 inbound) → **continue + raise/sell**
- Hit 1-2 → continue but reconsider scope
- Hit 0 → archive PokeClaw

---

## Cross-cutting threads

### Community delegation (runs continuously from Phase 1)

For each OEM bug we can't repro:
1. Leave **visible reply within 24 hr**：「v0.X.Y diagnostics ship 緊，請 attach fresh debug-report.zip」
2. **Issue template enforcement** — require OEM + Android version + Build number + debug-report.zip
3. **Vendor-specific code directories** — `vendor/xiaomi/`、`vendor/samsung/`
4. **「PokeClaw OEM Verified」badge** in README — community-maintained matrix

### Active commit signal

**Minimum commit cadence**: 3+ commits/week visible on default branch.
Acquirer reads `Insights → Pulse` — silence = dead project.

### Architecture decision documentation (continuous)

Every Phase 2+ feature gets an ADR. By Phase 4 there should be 8-12 ADRs.
ADRs are **the single highest signal** for acqui-hire candidates: shows you think systematically.

---

## Ticket → Phase mapping (master table)

| # | Ticket | Phase | Confidence | Pixel-only? |
|---|---|---|---|---|
| #44 | Voice Input | 1 | 🟢 | ✅ |
| #50 | 簡中 strings | 1 | 🟢 | ✅ |
| #45 | Persistent global prompt | 1 | 🟢 | ✅ |
| #36 | Custom local model URLs | 1 | 🟢 | ✅ |
| BACKLOG | Rename chat session | 2 | 🟢 | ✅ |
| BACKLOG | Floating btn icon | 2 | 🟢 | ✅ |
| BACKLOG P0 | Missed-call follow-up | 2 | 🟡 | ✅ |
| #3 | Accessibility reconnect | 2 | 🟡 | ✅ partial |
| #41 + #14 | GPU/OpenCL diagnostics | 2 | 🟡 | ✅ |
| BACKLOG P1 | 1B/1.5B models | 2 | 🟡 | ✅ |
| #42 | Xiaomi HyperOS kill | 3 | 🔴 → 🟡 with cloud | ❌ |
| #23 | Xiaomi Redmi 14 Pro | 3 | 🔴 → 🟡 with cloud | ❌ |
| #48 | Xiaomi 23013RK75C | 3 | 🔴 → 🟡 with cloud | ❌ |
| #17 #16 | Samsung A52 | 3 | 🔴 → 🟡 with Samsung Lab | ❌ |
| #29 #2 | Xiaomi crashes | 3 | 🔴 → 🟡 with cloud | ❌ |
| BACKLOG P1 | Tinder automation | 4 | 🟡 | ✅ |
| BACKLOG P1 | Structured monitor IDs | 4 | 🟡 | ✅ |
| BACKLOG P1 | Structure-first matching | 4 | 🟡 | ✅ |
| BACKLOG P2 | Unified task registry | 4 | 🟡 | ✅ |
| #38 | HF model import docs | 4 | 🟢 | ✅ |
| #46 | Feishu support | onhold | requires reporter flow clarification |
| #40 | Virus detection | close | answered, VirusTotal clean |
| #30 | 0.5 v notification | close | old version, ask reporter to upgrade |
| #39 | Model download fails | close | device unknown |
| #26 | 使用崩溃 | close | too vague |
| #49 | Karem (Infinix) | close | empty body |

---

## Right now → Phase 1 Ticket 1 = #44 Voice Input

Starting QA-first design + implementation immediately.
