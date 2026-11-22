#!/usr/bin/env python3
"""AndCode guest-browser MCP server (stdlib only).

Bridges the agent to the in-app Guest Browser of the AndCode Android app:

- ``browser_show`` asks the app (via a command file in the guest home, never in
  a project, so a cloned repository cannot carry one) to open the guest browser
  so the user can watch and operate the page; ``browser_preview`` pins a page on
  this device inside the chat instead, and ``browser_handoff`` hands the open
  page to the user and waits for it back (through a status file beside the
  command, which only the app writes).
- The remaining tools drive the same WebView over CDP. While the Guest Browser is
  open with "Let agents control this browser" on, the app enables WebView
  debugging, which exposes ``webview_devtools_remote_<pid>`` as a Linux abstract
  socket; the guest shares the kernel (and the app UID), so it can attach.

Speaks MCP over stdio (newline-delimited JSON-RPC) with no third-party deps so
it runs on the bare runtime rootfs python3.
"""

import base64
import json
import math
import os
import socket
import struct
import sys
import time
import urllib.parse
import uuid
from pathlib import Path
from urllib.parse import urlparse

# In the guest home, outside every workspace: a command file in the project could come with a cloned
# or pulled repository. The app reads it from here (BROWSER_COMMAND_PATH in BrowserOpenCommand.kt).
COMMAND_FILE = Path("/root/.local/share/and-code/browser-command.json")
# How a handoff stands, written by the app (BROWSER_STATUS_PATH in BrowserOpenCommand.kt).
STATUS_FILE = Path("/root/.local/share/and-code/browser-status.json")
# Logged to the page console before each action; the app shows "An agent is controlling this page".
AGENT_ACTIVITY_MARKER = "andcode:agent-control"


DEVTOOLS_PREFIX = "webview_devtools_remote_"

NOT_REACHABLE = (
    "The Guest Browser is not reachable: it is closed, or 'Let agents control this browser' is off "
    "in its menu. Call browser_show with a URL to open it."
)
NO_PAGE = (
    "The Guest Browser has no page to drive. If it was showing one, that page stopped responding and the "
    "browser is waiting for the user to tap Reload; otherwise call browser_show with a URL."
)
# Android ended the renderer under the page (to free memory, or the page crashed it). The app takes the
# dead WebView away, which ends every session on it: nothing on that page will answer again.
PAGE_GONE = (
    "The Guest Browser's page stopped responding: Android ended the process that ran it, often to free "
    "memory, so this action did not finish. The browser now offers the user a Reload button; once the "
    "page is back, call browser_read again."
)
PAGE_CLOSED = (
    "The Guest Browser's page went away before answering: it stopped responding, or the browser was "
    "closed. Call browser_status to see what is open now."
)
AMBIGUOUS = (
    "More than one of the app's web pages could be the Guest Browser (another one may be closing). "
    "Wait a moment and retry, or close the other page."
)


def socket_names_from_proc_net_unix(text: str) -> list[str]:
    """WebView DevTools socket names listed in /proc/net/unix, in order.

    Linux prints an abstract socket's name as text after an ``@``; hex-encoded names are accepted
    too, as some kernels and tools print them that way.
    """
    names: list[str] = []
    for line in text.splitlines()[1:]:
        parts = line.split()
        if len(parts) < 8:
            continue
        path = parts[-1]
        if path.startswith("@"):
            name = path[1:]
        else:
            try:
                name = bytes.fromhex(path).decode("ascii")
            except (ValueError, UnicodeDecodeError):
                continue
        if name.startswith(DEVTOOLS_PREFIX) and name not in names:
            names.append(name)
    return names


def _can_connect(name: str) -> bool:
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(1)
    try:
        sock.connect("\0" + name)
        return True
    except OSError:
        return False
    finally:
        sock.close()


def _visible_pids() -> list[str]:
    try:
        return sorted((entry for entry in os.listdir("/proc") if entry.isdigit()), key=int)
    except OSError:
        return []


def _owned_by_me(pid: str) -> bool:
    try:
        return os.stat(f"/proc/{pid}").st_uid == os.getuid()
    except (OSError, ValueError):
        return False


def _find_devtools_socket() -> str | None:
    try:
        with open("/proc/net/unix", encoding="ascii", errors="replace") as fh:
            listed = socket_names_from_proc_net_unix(fh.read())
    except OSError:
        listed = []
    # Other apps' WebViews may be listed too; this app's own processes come first.
    for name in sorted(listed, key=lambda n: not _owned_by_me(n[len(DEVTOOLS_PREFIX):])):
        if _can_connect(name):
            return name
    # Android hides /proc/net/unix from apps. The socket is named after the app's process id, and
    # an app (this guest included) sees only its own processes in /proc, so try each of those.
    for pid in _visible_pids():
        name = DEVTOOLS_PREFIX + pid
        if name not in listed and _can_connect(name):
            return name
    return None


def choose_target(targets: list[dict]) -> dict | None:
    """The Guest Browser's page among the app's WebView targets.

    The app has other WebViews (an artifact preview, say). DevTools marks the page on screen as
    attached and visible, which is the browser. Two pages on screen at once (one fading out while
    the other opens), or several with none on screen, cannot be told apart: raise rather than drive
    the wrong one, and the agent can retry.
    """
    pages = [t for t in targets if t.get("type") in ("document", "page")]
    if not pages:
        return None
    on_screen = [page for page in pages if _on_screen(page)]
    if len(on_screen) == 1:
        return on_screen[0]
    if not on_screen and len(pages) == 1:
        return pages[0]
    raise CdpError(AMBIGUOUS)


def _on_screen(page: dict) -> bool:
    try:
        description = json.loads(page.get("description") or "{}")
    except (ValueError, TypeError):
        return False
    return isinstance(description, dict) and bool(description.get("attached") and description.get("visible"))


class CdpError(RuntimeError):
    pass


class PageGone(CdpError):
    """The page went away mid-session: its renderer died, or its WebView was destroyed."""


class _WsClosed(CdpError):
    """The other end closed the WebSocket."""


class _UnixWs:
    """Minimal WebSocket client (text frames) over an AF_UNIX abstract socket."""

    def __init__(self, sock: socket.socket, host: str, path: str):
        self.sock = sock
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        sock.sendall(req.encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = sock.recv(4096)
            if not chunk:
                raise CdpError("socket closed during ws handshake")
            resp += chunk
        if b"101" not in resp.split(b"\r\n")[0]:
            raise CdpError(f"ws handshake failed: {resp[:120]!r}")

    def send_text(self, text: str) -> None:
        payload = text.encode()
        mask = os.urandom(4)
        header = b"\x81"
        n = len(payload)
        if n < 126:
            header += bytes([0x80 | n])
        elif n < 65536:
            header += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            header += bytes([0x80 | 127]) + struct.pack(">Q", n)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def _recv_exact(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise _WsClosed("socket closed")
            buf += chunk
        return buf

    def recv_text(self) -> str:
        while True:
            b1, b2 = self._recv_exact(2)
            opcode = b1 & 0x0F
            masked = b2 & 0x80
            n = b2 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._recv_exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._recv_exact(8))[0]
            mask = self._recv_exact(4) if masked else b""
            payload = self._recv_exact(n)
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == 0x1:
                return payload.decode()
            if opcode == 0x8:
                raise _WsClosed("server closed ws")
            if opcode == 0x9:
                self._send_pong(payload)

    def _send_pong(self, payload: bytes) -> None:
        mask = os.urandom(4)
        header = b"\x8a" + bytes([0x80 | len(payload)])
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


def _read_http_body(sock: socket.socket) -> bytes:
    """Reads one HTTP response's body off [sock], framed by Content-Length.

    The previous reader stopped at a trailing ``]``, but Chrome ends its /json bodies with a
    newline, so the sentinel never arrived and the read blocked on the kept-alive connection
    until the socket timed out - every CDP tool then failed on such WebViews.
    """
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(65536)
        if not chunk:
            return data
        data += chunk
    head, _, body = data.partition(b"\r\n\r\n")
    length = None
    for line in head.split(b"\r\n"):
        if line.lower().startswith(b"content-length:"):
            try:
                length = int(line.split(b":", 1)[1].strip())
            except ValueError:
                pass
    if length is None:
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                break
            body += chunk
        return body
    while len(body) < length:
        chunk = sock.recv(65536)
        if not chunk:
            break
        body += chunk
    return body[:length]


def _dechunk(body: bytes) -> bytes:
    """A chunked response's payload, or [body] unchanged when it is not chunked."""
    if b"\r\n0\r\n\r\n" not in body:
        return body
    chunks = []
    rest = body
    while rest:
        size_end = rest.find(b"\r\n")
        if size_end < 0:
            break
        size = int(rest[:size_end], 16)
        if size == 0:
            break
        chunks.append(rest[size_end + 2 : size_end + 2 + size])
        rest = rest[size_end + 2 + size + 2 :]
    return b"".join(chunks)


class CdpSession:
    def __init__(self, socket_name: str):
        self.socket_name = socket_name

    def _connect(self) -> socket.socket:
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(10)
        sock.connect("\0" + self.socket_name)
        return sock

    def list_targets(self) -> list[dict]:
        sock = self._connect()
        try:
            sock.sendall(b"GET /json HTTP/1.1\r\nHost: localhost\r\n\r\n")
            body = _read_http_body(sock)
        finally:
            sock.close()
        return json.loads(_dechunk(body))

    def open_page_session(self) -> "_PageSession":
        target = choose_target(self.list_targets())
        if target is None:
            raise CdpError(NO_PAGE)
        ws_url = target.get("webSocketDebuggerUrl")
        if not ws_url:
            raise CdpError("The Guest Browser's page is already being inspected; close other DevTools and retry.")
        parsed = urlparse(ws_url)
        ws = _UnixWs(self._connect(), parsed.netloc or "localhost", parsed.path or "/")
        page = _PageSession(ws)
        try:
            page.announce()
        except BaseException:
            # A page gone already, or a broken socket: the tool fails, and the socket is not left open.
            page.close()
            raise
        return page


class _PageSession:
    def __init__(self, ws: _UnixWs):
        self.ws = ws
        self._next_id = 0

    def call(self, method: str, **params) -> dict:
        # A page whose renderer died never answers: say so at once rather than wait out the timeout.
        try:
            # Each call gets its own id, so a late answer to an earlier call is never taken for this one's.
            self._next_id += 1
            request_id = self._next_id
            self.ws.send_text(json.dumps({"id": request_id, "method": method, "params": params}))
            while True:
                msg = json.loads(self.ws.recv_text())
                if msg.get("method") == "Inspector.targetCrashed":
                    raise PageGone(PAGE_GONE)
                if msg.get("id") == request_id:
                    if "error" in msg:
                        raise CdpError(f"{method}: {msg['error']}")
                    return msg.get("result", {})
        except (_WsClosed, BrokenPipeError, ConnectionResetError) as closed:
            raise PageGone(PAGE_CLOSED) from closed

    def announce(self) -> None:
        """Tells the Guest Browser an agent is acting on the page, so it can say so on screen."""
        # Only a refusal (no page context mid-navigation, say) is ignored: that reply was read in
        # full. A socket error or timeout leaves the stream mid-reply, so it fails the tool as before,
        # and so does a page that went away, whose next call would otherwise wait for an answer.
        try:
            self.call("Runtime.evaluate", expression=f"console.debug({json.dumps(AGENT_ACTIVITY_MARKER)})")
        except PageGone:
            raise
        except CdpError:
            pass

    def close(self) -> None:
        self.ws.close()


def _session() -> CdpSession:
    name = _find_devtools_socket()
    if name is None:
        raise CdpError(NOT_REACHABLE)
    return CdpSession(name)


# Lists what a person could act on, tagging each element with a numeric handle (data-andcode-ref) that
# stays the same across reads while the element stays in the page.
READ_JS = r"""(() => {
  const limit = %d;
  const state = window.__andcodeRefs || (window.__andcodeRefs = { next: 1 });
  const selector = [
    'a[href]', 'button', 'input', 'select', 'textarea', 'summary',
    '[role=button]', '[role=link]', '[role=checkbox]', '[role=radio]', '[role=tab]',
    '[role=menuitem]', '[role=switch]', '[role=option]', '[onclick]', '[contenteditable=""]',
    '[contenteditable=true]'
  ].join(',');
  const shown = (el) => {
    const rect = el.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return false;
    const style = getComputedStyle(el);
    return style.visibility !== 'hidden' && style.display !== 'none';
  };
  const clip = (text) => String(text || '').replace(/\s+/g, ' ').trim().slice(0, 80);
  // A field's own value never names it (that is what value= is for, and a password must not leak);
  // only buttons made of an <input> are named by their value.
  const valueNames = (type) => type === 'submit' || type === 'button' || type === 'reset';
  const nameOf = (el, type) =>
    clip(el.getAttribute('aria-label') || el.labels && el.labels[0] && el.labels[0].innerText ||
      el.innerText || el.placeholder || el.alt || el.title || (valueNames(type) ? el.value : ''));
  const out = [];
  const seen = new Set();
  let total = 0;
  for (const el of document.querySelectorAll(selector)) {
    if (!shown(el)) continue;
    total++;
    if (out.length >= limit) continue;
    let ref = el.getAttribute('data-andcode-ref');
    // A cloned element carries its original's handle; give it its own.
    if (!ref || seen.has(ref)) { ref = String(state.next++); el.setAttribute('data-andcode-ref', ref); }
    seen.add(ref);
    const tag = el.tagName.toLowerCase();
    const type = tag === 'input' ? (el.type || 'text') : '';
    const rect = el.getBoundingClientRect();
    out.push({
      ref: Number(ref),
      tag: tag,
      role: el.getAttribute('role') || '',
      type: type,
      name: nameOf(el, type),
      value: type === 'password' ? '' : clip(el.value),
      href: tag === 'a' ? clip(el.getAttribute('href')) : '',
      checked: el.checked === true,
      disabled: el.disabled === true,
      x: Math.round(rect.left + rect.width / 2),
      y: Math.round(rect.top + rect.height / 2)
    });
  }
  const headings = Array.from(document.querySelectorAll('h1,h2,h3')).filter(shown).slice(0, 8).map((h) => clip(h.innerText));
  return JSON.stringify({ url: location.href, title: document.title, headings: headings, total: total, elements: out });
})()"""

READ_LIMIT = 150


def format_outline(data: dict) -> str:
    """One line per element: [ref] kind "name" and what matters for acting on it."""
    lines = [f"{data.get('title', '')} - {data.get('url', '')}"]
    for heading in data.get("headings", []):
        lines.append(f"# {heading}")
    for el in data.get("elements", []):
        kind = el.get("role") or el.get("tag", "")
        if el.get("type"):
            kind += f"({el['type']})"
        line = f"[{el['ref']}] {kind}"
        if el.get("name"):
            line += f' "{el["name"]}"'
        if el.get("value"):
            line += f' value="{el["value"]}"'
        if el.get("href"):
            line += f" -> {el['href']}"
        if el.get("checked"):
            line += " (checked)"
        if el.get("disabled"):
            line += " (disabled)"
        lines.append(line)
    shown = len(data.get("elements", []))
    total = data.get("total", shown)
    if total > shown:
        lines.append(f"... {total - shown} more not shown; scroll or navigate to narrow it down")
    return "\n".join(lines)


BAD_REF = "ref must be the whole-number handle shown in [brackets] by browser_read"


def _handle(ref: object) -> int:
    if isinstance(ref, bool):
        raise ValueError(BAD_REF)
    try:
        number = int(ref)
    except (TypeError, ValueError, OverflowError):
        raise ValueError(BAD_REF) from None
    if isinstance(ref, float) and ref != number:
        raise ValueError(BAD_REF)
    return number


def _evaluate(page: "_PageSession", expression: str) -> object:
    """Runs [expression] in the page and returns its value; a script that threw is an error, not a blank."""
    result = page.call("Runtime.evaluate", expression=expression, returnByValue=True)
    details = result.get("exceptionDetails")
    if details:
        exception = details.get("exception", {})
        reason = exception.get("description") or details.get("text") or "unknown error"
        raise CdpError(f"the page script failed: {reason}")
    return result.get("result", {}).get("value")


def ref_expression(ref: object, action: str) -> str:
    """JavaScript that finds the element with handle [ref] and runs [action] on it (`el`)."""
    number = _handle(ref)
    return (
        "(() => { const el = document.querySelector('[data-andcode-ref=\"%d\"]'); "
        "if (!el) return 'stale'; el.scrollIntoView({block: 'center', inline: 'center'}); %s })()"
    ) % (number, action)


NOT_WEB_URL = "only http:// and https:// addresses can be opened in the Guest Browser"


def is_web_url(url) -> bool:
    """Whether [url] is an http(s) address with a host: never file:, content:, javascript: or data:."""
    if not isinstance(url, str):
        return False
    parts = urllib.parse.urlsplit(url.strip())
    return parts.scheme.lower() in ("http", "https") and bool(parts.hostname)


def _write_command(command: dict) -> None:
    """Leaves [command] for the app in the guest home, stamped with the time it was written."""
    COMMAND_FILE.parent.mkdir(parents=True, exist_ok=True)
    # The app acts only on a command stamped in the last few minutes, so one left from long before the
    # user came back is dropped rather than carried out.
    command = dict(command, createdAtMs=int(time.time() * 1000))
    # Written beside it and renamed over it, so the app never reads half a command.
    # One per server: each agent runs its own, and two could ask at once.
    partial = COMMAND_FILE.with_name(f"{COMMAND_FILE.name}.{os.getpid()}.partial")
    partial.write_text(json.dumps(command), encoding="utf-8")
    os.replace(partial, COMMAND_FILE)


def tool_show(args: dict) -> str:
    url = args.get("url")
    if not is_web_url(url):
        return NOT_WEB_URL
    url = url.strip()
    _write_command({"action": "open", "url": url})
    return f"requested the app to open {url}"


NOT_LOOPBACK = (
    "only a page on this device (http://localhost:PORT or http://127.0.0.1:PORT) can be previewed in the "
    "chat; open any other page with browser_show"
)


def is_loopback_url(url) -> bool:
    """Whether [url] is an http(s) page served on this device, the only kind the chat previews."""
    if not is_web_url(url):
        return False
    host = (urllib.parse.urlsplit(url.strip()).hostname or "").lower()
    return host in ("localhost", "127.0.0.1", "::1")


def tool_preview(args: dict) -> str:
    url = args.get("url")
    if not is_loopback_url(url):
        return NOT_LOOPBACK
    url = url.strip()
    _write_command({"action": "preview", "url": url})
    return f"requested the app to show {url} as an inline preview"


def _read_status() -> dict | None:
    """The handoff status the app last wrote: its "state" and the "requestId" it answers."""
    try:
        status = json.loads(STATUS_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return status if isinstance(status, dict) else None


def _command_waiting(request_id: str) -> bool:
    """Whether the command file still holds [request_id]: the app has not taken it, and no other request replaced it."""
    try:
        return json.loads(COMMAND_FILE.read_text(encoding="utf-8")).get("requestId") == request_id
    except (OSError, ValueError, AttributeError):
        return False


def _wait_for_socket(timeout: float) -> str | None:
    """The app builds the WebView just after browser_show returns, so give it a moment to appear."""
    deadline = time.monotonic() + timeout
    while True:
        name = _find_devtools_socket()
        if name is not None or time.monotonic() >= deadline:
            return name
        time.sleep(0.25)


HANDOFF_BACK = "the user handed the browser back; continue with the page as it is now"
HANDOFF_CANCELLED = "the user cancelled the handoff; do not continue with the page"
HANDOFF_SUPERSEDED = (
    "another handoff took the user's screen before they answered this one; do not continue with the page"
)
HANDOFF_POLL_S = 0.5
HANDOFF_TIMED_OUT = (
    "timed out waiting for the user to hand the browser back; ask them to finish and tap "
    '"Hand back to agent"'
)


def tool_handoff(args: dict) -> str:
    """Hand the open page to the user and block until they hand it back.

    This is the only way the agent can ask for something it must not do itself, signing in above
    all: the user operates the same visible WebView, then taps "Hand back to agent". The app and
    the agent share no memory, so the outcome travels through the status file.
    """
    # Stay below the MCP client's timeout (LocalRuntimeInstaller.BROWSER_MCP_TIMEOUT_MILLIS = 120 s)
    # so this tool's own timeout reply can reach the agent instead of the client killing the call.
    # Measured from here: the wait for the browser counts against it too.
    started = time.monotonic()
    timeout = max(1.0, min(float(args.get("timeout_s") or 90), 110.0))
    if _wait_for_socket(min(10.0, timeout)) is None:
        raise CdpError(NOT_REACHABLE)
    try:
        STATUS_FILE.unlink()
    except FileNotFoundError:
        pass
    except OSError as exc:
        # A leftover "agent" would make the next handoff return immediately, so never ignore this.
        raise CdpError(f"could not clear the previous handoff status: {exc}") from exc
    # The status names the handoff it answers: another agent's server shares the file, and its
    # answer must not end this call.
    request_id = uuid.uuid4().hex
    _write_command({"action": "handoff", "requestId": request_id})
    deadline = started + timeout
    shown = False
    while time.monotonic() < deadline:
        status = _read_status() or {}
        if status.get("requestId") == request_id:
            shown = True
            if status.get("state") == "agent":
                return HANDOFF_BACK
            if status.get("state") == "dismissed":
                return HANDOFF_CANCELLED
        elif status.get("requestId") and (shown or not _command_waiting(request_id)):
            # Another request's status once this one has left the command file: the app showed a newer
            # handoff, or a newer one replaced this command before the app took it. Either way the
            # user can no longer answer this one. While it still waits there, that status is an
            # earlier handoff's and is ignored.
            return HANDOFF_SUPERSEDED
        time.sleep(HANDOFF_POLL_S)
    return HANDOFF_TIMED_OUT


def tool_status(args: dict) -> str:
    name = _find_devtools_socket()
    if name is None:
        return f"not connected: {NOT_REACHABLE}"
    target = choose_target(CdpSession(name).list_targets())
    if target is None:
        return f"connected: {name} / {NO_PAGE}"
    return f"connected: {name} / page: {target.get('url', '')} ({target.get('title', '')})"


def tool_navigate(args: dict) -> str:
    # The page runs inside the app: a file: or content: address could show the agent files the app
    # can read, and a javascript: one would run in whatever page is open.
    if not is_web_url(args.get("url")):
        return NOT_WEB_URL
    page = _session().open_page_session()
    try:
        page.call("Page.navigate", url=args["url"])
        return f"navigated to {args['url']}"
    finally:
        page.close()


STALE_REF = "that handle is gone from the page; call browser_read again for fresh ones"
NO_TARGET = "pass a handle from browser_read, or both x and y"
NOT_EDITABLE = "that element does not take typed text; pass the handle of an enabled text field"
# Focuses `el` only if it takes typed text, and confirms it got focus: otherwise Input.insertText
# would type into whatever field had focus before.
FOCUS_EDITABLE_JS = (
    "const types = ['button', 'submit', 'reset', 'checkbox', 'radio', 'file', 'image', 'hidden', 'range', 'color']; "
    "const editable = el.isContentEditable || el.tagName === 'TEXTAREA' || "
    "(el.tagName === 'INPUT' && !types.includes(el.type)); "
    "if (!editable || el.disabled || el.readOnly) return 'not editable'; "
    "el.focus(); return document.activeElement === el ? 'ok' : 'not editable';"
)


def tool_read(args: dict) -> str:
    page = _session().open_page_session()
    try:
        return format_outline(json.loads(str(_evaluate(page, READ_JS % READ_LIMIT))))
    finally:
        page.close()


def tool_click(args: dict) -> str:
    if args.get("ref") is None and (args.get("x") is None or args.get("y") is None):
        return NO_TARGET
    page = _session().open_page_session()
    try:
        if args.get("ref") is not None:
            expr = ref_expression(args["ref"], "el.click(); return el.tagName;")
        else:
            expr = (
                "(() => { const el = document.elementFromPoint(%f, %f); "
                "if (!el) return 'no element'; el.click(); return el.tagName; })()"
                % (float(args["x"]), float(args["y"]))
            )
        value = _evaluate(page, expr)
        return STALE_REF if value == "stale" else f"clicked: {value}"
    finally:
        page.close()


PASSWORD_REFUSED = (
    "refused: that is a password field. Ask the user to type their own password, or call browser_handoff "
    "so they can."
)
# Whether the focused element takes a password: credentials are the user's to type.
FOCUSED_PASSWORD_JS = (
    "(() => { const el = document.activeElement; "
    "return !!el && (el.type === 'password' || "
    "el.autocomplete === 'current-password' || el.autocomplete === 'new-password'); })()"
)


def tool_type(args: dict) -> str:
    page = _session().open_page_session()
    try:
        if args.get("ref") is not None:
            state = _evaluate(page, ref_expression(args["ref"], FOCUS_EDITABLE_JS))
            if state == "stale":
                return STALE_REF
            if state != "ok":
                return NOT_EDITABLE
        else:
            page.call("Runtime.evaluate", expression="document.activeElement && document.activeElement.focus()")
        # A password field means the agent should have handed the page over instead of filling it in.
        if _evaluate(page, FOCUSED_PASSWORD_JS):
            return PASSWORD_REFUSED
        page.call("Input.insertText", text=args["text"])
        return "typed"
    finally:
        page.close()


def _find_file_input(page: "_PageSession", selector: str) -> str | None:
    page.call("DOM.enable")
    result = page.call(
        "Runtime.evaluate",
        expression=f"(() => document.querySelector({json.dumps(selector)}))()",
        returnByValue=False,
        objectGroup="upload",
    )
    obj = result.get("result", {})
    if "objectId" not in obj or obj.get("subtype") in ("null", "undefined"):
        return None
    return obj["objectId"]


UPLOAD_ROOTS = ("/sdcard", "/storage")


def upload_path(raw) -> str:
    """The real path of a device-storage file to attach, or a CdpError saying why it is not one."""
    if not isinstance(raw, str) or not raw.strip():
        raise CdpError("pass the path of a file under /sdcard or /storage")
    path = os.path.realpath(os.path.expanduser(raw.strip()))
    if not any(path == root or path.startswith(root + os.sep) for root in UPLOAD_ROOTS):
        raise CdpError("attaching is limited to device storage under /sdcard or /storage")
    if not os.path.isfile(path):
        raise CdpError(f"no such file: {path}")
    return path


def tool_upload(args: dict) -> str:
    """Attach a file from device storage to the page's file input without the Android picker.

    The WebView process reads the file itself, so the path must be one it can open: device storage
    under /sdcard or /storage, which holds no links. Best effort: only an input reachable from
    document.querySelector is found; pass click_selector for a picker the site creates lazily. The
    file chooser dialog is intercepted so clicking such a button does not open the native chooser.
    """
    path = upload_path(args.get("path"))
    selector = args.get("selector") or "input[type=file]"
    click_selector = args.get("click_selector")
    page = _session().open_page_session()
    try:
        try:
            page.call("Page.setInterceptFileChooserDialog", enabled=True)
        except PageGone:
            raise
        except CdpError:
            pass
        object_id = _find_file_input(page, selector)
        if object_id is None and click_selector:
            page.call(
                "Runtime.evaluate",
                expression=(
                    f"(() => {{ const el = document.querySelector({json.dumps(click_selector)}); "
                    "if (el) el.click(); return !!el; })()"
                ),
                returnByValue=True,
                userGesture=True,
            )
            time.sleep(0.5)
            object_id = _find_file_input(page, selector)
        if object_id is None:
            raise CdpError(
                "no file input found: pass the site's button as click_selector, or click the "
                "upload button yourself and retry with a selector that matches the hidden input"
            )
        page.call("DOM.setFileInputFiles", files=[path], objectId=object_id)
        return f"attached {os.path.basename(path)} to {selector}"
    finally:
        # The page is closed whatever the clean-up runs into: an open session holds the WebView's
        # only DevTools connection for this tool.
        try:
            page.call("Page.setInterceptFileChooserDialog", enabled=False)
        except (CdpError, OSError):
            pass
        finally:
            page.close()


# Reads the page agent's log (assets/devtools/andcode-devtools.js): console lines, uncaught errors,
# failed loads and failed requests, after a cursor. The page agent starts again after a navigation,
# so a cursor names the page it came from as well as the position ("<page>:<position>").
CONSOLE_JS = "(() => { const d = window.__andcodeDT; return d && d.agentLog ? d.agentLog(%d, %s) : null; })()"
CONSOLE_LIMIT = 100
CONSOLE_LINE_LIMIT = 400
NO_AGENT_LOG = (
    "this page has no log to read: it is an error page or a file the browser shows itself (a PDF, "
    "an image), or it has not started loading"
)
BAD_SINCE = "since must be the next value from an earlier browser_console reply"


def parse_since(raw: object) -> tuple[str | None, int] | None:
    """(page, position) from a cursor like "k3x9:40", or a bare position; None when it is neither."""
    if raw is None or raw == "":
        return None, 0
    if isinstance(raw, bool):
        return None
    if isinstance(raw, (int, float)):
        if not math.isfinite(raw) or raw != int(raw) or raw < 0:
            return None
        return None, int(raw)
    if isinstance(raw, str):
        page, _, position = raw.strip().rpartition(":")
        if not (position.isascii() and position.isdigit()):
            return None
        return (page or None), int(position)
    return None


def _clip_line(text: str) -> str:
    return text if len(text) <= CONSOLE_LINE_LIMIT else text[:CONSOLE_LINE_LIMIT] + f"... ({len(text)} chars)"


def format_console(data: dict, since: int, limit: int = CONSOLE_LIMIT) -> str:
    """One line per entry, newest last, then the cursor to pass as since next time."""
    entries = data.get("entries") or []
    lines = [str(data.get("url", ""))]
    if data.get("reset"):
        lines.append("the page changed since that cursor (a navigation or reload); this is the new page's log")
    if data.get("dropped"):
        lines.append(f"... {data['dropped']} older entries were dropped to keep the log short")
    hidden = len(entries) - limit
    if hidden > 0:
        lines.append(f"... {hidden} earlier {'entry is' if hidden == 1 else 'entries are'} not shown")
    for entry in entries[-limit:]:
        line = f"[{entry.get('kind', '')}] {entry.get('text', '')}"
        if entry.get("source"):
            line += f" ({entry['source']})"
        lines.append(_clip_line(line))
        stack = [frame.strip() for frame in str(entry.get("stack") or "").splitlines()[1:4] if frame.strip()]
        lines.extend(f"    {_clip_line(frame)}" for frame in stack)
    if not entries:
        fresh = since and not data.get("reset")
        lines.append("no console messages, errors or failed requests" + (" since the last read" if fresh else ""))
    cursor = f"{data.get('doc', '')}:{data.get('next', since)}"
    lines.append(f"next: {cursor} (pass since={cursor} to read only what comes after this)")
    return "\n".join(lines)


def tool_console(args: dict) -> str:
    cursor = parse_since(args.get("since"))
    if cursor is None:
        return BAD_SINCE
    page_id, since = cursor
    page = _session().open_page_session()
    try:
        value = _evaluate(page, CONSOLE_JS % (since, json.dumps(page_id)))
    finally:
        page.close()
    if value is None:
        return NO_AGENT_LOG
    data = json.loads(str(value))
    return format_console(data, 0 if data.get("reset") else since)


# Keys by the name the tool takes: (DOM key, DOM code, Windows virtual key code, text it types).
NAMED_KEYS = {
    "enter": ("Enter", "Enter", 13, "\r"),
    "tab": ("Tab", "Tab", 9, None),
    "escape": ("Escape", "Escape", 27, None),
    "backspace": ("Backspace", "Backspace", 8, None),
    "delete": ("Delete", "Delete", 46, None),
    "space": (" ", "Space", 32, " "),
    "arrowup": ("ArrowUp", "ArrowUp", 38, None),
    "arrowdown": ("ArrowDown", "ArrowDown", 40, None),
    "arrowleft": ("ArrowLeft", "ArrowLeft", 37, None),
    "arrowright": ("ArrowRight", "ArrowRight", 39, None),
    "home": ("Home", "Home", 36, None),
    "end": ("End", "End", 35, None),
    "pageup": ("PageUp", "PageUp", 33, None),
    "pagedown": ("PageDown", "PageDown", 34, None),
}
# CDP's modifier bits.
MODIFIER_BITS = {"alt": 1, "ctrl": 2, "control": 2, "meta": 4, "cmd": 4, "shift": 8}
BAD_KEY = (
    "key must be a key name (Enter, Tab, Escape, Backspace, Delete, Space, ArrowUp, ArrowDown, "
    "ArrowLeft, ArrowRight, Home, End, PageUp, PageDown) or one letter or digit, optionally after "
    "modifiers joined with +, such as ctrl+a or shift+Tab"
)


def key_event(spec: object) -> dict | None:
    """The Input.dispatchKeyEvent fields for a key like "Enter" or "ctrl+a"; None when it is not one."""
    if not isinstance(spec, str) or not spec.strip():
        return None
    parts = spec.strip().split("+")
    name, modifiers = parts[-1], parts[:-1]
    bits = 0
    for modifier in modifiers:
        bit = MODIFIER_BITS.get(modifier.strip().lower())
        if bit is None:
            return None
        bits |= bit
    if name.lower() in NAMED_KEYS:
        key, code, vk, text = NAMED_KEYS[name.lower()]
    elif len(name) == 1 and name.isascii() and name.isalnum():
        upper = name.upper()
        key = upper if bits & MODIFIER_BITS["shift"] else name.lower()
        code = f"Key{upper}" if name.isalpha() else f"Digit{name}"
        vk, text = ord(upper), key
    else:
        return None
    # A shortcut (ctrl+a) types nothing; only a plain or shifted key does.
    if bits & (MODIFIER_BITS["ctrl"] | MODIFIER_BITS["alt"] | MODIFIER_BITS["meta"]):
        text = None
    event = {"key": key, "code": code, "windowsVirtualKeyCode": vk, "nativeVirtualKeyCode": vk, "modifiers": bits}
    if text is not None:
        event["text"] = text
    return event


FOCUS_ANY_JS = "el.focus(); return document.activeElement === el ? 'ok' : 'not focusable';"
NOT_FOCUSABLE = "that element cannot take keyboard focus; pass the handle of a link, button or field"


def tool_press_key(args: dict) -> str:
    event = key_event(args.get("key"))
    if event is None:
        return BAD_KEY
    page = _session().open_page_session()
    try:
        if args.get("ref") is not None:
            # Only an element that took focus gets the key; otherwise it would go to whatever had focus.
            state = _evaluate(page, ref_expression(args["ref"], FOCUS_ANY_JS))
            if state == "stale":
                return STALE_REF
            if state != "ok":
                return NOT_FOCUSABLE
        page.call("Input.dispatchKeyEvent", type="keyDown" if "text" in event else "rawKeyDown", **event)
        up = {name: value for name, value in event.items() if name != "text"}
        page.call("Input.dispatchKeyEvent", type="keyUp", **up)
        return f"pressed {args['key']}"
    finally:
        page.close()


# Scrolls the window by (dx, dy); when the window cannot move that way, the nearest element under
# the middle of the screen that can (apps that scroll an inner panel). Reports where it ended up.
# Instant, not smooth: a page styled to scroll smoothly would otherwise not have moved yet when the
# position is read back.
SCROLL_JS = r"""(() => {
  const dx = %f, dy = %f;
  const before = [scrollX, scrollY];
  window.scrollBy({ left: dx, top: dy, behavior: 'instant' });
  let box = null;
  if (scrollX === before[0] && scrollY === before[1]) {
    let el = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
    while (el && el !== document.documentElement) {
      const style = getComputedStyle(el);
      const canY = dy !== 0 && /(auto|scroll)/.test(style.overflowY) && el.scrollHeight > el.clientHeight;
      const canX = dx !== 0 && /(auto|scroll)/.test(style.overflowX) && el.scrollWidth > el.clientWidth;
      if (canY || canX) {
        const start = [el.scrollLeft, el.scrollTop];
        el.scrollBy({ left: dx, top: dy, behavior: 'instant' });
        if (el.scrollLeft !== start[0] || el.scrollTop !== start[1]) { box = el; break; }
      }
      el = el.parentElement;
    }
  }
  const at = box || document.scrollingElement || document.documentElement;
  return JSON.stringify({
    moved: box !== null || scrollX !== before[0] || scrollY !== before[1],
    inner: box !== null,
    x: Math.round(box ? box.scrollLeft : scrollX), y: Math.round(box ? box.scrollTop : scrollY),
    width: at.scrollWidth, height: at.scrollHeight,
    viewWidth: box ? box.clientWidth : innerWidth, viewHeight: box ? box.clientHeight : innerHeight
  });
})()"""
SCROLL_DIRECTIONS = {"down": (0, 1), "up": (0, -1), "right": (1, 0), "left": (-1, 0)}
BAD_DIRECTION = "direction must be down, up, left or right"


def describe_scroll(data: dict) -> str:
    if not data.get("moved"):
        return "the page did not move: it is already at that end, or nothing here scrolls that way"
    where = "a scrolling panel" if data.get("inner") else "the page"
    return (
        f"scrolled {where} to x={data['x']}, y={data['y']} of {data['width']}x{data['height']} "
        f"(view {data['viewWidth']}x{data['viewHeight']}); call browser_read for what is now in view"
    )


BAD_AMOUNT = "amount must be a positive number of CSS pixels"


def scroll_request(args: dict) -> tuple[tuple[int, int], float | None] | str:
    """The direction and amount asked for, or what is wrong with them; checked before the browser is reached."""
    direction = SCROLL_DIRECTIONS.get(str(args.get("direction") or "down").lower())
    if direction is None:
        return BAD_DIRECTION
    amount = args.get("amount")
    if amount is None:
        return direction, None
    if isinstance(amount, bool) or not isinstance(amount, (int, float)) or not math.isfinite(amount) or amount <= 0:
        return BAD_AMOUNT
    return direction, float(amount)


def tool_scroll(args: dict) -> str:
    request = None if args.get("ref") is not None else scroll_request(args)
    if isinstance(request, str):
        return request
    page = _session().open_page_session()
    try:
        if request is None:
            state = _evaluate(page, ref_expression(args["ref"], "return 'ok';"))
            return STALE_REF if state == "stale" else "scrolled the element into view"
        direction, amount = request
        if amount is None:
            # Most of a screen, so a line or two stays in view across the jump.
            width, height = json.loads(str(_evaluate(page, "JSON.stringify([innerWidth, innerHeight])")))
            amount = (width if direction[0] else height) * 0.8
        dx, dy = direction[0] * amount, direction[1] * amount
        return describe_scroll(json.loads(str(_evaluate(page, SCROLL_JS % (dx, dy)))))
    finally:
        page.close()


def tool_screenshot(args: dict) -> str:
    save_path = args.get("save_path") or "/tmp/andcode-browser.png"
    page = _session().open_page_session()
    try:
        result = page.call("Page.captureScreenshot", format="png")
        target = Path(save_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(base64.b64decode(result["data"]))
        return str(target)
    finally:
        page.close()


def tool_info(args: dict) -> str:
    page = _session().open_page_session()
    try:
        result = page.call(
            "Runtime.evaluate",
            expression="JSON.stringify({url: location.href, title: document.title})",
            returnByValue=True,
        )
        return result.get("result", {}).get("value", "{}")
    finally:
        page.close()


TOOLS = [
    {
        "name": "browser_show",
        "description": (
            "Open the AndCode in-app Guest Browser full-screen at the given URL so the user can watch "
            "and operate the page. Use browser_preview instead when a page on this device should stay "
            "pinned inside the chat. Call this before other browser_* tools."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
        },
    },
    {
        "name": "browser_preview",
        "description": (
            "Show a page served on this device (http://localhost:PORT or http://127.0.0.1:PORT) as a live, "
            "interactive preview pinned inside the AndCode chat; it reloads as files change. Any other page "
            "opens with browser_show. The other browser_* tools do not drive the preview."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
        },
    },
    {
        "name": "browser_handoff",
        "description": (
            "Hand the open Guest Browser to the user for a step only they can do (signing in with "
            "their own credentials, approving a prompt, solving a captcha). Returns when the user taps "
            "'Hand back to agent' (continue), taps Cancel (stop), or after timeout_s (call it again if "
            "they are still busy). Call browser_show first. The page the user is on is left exactly as it is."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "timeout_s": {
                    "type": "number",
                    "description": "How long to wait for the user, in seconds (default 90, max 110).",
                },
            },
        },
    },
    {
        "name": "browser_status",
        "description": "Report CDP connectivity to the Guest Browser WebView and the current pages.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "browser_navigate",
        "description": "Navigate the open Guest Browser to a URL.",
        "inputSchema": {
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
        },
    },
    {
        "name": "browser_read",
        "description": (
            "List what can be acted on in the Guest Browser page: links, buttons, fields and so on, "
            "one per line as [handle] kind \"name\" (at most 150, in page order; password values are "
            "never shown). Pass a handle to browser_click or browser_type instead of guessing "
            "coordinates. Handles stay valid while the element stays in the page; read again after "
            "the page changes or when told a handle is gone."
        ),
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "browser_click",
        "description": (
            "Click an element of the Guest Browser page by its handle from browser_read, or tap "
            "viewport coordinates (x, y) when there is none."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "ref": {"type": "integer", "description": "Handle from browser_read; used instead of x/y."},
                "x": {"type": "number", "description": "Viewport x in CSS pixels, when there is no handle."},
                "y": {"type": "number", "description": "Viewport y in CSS pixels, when there is no handle."},
            },
        },
    },
    {
        "name": "browser_type",
        "description": (
            "Type text into an input of the Guest Browser page: the one with the given handle from "
            "browser_read, or else the focused one. Text is inserted at the cursor; existing text "
            "is not cleared. A password field is refused (best effort: fields inside iframes or a "
            "shadow root are not inspected); use browser_handoff when credentials are needed."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string"},
                "ref": {"type": "integer", "description": "Handle from browser_read; otherwise the focused input."},
            },
            "required": ["text"],
        },
    },
    {
        "name": "browser_console",
        "description": (
            "Read what the Guest Browser page logged: console messages, uncaught exceptions, files that "
            "failed to load, and requests that failed or answered 4xx/5xx, oldest first (at most the "
            "last 100, from the top page only, not its iframes). Sign-in details, query values and "
            "fragments of the page's and requests' URLs are hidden. The "
            "reply ends with next: <cursor>; pass since=<cursor> to read only what came after. After a "
            "navigation the old cursor reads the new page's whole log."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "since": {"type": "string", "description": "The next cursor from an earlier reply; omit for everything kept."},
            },
        },
    },
    {
        "name": "browser_press_key",
        "description": (
            "Press a key in the Guest Browser page, e.g. Enter to submit, Tab, Escape, the arrows, or a "
            "page shortcut like ctrl+a (not the phone's own shortcuts). With a handle from browser_read "
            "the element gets focus first; otherwise the key goes to the focused element."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "key": {"type": "string", "description": "Enter, Tab, Escape, ArrowDown, a letter, ctrl+a, shift+Tab ..."},
                "ref": {"type": "integer", "description": "Handle from browser_read to focus first."},
            },
            "required": ["key"],
        },
    },
    {
        "name": "browser_scroll",
        "description": (
            "Scroll the Guest Browser page (or, when the page itself does not scroll, the panel in the "
            "middle of the screen; not inside iframes) by most of a screen, or by amount CSS pixels, in "
            "a direction; or bring the element with a handle from browser_read into view."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "direction": {"type": "string", "enum": ["down", "up", "left", "right"]},
                "amount": {"type": "number", "description": "CSS pixels; most of a screen when omitted."},
                "ref": {"type": "integer", "description": "Handle from browser_read to bring into view instead."},
            },
        },
    },
    {
        "name": "browser_upload",
        "description": (
            "Attach a file from device storage (/sdcard or /storage) to the Guest Browser page's file input, "
            "bypassing the Android file picker. Best effort: only an input reachable from "
            "document.querySelector is found; pass click_selector to open the site's upload button "
            "when the input is created lazily."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Absolute device-storage path, e.g. /sdcard/DCIM/Camera/photo.jpg",
                },
                "selector": {
                    "type": "string",
                    "description": "CSS selector for the file input (default input[type=file]).",
                },
                "click_selector": {
                    "type": "string",
                    "description": "Optional CSS selector for the button that reveals the file input.",
                },
            },
            "required": ["path"],
        },
    },
    {
        "name": "browser_screenshot",
        "description": "Capture the Guest Browser page as PNG and return the saved path.",
        "inputSchema": {
            "type": "object",
            "properties": {"save_path": {"type": "string"}},
        },
    },
    {
        "name": "browser_info",
        "description": "Return the current URL and title of the Guest Browser page.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]

HANDLERS = {
    "browser_show": tool_show,
    "browser_preview": tool_preview,
    "browser_handoff": tool_handoff,
    "browser_status": tool_status,
    "browser_navigate": tool_navigate,
    "browser_read": tool_read,
    "browser_click": tool_click,
    "browser_type": tool_type,
    "browser_upload": tool_upload,
    "browser_console": tool_console,
    "browser_press_key": tool_press_key,
    "browser_scroll": tool_scroll,
    "browser_screenshot": tool_screenshot,
    "browser_info": tool_info,
}


def respond(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def main() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        method = msg.get("method")
        mid = msg.get("id")
        if method == "initialize":
            respond(
                {
                    "jsonrpc": "2.0",
                    "id": mid,
                    "result": {
                        "protocolVersion": msg.get("params", {}).get("protocolVersion", "2024-11-05"),
                        "capabilities": {"tools": {}},
                        "serverInfo": {"name": "and-code-browser", "version": "1.0.0"},
                    },
                }
            )
        elif method in ("notifications/initialized", "initialized"):
            continue
        elif method == "tools/list":
            respond({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            params = msg.get("params", {})
            name = params.get("name")
            args = params.get("arguments", {}) or {}
            handler = HANDLERS.get(name)
            if handler is None:
                respond(
                    {
                        "jsonrpc": "2.0",
                        "id": mid,
                        "result": {
                            "content": [{"type": "text", "text": f"unknown tool: {name}"}],
                            "isError": True,
                        },
                    }
                )
            else:
                try:
                    text = handler(args)
                    respond(
                        {"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": text}]}}
                    )
                except Exception as exc:  # noqa: BLE001 - report any failure to the agent
                    respond(
                        {
                            "jsonrpc": "2.0",
                            "id": mid,
                            "result": {
                                "content": [{"type": "text", "text": f"{type(exc).__name__}: {exc}"}],
                                "isError": True,
                            },
                        }
                    )
        elif mid is not None:
            respond(
                {
                    "jsonrpc": "2.0",
                    "id": mid,
                    "error": {"code": -32601, "message": f"method not found: {method}"},
                }
            )


if __name__ == "__main__":
    main()
