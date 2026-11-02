# Fork notes (Demonic1594/and-code)

This repository is a fork of [yuga-hashimoto/and-code](https://github.com/yuga-hashimoto/and-code).
It tracks upstream `main` and carries a small, deliberate set of customizations on top. This
page is the official list of them, so an upstream sync never drops one by accident.

## The "AndCode LH" debug build

The debug build type is the build this fork actually installs and uses day to day. It is a
supported variant, not a leftover.

| Piece | File | Why |
|-------|------|-----|
| Package suffix `.heap` | `app/build.gradle.kts` (`buildTypes.debug.applicationIdSuffix`) | Installs as `com.yugahashimoto.andcode.heap`, so it lives side by side with an upstream release build and keeps its own data, runtime and sign-ins. |
| Runtime port offset | `app/build.gradle.kts` (`LOCAL_RUNTIME_PORT_OFFSET`, `1` in `buildTypes.debug`) | Loopback is shared by every app on the device, so two installs both serving OpenCode on `127.0.0.1:4097` collide: the one that starts second reaches the other's server, which rejects its password, and providers and replies never load. A debug build installs its runtime on `4098` instead. The port is recorded when the runtime is installed, so a debug install made before this change keeps `4097` until its runtime is reinstalled. |
| Launcher label `AndCode LH` | `app/src/debug/res/values/strings.xml` | Tells the two installs apart on the home screen and in the recents list. Debug-only resources are outside the i18n parity check, which only covers `app/src/main/res`. |
| Placeholder Firebase config | `app/src/debug/google-services.json` | The google-services Gradle plugin needs a client entry for the exact package name. This file carries a placeholder project with no real keys, so debug builds compile and never report to the upstream Firebase project. |
| Large heap | `android:largeHeap="true"` in `AndroidManifest.xml` | Upstream adopted this in v1.2.26, so the fork no longer carries its own copy. Listed here so nobody re-adds a duplicate. |

Build and install it with:

```bash
./gradlew :app:assembleGithubDebug
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
```

CI (`.github/workflows/android.yml`) builds `assembleGithubDebug` on every PR. To get an
installable "AndCode LH" APK attached to a PR, add the `build-apk` label (or run the
`pr-apk-build.yml` workflow by hand); it uploads `and-code-debug-apk` and links it in a comment.

## Releases

Bump `versionName` and `versionCode` in `app/build.gradle.kts`, `.release-version`,
`ReleaseMetadataTest` and add `fastlane/metadata/android/*/changelogs/<versionCode>.txt`; once that
is on `main`, run **Fork release** from the Actions tab. It creates the release for the tag in
`.release-version` and attaches:

- `and-code-lh-<tag>.apk`, the release build signed with the fork's own key. It is built with
  `-Pandcode.lhRelease=true`, which gives the release build type the debug build's `.heap` package
  suffix, runtime port offset and "AndCode LH" label, and turns off the Crashlytics mapping upload.
  The job copies the placeholder `app/src/debug/google-services.json` to
  `app/src/githubRelease/` in its own checkout (never committed), so this build never reports to
  upstream's Firebase project. It checks the signature and that the package is
  `com.yugahashimoto.andcode.heap` before publishing, and attaches the R8 mapping
  (`and-code-lh-<tag>-mapping.txt`) for reading crash stacks.
- `and-code-lh-<tag>-debug.apk`, the debug-signed build, for updating an existing debug install.

Each APK has a `.sha256` file. The signed build needs the repository secrets
`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD`; without them only the debug APK is published. Keep the keystore and its
password backed up outside the repository: a signed install only accepts updates signed with the
same key. Android does not let a debug-signed install update to the signed one (or back), so moving
between them means uninstalling first, which removes that install's data.

`release.yml` (upstream's signed flow, which builds `com.yugahashimoto.andcode` with upstream's
Firebase project) is skipped on a repository whose `AND_CODE_GITHUB_CLIENT_ID` variable is not set.
Leave that variable unset here: setting it would switch `release.yml` back on and publish an APK
that clashes with an upstream install.

## Syncing with upstream

```bash
git remote add upstream https://github.com/yuga-hashimoto/and-code   # once
git fetch upstream main
git merge --no-ff upstream/main
```

After the merge, check that the three debug-variant files above are still present and that
`AndroidManifest.xml` declares `largeHeap` exactly once.

## Fork-only features

Features developed in this fork first are documented in [`docs/FEATURES.md`](FEATURES.md), and
the competitive research that motivated them is in
[`docs/AGENTIC_CODING_LANDSCAPE.md`](AGENTIC_CODING_LANDSCAPE.md).
