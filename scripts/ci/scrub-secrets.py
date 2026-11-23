#!/usr/bin/env python3
"""Removes secret values from a directory of CI artifacts before they are uploaded.

Usage: scrub-secrets.py DIR VAR [VAR ...]

Every VAR names an environment variable holding a secret; unset or empty ones are ignored. In every
file under DIR, text or binary (JUnit XML, HTML reports, test-result protobufs), each secret is
replaced with [REDACTED], as is its URL-encoded form. Raw logcat captures and screenshots are
deleted outright: logcat can hold anything the app or the agents printed, and a screenshot could
show a settings field. Exits non-zero, after deleting the file, if a secret is still found
anywhere, so a failed scrub can never be uploaded.
"""

import os
import sys
import urllib.parse

REDACTED = b"[REDACTED]"
DELETE_SUFFIXES = (".png", ".jpg", ".jpeg", ".webp", ".mp4", ".pb")


def forms(secret):
    """The byte strings a secret can appear as in a report."""
    variants = {secret, urllib.parse.quote(secret, safe=""), urllib.parse.quote_plus(secret)}
    return sorted((v.encode() for v in variants if v), key=len, reverse=True)


def should_delete(name):
    lower = name.lower()
    return "logcat" in lower or lower.endswith(DELETE_SUFFIXES)


def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    root, names = sys.argv[1], sys.argv[2:]
    secrets = [os.environ.get(name, "").strip() for name in names]
    patterns = [form for secret in secrets if secret for form in forms(secret)]
    deleted = scrubbed = leaks = 0
    for directory, dirs, files in os.walk(root):
        for name in list(dirs):
            if os.path.islink(os.path.join(directory, name)):
                os.remove(os.path.join(directory, name))
                dirs.remove(name)
        for name in files:
            path = os.path.join(directory, name)
            if os.path.islink(path):
                os.remove(path)
                continue
            if should_delete(name):
                os.remove(path)
                deleted += 1
                continue
            with open(path, "rb") as handle:
                data = handle.read()
            cleaned = data
            for pattern in patterns:
                cleaned = cleaned.replace(pattern, REDACTED)
            if cleaned != data:
                with open(path, "wb") as handle:
                    handle.write(cleaned)
                scrubbed += 1
            if any(pattern in cleaned for pattern in patterns):
                os.remove(path)
                leaks += 1
    # Counts only: never print a path's content or a secret.
    print(f"scrub: {len(patterns)} patterns, {scrubbed} files redacted, {deleted} deleted, {leaks} leaks")
    return 1 if leaks else 0


if __name__ == "__main__":
    sys.exit(main())
