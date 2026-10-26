# Daily Pause

A quiet Android journal for checking in with your mood and making space for reflection. Journaling works offline, with optional encrypted Google Drive backups. Supports Android 8.0 (API 26) and newer.

## First launch

A fresh installation opens a themed welcome page with a brief introduction, **Quick Tour**, and **Thanks!**. Thanks goes straight to Check in; Quick Tour follows nine manually advanced steps: Check in tab → emotions → Let it out → weather → My Journal tab → journal/Heap **+** → Analytics tab → Mood Graph → Mood Analysis. The first seven coach marks highlight a control and point to it with an arrow. The final two explain each Analytics view in a centered pop-up. Every step waits for **Next**, including the final step; **Skip tour** returns to Check in.

Private steps use clearly marked, empty previews without opening saved entries, generating reports, creating a PIN, or bypassing the journal lock. Notification permission is requested after leaving onboarding. Progress survives backgrounding, rotation, process recreation and reminder navigation. The welcome page does not replay after completion or skipping, and existing installations with app data or an update history bypass onboarding. Clearing app data or reinstalling starts fresh. Onboarding preferences remain on the device and are excluded from journal backups.

## Features

- Choose from five moods, from low to great.
- Get a casual, mood-specific message and an optional invitation to reflect when selecting a mood.
- Journal freely with optional prompts about what happened, what you felt, and what you need next.
- Save a mood without writing when that is all you have energy for.
- Return to an unfinished draft after leaving the app.
- Browse your entries, read a full reflection, and permanently delete an entry after confirmation.
- Organize journals in named Heaps with optional categories and color profiles, or keep entries outside all Heaps.
- Use the **+** menu in My Journal to create a journal or Heap; filter journals across Heaps by category.
- See today’s weather high/low and description at the top left of Check in; choose Celsius or Fahrenheit in Settings → Weather.
- Discover private, on-device weather, journal-theme and calendar associations after 30 distinct journaling days; reports are reviewed weekly.
- Open Settings from the top-right gear for About, a saved Light or Dark theme, the daily notification time, Weather, and Updates.
- Check public GitHub Releases for in-app updates, download through Android, and install after confirmation.
- Protect My Journal with a six-digit PIN and optional enrolled strong biometrics.
- Open the protected **Analytics** menu for **Mood Graph** (existing ranges and chart) or **Mood Analysis** (a formatted pattern report).
- Save passphrase-encrypted backups to Google Drive using Android's file picker, and restore missing entries without overwriting your journal.

Selecting a mood opens a supportive pop-up tailored to Low, Down, Okay, Good, or Great. Each message invites a few words for future reflection: remembering good days or noticing what helped through hard ones. **Let’s journal** focuses the reflection field without changing existing writing; **Maybe later** dismisses the message. Selection keeps a draft; **Save this moment** still records the check-in. Restoring a draft does not replay the pop-up, and leaving the app dismisses it.

## Settings and appearance

The top-right gear opens **Settings** from Check in, My Journal, or Analytics. **About** gives a brief overview of Daily Pause and its privacy features. **Theme** lets you select **Light** or **Dark**, applying the appearance immediately and remembering it across app restarts. Light is the default. The back button returns to Settings and then to the previous tab; the bottom navigation also returns directly to a tab.

**Notification** sets the daily check-in time, defaulting to **9:00 PM** in the device's local time zone. There is no in-app off switch. Reminders cycle through 20 casual, supportive messages, with no journal text included. Tapping one opens Check in, preserving the current draft and leaving My Journal and Analytics locked.

Android 13 and later asks for notification permission once after the welcome page or tour is finished. If permission or the notification channel is blocked in Android, this page offers **Allow notifications** to request permission or open Android's notification settings. Android ultimately controls notification access. The app uses local, idle-allowed clock alarms and reschedules after delivery, reboot, app update, or a clock/time-zone change. Android can deliver reminders later while saving battery; no special exact-alarm permission is requested. Force-stopping the app prevents reminders until it is opened again. The chosen time and message position stay on this device and are not included in journal backups.

Dark mode covers pages, journal and Heap cards, forms, dialogs, authentication, backups, Mood Graph, analysis cards, chart labels, and system bars. Recommended pastel Heap colors use darker versions of the same hues. Other custom RGB colors retain their exact values, with card text and menu icons adjusted for contrast; Default follows the app theme. The saved color values and encrypted backup format remain unchanged. Opening Settings locks private tabs. Returning to My Journal or Analytics requires another unlock, including after a theme change. Check-in drafts and the selected Mood Graph range are retained. The appearance preference stays on this device and is not included in journal backups.

App-owned pop-ups share the app palette in both themes: rounded cream/dark surfaces, a Daily Pause heading, serif titles, lavender secondary actions and a purple primary action with pressed/disabled states. Journal, Heap, backup and weather forms use matching fields and scroll when space is limited. Analytics/export menus and Spinner lists use the same surface colors; native date/time pickers keep their controls while receiving themed dialog surfaces and actions. Existing validation, cancellation, private-draft handling and secure-window protection are retained. Android controls the appearance of its permission prompts, biometric prompt, system notifications/toasts and document picker.

## In-app updates

**Settings → Updates** shows the installed app version, an **Automatic update checks** toggle (on by default), and **Check for updates**. Automatic checks run at most once per 24 hours while the app is in the foreground on Check in, after onboarding. They avoid private screens and other open dialogs. Manual checks work with automatic checks disabled, and completed manual checks also reset the daily automatic-check interval. Network failures never block journaling; a failed automatic check waits until the next day, while manual retry remains available. The last successful check is shown in local device time.

Checks read the latest stable public GitHub Release from `jkumarakshay/Mood-Journal-App`; drafts and prereleases are excluded. Newer releases show version, download size and plain-text release notes. An automatic offer appears once per version. Downloading always requires a tap; **Automatic update checks** does not enable automatic downloads or silent installations. **View GitHub releases** opens the repository in a browser.

Android Download Manager continues requested downloads across backgrounding and process/activity recreation. Updates shows progress, retry/cancel controls and **Install update** when ready. Before each installation attempt, the app checks the exact file size and SHA-256, package identity, increasing Android version code, version name, minimum SDK and signing certificate. A failed verification removes the download. A narrow FileProvider shares only the downloaded APK with read permission. Android may ask for **Allow from this source** for Daily Pause; returning from that page rechecks the file before opening the installer. Android always confirms installation. Cancelling the installer leaves the verified download available for retry; the app does not claim installation succeeded before Android installs it.

The package ID and signing key must remain the same to preserve journals, Heaps, PIN, preferences and backups during an update. No database or encrypted-backup migration is introduced by the updater. Update metadata, preferences and download IDs stay on the device and are excluded from encrypted journal backups. Old downloaded APKs are removed after installing the newer version. No GitHub token, journal text, mood data or location is sent with checks.

### Publishing an update

Install a version containing the updater once to enable it; older APKs do not yet contain it. **`version.properties` is the single source of release numbering.** Set `releaseCode=11` for version code **11**, app version **1.11.0**, APK **Daily-Pause-v1.11.0.apk**, GitHub tag **v1.11.0**, and release title **Daily Pause 1.11.0**. For the next published APK, change only the counter to **12**; these values become **12 / 1.12.0 / Daily-Pause-v1.12.0.apk / v1.12.0**. Ordinary commits do not advance the release number. Existing version-code-9 and version-code-10 installations can update normally with the same signing key. Keep the counter between 1 and 99999 to stay compatible with existing updaters.

1. Build and test the APK with the **same existing signing key**. Keep that key outside the public repository. A fresh CI-generated debug key will not update existing installations.
2. Run `python3 scripts/create-update-manifest.py path/to/Daily-Pause-v1.11.0.apk` with `ANDROID_HOME` set, or pass `--build-tools /path/to/sdk/build-tools/35.0.0`. The helper verifies the signature and actual package/version/SDK, rejects a mismatched release number or filename, then writes `daily-pause-update.json` beside the APK with its size and SHA-256. It also prints the matching GitHub tag and title.
3. Commit and push the tested release source to `main`. Run `python3 scripts/publish-release.py path/to/Daily-Pause-v1.11.0.apk --notes-file path/to/release-notes.md --dry-run` to check the release plan. Without `--dry-run`, the helper uses an authenticated GitHub CLI to create a draft, upload **both the unchanged APK and daily-pause-update.json**, then publish it as the latest stable release. It derives the tag/title from the APK manifest and checks the counter against `version.properties`; no separately typed tag is needed. Alternatively, create the matching release manually in GitHub and attach both files, without marking it as a prerelease.
4. Users can use Check for updates immediately or receive the next automatic offer. An incompatible Android minimum is explained without offering installation; an equal or lower version code is never offered.

No release or tag is published by the app or the manifest helper; publishing is an explicit developer action. The updater requires the manifest and APK for a supported release; it reports a retryable check failure if a latest release is incomplete. There are no background periodic jobs or extra notification permissions for checks. Daily check-in notification settings are independent.

## Weather and reflection

The top-left Check in header shows the selected location’s daily high/low and general description, such as Sunny, Partly sunny, Rainy, or Cloudy. Tap it or open **Settings → Weather**. **Celsius (°C)** is the default; **Fahrenheit (°F)** is remembered across restarts and applies to the header, saved journal details, and all PDF export formats. Changing units does not change the stored temperatures.

Use **Choose city** to search for a location without granting device location permission, or tap **Use current location** to request approximate foreground location. Only a city search or coordinates rounded to two decimal places go to Open-Meteo. The app does not send journal text, moods, or weather answers to the weather service, and coordinates are not copied into journal entries or backups. The location choice stays on this device; update it when you travel. There is no background location tracking. A device location failure or denied permission still leaves city search and journaling available.

Weather comes from [Open-Meteo’s forecast API](https://open-meteo.com/en/docs), whose data can be retained with journals under [CC BY 4.0](https://open-meteo.com/en/terms). Daily values are forecasts, with WMO conditions simplified into short English descriptions. The free endpoint needs no API key and is intended for non-commercial apps without subscriptions or advertising; a commercial deployment must use an appropriate Open-Meteo plan. City search uses [Open-Meteo Geocoding](https://open-meteo.com/en/docs/geocoding-api). Source/license attribution is shown in Settings and included with exported weather.

Forecasts refresh on showing/resuming Check in when older than three hours, with a 15-minute automatic retry interval after failures; **Refresh weather** retries immediately. Only the chosen location’s current local day can appear as today’s weather. The header updates at that location’s midnight while the app stays active. A network failure can keep that day’s cached forecast; yesterday’s data is never substituted for today, and unavailable values are never replaced with fabricated zero temperatures. Weather uses HTTPS with bounded responses and timeouts on a worker thread.

The weather-influence question has been removed from **Let it out** and the private journal composer. Saving a Check in moment still captures the available forecast before Heap selection/unlocking, so a later refresh cannot change the weather attached to that journal. Each entry keeps its own date, high/low in Celsius, condition and source. Journaling never waits for a weather request, and saving without weather is allowed. Existing saved answers and unfinished legacy draft answers are preserved, readable and included in exports and encrypted backups; new drafts have no weather answer.

Older journals retain their original content and have no invented weather or answer. SQLite upgrades from versions 1, 2, 3 and 4 preserve entries, Heaps, weather, legacy answers and mood history. Weather snapshots and answers participate in encrypted backup/restore and all PDF/ZIP exports. An answer-only reflection is exportable; a mood-only check-in without writing or a weather answer still has Export disabled. Automatic weather data alone does not make an empty journal exportable.

## Heaps in My Journal

Unlock My Journal and tap **+**, then choose **Journal entry** or **Heap**. Before creating your first Heap, a pop-up explains how these folders separate topics, concerns, and facets of life, and how compartmentalizing thoughts can help clear your mind. A Heap requires a name; category, description (up to 2,000 characters) and color profile are optional. The explanation is shown again if you cancel before creating your first Heap.

Under **Color profile**, tap **Choose color** for an RGB color wheel. Drag around the circle for hue and toward the center for softer colors; a brightness slider includes dark shades, black and white. Lavender, Sage, Peach, Sky and Rose remain as recommended swatches. You can also enter a six-digit RGB hex code, such as `#6FAEE8`, or choose **Use default color**. The preview and RGB values update as you choose. The wheel supports keyboard arrows and accessibility hue adjustment, and the text field provides precise selection without dragging. Invalid codes keep Heap creation open for correction. Picking a color does not save the Heap until **Create Heap** is pressed.

Custom colors are stored as opaque RGB values and survive journal/category listings, PDF accent colors and encrypted backup/restore. The color encoding stores the full RGB integer unchanged; the description field has its own migration described below. Older app versions that only accepted the fixed palette may reject backups containing custom colors; restore these with this version or newer. Existing preset/default colors remain compatible.

Open a Heap to browse its journals. New journal entries created there default to that Heap; the **Save in** selector also lets you choose another Heap or **Outside Heaps**. Private unfinished entries are kept as a separate draft when the editor closes. The Check in tab keeps its own draft. **Save this moment** asks **Save in a Heap?**: **Choose Heap** opens the current Heap list after unlocking My Journal, **Create New** opens Heap creation after unlocking, and **No** immediately saves outside Heaps. The unlocked list also offers **Create New** and **No**. Creating a Heap saves the pending moment into it automatically. Cancelling the choice or unlock leaves the moment as a draft; cancelling Heap creation returns to the destination list. Backgrounding or rotating the app requires unlocking again before any Heap names appear, and does not save a pending moment automatically. Open an existing journal and choose **Move to Heap** to move it into a Heap or back outside all Heaps.

The main journal view lists Heaps and all journals. Each journal card identifies its Heap and uses its color profile. **Show journals outside Heaps** displays only loose entries. Selecting a category searches journals across all matching Heaps, including when you were viewing only journals outside Heaps; **All categories** removes the category restriction, while **Uncategorized** includes both Heaps without a category and entries outside Heaps. Category matching ignores letter case. Existing journals remain outside Heaps after the database upgrade; timestamps, reflections, and mood history are preserved. Heap names, categories, and the private journal editor use the existing journal lock and secure dialogs.

The three-dot menus on journal cards, journal details, Heap cards and an open Heap put **Edit** before **Export**. Editing a journal opens a prefilled, secure pop-up for mood, answers and Heap destination, with **Save Changes** at the bottom. It updates the same entry and preserves its original date, recorded weather and any legacy weather answer. Editing a Heap lets you change its name, category, description and RGB color without changing its identity or moving its journals. Blank journals and empty Heaps can still be edited while their Export action stays disabled.

Changes are applied only when **Save Changes** succeeds. Cancel, backgrounding or recreation discards unsaved edits and requires a fresh unlock; edits do not replace the separate new-entry or Check in drafts. Failed saves keep the edit form open for retry and leave the stored item intact. Saving edits refreshes cards, categories and colors; editing mood or text also invalidates stale Analytics reports. SQLite schema version 5 adds an empty description to existing Heaps without replacing journals or history.

## Journal and Heap exports

Unlock **My Journal**, then use **⋮ → Export** at the top right of a journal card or its open detail view to save a PDF. Each Heap has the same menu, both in the Heap list and inside the Heap. Choose **Export as zip** for a separate PDF per exportable journal, or **Export as collated PDF** for one PDF ordered from oldest to newest, with every journal starting on a fresh page.

The A4 layout includes a Heap-color accent, date and time, mood, Heap/category, labeled answers, saved weather high/low and description, weather influence answer, attribution, and page numbers. Long answers wrap and continue onto additional pages. A journal with no writing or weather answer has a greyed-out Export action; mood-only check-ins remain saved and counted in Mood Graph. Heap exports exclude these blank journals and are disabled if no exportable journals are available, including an empty Heap. Category filters do not limit the contents of a Heap export.

Android's system save dialog chooses the destination, including local folders or document providers such as Google Drive. Generation and file writes run in the background after you choose a location. The app does not cache readable PDF or ZIP files. The picker request survives activity recreation using entry/Heap IDs, without placing journal text in saved view state; returning from the picker leaves the journal locked. Cancelling creates no export, and a failed write reports an error and attempts to remove the incomplete provider file.

PDF and ZIP exports are readable, unencrypted documents, separate from the encrypted `.dpa` backup format. They cannot be restored as backups. Use encrypted backups below to preserve and restore journal data privately.

## Encrypted Google Drive backups

Open **Settings → Backup/Restore** for both backup and import/restore. The controls are no longer below the journal listing. Unlock this page with the same PIN or enrolled biometrics used for journals and Analytics; its authenticated session stays separate, so it does not unlock those views. Restore is available even when there are no saved entries or Heaps, while Back up remains disabled until saved data exists. The actions use the app palette in both themes.

1. Install Google Drive on the Android device and sign in to the Google account you want to use. Google Drive manages account authentication; Daily Pause does not receive your Google password or request broad Drive access.
2. Open **Settings → Backup/Restore**, unlock with your PIN or biometrics, and tap **Back up to Google Drive**.
3. Choose and confirm a separate backup passphrase of 12–256 characters. Keep it safe; your six-digit journal PIN cannot decrypt backups, and there is no passphrase recovery.
4. In Android's system save dialog, choose **Google Drive**, an account/folder, and save the `.dpa` file. Other document providers are also supported if chosen explicitly. If Drive is absent from the locations list, open Drive and finish account setup first.

Saved entries, timestamps, moods, reflections, and all Heaps (including empty Heaps, categories, descriptions, colors, and journal membership) are encrypted on the device before the save dialog opens. Backups exclude unfinished drafts, your PIN verifier, and device authentication settings. A backup uses AES-256-GCM with a random 128-bit salt, a random 96-bit nonce, a 128-bit authentication tag, and a key derived using PBKDF2-HMAC-SHA256 with 600,000 iterations. The backup header is authenticated. No passphrase or plaintext backup is written to disk; the temporary app cache contains only encrypted bytes and is removed after save/cancel/failure, with abandoned encrypted files expired after one hour.

For backups, Daily Pause accesses only the file chosen through Android's Storage Access Framework. Backups need no storage/account permissions, OAuth client ID, Google Cloud project, Google access token, or app backend; the separate weather feature uses Internet access. The Drive document provider handles Google sign-in and network transport. Completing a local provider write does not prove cloud synchronization has finished: Drive may queue a save until connectivity returns. Check Drive before deleting a local journal.

To restore/import, open **Settings → Backup/Restore**, unlock the page, tap **Restore encrypted backup**, and choose the `.dpa` file in Drive or another provider. Returning from the picker locks Backup/Restore; unlock that page again, tap **Restore encrypted backup**, enter the original backup passphrase, and confirm the entry count. Existing entries remain intact, exact duplicates are skipped, and the merge runs in a transaction. A wrong passphrase, modified backup, unsupported format, or failed restore adds no entries. The local PIN and draft remain unchanged. Files are limited to 20 MiB, 50,000 saved entries, and 50,000 Heaps. Existing Heaps are matched by their stable IDs, preserving distinct folders with the same name. Restoring the same backup twice skips exact duplicates. Existing Heap metadata, including any local edits, is retained when an incoming Heap has the same stable ID.

Backups with a nonempty Heap description use format version 4 (`DPAUSE04`), which adds a bounded UTF-8 description to each Heap and includes the version-3 weather fields. Descriptions are also printed in PDF/ZIP exports. Older apps cannot restore version 4; this app continues reading versions 1, 2 and 3 with an empty description. When all descriptions are empty, backups with weather data or an influence answer use format version 3 (`DPAUSE03`), which adds an answer byte and a bounded, versioned weather snapshot to each entry inside the encrypted payload. Snapshots preserve the date, original Celsius temperatures, description, attribution, retrieval time and optional expiry. The weather snapshot is binary, Base64-encoded for storage, and validated before restore. Older app versions cannot restore version 3. Snapshots without weather or answers continue using version 2 (`DPAUSE02`). Inside the encrypted payload, a Heap count precedes the Heap records (stable ID, name, category, and optional color); the entry records also include an optional Heap ID. Empty Heaps are preserved. Version 1 and 2 backups remain readable with no weather or answer; version 1 restores entries outside Heaps. Version 2 requires this version of Daily Pause or newer to restore.

Backup format version 1 is binary: 8-byte ASCII `DPAUSE01`, 16-byte random salt, 12-byte random nonce, then AES-GCM ciphertext including its 16-byte tag. The 36-byte header is the additional authenticated data. The encrypted payload uses big-endian integers: entry count (32-bit), then timestamp (64-bit epoch milliseconds), mood (8-bit), and three UTF-8 reflection strings, each prefixed with a 32-bit byte length. Local database IDs are omitted. Parsing enforces file, entry, mood, timestamp, and string bounds before any restore.

## Analytics

Tap **Analytics** in the bottom navigation to open a rounded, themed dropdown with **Mood Graph** and **Mood Analysis**, matching icons and brief descriptions. The current Analytics view has a lavender highlight and checkmark. The same menu opens from the view selector; it supports keyboard navigation and both light and dark themes. Both views use the existing journal PIN/biometric gate. Switching between these two views keeps the current Analytics unlock; leaving Analytics, opening Settings, backgrounding or recreating the activity locks it again. The selected view and graph range survive recreation without preserving an unlocked state.

### Mood Graph

Mood Graph plots the saved "How are you feeling?" responses against their recorded dates in the device's local time zone. The mood axis is fixed at Low = 1, Down = 2, Okay = 3, Good = 4, Great = 5. The week preset includes today and the previous six days. Month, three-month, and year presets use trailing calendar ranges ending today; custom dates include both endpoints.

Ranges up to one calendar month show daily averages. Longer ranges up to three months show averages over consecutive seven-day buckets starting at the selected start date. Longer ranges show calendar-month averages, clipping the first and last month to the selected dates. Multiple check-ins in a day or longer bucket are averaged across the recorded responses, so all recorded check-ins carry equal weight.

Days without entries never become zero and never contribute to an average. Entirely missing days or aggregate buckets appear as gaps, and lines do not cross those gaps. An empty range displays "No check-ins in this range". Expand "View plotted averages" for exact values, date spans, check-in counts, and missing periods. The graph and mood scale also have screen-reader descriptions.

### Mood Analysis

The first report requires **30 distinct journaling days within the last 90 days**. Days need not be consecutive; multiple entries on one day do not fill the threshold faster. The waiting view shows progress. Open Mood Analysis to generate the first eligible report. Reports use daily mood averages with equal weight per day, leaving missing days out, and present three sections with comparison counts and averages:

- **Weather-related patterns:** saved same-day forecast descriptions and relative high/low temperature groups. Missing, expired, mismatched-date and conflicting same-day weather are excluded. Both comparison groups need usable weather.
- **Response-related patterns:** a compact bundled English TF-IDF centroid model recognizes 12 broad themes in journal responses and Heap names/categories, then compares mood on days mentioning each theme with other days that have recognized themes; unclassified days are excluded from these text comparisons. Themes can describe experiences, concerns or wishes; mentioning exercise does not prove the person exercised. Written tone is estimated from the event/thoughts fields, excluding future-needs answers.
- **Time-related patterns:** weekday comparisons, week-of-month blocks (days 1–7, 8–14, 15–21, 22–28, and 29–end) after at least three recorded months, and repeated month-of-year comparisons after at least a year of history and observations of that month in two years. The latter can use up to three years of mood history; other comparisons use the recent 90 days. Matching written-tone differences are mentioned when enough confidently classified text supports them. A supported wording pattern can also appear when mood ratings stay stable; it is explicitly labeled as an estimated written-tone pattern.

Comparisons require repeat observations (normally at least 5 group days and 8 comparison days; weekdays require 4 group days, repeated calendar months 8). A mood difference must be at least 0.5 points on the five-point scale and pass a two-sided Welch comparison with conservative adjustment for the planned mood and written-tone comparisons in that section. Only a few strongest supported associations are displayed. No result is also a valid report: 30 recorded days do not guarantee a detectable pattern. These exploratory checks assume daily observations are independent; they do not establish causation or clinical significance, and correlated days, changing circumstances and missing observations can distort results.

Once generated, the report is cached privately and becomes due **one local-calendar week later**. New entries join the next review. Android JobScheduler performs the persisted review without network access when battery conditions allow; it may run late, and force-stop delays it until the app is reopened. Opening Mood Analysis catches up when overdue. Clock rollback, a timezone change, or an analysis/model-version change forces a fresh review. Editing, moving or deleting a journal, or changing an existing Heap, clears the old report so removed content cannot remain in cached insights. Reports and model classifications are derived data and are excluded from encrypted backups; restored journals can be analyzed again.

Processing runs on a background thread entirely on-device, with no OpenAI account, API, subscription, model download or generative LLM. The small reference corpus is included in [the app assets](app/src/main/assets/journal-text-model.tsv); see the [text model card](docs/text-model-card.md) for scope and limitations. English wording and 12 broad themes limit what can be recognized. Unknown or ambiguous text can remain unclassified, and sarcasm, negation, mixed feelings and languages other than English may be misread. Report wording describes associations and estimates rather than diagnoses. This lightweight approach is intended to fit phones such as the Galaxy A15 with 6 GB RAM; physical-device performance has not been measured.

Entries use a private SQLite database. The app requests notification and boot-completed permissions for local daily reminders, requests Internet permission for Open-Meteo and optional approximate foreground location permission (city selection does not need it), requests no storage permissions, contains no telemetry or third-party analytics SDK, and disables automatic Android cloud backup and device-transfer backup. Uninstalling the app or clearing its data deletes the local journal. Manually saved encrypted `.dpa` files remain in the chosen provider and require the backup passphrase to restore. The local database and drafts have no additional encryption; protect the device with its system lock too. Drive backup is a manual action rather than background account synchronization.

## Journal lock

The first visit to My Journal, Analytics or Settings → Backup/Restore shows styled **Set PIN and unlock** and **Use Biometrics** actions. Set PIN opens a themed, secure pop-up to enter and confirm six digits. Use Biometrics also requires a backup PIN first. After the PIN is saved, devices with enrolled strong biometrics ask **Use biometrics first?** Choosing **Yes, use biometrics** makes Android's biometric prompt open automatically on later visits, with **Sign in using PIN instead** available on both the locked page and system prompt. Choosing **Use PIN** keeps PIN-first authentication and does not ask repeatedly. PIN fallback does not turn off the saved biometric preference.

Existing users keep their PIN and are offered the same preference after their next successful PIN unlock. Devices without available strong biometrics can use PIN without opting in; a biometric-first user can always fall back to PIN if biometrics become unavailable. The two private tabs and Backup/Restore share the PIN and on-device biometric preference; unlocking one context does not unlock another. Biometric templates stay with Android and are never accessed or saved by Daily Pause. You can return to Check in without unlocking.

The two private tabs and Backup/Restore lock every time you leave their context, background the app, or recreate the activity (including rotation). Backup passphrase and confirmation dialogs close when you leave the Backup/Restore page; stale controls cannot approve a later operation. Saving an entry also passes through the same gate before showing any saved content. Open journal and date-picker dialogs close when the app is paused. Screenshots and recent-app previews are protected using Android's secure-window flag. Check-in writing and its unfinished draft remain accessible without unlocking. Mood Graph retains the selected range across activity recreation, but requires authentication again before displaying it.

Only a randomly salted PBKDF2-HMAC-SHA256 PIN verifier (600,000 iterations) is saved, not the PIN itself. PIN fields are masked, excluded from saved view state and autofill, and cleared when submitted or dismissed. Setup, fallback and preference pop-ups close when the app locks. Five incorrect PIN attempts start a persistent retry delay, increasing from 30 seconds to a maximum of five minutes. Enrolled biometrics can still unlock during a PIN retry delay. Switching authentication methods, cancelling a dialog, leaving the screen or backgrounding invalidates earlier callbacks. The biometric preference and PIN verifier are excluded from encrypted backups.

There is no PIN recovery or PIN-change flow in this version. Remember your PIN; clearing app data resets it but also deletes all entries. The journal lock controls access through the app's interface and does not add encryption to the database or drafts.

## Build an APK

Open this folder in Android Studio, or use a JDK 17 or 21 and the Android SDK with platform 35 and build tools 35.0.0. Set `ANDROID_HOME` to the SDK directory, or use Android Studio's generated `local.properties`.

```sh
./gradlew assembleDebug
```

The installable APK is **`app/build/outputs/apk/debug/Daily-Pause-v1.11.0.apk`** for `releaseCode=11`. Every branch and detached/source-archive build uses the same version-based rule. APK naming does not run Git, depend on `origin/main`, or count commits. The old `apkCommitCount`, `apkBranch`, and branch-count overrides are no longer used. Increment `releaseCode` in `version.properties` once for each newly published APK; Android requires it to increase to offer an update.

Transfer the APK to an Android phone, open it, and allow installation from the app opening the file when prompted. With a connected development device, use:

```sh
adb install -r app/build/outputs/apk/debug/Daily-Pause-v1.11.0.apk
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

The test suite exercises Android activities and SQLite: saving and reopening a reflection, preserving drafts across activity recreation, mood validation, mood-only entries, draft preservation across tabs, and confirmed deletion without affecting other entries. Security tests cover PIN setup and confirmation, incorrect PIN rejection, persistent retry limits, salt uniqueness, malformed verifier rejection, tab and background relocking, recreation, gated post-save navigation, secure-window flags, and simulated biometric callbacks. History tests cover daily/weekly/monthly averages, missing data, multiple same-day check-ins, leap years, inclusive custom ranges, local-date filtering across daylight-saving transitions, range selection, and protected navigation. Backup tests cover authenticated encryption round trips, fresh randomness, wrong-passphrase and tamper rejection, size limits, file-picker save/cancel/failure, encrypted-only provider writes, pending-export recreation, passphrase validation, gated UI, explicit restore confirmation, duplicate skipping, and atomic rollback. Reminder tests cover the 20-message cycle, local-time scheduling, daylight-saving changes, alarm replacement, duplicate prevention, permission denial, clock/reboot recovery, notification navigation, and settings in both themes without an off switch. Test results are written to `app/build/reports/tests/testDebugUnitTest/`; lint reports are in `app/build/reports/`.

Export tests cover disabled blank/empty actions, PDF/ZIP picker types, safe unique ZIP filenames, wrapped multi-page text, fresh pages between journals, provider failures, cancellation, recreation, and private navigation. Robolectric lacks Android's native `PdfDocument` implementation: a test-only recording substitute captures page sizes and native Canvas rendering and supplies a distinct fixture for provider/ZIP tests. This does not validate native PDF encoding. Production uses the Android PDF writer directly with no export library or extra permissions.

Weather tests cover forecast parsing and failures, selected-location dates, unit conversion, persistence, migration, old and new encrypted backups, snapshot freezing during Heap selection, optional answer-only reflection, permission denial, manual city search and cached offline use. Weather HTTP is replaced by deterministic fixtures in automated tests.

Before a public release, also test the APK on physical devices, including real forecast/city requests, approximate location grant/denial/provider failure, local-midnight transitions, native PDF readability/text selection, ZIP extraction, long and multilingual journals, actual Google Drive sign-in, folder selection, offline save/sync, reinstallation and encrypted restore, biometric success/failure/cancellation/lockout, keyboard use, rotation, larger font sizes, and Android 15 edge-to-edge layouts. Robolectric uses a simulated document provider and biometric callbacks; it does not establish real Google sign-in or completed cloud uploads.

Analytics tests cover distinct-day eligibility, sparse and conflicting weather, strong and absent associations, equal daily weighting, weekly/DST refresh dates, cache invalidation after mutations, schema migration, background-job constraints, text classification/abstention and protected navigation/recreation.

First-launch tests cover both welcome choices, exact manual tour order, resume/recreation, reminder intents, existing-install migration, notification deferral and locked, data-free private previews. Native rendering checks coach-mark targets and reachable Next actions in both themes and with larger text on a small screen. Existing feature tests explicitly start as returning users through a test-only Application.

Updater tests cover stable-release parsing, malformed/cross-repository metadata, manifest/checksum/size limits, version ordering, clock changes, toggling and manual checks, automatic throttling/offers, stale callbacks, persistent Android downloads, certificate/package/SDK verification, narrow installer URI permissions, permission-return revalidation, journal preservation and Android 8 compatibility. Release HTTP and Android package verification metadata use deterministic test fixtures; real Android installer consent remains a device-level check.

Authentication preference tests cover secure PIN pop-ups, required confirmation before biometric opt-in, persistent Yes/Use PIN choices, migration after existing-PIN verification, unavailable biometrics, automatic prompt cancellation, PIN fallback, failed scans, terminal cancellation, duplicate/stale callbacks and persistent PIN retry delays. Android 8 compatibility and native light/dark layouts at normal and enlarged text sizes are included. Actual enrolled fingerprint/face hardware and manufacturer-specific system prompts require device testing.
