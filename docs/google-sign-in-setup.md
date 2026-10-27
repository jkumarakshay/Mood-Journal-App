# Set up Google sign-in for Daily Pause

Google sign-in requires a Firebase project and Google OAuth configuration. Supabase and a custom email-code service are not required. The Android integration is on `fix-incomplete-features`; the supplied configuration for project **daily-pause-a0aa2** has been added. Confirm Google authentication and the signing certificates are enabled in the console before testing on a phone.

## 1. Create the Firebase project

1. Open the [Firebase console](https://console.firebase.google.com/) and select **Create a project**.
2. Name the project **Daily Pause**, or choose another name. Keep a note of its **Project ID**.
3. Google Analytics is optional; leave it disabled if you only want authentication. Journals and mood analysis do not require Google Analytics, Firestore or Cloud Storage.

## 2. Register the Android app

1. In **Project settings → General → Your apps**, add an Android app.
2. Set the Android package name to **`com.moodjournal.app`**. This must match exactly.
3. Give it a nickname such as **Daily Pause Android**.
4. Add the signing certificate fingerprints below under **SHA certificate fingerprints**. They belong to the existing debug signing key used for this branch's APKs:

```text
SHA-1:   09eaa535b3319d3d9fbe8b2a4ef51db9164a6efd
SHA-256: f97521ef16217610408ed56be975bc832667b0310ea8b97e1e4f26a03c09d0f8
```

Firebase accepts fingerprints with or without colon separators. Keep using the existing signing key for updates. If you later distribute an APK signed with a different key or use Google Play App Signing, register that certificate too. These fingerprints are public; never upload or share the signing keystore or its password.

## 3. Enable Google authentication

1. Open **Authentication → Get started → Sign-in method**.
2. Enable **Google**.
3. Set the public-facing project name and select a support email, then save.
4. If Google asks you to configure consent/branding in the linked Google Cloud project, complete it. While the OAuth app is in testing, add the Google accounts that will test Daily Pause as test users.

The labels in Firebase/Google Cloud may vary slightly. See the official [Firebase Android Google sign-in guide](https://firebase.google.com/docs/auth/android/google-signin).

## 4. Collect the public app configuration

The Android implementation will use these three public values:

| Value | Where to find it |
| --- | --- |
| Firebase Project ID | Firebase **Project settings → General** |
| Firebase Web API Key | Firebase **Project settings → General**, or the `current_key` field in the Android app's downloaded `google-services.json` |
| Web OAuth client ID | The linked Google Cloud project's **APIs & Services → Credentials → OAuth 2.0 Client IDs**, selecting a **Web application** client associated with Firebase |

The OAuth client ID ends in **`.apps.googleusercontent.com`**. Use the **Web application** client ID as the Google token audience; do not substitute the Android client ID. Enabling Google sign-in normally creates the relevant OAuth clients. If the downloaded `google-services.json` contains a `client_type: 3` OAuth entry, that entry identifies the Web client.

These values can be provided as text; they are identifiers/client configuration, not administrative credentials. **Do not provide a service-account JSON file, OAuth client secret, private key, signing keystore or password.** An OAuth client secret does not belong in an Android APK.

The supplied values are in [google_sign_in_config.xml](../app/src/main/res/values/google_sign_in_config.xml). For another project, replace these three public strings:

```xml
<resources>
    <string name="firebase_project_id" translatable="false">YOUR_PROJECT_ID</string>
    <string name="firebase_web_api_key" translatable="false">YOUR_PUBLIC_API_KEY</string>
    <string name="google_web_client_id" translatable="false">YOUR_WEB_CLIENT_ID.apps.googleusercontent.com</string>
</resources>
```

## 5. Expected app behavior

- Initial setup requires Google sign-in. Existing users must also prove access with their current PIN or biometrics before the account is linked to existing local journals.
- Once linked, PIN/biometrics continue to protect private screens, with normal offline access.
- A forgotten PIN can be replaced only after a new Google sign-in verifies the **same linked account**. Choosing another Google account must not authorize a reset or expose existing journals.
- Google sign-in establishes account identity. It does not upload or synchronize journals, replace encrypted backup passphrases, or grant broad Google Drive access.

The app uses Android Credential Manager's explicit **Sign in with Google** action with a fresh nonce. Firebase Auth checks the Google credential, and an authenticated account lookup confirms the Google provider identity. The installation stores the Firebase UID, Google subject, project ID and verified email; it never stores Google or Firebase ID/refresh tokens. Email alone is not the ownership proof, and changing the Google account's email does not change its stable identity. No account-switch button can assign existing journals to another user.

For this local-only journal, Firebase's documented Auth REST API performs the one-time verification without adding a second account-session SDK or any cloud journal storage. Keep the API key enabled for the Identity Toolkit API. If you restrict the key to Android apps, use the package and SHA-1 above; verification requests include the Android package and certificate headers. No OAuth client secret or service account is used.

Google controls its account chooser and any password/account challenges. An explicit sign-in does not guarantee Google will ask for a password again when the device already has an authorized account. Existing PIN/biometrics continue to provide the everyday local privacy lock. First setup and PIN recovery require connectivity and supported Google Play services; normal linked local access remains offline. Changing projects or deleting/recreating the Firebase user changes the account identity and cannot silently take over an existing installation.

During an update, an existing PIN must be verified before the first account is linked. If that PIN is already forgotten and biometrics are unavailable, a newly selected Google account cannot establish ownership retroactively. Keep an encrypted backup and its independent passphrase.

## 6. Validate before publishing

Build an APK with the configured project, then test first sign-in, cancellation, linking existing data, offline local unlock and PIN reset with both the correct and a different Google account. Verify journals, Heaps and encrypted backups survive an app update. A build without project configuration cannot complete Google sign-in and should not be distributed as a working Google-authenticated release.

Project setup is performed in your Firebase/Google Cloud console. Actual sign-in creates or signs into the Firebase Google-provider user; journals remain local. No cloud journal data store has been created by this implementation.
