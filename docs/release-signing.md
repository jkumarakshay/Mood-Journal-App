# Optimized releases and signing migration

Release builds disable debugging and use R8 code shrinking plus resource shrinking. Their Gradle output is unsigned until the explicit signing step below succeeds. Neither shrinking nor production signing guarantees Play Protect approval. Keep the tested permissions and authentication checks; do not weaken them to change a scanner score.

## Signing material

The production key generated for Daily Pause is outside the public repository in `/workspace/.daily-pause-signing/`. The directory and password files have private filesystem permissions. **Keep a secure, independent backup before relying on this key for distribution.** Never commit, attach to a GitHub Release, or upload private signing files to a scanner.

Private files:

- `daily-pause-production.p12`: the production signing key (alias `daily-pause`).
- `daily-pause.password`: its randomly generated password. Store a recovery copy separately from the keystore.
- `daily-pause.lineage`: the old-key-authorized signing rotation record. It contains public certificates and signed capabilities, not a private key.
- `legacy-debug.keystore` and `legacy.password`: preserved original signer and its password, needed for compatibility builds and signing pre-Android-13 APK blocks.

A private backup archive is available at `/workspace/.daily-pause-signing/Daily-Pause-private-signing-backup.zip`. It contains both keystores and the lineage, and deliberately excludes `daily-pause.password`. Download/store the archive privately and save the password separately; neither belongs in a public release.

Retain both keys. Generating a new key each build prevents consistent updates. Source and public artifact archives exclude all private signing material. Anyone controlling a trusted signing key can sign updates; protect the production key accordingly.

## Register the production certificate before using Google sign-in

In Firebase project **daily-pause-a0aa2**, open **Project settings → General → Your apps → com.moodjournal.app → Add fingerprint**. Add both values and retain the existing debug certificate registrations:

```text
Production SHA-1:
5D:53:AE:01:31:0C:DF:CF:23:2D:01:0B:5D:73:9D:9D:C7:02:10:C2

Production SHA-256:
46:1A:FE:B4:18:5B:96:4F:7C:46:56:4A:B4:68:F8:C0:87:9F:AA:D6:2D:BA:58:23:B0:E9:5B:39:22:FF:05:F1
```

Check that the linked Google Cloud project has an Android OAuth client for package `com.moodjournal.app` with the production SHA-1. Firebase normally creates it when the fingerprint is registered. If the Firebase API key is restricted to Android apps, allow this package/SHA-1 too. Keep the existing Web OAuth client ID used as the token audience. These fingerprints are public identifiers, not private keys.

First sign-in and PIN recovery must be tested on a phone after this console change. Do not distribute a new-signer APK as ready for Google authentication before registration. Existing linked accounts remain the same Firebase UID/Google subject; a signing-key transition must not change the Firebase project or installation owner.

## Build and sign

With the normal Android SDK/JDK toolchain:

```sh
./gradlew assembleRelease testDebugUnitTest lintRelease
```

Find the unsigned APK under `app/build/outputs/apk/release/`. The release filename follows `version.properties`, currently `Daily-Pause-v1.11.0.apk`. Signing refuses debuggable, test-only and already signed inputs, and refuses to overwrite an existing output.

For a rotated release with the new signer on Android 13+ and the original signer on Android 8–12L:

```sh
python3 scripts/sign-release.py app/build/outputs/apk/release/Daily-Pause-v1.11.0.apk \
  --output-dir build/signed/production \
  --keystore /workspace/.daily-pause-signing/daily-pause-production.p12 \
  --password-file /workspace/.daily-pause-signing/daily-pause.password \
  --lineage /workspace/.daily-pause-signing/daily-pause.lineage \
  --legacy-keystore /workspace/.daily-pause-signing/legacy-debug.keystore \
  --legacy-password-file /workspace/.daily-pause-signing/legacy.password
```

For an optimized compatibility build retaining the original signer on every supported Android version:

```sh
python3 scripts/sign-release.py app/build/outputs/apk/release/Daily-Pause-v1.11.0.apk \
  --output-dir build/signed/compatibility \
  --keystore /workspace/.daily-pause-signing/legacy-debug.keystore \
  --password-file /workspace/.daily-pause-signing/legacy.password \
  --alias androiddebugkey
```

Generate a sidecar for the **exact** signed APK chosen for publication:

```sh
python3 scripts/create-update-manifest.py build/signed/production/Daily-Pause-v1.11.0.apk
```

Both example variants share the same package/version/filename but have different hashes. Keep them in separate directories. Publish only the chosen APK and its matching manifest for a given version. Do not replace one release asset with the other after users have downloaded it. Increment `releaseCode` once for each newly published release, not per local build or commit. No version bump or publishing is implied by preparing these artifacts.

## Existing installations and the updater

The production APK contains a signed certificate lineage granting installed-data continuity, with rollback to the old key disabled. `apksigner` uses the new signer starting at API 33; older versions use the original signer. This preserves the existing minimum API 26 without forcing an uninstall. The lineage and older signing blocks still contain the debug certificate, so a scanner may continue flagging that certificate.

The revised updater keeps exact-signer checks on Android 8–12L. On Android 13+ it additionally accepts a forward transition only when Android's archive signature parser supplies a proven certificate history containing the installed current signer and ending at the incoming current signer. It rejects unrelated keys, sibling branches sharing an ancestor, old-key rollback, malformed histories, and partial matches of multiple signers. Manifest/hash/version checks still apply. Android's installer independently validates the update.

An already installed older updater does not know this policy. For in-app migration, first publish an original-key compatibility release containing the revised updater, then publish a higher-version rotated release. Manual installation of a valid rotated APK can use Android's own rotation support, but must be tested on a phone. Same-version local builds do not appear as in-app updates because the updater correctly requires a higher version code.

Before testing migration, make an encrypted backup and verify its passphrase. Install over the existing app without uninstalling or clearing app data. Check journals, Heap membership/descriptions, PIN/biometric settings, linked Google identity, backups, mood history/analysis and notification preferences. A fresh installation is a separate test and does not verify preservation of existing data.

## Release validation

Test the optimized APK itself on a supported phone after registering its certificate. Cover Google sign-in and PIN recovery, biometric/PIN unlock, editing/deleting journals and Heaps, filters, PDF/ZIP export, encrypted backup/restore, reminders and the tutorial. Verify both exact-signer updates and rotated updates, including rejection of modified/unrelated/rollback APKs.

Retain R8 mapping, usage and effective-rule outputs with the build for diagnosis. Inspect whether unused camera/telephony helper classes were actually removed. Scan the exact distributed APK and compare its SHA-256, not merely its filename. VirusTotal sandbox scores are separate from antivirus vendor detections and Play Protect classification.

References: [Android signing](https://developer.android.com/tools/apksigner), [SigningInfo certificate history](https://developer.android.com/reference/android/content/pm/SigningInfo#getSigningCertificateHistory()), [R8 optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization), [Firebase Google authentication](https://firebase.google.com/docs/auth/android/google-signin).
