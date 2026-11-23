# CI

The Android debug APK workflow is intentionally opt-in because a full Android build is expensive.

Add the `build-apk` label to a pull request to run the workflow. It uploads the debug APK as a
14-day artifact and adds or updates a download link in the pull request. New commits do not rerun
the build automatically; remove and re-add the label when a fresh APK is needed.

## Discord notifications

`.github/workflows/notify.yml` posts to the Discord webhook in the `DISCORD_WEBHOOK_URL` secret:

- **Pushes to `main`:** one card per commit, from `.github/scripts/discord_commits.py`, with the
  message body (trailers removed) and the author. Lines and files changed are added when GitHub
  returns the commit's details (for up to 60 commits per run), and a pull request link when the
  subject ends with `(#123)`. A PR merged into `main` is posted only as its commit, not a second time.
- **Pull requests** opened or reopened, and merged into a branch other than `main`, **releases**, and **CI failures on `main`** (Android CI,
  Release, Native runtime source build, Publish F-Droid repository, i18n Check).
- **Backfill:** Actions → **Discord Notify** → Run workflow, `mode: backfill`, `since` = the last
  commit already posted, `until` = `main` (or a commit). Posts the commits in between under a
  "Catching up" header. Use it after an outage; the run's log says how many were posted.

Run `actionlint` on the file after editing it: a trigger GitHub rejects (such as `workflow_run`
without `workflows:`) fails every run before it starts, which silenced notifications from 1 to
2 November 2026.

## Live agent tests

`.github/workflows/live-agent-tests.yml` runs real agents on an x86_64 emulator against real
providers: it installs the local runtime from its release assets, has each agent create
`hello.txt` in a fresh project, and has OpenCode read https://example.com in the hidden browser
(the "Agent is browsing" notice shows and the Guest Browser stays closed). The tests are in
`app/src/androidTest/java/com/yugahashimoto/andcode/live`. A run takes up to two hours, mostly
the runtime download. Each agent gets one short prompt, except OpenCode, which gets three: the
file prompt, its retry (the reply's Retry action) and the hidden-browser prompt.

**Secrets** (Settings → Secrets and variables → Actions → New repository secret). Each test runs
only when its secret is set and is skipped otherwise:

| Secret | Agent | Notes |
| --- | --- | --- |
| `ZAI_API_KEY` | OpenCode | A z.ai GLM Coding Plan key, used through OpenCode's `zai-coding-plan` provider. Pick the model with the run's `zai-model` input (default `glm-4.7`). |
| `ANTHROPIC_API_KEY` | Claude Code | Optional. An Anthropic API key. |
| `OPENAI_API_KEY` | Codex | Optional. An OpenAI API key. |

Antigravity needs a Google sign-in and is not covered.

Use a **dedicated key** for each, made for this purpose only, with the lowest spending or usage
limit the provider allows, so a leak or a runaway run costs little; revoke it if a run ever
looks wrong. The repository is public: the job uploads no logcat and no screenshots, and
`scripts/ci/scrub-secrets.py` replaces every key with `[REDACTED]` in the reports before they are
uploaded (the upload is skipped if a key is still found). The workflow file's header comment has
the details; keep them true when editing it.

**Starting a run:** Actions → **Live agent tests** → Run workflow, on the branch to test. The
scrubbed JUnit and HTML reports are attached to the run for 7 days. A secret that is set but whose
test did not run fails the run, since it means the key never reached the device.
