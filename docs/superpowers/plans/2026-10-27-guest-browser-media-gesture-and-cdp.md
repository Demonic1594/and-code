# Guest Browser: a page's tap can't unmute cross-origin media, and the agent's CDP tools are debug-build only

Status: **built** (imported from PR #34). Decisions made while building:

- Sound: `mediaPlaybackRequiresUserGesture = false`, unconditionally (Task 1's first option).
- CDP: the toggle variant, as a check item **Let agents control this browser** in the Guest
  Browser's menu, default on. The DevTools socket exists only while the Guest Browser is on screen
  with the item on, and goes back to the build's default (on in debug builds only) when it closes.
- A second cause turned up: the MCP script parsed the names in `/proc/net/unix` as hex, while Linux
  prints an abstract socket's name as text after an `@`, so it never found the socket even on
  debug builds where it existed; Android may also hide that file from apps. The script now reads
  the text form and falls back to trying `webview_devtools_remote_<pid>` for each process it can
  see (an app sees only its own). It also picks the page DevTools reports as visible, not the
  first WebView listed, since the app has others (artifact previews).
- Errors say what is wrong: the browser is closed or agent control is off, or no page is loaded.
- Tests: `BrowserMcpScriptTest` runs the script's discovery and target choice under python3.

## Symptom

Two findings from one agent session (2026-10-27, release build of the app, developing a feed of
YouTube Shorts in the Guest Browser against a localhost dev server):

1. **Sound.** A page whose video plays through a cross-origin embed (YouTube IFrame API) autoplays
   muted, as browsers require. The page's own "Tap for sound" button calls the player's `unMute()` —
   and instead of sound, **the video pauses**. The same URL opened in Chrome on the same device:
   sound comes on. (The web app's own pause-retry logic turned the refused unmute into a frozen
   reel until the app was taught to fall back to muted playback — a workaround the app should not
   need.)

2. **Agent browser tools.** The in-guest agent's `browser_*` tools (`browser_status`,
   `browser_info`, `browser_screenshot`, …) all fail with

   ```
   not connected: no WebView devtools socket; call browser_show first
   ```

   on a release build, with the Guest Browser open and showing a page. They work on debug builds.

## Cause

**Sound — read, with the mechanism confirmed from the outside.**
`GuestBrowserScreen.kt`'s `WebView.configure()` never touches `mediaPlaybackRequiresUserGesture`,
so it keeps the framework default, `true`. Under that setting Chromium enforces the **per-frame**
"user gesture required" rule: a tap on the page produces user activation in the top frame only, and
a cross-origin iframe — even one granted `allow="autoplay"` by the embed — receives none of it, so
the embed's video element may not play unmuted. Chrome the browser instead lets a delegated
`allow="autoplay"` iframe follow the **top frame's** activation, which is why the identical page
works there. YouTube's player, asked to unmute when it isn't allowed, pauses playback — that pause
is what the page sees. Muted autoplay and `speechSynthesis` are not gated and worked in the Guest
Browser throughout, which is why only sound was affected.

**CDP — one line.** `GuestBrowserScreen.kt`:

```kotlin
// Lets the in-guest agent drive this WebView over CDP (the devtools abstract domain socket).
WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
```

The comment states the intent — the agent drives the WebView over the devtools socket — but on
release builds the call is compiled out, so the socket never exists and every agent browser tool
fails. The in-app DevTools panel is unaffected: it talks through the WebMessage bridge
(`DevToolsBridge.kt`), not CDP.

## The fix

**Task 1 — let the Guest Browser play sound like a browser**
- In `configure()`: `settings.mediaPlaybackRequiresUserGesture = false`. The Guest Browser is a
  developer browser; pages under development expect Chrome's behavior, and Chrome's practical rule
  for a delegated `allow="autoplay"` iframe after any interaction with the page is "sound allowed".
  This flag is the only public knob — there is no WebView API for Chrome's exact
  delegated-activation mode — and its tradeoff (pages may autoplay with sound at all) is the
  expected default for a test browser.
- If that is judged too broad: a "Media autoplay with sound" toggle in the browser settings,
  default **on** ("matches Chrome"), off restores today's behavior.
- `GuestBrowserScreenInstrumentedTest.kt`: assert `settings.mediaPlaybackRequiresUserGesture ==
  false` after `configure()` (the desktop-mode test next to it shows the pattern).

**Task 2 — make the agent's CDP tools work on release builds**
- Enable the devtools socket on release too. Either:
  - unconditional `WebView.setWebContentsDebuggingEnabled(true)` — the agent-driving-the-browser
    feature is the point of the Guest Browser, and it is not a general-purpose browser for
    sensitive pages; or
  - a settings toggle "Agent browser control", default on, for an explicit off switch.
- To record in the PR: with the socket on, anything that can reach the device's abstract sockets —
  adb over USB, or another app on the device, since abstract sockets carry no permission check —
  can inspect that WebView's pages. The toggle variant keeps an escape hatch.
- Small bonus: when the socket is absent, the agent-side error should say "this build doesn't
  expose the browser to the agent" rather than "call browser_show first", which sends the user
  looking for a browser that is already open.

**Task 3 — checks**
- Per repo convention: `./gradlew testGithubDebugUnitTest lintGithubDebug assembleGithubDebug`,
  detekt and spotless, i18n parity for any new strings, and the pre-PR review.
- On the device: a page with a YouTube IFrame embed (or any `allow="autoplay"` cross-origin video)
  in the Guest Browser — the page's unmute control gives sound; then an agent session's
  `browser_screenshot` returns an image on a release build.

## Other Guest Browser notes from the same session (no change proposed)

- The in-app DevTools panel — console, network, element inspection through the WebMessage bridge —
  worked throughout; only the agent's CDP path was missing.
- The default viewport is short enough to trip pages' compact media queries (a `max-height:
  700px` rule fired, switching the page to its small-screen layout). Not a bug; worth knowing when
  checking responsive layouts, and desktop mode already covers the wide case.
- Muted autoplay and `speechSynthesis` (TTS read-aloud) both work — only unmuted media is gated.

## Out of scope

- How pages themselves handle a refused unmute — the web app's muted fallback is the page's
  business, not the browser's.
