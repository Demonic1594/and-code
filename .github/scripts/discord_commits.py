#!/usr/bin/env python3
"""Post commits to a Discord webhook, one embed per commit.

Used by .github/workflows/notify.yml for pushes to main, and by its manual "backfill" run to send a
range of commits that were never posted. Standard library only.

Environment:
  REPO       owner/name
  BEFORE     the commit before the range (exclusive); all zeros for a new branch
  AFTER      the last commit of the range (inclusive)
  BRANCH     branch name shown in the header
  ACTOR      who pushed (header); empty for a backfill
  MODE       "push" or "backfill"
  WEBHOOK    Discord webhook URL; when empty, payloads are printed instead of sent
  GH_TOKEN   token for the GitHub API (optional for a public repository)
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"
EMBEDS_PER_MESSAGE = 10
# Discord rejects a message whose embeds total more than 6000 characters.
MESSAGE_CHAR_BUDGET = 5500
BODY_LIMIT = 700
DETAILED_COMMITS = 60

COLORS = {
    "feat": 0x2ECC71,
    "fix": 0xE67E22,
    "docs": 0x3498DB,
    "refactor": 0x9B59B6,
    "test": 0x1ABC9C,
    "build": 0x95A5A6,
    "ci": 0x95A5A6,
    "chore": 0x95A5A6,
    "perf": 0xF1C40F,
    "style": 0x95A5A6,
    "revert": 0xE74C3C,
}
DEFAULT_COLOR = 0x7289DA
TRAILER = re.compile(r"^(Co-authored-by|Signed-off-by|Reviewed-by|Change-Id):", re.IGNORECASE)
PR_SUFFIX = re.compile(r"\(#(\d+)\)\s*$")
TYPE_PREFIX = re.compile(r"^(\w+)(\([^)]*\))?!?:")


def github(path):
    request = urllib.request.Request(API + path, headers={"Accept": "application/vnd.github+json"})
    token = os.environ.get("GH_TOKEN", "")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def commits_in_range(repo, before, after):
    """Oldest first, as GitHub lists them. A new branch (zero `before`) is just its head commit."""
    if not before or set(before) == {"0"}:
        return [github(f"/repos/{repo}/commits/{after}")]
    # The compare API pages its commit list; without paging it stops at 250.
    commits, page = [], 1
    while True:
        result = github(f"/repos/{repo}/compare/{before}...{after}?per_page=100&page={page}")
        batch = result.get("commits", [])
        commits += batch
        if not batch or len(commits) >= result.get("total_commits", 0) or page >= 20:
            return commits
        page += 1


def clean_body(message):
    """The commit body without its subject, trailers or separator lines, shortened for an embed."""
    lines = message.split("\n")[1:]
    kept = [line for line in lines if not TRAILER.match(line.strip()) and set(line.strip()) != {"-"}]
    body = re.sub(r"\n{3,}", "\n\n", "\n".join(kept)).strip()
    if len(body) > BODY_LIMIT:
        body = body[: BODY_LIMIT - 1].rstrip() + "…"
    return body


def embed_for(repo, commit, detail):
    message = commit["commit"]["message"]
    subject = message.split("\n", 1)[0].strip()
    sha = commit["sha"]
    author = commit.get("author") or {}
    name = author.get("login") or commit["commit"]["author"].get("name") or "unknown"
    kind = TYPE_PREFIX.match(subject)
    embed = {
        "title": subject[:256] or sha[:7],
        "url": commit.get("html_url") or f"https://github.com/{repo}/commit/{sha}",
        "color": COLORS.get(kind.group(1).lower(), DEFAULT_COLOR) if kind else DEFAULT_COLOR,
        "author": {"name": name},
        "footer": {"text": f"{repo} · {sha[:7]}"},
    }
    if author.get("avatar_url"):
        embed["author"]["icon_url"] = author["avatar_url"]
    if author.get("html_url"):
        embed["author"]["url"] = author["html_url"]
    date = commit["commit"]["author"].get("date")
    if date:
        embed["timestamp"] = date
    body = clean_body(message)
    if body:
        embed["description"] = body
    fields = []
    if detail and "stats" in detail:
        stats = detail["stats"]
        files = len(detail.get("files") or [])
        noun = "file" if files == 1 else "files"
        fields.append(
            {"name": "Changes", "value": f"+{stats.get('additions', 0)} / −{stats.get('deletions', 0)} · {files} {noun}", "inline": True}
        )
    pr = PR_SUFFIX.search(subject)
    if pr:
        fields.append({"name": "Pull request", "value": f"[#{pr.group(1)}](https://github.com/{repo}/pull/{pr.group(1)})", "inline": True})
    if fields:
        embed["fields"] = fields
    return embed


def embed_size(embed):
    size = len(embed.get("title", "")) + len(embed.get("description", ""))
    size += len(embed["author"]["name"]) + len(embed["footer"]["text"])
    size += sum(len(field["name"]) + len(field["value"]) for field in embed.get("fields", []))
    return size


def batches(embeds):
    batch, used = [], 0
    for embed in embeds:
        size = embed_size(embed)
        if batch and (len(batch) == EMBEDS_PER_MESSAGE or used + size > MESSAGE_CHAR_BUDGET):
            yield batch
            batch, used = [], 0
        batch.append(embed)
        used += size
    if batch:
        yield batch


def post(webhook, payload):
    data = json.dumps(payload).encode()
    for attempt in range(6):
        try:
            request = urllib.request.Request(
                webhook + ("&" if "?" in webhook else "?") + "wait=true",
                data=data,
                headers={"Content-Type": "application/json", "User-Agent": "and-code-notify"},
            )
        except ValueError:
            # Never echo the URL: it is the secret.
            raise SystemExit("The webhook secret is not a valid URL.") from None
        # A send that times out is not retried: Discord may already have taken it, and a retry would
        # post it twice. A failed run can be repeated with the backfill run instead.
        try:
            with urllib.request.urlopen(request, timeout=30):
                return
        except urllib.error.HTTPError as error:
            if error.code == 429:
                try:
                    retry = float(json.loads(error.read() or b"{}").get("retry_after", 2))
                except ValueError:
                    retry = float(error.headers.get("Retry-After") or 2)
                time.sleep(retry + 0.5)
            elif error.code >= 500 and attempt < 5:
                time.sleep(2**attempt)
            else:
                raise SystemExit(f"Discord rejected the message: HTTP {error.code} {error.read()[:300]!r}")
        except urllib.error.URLError:
            if attempt == 5:
                raise
            time.sleep(2**attempt)
    raise SystemExit("Discord kept rate-limiting; giving up.")


def main():
    repo = os.environ["REPO"]
    before = os.environ.get("BEFORE", "")
    after = os.environ["AFTER"]
    branch = os.environ.get("BRANCH", "main")
    actor = os.environ.get("ACTOR", "")
    mode = os.environ.get("MODE", "push")
    forced = os.environ.get("FORCED", "") == "true"
    webhook = os.environ.get("WEBHOOK", "")

    commits = commits_in_range(repo, before, after)
    # A merge commit only joins commits already listed; its pull request is named in the header.
    merged_prs = []
    for commit in commits:
        if len(commit.get("parents") or []) > 1:
            found = re.match(r"Merge pull request #(\d+)", commit["commit"]["message"])
            if found:
                merged_prs.append(found.group(1))
    commits = [c for c in commits if len(c.get("parents") or []) < 2]
    if not commits:
        print("No commits to post.")
        return

    embeds = []
    for index, commit in enumerate(commits):
        detail = None
        if index < DETAILED_COMMITS:
            try:
                detail = github(f"/repos/{repo}/commits/{commit['sha']}")
            except (OSError, ValueError) as error:
                print(f"No stats for {commit['sha'][:7]}: {error}", file=sys.stderr)
        embeds.append(embed_for(repo, commit, detail))

    count = len(commits)
    noun = "commit" if count == 1 else "commits"
    if mode == "backfill":
        header = f"Catching up: {count} {noun} on `{branch}` that were not posted"
    else:
        verb = "force-pushed" if forced else "pushed"
        header = f"**{actor or 'Someone'}** {verb} {count} {noun} to `{branch}`"
    if merged_prs:
        links = ", ".join(f"[#{n}](https://github.com/{repo}/pull/{n})" for n in merged_prs)
        header += f" · merges {links}"
    if before and set(before) != {"0"} and count > 1:
        header += f" · [compare](https://github.com/{repo}/compare/{before[:12]}...{after[:12]})"

    for number, batch in enumerate(batches(embeds)):
        payload = {"username": "and-code", "embeds": batch}
        if number == 0:
            payload["content"] = header
        if webhook:
            post(webhook, payload)
            time.sleep(1)
        else:
            print(json.dumps(payload, ensure_ascii=False, indent=2))
    print(f"Posted {count} {noun}." if webhook else f"Dry run: {count} {noun}, no webhook set.")


if __name__ == "__main__":
    main()
