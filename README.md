# Daily Pause

A quiet, offline Android journal for checking in with your mood and making space for reflection. Supports Android 8.0 (API 26) and newer.

## Features

- Choose from five moods, from low to great.
- Journal freely with optional prompts about what happened, what you felt, and what you need next.
- Save a mood without writing when that is all you have energy for.
- Return to an unfinished draft after leaving the app.
- Browse your entries, read a full reflection, and permanently delete an entry after confirmation.
- Protect My Journal with a six-digit PIN and optional enrolled strong biometrics.
- Explore mood trends in a protected History tab with 1 week, 1 month, 3 months, 1 year, and custom date ranges.

## Mood history

History plots the saved "How are you feeling?" responses against their recorded dates in the device's local time zone. The mood axis is fixed at Low = 1, Down = 2, Okay = 3, Good = 4, Great = 5. The week preset includes today and the previous six days. Month, three-month, and year presets use trailing calendar ranges ending today; custom dates include both endpoints.

Ranges up to one calendar month show daily averages. Longer ranges up to three months show averages over consecutive seven-day buckets starting at the selected start date. Longer ranges show calendar-month averages, clipping the first and last month to the selected dates. Multiple check-ins in a day or longer bucket are averaged across the recorded responses, so all recorded check-ins carry equal weight.

Days without entries never become zero and never contribute to an average. Entirely missing days or aggregate buckets appear as gaps, and lines do not cross those gaps. An empty range displays "No check-ins in this range". Expand "View plotted averages" for exact values, date spans, check-in counts, and missing periods. The graph and mood scale also have screen-reader descriptions.

Entries use a private SQLite database. The app requests no network or storage permissions, contains no analytics, and disables cloud backup and device-transfer backup. Uninstalling the app or clearing its data deletes the journal. This version has no export, account sync, or additional database encryption; protect the device with its system lock too.

## Journal lock

The first visit to My Journal or History asks you to create and confirm a six-digit PIN. Later visits require that PIN or a strong biometric already enrolled on the device. Both private tabs use the same PIN. PIN entry works on devices without biometrics; fingerprint or other strong biometric authentication uses Android's system prompt. You can return to Check in without unlocking.

Both private tabs lock every time you leave their tab, background the app, or recreate the activity (including rotation). Saving an entry also passes through the same gate before showing any saved content. Open journal and date-picker dialogs close when the app is paused. Screenshots and recent-app previews are protected using Android's secure-window flag. Check-in writing and its unfinished draft remain accessible without unlocking. History retains the selected range across activity recreation, but requires authentication again before displaying it.

Only a randomly salted PBKDF2-HMAC-SHA256 PIN verifier (600,000 iterations) is saved, not the PIN itself. Five incorrect PIN attempts start a persistent retry delay, increasing from 30 seconds to a maximum of five minutes. Enrolled biometrics can still unlock during a PIN retry delay. Authentication results from an earlier screen or background session cannot unlock a later session.

There is no PIN recovery or PIN-change flow in this version. Remember your PIN; clearing app data resets it but also deletes all entries. The journal lock controls access through the app's interface and does not add encryption to the database or drafts.

## Build an APK

Open this folder in Android Studio, or use a JDK 17 or 21 and the Android SDK with platform 35 and build tools 35.0.0. Set `ANDROID_HOME` to the SDK directory, or use Android Studio's generated `local.properties`.

```sh
./gradlew assembleDebug
```

Installable APK: `app/build/outputs/apk/debug/app-debug.apk`.

Transfer the APK to an Android phone, open it, and allow installation from the app opening the file when prompted. With a connected development device, use:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This is a debug-signed APK for personal testing. Store distribution requires a release build and a securely managed release signing key. Keep the same signing key for future updates; Android cannot update an installation with an APK signed by a different key.

## Cloud environment

The Linux cloud toolchain is pinned to Temurin JDK 21.0.8+9, Gradle 8.13, Android Gradle Plugin 8.9.2, and Android API 35. The wrapper verifies the Gradle distribution checksum. The setup script verifies downloaded JDK and SDK tooling against publisher checksums and preserves TLS verification.

```sh
bash scripts/setup-cloud.sh
```

For subsequent commands in the prepared cloud environment:

```sh
source scripts/cloud-env.sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Use the existing checkout at `/workspace/Mood-Journal-App`; each cloud task is already isolated. No additional Git worktree is needed. Toolchains and caches live under `/workspace/toolchains`. There is no backend service or API key to configure.

## Validation

```sh
./gradlew testDebugUnitTest lintDebug
```

The test suite exercises Android activities and SQLite: saving and reopening a reflection, preserving drafts across activity recreation, mood validation, mood-only entries, draft preservation across tabs, and confirmed deletion without affecting other entries. Security tests cover PIN setup and confirmation, incorrect PIN rejection, persistent retry limits, salt uniqueness, malformed verifier rejection, tab and background relocking, recreation, gated post-save navigation, secure-window flags, and simulated biometric callbacks. History tests cover daily/weekly/monthly averages, missing data, multiple same-day check-ins, leap years, inclusive custom ranges, local-date filtering across daylight-saving transitions, range selection, and protected navigation. Test results are written to `app/build/reports/tests/testDebugUnitTest/`; lint reports are in `app/build/reports/`.

Before a public release, also test the APK on physical devices, including actual biometric success, failure, cancellation, and lockout; keyboard use; rotation; larger font sizes; and Android 15 edge-to-edge layouts. Robolectric tests use simulated biometric callbacks and do not replace hardware testing.
