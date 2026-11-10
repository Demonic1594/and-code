# App checkup (2026-11-09)

Status: **in progress**. A pass over the whole app after the Cursor report and harness plan were
done (`2026-11-07-cursor-report-assessment.md`, `2026-10-31-agent-harness-improvements.md`), to find
what is wrong or worse than it should be. Fixes go in small PRs; this file says what was found, what
was fixed where, and what is left on purpose.

## What was checked

- Android lint on the github debug variant: 292 warnings, no errors.
- Kotlin compiler warnings from a full build: 22.
- detekt's baseline (670 entries before this pass) by rule, and the largest files.
- Crash-prone patterns in `app/src/main`: `!!`, `runBlocking`, `GlobalScope`, `Thread.sleep`.
- Startup work in `AndCodeApplication.onCreate`.
- Icon buttons without a label for screen readers.
- The manifest's exported components, the network security config and file permission calls.
- The unit test suite (2316 tests) and CI on main.
- The fork's open issues (none), and the "Not done yet" list in `docs/FEATURES.md`.

No device or emulator was available (no KVM in this container), so nothing here was seen running;
each fix that changes what the user sees lists a device check in its PR.

## Healthy

- Every `!!` in the main code (28) is safe: inside a `runCatching` (the OpenCode event parser, the
  chat image loader), right after the value is set or checked for null (`startedAtMillis`, the
  installed Antigravity version, the browser's `loadError`, the provider's `models`), or on a regex
  group that is part of every match. No `GlobalScope`. The one `runBlocking` (Codex's JSON-RPC reader)
  and the `Thread.sleep` calls run on background threads, as their comments say.
- The pricing snapshot loads in the background, when `PriceCatalogRepository` is built.
- Every standalone icon button has a content description (icons inside a labelled button rightly have none).
- The lint warnings about WebView features (`RequiresFeature`), haptic constants and API 30/33
  constants (`InlinedApi`) are already guarded by feature or SDK checks.
- The exported `ScheduleTriggerReceiver` refuses any intent without the user's token.
- Cleartext HTTP is allowed in the base config on purpose (remote runtimes on LAN and Tailscale
  addresses, which the config cannot list). `OpenCodeUrl.normalize()` accepts a plain `http://`
  server address only for local, private and Tailscale hosts. It checks the address the user enters,
  not where that server redirects, so redirects between https and http are refused (row 9).
- `SetWorldReadable`/`SetWorldWritable` are on files inside the app's own PRoot environment, which
  the guest's tools need to read or write.
- All unit tests pass apart from `ProcessTerminalSessionTest > decodes multi byte characters`, which
  fails only in this container's locale and passes on CI.

## Found and fixed

| # | Finding | Fix |
|---|---|---|
| 1 | Six icons that point a direction (Send, Open in new, Chat, Receipt, Article, File) used the old, non-mirrored versions, so they point the wrong way in the Arabic UI. | The `AutoMirrored` versions (this PR). |
| 2 | `LocalLifecycleOwner` from `compose-ui` is deprecated (4 screens); `ArArchiveInputStream.nextArEntry` is deprecated; two conditions were always true; one `Json` was built on every call. | Moved to `lifecycle-runtime-compose`, `nextEntry`, the dead conditions removed, one shared `Json` (this PR). |
| 3 | The home screen widget's Send and microphone buttons had English labels in every language. | The app's `cd_send` and `cd_microphone` strings, translated in the 6 languages where they were still English, and set with the hint from the app's language when one is picked in AndCode, so the widget matches the app (this PR). |
| 4 | detekt's top-level constant pattern rejected Kotlin's own convention (`SCREAMING_SNAKE_CASE`), so 172 constants sat in the baseline and every new one had to be added there by hand. | The pattern accepts it; the 172 entries are gone (this PR). |
| 5 | Count strings were not plurals: "1 minutes ago", "Ran 1 command(s)", "1 chats deleted", and their Russian and Arabic forms were wrong for most numbers. | **Pending**: plurals in all 8 locales (follow-up PR). |
| 6 | 151 resources (mostly strings, each translated 8 times) are no longer used. | **Pending**: removed (follow-up PR). |
| 7 | Only Claude Code had a shortcut to its project instructions file; Codex and OpenCode, which read `AGENTS.md`, had none (`docs/FEATURES.md`, "Not done yet"). | The same shortcut for both; a new `AGENTS.md` starts as a copy of `CLAUDE.md` (#127). |
| 8 | Spanish, French, Brazilian Portuguese, Russian, Chinese and Arabic still show English for strings added since they were last translated: 184 to 230 per language match the English text, about 165 of them after names and terms that stay the same (GitHub, MCP, Claude Code). Japanese is up to date. | **Pending**: translated (follow-up PR). |
| 9 | The OpenCode client followed a redirect from https to http, which would resend a prompt (with code in it) in the clear to an address nobody checked. The context sheet could show over 100% for Codex. `docs/FEATURES.md` still said only Claude Code had the context sheet, and listed neither suggested MCP servers nor that Checks apply to every project. | Redirects between https and http are refused for OpenCode servers; the percentage stops at 100; the rows are updated (this PR). |

## Left on purpose

- **Kotlin language and API version 2.0** (the compiler is 2.4). The compiler warns 2.0 is deprecated,
  but it is pinned because this Android Gradle plugin's lint and R8 cannot read newer metadata
  (`app/build.gradle.kts`).
- **`window.statusBarColor`.** Deprecated, and Android 15 ignores it now that the app targets SDK
  35, but older Android versions still use it. Calling `enableEdgeToEdge()` everywhere would draw
  content under the bars on screens that do not pad for them yet; that needs a device to check.
- **18 outdated dependencies** (lint's `GradleDependency`). Updating them is its own change, with
  its own risk, and is not part of a checkup.
- **Large files.** `ChatViewModel.kt` is about 4,000 lines and `OpenCodeV2ApiClient.kt` 1,700. Both
  are covered by many tests; splitting them is a refactor with no change for the user, and is left
  until a change needs it.
- **The other 498 detekt baseline entries** (long methods, long parameter lists, complexity). They
  shrink as the code they point at is changed.
- **`UsableSpace`** (11): the free-space checks before an install are estimates, and
  `StorageManager.getAllocatableBytes` would only make them less strict.
- **Startup on the main thread.** `AndCodeApplication` reads the app language from encrypted
  preferences before the first screen (`attachBaseContext`), then opens the encrypted settings and
  decodes the schedules and their run history in `onCreate`, all on the main thread. The first screen
  needs the language and settings, so moving them off it means a loading state; whether that is
  worth it needs a cold-start measurement on a device.
- **`AppBundleLocaleChanges`**: the app is not shipped as an app bundle split by language.
- **Antigravity's instructions file.** Its project-level file is not confirmed (`agy` reads its
  global `~/.gemini/GEMINI.md`), so no shortcut is offered for it yet.
