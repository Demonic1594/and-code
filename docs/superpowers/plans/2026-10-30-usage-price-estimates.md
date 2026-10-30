# Usage: an auto-updating price list estimates cost when the runtime reports none

Status: **not started**. Written down to build later; nothing in the app has been changed.
Held as a draft PR on purpose — the design decisions below (source, fallback, labeling) want a
look before any of it is built.

## Goal

The Usage page shows `$0.00` all day for plan-based access, because that is literally what the
runtime reports: OpenCode prices each turn from its models.dev catalog, and every
`*-coding-plan` provider entry there — `zai-coding-plan`'s `glm-5.3` included — carries
`input: 0, output: 0`, while the same model pay-as-you-go (`zai`) is priced $1.4/M in, $4.4/M
out, $0.26/M cache-read. Plan access has no per-token charge, so the number is honest — but it
answers nothing for a user who wants to know what the day *would have cost* at list prices.

The app therefore gains its own auto-updating copy of the price list, and shows an
**estimate at list prices** next to the reported cost wherever usage is shown. The reported
number stays the source of truth; the estimate is a separate, clearly-labeled figure.

## What exists today

- `UsageRepository.turnsOf` (`UsageRepository.kt:126-146`) takes each assistant turn's
  `info.cost` as-is; `TurnUsage.cost` is "USD, or null when the runtime does not report cost".
- Only OpenCode fills `info.cost` (from models.dev). Claude Code reports tokens but never a
  cost; Codex and Antigravity are `UNTRACKED_AGENTS` (`:123`) with no per-turn tokens either.
- `summarizeSessionUsage` (`SessionUsage.kt`) sums turn costs for the chat's ⋮ usage row;
  `UsageScreen` shows `totals.cost` and hides the row when null.
- The app already knows each turn's `providerId/modelId` (`UsageRepository.kt:136`) and its
  token mix including cache read/write — everything an estimate needs except the prices.
- models.dev publishes one JSON (`https://models.dev/api.json`, ~6 MB; the project is the
  open-source anomalyco/models.dev repository, MIT for the code) keyed by provider → model →
  `cost {input, output, cache_read, cache_write}` in $/M tokens. It is the same catalog
  OpenCode itself prices from, so estimates match what OpenCode would have reported for a paid
  provider. Task 4 confirms the **data's** license from the repository (the code's MIT may not
  cover the database) before anything is bundled.

## Design

### The price list

- A `PriceCatalog` (pure Kotlin, `core/`): parse a filtered projection of the models.dev JSON —
  keep provider id, model id, and the four cost fields; drop the other ~95% of the document so
  the stored form is small (a few hundred KB), not 6 MB.
- Bundled snapshot in `assets/` as the offline start (with its fetch date), refreshed from the
  network **at most weekly** (staleness header on the stored copy; refresh attempted when the
  Usage page opens, in the background, never blocking the page). The fetch is a plain GET with
  no identifiers; a failure keeps the last good copy and its date. The date is shown under the
  estimate ("list prices as of …").
- Storage under the app's files dir; the Storage manager counts it under caches.

### The estimate

- A turn is estimable when it has tokens but its `cost` is null or `0.0`:
  `estimate = (input·p_in + output·p_out + cacheRead·p_cr + cacheWrite·p_cw) / 1e6`.
- Lookup order: exact `providerId/modelId`; then — the case that matters for coding plans —
  when the exact entry is missing or priced all-zero, fall back to the **first-party** entry
  for the same model id (`zai`, `zhipuai`, `anthropic`, `openai`, …; a small fixed list of
  first-party provider ids, so a random router's zero-price mirror does not decide the
  estimate). Still nothing → no estimate for that turn.
- Estimates never overwrite `TurnUsage.cost`. They live beside it
  (`estimatedCost: Double?`) and every surface labels them: the Usage page shows
  **Cost (reported)** and **≈ at list prices**, the chat's ⋮ usage row likewise, and the share
  text carries the same two figures with the catalog date.
- A Settings toggle (Usage → **Estimate costs at list prices**, on by default) turns the whole
  thing off for anyone who finds the second number noise.

### Out of the estimate's reach

Codex and Antigravity turns stay untracked — they report no per-turn tokens to estimate from.
Claude Code gains estimates (it reports tokens); that is the quiet win of this design.

## Tasks

**Task 1 — catalog core**: the filtered parser, lookup with the zero-price fallback, unit
tests on a trimmed fixture (exact hit, all-zero plan entry → first-party fallback, miss, cache
fields).

**Task 2 — updater**: background refresh with weekly staleness, atomic replace, date kept,
failure keeps the old copy; tests with a fake clock and a fake fetch.

**Task 3 — wiring**: `TurnUsage.estimatedCost`, `summarizeSessionUsage` and the Usage page
rows, the chat ⋮ usage row, the toggle in Settings; strings in all eight locales.

**Task 4 — notices**: confirm the api.json data license in the anomalyco/models.dev repository
first; if it is permissive, `THIRD_PARTY_NOTICES.md` gains the models.dev entry and the bundled
snapshot's provenance. If it is not permissive enough to bundle, the plan falls back to
fetch-only (no bundled snapshot; estimates simply wait for the first successful refresh) — a
decision this task records. The plan's data-flow docs (prompts/tokens never leave the device;
one plain GET to models.dev when refreshing) noted in PRIVACY-relevant wording if the doc lists
outbound requests.

**Task 5 — checks**: `./gradlew detekt spotlessCheck :app:lintGithubDebug
  :app:testGithubDebugUnitTest`, i18n parity, screenshots of the Usage page with both figures,
  and the pre-PR review.

## Confirm on a device

1. A chat on a coding-plan provider: reported $0.00, estimate ≈ what the day's tokens cost at
   the first-party list price; the label and date are visible.
2. Airplane mode: the Usage page still shows estimates from the bundled snapshot, dated.
3. A paid OpenCode provider: reported cost unchanged; the estimate equals it (same catalog).
4. Toggle off: the estimate rows disappear everywhere.

## Out of scope

- Changing what runtimes report, or OpenCode's own pricing.
- Per-model price overrides by the user (a small editor can follow if the estimate is wanted
  for a model the catalog lacks).
- Budgets or alerts on the estimate.
