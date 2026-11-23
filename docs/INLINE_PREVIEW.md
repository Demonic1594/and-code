# Inline chat preview

AndCode can show a web page inside the conversation — either a loopback dev server or a static
site from the workspace — as a live, interactive card. Previews are always loopback
(`localhost`, `127.0.0.1`, `::1`) or the workspace's static site: a public page is never embedded in
a conversation, and the agent opens one in the full-screen Guest Browser (`browser_show`) instead.

## Three states

| State | What runs | How it looks |
| --- | --- | --- |
| `Active` | One WebView + live reload | Full card above the composer |
| `Collapsed` | Nothing (WebView destroyed, watcher paused) | Floating **Preview** button above the composer |
| `None` | Nothing | A **Preview** chip above the composer if a target is known |

- **Minimize** tears the WebView down and leaves the floating button (cheap when not watching).
- **Restore** (tap the button) loads the current files fresh, so recent edits are already visible.
- **Expand** opens the full-screen Guest Browser for a dev server, or a full-screen overlay for a
  static site.
- **Refresh** reloads manually.
- **Cancel** removes the preview; the **Preview** chip can restart it from the session's known
  targets without asking the agent again.

## How a preview appears

1. **Agent request** — the in-guest `browser_preview` MCP tool writes the browser command file in
   the guest home (`/root/.local/share/and-code/browser-command.json`, never in the project, so a
   cloned repository cannot carry one) with `{"action":"preview","url":"http://127.0.0.1:5173/"}`
   and the time it was written; the app drops a command more than five minutes old.
2. **Loopback link in a reply** — a bare `http://localhost:PORT` / `http://127.0.0.1:PORT` link
   renders a small **Preview** chip.
3. **Static site** — when the workspace has a shallow `index.html`, the **Preview** chip serves it
   directly, no dev server or npm needed.

`browser_show` keeps its existing meaning: it opens the full-screen Guest Browser.

## Human handoff and file upload

The card is for the user to look at: the agent's browser tools drive the full-screen Guest Browser
only. An agent that needs a step only the user can do (signing in, approving a prompt, a captcha)
opens the page there with `browser_show` and asks for it with `browser_handoff`. The app records
`awaiting_user` in `/root/.local/share/and-code/browser-status.json` in the guest home (written
without following a link the guest planted) and shows **"You have control"** over the Guest
Browser's page; the agent waits until the user taps **Hand back to agent** (or **Cancel**, which
writes `dismissed`). Each handoff carries a request ID that the status repeats, so an agent reads
only the answer to its own request; one that a newer handoff replaced before the user answered is
told so. The app records `awaiting_user` before it removes the command, and a late answer to a
replaced handoff never overwrites the newer one's status, so the tool can tell a newer handoff from
one the app is still getting to. If the answer cannot be written, the banner stays so the user can
tap again. A handoff
never carries a URL, so it cannot reload the sign-in the user just completed. `browser_type`
refuses a password field it can see.

`browser_upload(path)` attaches a file from device storage (`/sdcard`, `/storage`) to the page's
file input with CDP `DOM.setFileInputFiles`, so the Android file picker never opens. It is best
effort: only an input reachable from `document.querySelector` is found, and `click_selector` can
open a site button that creates one lazily.

## Live reload

While a preview is `Active` and the app is foregrounded, the workspace is polled for changes to
web-facing files (html/css/js/…), debounced so one burst of writes causes one reload. Vendored and
generated directories (`node_modules`, `.git`, `build`, `dist`, …) are skipped, and the scan is
capped. Collapsing or backgrounding cancels the watcher, so a hidden preview does no work; the
fingerprint of what the page shows is kept, so changes made meanwhile reload it when the app is back.
A watch that starts again before the last one recorded anything reloads the page too.
Remote (PC) runtimes are not watched from the phone; their dev server's own hot reload applies.

## Static serving

`StaticAssetInterceptor` serves workspace files to the WebView under the virtual host
`https://andcode.preview/…` through `shouldInterceptRequest`:

- request paths with `..` are refused, and every file is read through `GuestFiles`, so a link the
  guest put in the workspace is never followed (to one of the app's own files, say),
- MIME types for html/css/js/images/fonts/wasm,
- `index.html` and single-page-app fallback,
- `Cache-Control: no-store`, so a reload always sees the latest files,
- no server process and no open port.

## Implementation

- `feature/browser/PreviewTarget.kt` — `PreviewTarget` (Url/Static), `PreviewUiState`, entry scan.
- `feature/browser/PreviewUrl.kt` / `PreviewUrls.kt` — loopback gate and URL extraction.
- `feature/browser/StaticAssetInterceptor.kt` — virtual-host static file serving.
- `feature/browser/WorkspaceChangeWatcher.kt` — debounced change fingerprint.
- `feature/browser/GuestWebContent.kt` — the card's WebView, with the Guest Browser's settings;
  accepts the interceptor and reports its instance so the watcher can reload it. Destroyed when it
  leaves composition; survives its renderer being ended (`WebPageRecovery`).
- `feature/browser/BrowserOpenCommand.kt` — `Open`/`Preview`/`Handoff` commands and workspace resolution.
- `feature/chat/ChatPreviewCard.kt` — card controls, floating restore button, reopen chip.
- `feature/chat/PreviewTargets.kt` — loopback targets recovered from the transcript.
- `ChatHomeScreen` / `AndCodeApp` — render the states and own the preview target.

## Security and lifecycle

- Previews are loopback-only (or the workspace's static site), also after the page navigates: a link,
  script or redirect to any other page opens it in the Guest Browser instead of in the card, and a
  remote runtime's replies offer no preview (`127.0.0.1` is the phone, not that machine). No
  `addJavascriptInterface`, no mixed
  content, file/content access left off. Static serving is limited to the workspace directory and
  never follows a link.
- One WebView at a time; minimized and cancelled previews destroy it.
- The card never turns WebView debugging on. The agent's CDP tools reach the Guest Browser while it
  is on screen with **Let agents control this browser** on (its ⋮ menu); the card is not theirs to
  drive.
