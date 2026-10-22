# Daily Pause

A quiet Android journal for checking in with your mood and making space for reflection. Journaling works offline, with optional encrypted Google Drive backups. Supports Android 8.0 (API 26) and newer.

## Features

- Choose from five moods, from low to great.
- Get a casual, mood-specific message and an optional invitation to reflect when selecting a mood.
- Journal freely with optional prompts about what happened, what you felt, and what you need next.
- Save a mood without writing when that is all you have energy for.
- Return to an unfinished draft after leaving the app.
- Browse your entries, read a full reflection, and permanently delete an entry after confirmation.
- Organize journals in named Heaps with optional categories and color profiles, or keep entries outside all Heaps.
- Use the **+** menu in My Journal to create a journal or Heap; filter journals across Heaps by category.
- Protect My Journal with a six-digit PIN and optional enrolled strong biometrics.
- Explore mood trends in a protected History tab with 1 week, 1 month, 3 months, 1 year, and custom date ranges.
- Save passphrase-encrypted backups to Google Drive using Android's file picker, and restore missing entries without overwriting your journal.

Selecting a mood opens a supportive pop-up tailored to Low, Down, Okay, Good, or Great. Each message invites a few words for future reflection: remembering good days or noticing what helped through hard ones. **Let’s journal** focuses the reflection field without changing existing writing; **Maybe later** dismisses the message. Selection keeps a draft; **Save this moment** still records the check-in. Restoring a draft does not replay the pop-up, and leaving the app dismisses it.

## Heaps in My Journal

Unlock My Journal and tap **+**, then choose **Journal entry** or **Heap**. Before creating your first Heap, a pop-up explains how these folders separate topics, concerns, and facets of life, and how compartmentalizing thoughts can help clear your mind. A Heap requires a name; category and color profile are optional. The explanation is shown again if you cancel before creating your first Heap.

Open a Heap to browse its journals. New journal entries created there default to that Heap; the **Save in** selector also lets you choose another Heap or **Outside Heaps**. Private unfinished entries are kept as a separate draft when the editor closes. The Check in tab keeps its existing draft and saves outside Heaps. Open an existing journal and choose **Move to Heap** to move it into a Heap or back outside all Heaps.

The main journal view lists Heaps and all journals. Each journal card identifies its Heap and uses its color profile. **Show journals outside Heaps** displays only loose entries. Selecting a category searches journals across all matching Heaps, including when you were viewing only journals outside Heaps; **All categories** removes the category restriction, while **Uncategorized** includes both Heaps without a category and entries outside Heaps. Category matching ignores letter case. Existing journals remain outside Heaps after the database upgrade; timestamps, reflections, and mood History are preserved. Heap names, categories, and the private journal editor use the existing journal lock and secure dialogs.

## Encrypted Google Drive backups

1. Install Google Drive on the Android device and sign in to the Google account you want to use. Google Drive manages account authentication; Daily Pause does not receive your Google password or request broad Drive access.
2. Unlock My Journal and tap **Back up to Google Drive**.
3. Choose and confirm a separate backup passphrase of 12–256 characters. Keep it safe; your six-digit journal PIN cannot decrypt backups, and there is no passphrase recovery.
4. In Android's system save dialog, choose **Google Drive**, an account/folder, and save the `.dpa` file. Other document providers are also supported if chosen explicitly. If Drive is absent from the locations list, open Drive and finish account setup first.

Saved entries, timestamps, moods, reflections, and all Heaps (including empty Heaps, categories, colors, and journal membership) are encrypted on the device before the save dialog opens. Backups exclude unfinished drafts, your PIN verifier, and device authentication settings. A backup uses AES-256-GCM with a random 128-bit salt, a random 96-bit nonce, a 128-bit authentication tag, and a key derived using PBKDF2-HMAC-SHA256 with 600,000 iterations. The backup header is authenticated. No passphrase or plaintext backup is written to disk; the temporary app cache contains only encrypted bytes and is removed after save/cancel/failure, with abandoned encrypted files expired after one hour.

Daily Pause accesses only the file chosen through Android's Storage Access Framework. It needs no Internet/storage/account permissions, OAuth client ID, Google Cloud project, Google access token, or backend. The Drive document provider handles Google sign-in and network transport. Completing a local provider write does not prove cloud synchronization has finished: Drive may queue a save until connectivity returns. Check Drive before deleting a local journal.

To restore, unlock My Journal, tap **Restore encrypted backup**, and choose the `.dpa` file in Drive. Returning from the picker locks the journal; unlock again, tap **Restore encrypted backup**, enter the backup passphrase, and confirm the entry count. Existing entries remain intact, exact duplicates are skipped, and the merge runs in a transaction. A wrong passphrase, modified backup, unsupported format, or failed restore adds no entries. The local PIN and draft remain unchanged. Files are limited to 20 MiB, 50,000 saved entries, and 50,000 Heaps. Existing Heaps are matched by their stable IDs, preserving distinct folders with the same name. Restoring the same backup twice skips exact duplicates.

New exports use backup format version 2 (`DPAUSE02`). Inside the encrypted payload, a Heap count precedes the Heap records (stable ID, name, category, and optional color); the entry records also include an optional Heap ID. Empty Heaps are preserved. Version 1 backups remain readable and restore their entries outside Heaps. Version 2 requires this version of Daily Pause or newer to restore.

Backup format version 1 is binary: 8-byte ASCII `DPAUSE01`, 16-byte random salt, 12-byte random nonce, then AES-GCM ciphertext including its 16-byte tag. The 36-byte header is the additional authenticated data. The encrypted payload uses big-endian integers: entry count (32-bit), then timestamp (64-bit epoch milliseconds), mood (8-bit), and three UTF-8 reflection strings, each prefixed with a 32-bit byte length. Local database IDs are omitted. Parsing enforces file, entry, mood, timestamp, and string bounds before any restore.

## Mood history

History plots the saved "How are you feeling?" responses against their recorded dates in the device's local time zone. The mood axis is fixed at Low = 1, Down = 2, Okay = 3, Good = 4, Great = 5. The week preset includes today and the previous six days. Month, three-month, and year presets use trailing calendar ranges ending today; custom dates include both endpoints.

Ranges up to one calendar month show daily averages. Longer ranges up to three months show averages over consecutive seven-day buckets starting at the selected start date. Longer ranges show calendar-month averages, clipping the first and last month to the selected dates. Multiple check-ins in a day or longer bucket are averaged across the recorded responses, so all recorded check-ins carry equal weight.

Days without entries never become zero and never contribute to an average. Entirely missing days or aggregate buckets appear as gaps, and lines do not cross those gaps. An empty range displays "No check-ins in this range". Expand "View plotted averages" for exact values, date spans, check-in counts, and missing periods. The graph and mood scale also have screen-reader descriptions.

Entries use a private SQLite database. The app requests no network or storage permissions, contains no analytics, and disables automatic Android cloud backup and device-transfer backup. Uninstalling the app or clearing its data deletes the local journal. Manually saved encrypted `.dpa` files remain in the chosen provider and require the backup passphrase to restore. The local database and drafts have no additional encryption; protect the device with its system lock too. Drive backup is a manual action rather than background account synchronization.

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

The test suite exercises Android activities and SQLite: saving and reopening a reflection, preserving drafts across activity recreation, mood validation, mood-only entries, draft preservation across tabs, and confirmed deletion without affecting other entries. Security tests cover PIN setup and confirmation, incorrect PIN rejection, persistent retry limits, salt uniqueness, malformed verifier rejection, tab and background relocking, recreation, gated post-save navigation, secure-window flags, and simulated biometric callbacks. History tests cover daily/weekly/monthly averages, missing data, multiple same-day check-ins, leap years, inclusive custom ranges, local-date filtering across daylight-saving transitions, range selection, and protected navigation. Backup tests cover authenticated encryption round trips, fresh randomness, wrong-passphrase and tamper rejection, size limits, file-picker save/cancel/failure, encrypted-only provider writes, pending-export recreation, passphrase validation, gated UI, explicit restore confirmation, duplicate skipping, and atomic rollback. Test results are written to `app/build/reports/tests/testDebugUnitTest/`; lint reports are in `app/build/reports/`.

Before a public release, also test the APK on physical devices, including actual Google Drive sign-in, folder selection, offline save/sync, reinstallation and encrypted restore, biometric success/failure/cancellation/lockout, keyboard use, rotation, larger font sizes, and Android 15 edge-to-edge layouts. Robolectric uses a simulated document provider and biometric callbacks; it does not establish real Google sign-in or completed cloud uploads.
