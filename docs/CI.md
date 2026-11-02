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
