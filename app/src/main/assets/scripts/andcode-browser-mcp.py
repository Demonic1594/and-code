#!/usr/bin/env python3
"""AndCode guest-browser MCP server (stdlib only).

Bridges the agent to the in-app Guest Browser of the AndCode Android app:

- ``browser_show`` asks the app (via a command file in the active workspace) to
  open the guest browser so the user can watch and operate the page.
- The remaining tools drive the same WebView over CDP. While the Guest Browser is
  open with "Let agents control this browser" on, the app enables WebView
  debugging, which exposes ``webview_devtools_remote_<pid>`` as a Linux abstract
  socket; the guest shares the kernel (and the app UID), so it can attach.

Speaks MCP over stdio (newline-delimited JSON-RPC) with no third-party deps so
it runs on the bare runtime rootfs python3.
"""

import base64
import json
import os
import socket
import struct
import sys
from pathlib import Path
from urllib.parse import urlparse

COMMAND_FILE = Path(".and-code") / "browser-command.json"
# Logged to the page console before each action; the app shows "An agent is controlling this page".
AGENT_ACTIVITY_MARKER = "andcode:agent-control"


DEVTOOLS_PREFIX = "webview_devtools_remote_"

NOT_REACHABLE = (
    "The Guest Browser is not reachable: it is closed, or 'Let agents control this browser' is off "
    "in its menu. Call browser_show with a URL to open it."
)
NO_PAGE = "The Guest Browser has no page loaded. Call browser_show or browser_navigate with a URL."
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
                raise CdpError("socket closed")
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
                raise CdpError("server closed ws")
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
        page.announce()
        return page


class _PageSession:
    def __init__(self, ws: _UnixWs):
        self.ws = ws

    def call(self, method: str, **params) -> dict:
        self.ws.send_text(json.dumps({"id": 1, "method": method, "params": params}))
        while True:
            msg = json.loads(self.ws.recv_text())
            if msg.get("id") == 1:
                if "error" in msg:
                    raise CdpError(f"{method}: {msg['error']}")
                return msg.get("result", {})

    def announce(self) -> None:
        """Tells the Guest Browser an agent is acting on the page, so it can say so on screen."""
        # Only a refusal (no page context mid-navigation, say) is ignored: that reply was read in
        # full. A socket error or timeout leaves the stream mid-reply, so it fails the tool as before.
        try:
            self.call("Runtime.evaluate", expression=f"console.debug({json.dumps(AGENT_ACTIVITY_MARKER)})")
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
  const nameOf = (el) =>
    clip(el.getAttribute('aria-label') || el.labels && el.labels[0] && el.labels[0].innerText ||
      el.innerText || el.placeholder || el.alt || el.title || el.value);
  const out = [];
  let total = 0;
  for (const el of document.querySelectorAll(selector)) {
    if (!shown(el)) continue;
    total++;
    if (out.length >= limit) continue;
    let ref = el.getAttribute('data-andcode-ref');
    if (!ref) { ref = String(state.next++); el.setAttribute('data-andcode-ref', ref); }
    const tag = el.tagName.toLowerCase();
    const type = tag === 'input' ? (el.type || 'text') : '';
    const rect = el.getBoundingClientRect();
    out.push({
      ref: Number(ref),
      tag: tag,
      role: el.getAttribute('role') || '',
      type: type,
      name: nameOf(el),
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


def ref_expression(ref: object, action: str) -> str:
    """JavaScript that finds the element with handle [ref] and runs [action] on it (`el`)."""
    number = int(ref)
    return (
        "(() => { const el = document.querySelector('[data-andcode-ref=\"%d\"]'); "
        "if (!el) return 'stale'; el.scrollIntoView({block: 'center', inline: 'center'}); %s })()"
    ) % (number, action)


def tool_show(args: dict) -> str:
    url = args["url"]
    COMMAND_FILE.parent.mkdir(parents=True, exist_ok=True)
    COMMAND_FILE.write_text(json.dumps({"action": "open", "url": url}), encoding="utf-8")
    return f"requested the app to open {url}"


def tool_status(args: dict) -> str:
    name = _find_devtools_socket()
    if name is None:
        return f"not connected: {NOT_REACHABLE}"
    target = choose_target(CdpSession(name).list_targets())
    if target is None:
        return f"connected: {name} / {NO_PAGE}"
    return f"connected: {name} / page: {target.get('url', '')} ({target.get('title', '')})"


def tool_navigate(args: dict) -> str:
    page = _session().open_page_session()
    try:
        page.call("Page.navigate", url=args["url"])
        return f"navigated to {args['url']}"
    finally:
        page.close()


STALE_REF = "that handle is gone from the page; call browser_read again for fresh ones"
NO_TARGET = "pass a handle from browser_read, or both x and y"


def tool_read(args: dict) -> str:
    page = _session().open_page_session()
    try:
        result = page.call("Runtime.evaluate", expression=READ_JS % READ_LIMIT, returnByValue=True)
        return format_outline(json.loads(result.get("result", {}).get("value", "{}")))
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
        result = page.call("Runtime.evaluate", expression=expr, returnByValue=True)
        value = result.get("result", {}).get("value")
        return STALE_REF if value == "stale" else f"clicked: {value}"
    finally:
        page.close()


def tool_type(args: dict) -> str:
    page = _session().open_page_session()
    try:
        if args.get("ref") is not None:
            focused = page.call(
                "Runtime.evaluate",
                expression=ref_expression(args["ref"], "el.focus(); return 'ok';"),
                returnByValue=True,
            )
            if focused.get("result", {}).get("value") == "stale":
                return STALE_REF
        else:
            page.call("Runtime.evaluate", expression="document.activeElement && document.activeElement.focus()")
        page.call("Input.insertText", text=args["text"])
        return "typed"
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
            "Open the AndCode in-app Guest Browser at the given URL so the user can watch and "
            "operate the page. Call this before other browser_* tools."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
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
            "one per line as [handle] kind \"name\". Pass a handle to browser_click or browser_type "
            "instead of guessing coordinates. Handles stay valid while the element stays in the page; "
            "read again after the page changes."
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
            "properties": {"ref": {"type": "integer"}, "x": {"type": "number"}, "y": {"type": "number"}},
        },
    },
    {
        "name": "browser_type",
        "description": (
            "Type text into an input of the Guest Browser page: the one with the given handle from "
            "browser_read, or else the focused one."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}, "ref": {"type": "integer"}},
            "required": ["text"],
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
    "browser_status": tool_status,
    "browser_navigate": tool_navigate,
    "browser_read": tool_read,
    "browser_click": tool_click,
    "browser_type": tool_type,
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
