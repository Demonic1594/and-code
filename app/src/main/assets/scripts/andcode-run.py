#!/usr/bin/env python3
"""andcode-run: keep dev servers and other long-running commands alive under the AndCode app.

A process started with nohup, & or setsid from an agent's shell dies when the phone sleeps and
nobody can see or stop it. andcode-run hands the command to the AndCode app instead, which keeps
the device awake while it runs, restarts it if it crashes, keeps its log, and shows it to the user
with a Stop button.

Talks to the app through a request/response file bridge under /workspace/.and-code/run-bridge
(stdlib only, so it runs on the bare runtime python3).
"""

import argparse
import json
import os
import shlex
import sys
import time
import uuid
from pathlib import Path

BRIDGE_ROOT = Path("/workspace/.and-code/run-bridge")
PENDING_DIR = BRIDGE_ROOT / "pending"
RESPONSES_DIR = BRIDGE_ROOT / "responses"

# Keep in step with RunBridge.REQUEST_EXPIRES_AFTER_MILLIS in the app.
RESPONSE_TIMEOUT_S = 30
POLL_INTERVAL_S = 0.2

NO_REPLY = (
    "The AndCode app did not answer within {timeout} s. It handles requests while it is open or while "
    "an agent is running in it. Tell the user; do not start the server with nohup or & instead."
)


class BridgeError(RuntimeError):
    pass


def call(op, args):
    PENDING_DIR.mkdir(parents=True, exist_ok=True)
    request_id = uuid.uuid4().hex
    tmp = PENDING_DIR / f"{request_id}.json.tmp"
    tmp.write_text(json.dumps({"op": op, "args": args, "createdAtMs": int(time.time() * 1000)}), encoding="utf-8")
    os.replace(tmp, PENDING_DIR / f"{request_id}.json")
    response_file = RESPONSES_DIR / f"{request_id}.json"
    deadline = time.monotonic() + RESPONSE_TIMEOUT_S
    while time.monotonic() < deadline:
        if response_file.is_file():
            payload = json.loads(response_file.read_text(encoding="utf-8"))
            try:
                response_file.unlink()
            except OSError:
                pass
            if payload.get("ok"):
                return payload.get("data") or {}
            raise BridgeError(payload.get("error") or "the app refused the request")
        time.sleep(POLL_INTERVAL_S)
    raise BridgeError(NO_REPLY.format(timeout=RESPONSE_TIMEOUT_S))


def describe(process):
    parts = [f"{process['name']}: {process['status']}"]
    if process.get("url"):
        parts.append(process["url"])
    if process.get("status") == "running":
        parts.append(f"up {process.get('uptimeSeconds', 0)} s")
    if process.get("exitCode") is not None:
        parts.append(f"exit code {process['exitCode']}")
    if process.get("restarts"):
        parts.append(f"{process['restarts']} restarts")
    return ", ".join(parts) + f"\n  $ {process['command']}  (in {process['directory']})"


def main(argv):
    parser = argparse.ArgumentParser(
        prog="andcode-run",
        description="Run long-lived commands (dev servers, watchers) under the AndCode app instead of nohup or &.",
        epilog="Example: andcode-run start web --port 5173 -- npm run dev -- --host 127.0.0.1 --port 5173",
    )
    parser.add_argument("--json", action="store_true", help="print the app's reply as JSON")
    sub = parser.add_subparsers(dest="op", required=True)

    start = sub.add_parser("start", help="start a command and keep it running")
    start.add_argument("name", help="a short name to refer to it by, e.g. web or api")
    start.add_argument("--port", type=int, help="the port it listens on (also passed as $PORT)")
    start.add_argument("--dir", help="working directory (default: the current one)")
    start.add_argument("--restart", choices=["never", "on_crash", "always"], default="on_crash")
    start.add_argument("--max-minutes", type=int, help="stop it after this long; 0 for no limit (default 240)")

    sub.add_parser("list", help="show every process and its status")
    for name in ("stop", "restart"):
        sub.add_parser(name, help=f"{name} a process").add_argument("name")
    logs = sub.add_parser("logs", help="print the end of a process's output")
    logs.add_argument("name")
    logs.add_argument("--chars", type=int, default=4000)

    # Everything after the first `--` is the command, untouched: its own flags must not be read
    # as andcode-run's.
    command = []
    if "--" in argv:
        split = argv.index("--")
        argv, command = argv[:split], argv[split + 1 :]
    options = parser.parse_args(argv)
    try:
        if options.op == "start":
            if not command:
                parser.error("give the command after --, e.g. andcode-run start web -- npm run dev")
            args = {
                "name": options.name,
                "command": shlex.join(command) if len(command) > 1 else command[0],
                "directory": options.dir or os.getcwd(),
                "restart": options.restart,
            }
            if options.port:
                args["port"] = options.port
            if options.max_minutes is not None:
                args["maxMinutes"] = options.max_minutes
            data = call("start", args)
            print(json.dumps(data) if options.json else "Started " + describe(data))
            if not options.json:
                print("Check it with `andcode-run list` (and the URL) before telling the user it works.")
        elif options.op == "list":
            data = call("list", {})
            processes = data.get("processes", [])
            if options.json:
                print(json.dumps(processes))
            elif not processes:
                print("No processes. Start one with: andcode-run start NAME -- COMMAND")
            else:
                print("\n".join(describe(p) for p in processes))
        elif options.op == "logs":
            data = call("logs", {"name": options.name, "characters": options.chars})
            print(json.dumps(data) if options.json else data.get("log", ""))
        else:
            data = call(options.op, {"name": options.name})
            if options.json:
                print(json.dumps(data))
            elif options.op == "stop":
                print(f"Stopped {options.name}.")
            else:
                print("Restarted " + describe(data))
    except BridgeError as error:
        print(f"andcode-run: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
