# Biometric lock for opening the app and for sending a message

Status: **built**. Decisions made while building:

- **The voice assistant** cannot show a `BiometricPrompt` (it is not a `FragmentActivity`, and it can
  open over the lock screen). With the app lock on, it refuses to send while the phone itself is
  locked (`KeyguardManager.isDeviceLocked`), which is the barrier that matters there; once the phone
  is unlocked it sends as before. A per-send prompt is not added to it.
- **In-app send paths are gated** when "also on send" is on: the composer, the review screen's
  **Send to agent** and the Inbox inline reply. Each keeps its text when the prompt is cancelled.
- **Out-of-app paths are closed while the lock is on** (a hybrid of "gate them" and "leave them"):
  notifications drop Reply and Allow/Reject and are private on the lock screen, the receivers refuse
  actions left on older notifications, and the widget and Quick Settings prompt open the app
  instead of sending. Gating them with a prompt would need an activity each; opening the locked app
  gives the same protection with one code path.
- **While locked, the app's content leaves the composition** (kept with `SaveableStateProvider`), so
  its dialogs, popups, back handlers and keyboard focus cannot leak past the lock.
- **Android 9 and later only.** On 8.x androidx.biometric uses an AppCompat dialog that crashes
  under this app's theme; the switch is greyed out there with the reason.
- **The lock is decided on the main thread** as the app returns, before its first frame.
- **Entering the PIN leaves the app** (the system's own screen), so unlocking also clears the
  "backgrounded at" time; otherwise a slow PIN entry would lock again on return.
- **Turning the lock on** locks the next fresh start and the next return after the grace period,
  not the screen the user is on. A phone whose screen lock was removed after the setting was turned
  on is let in rather than locked out.
- **The recents thumbnail** is hidden with `setRecentsScreenshotEnabled(false)` on Android 13+
  while the lock is on; screenshots are still allowed (`FLAG_SECURE` would block them everywhere).
- `MainActivity` is now a `FragmentActivity` (which `BiometricPrompt` needs); it is still a
  `ComponentActivity`, so Compose and everything else is unchanged.

## Goal

A user-facing toggle (off by default) that, once on, asks for the device's fingerprint/face
unlock in two places: once when AndCode is opened (or returned to from the background after a
timeout), and again, if the user wants it, on every message send. Closes a gap flagged in the
mobile-companion survey: one community OpenCode client ("OpenCode Mobile" by Agent Labs) gates
both app-open and per-send behind biometrics; AndCode has nothing comparable today — there is no
app-lock, PIN, or biometric code anywhere in this repo (confirmed by a full-text search for
`biometric`, `passcode`, `Keyguard`, `lockscreen`).

AndCode already protects *stored* secrets well (GitHub token, custom provider keys) in
`EncryptedSharedPreferences` backed by a Keystore `MasterKey`
(`data/connection/SecureSettingsRepository.kt:19-20`). This plan is about protecting *access to
the open app*, which is a different, currently unaddressed threat: someone who picks up an
unlocked or sleeping phone can open AndCode and read every chat, edit workspace files, or send a
prompt that runs shell commands, with no extra step beyond whatever unlocks the phone itself.

## What exists today

- No lock screen, PIN, or biometric prompt of any kind.
- `MainActivity.onResume()` (`MainActivity.kt:127-130`) only refreshes assistant status and the
  GitHub-star prompt nudge; nothing runs on backgrounding or on cold start before the UI is shown.
- Settings toggles follow one consistent pattern: a field in `AppPreferences`
  (`data/settings/AppPreferencesRepository.kt`, e.g. `hapticsEnabled` at `:52`), a getter/setter
  pair on `SecureSettingsRepository` (`data/connection/SecureSettingsRepository.kt:442`), a
  `setXEnabled` method on `AppPreferencesRepository` (`:344-345`) that writes through and updates
  the in-memory `StateFlow`, and a `SettingsToggleRow` in `SettingsScreenV2.kt` (pattern at
  `:244-250`).
- `SettingsScreenV2.kt` has six sections today (`:182` assistant, `:208` appearance, `:253` chat,
  `:308` sessions, `:346` system, `:396` app) — no privacy/security section exists.
- `androidx.biometric` is **not** a dependency (`app/build.gradle.kts` has no match for
  `biometric`); it has to be added.
- The composer's send action is `ChatHomeScreen`'s send button/IME action, wired through
  `ChatViewModel.sendMessage` — the hook point for a per-send prompt.

## Design

### Settings

Add a **Privacy** section to `SettingsScreenV2.kt` (new section, placed after "Sessions" and
before "System" — security belongs near the data it protects, not buried in "App") with two
toggles:

- **Require biometric unlock** — off by default. Grayed out with an explanatory subtitle when
  `BiometricManager.canAuthenticate()` reports no enrolled biometric/device-credential on the
  phone, rather than hidden, so a user without Face Unlock set up understands *why* it is
  unavailable instead of wondering where the feature went.
- **Also require it to send a message** — only shown/enabled when the first toggle is on. Off by
  default even when the first is on: most users will want the app itself gated, not every single
  send, which would make rapid back-and-forth chats painful.

New `AppPreferences` fields: `biometricLockEnabled: Boolean = false`,
`biometricLockOnSendEnabled: Boolean = false`. Same plumbing as `hapticsEnabled` throughout
`SecureSettingsRepository` → `AppPreferencesRepository` → `SettingsScreenV2`.

### App-open lock

- A new `AppLockGate` composable wraps the app's content in `MainActivity`'s `setContent` (next
  to the existing `AndCodeApp` call), showing a full-screen "Unlocked by [biometric]" scrim with a
  single "Unlock" button (tapping it, or the gate appearing at all, triggers
  `BiometricPrompt.authenticate()` immediately — the explicit button is the fallback the system
  prompt's own cancel leaves on screen) until authentication succeeds.
- Lock state lives in a small `AppLockState` object the `Application` class owns (not
  `MainActivity`, which is destroyed/recreated on rotation and by the back stack): `locked:
  Boolean`, set `true` whenever `biometricLockEnabled` is on and either (a) this is first launch
  of the process, or (b) the app was backgrounded for longer than a grace period.
- Backgrounding is tracked with `ProcessLifecycleOwner` (already a transitive dependency via
  lifecycle-runtime; confirm the artifact is present, add `lifecycle-process` if not), not
  `MainActivity.onPause/onResume`, since those also fire on device-config changes and cross-screen
  navigation inside the app, not just truly leaving it.
- Grace period: a short one (configurable later; default a value most password managers use,
  around 30 seconds) so switching to the share sheet or the GitHub OAuth browser tab and back does
  not force a re-unlock; reconsider after a device test.
- `BiometricPrompt` falls back to the device's own PIN/pattern/password automatically
  (`setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)`), so a phone with only a PIN set
  (no fingerprint/face) still gets a lock, which matches what "biometric lock" means to most users
  even though it is really "whatever unlocks your phone."

### Per-send lock

- When `biometricLockOnSendEnabled` is on, the composer's send action calls
  `BiometricPrompt.authenticate()` before calling `ChatViewModel.sendMessage`; on success, send
  proceeds, on cancel/failure it does not. Voice-triggered auto-send (if the wake-word flow
  auto-submits) needs the same gate — check `feature/assistant` for where that path calls send and
  route it through the same check, or exempt it explicitly and say so in Settings if gating a
  hands-free flow behind a prompt defeats its purpose (needs a decision during implementation, not
  guessed here).

## Tasks

1. Add `androidx.biometric:biometric` dependency; confirm `lifecycle-process` is present or add
   it.
2. `AppPreferences` + `SecureSettingsRepository` + `AppPreferencesRepository`: the two new fields
   and their setters.
3. `SettingsScreenV2.kt`: the new Privacy section, two toggles, the "no biometric enrolled"
   disabled state and its string.
4. `AppLockState` (Application-scoped) + `ProcessLifecycleOwner` observer for the grace-period
   backgrounding logic.
5. `AppLockGate` composable + wiring into `MainActivity`'s content root.
6. Per-send gate in the composer's send path (and the voice auto-send path, per the open question
   above).
7. Strings in all 8 locales.

## Tests

- `AppLockState`/grace-period logic as a plain unit test (fake clock, simulated
  background/foreground transitions) — this is the one piece with real logic to get wrong
  (off-by-one on the grace window, or locking on a config change).
- `AppPreferencesRepository` round-trip test for the two new fields, matching the existing
  `hapticsEnabled` test if one exists.
- Not unit-testable: the actual `BiometricPrompt` system dialog. Per repo convention, note this
  and verify by hand.

## Confirm on the device after building

1. Toggle on, background the app past the grace period, return: locked, biometric prompt appears.
2. Return within the grace period: not locked.
3. Toggle "also on send" on, send a message: prompted before it goes out; cancel the prompt: not
   sent.
4. A device/emulator with no biometric and no PIN set: the toggle is visibly disabled with an
   explanation, not just inert.
5. Rotate the screen while locked: still locked, no flash of unlocked content.

## Out of scope

- A custom in-app PIN independent of the device's own lock (this plan only wraps the system
  `BiometricPrompt`, which already falls back to the device PIN).
- Per-chat or per-workspace locking.
- Remote-wipe or any account-recovery story — this is a local convenience lock, not a security
  boundary against someone who already has the device unlocked at the OS level.
