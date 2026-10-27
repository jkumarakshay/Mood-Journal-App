package com.moodjournal.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Google tokens are checked by Firebase Auth and the authenticated user lookup, not trusted JWT decoding. */
final class FirebaseGoogleVerifier {
    interface Transport { JSONObject post(String action, JSONObject body) throws Exception; }
    private final Context context;
    private final String project, apiKey, clientId;
    private final Transport transport;
    FirebaseGoogleVerifier(Context context) { this(context, context.getString(R.string.firebase_project_id), context.getString(R.string.firebase_web_api_key), context.getString(R.string.google_web_client_id), null); }
    FirebaseGoogleVerifier(Context context, String project, String apiKey, String clientId, Transport transport) {
        this.context = context.getApplicationContext(); this.project = project; this.apiKey = apiKey; this.clientId = clientId;
        this.transport = transport == null ? this::http : transport;
    }
    boolean configured() { return project.matches("[a-z0-9-]{4,63}") && apiKey.matches("AIza[A-Za-z0-9_-]{35}") && clientId.matches("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"); }
    String clientId() { return clientId; }
    GoogleAccountStore.Profile verify(String token, String nonce) throws Exception {
        if (!configured()) throw new IOException("Google sign-in is not configured");
        JSONObject google = claims(token);
        long now = System.currentTimeMillis() / 1000;
        String issuer = google.optString("iss"), subject = google.optString("sub");
        if (!("https://accounts.google.com".equals(issuer) || "accounts.google.com".equals(issuer))
                || !clientId.equals(google.optString("aud")) || !subject.matches("[0-9]{1,40}")
                || nonce.isEmpty() || !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8), google.optString("nonce").getBytes(StandardCharsets.UTF_8))
                || google.optLong("exp", 0) <= now || google.optLong("iat", Long.MAX_VALUE) > now + 120)
            throw new IOException("Invalid Google sign-in response");
        JSONObject response = transport.post("signInWithIdp", new JSONObject()
                .put("postBody", "id_token=" + URLEncoder.encode(token, "UTF-8") + "&providerId=google.com")
                .put("requestUri", "http://localhost").put("returnSecureToken", true).put("returnIdpCredential", false));
        if (response.optBoolean("needConfirmation") || !"google.com".equals(response.optString("providerId")))
            throw new IOException("Google account could not be confirmed");
        String firebaseToken = response.getString("idToken"), uid = response.getString("localId");
        JSONObject firebase = claims(firebaseToken);
        if (!project.equals(firebase.optString("aud")) || !("https://securetoken.google.com/" + project).equals(firebase.optString("iss"))
                || !uid.equals(firebase.optString("sub")) || firebase.optLong("exp", 0) <= now)
            throw new IOException("Unexpected authentication project");
        JSONArray users = transport.post("lookup", new JSONObject().put("idToken", firebaseToken)).getJSONArray("users");
        if (users.length() != 1) throw new IOException("Invalid authenticated account");
        JSONObject user = users.getJSONObject(0);
        if (!uid.equals(user.optString("localId")) || user.optBoolean("disabled") || !user.optBoolean("emailVerified"))
            throw new IOException("Unverified or disabled account");
        boolean providerMatches = false; JSONArray providers = user.optJSONArray("providerUserInfo");
        if (providers != null) for (int i = 0; i < providers.length(); i++) {
            JSONObject provider = providers.getJSONObject(i);
            providerMatches |= "google.com".equals(provider.optString("providerId")) && subject.equals(provider.optString("rawId"));
        }
        GoogleAccountStore.Profile profile = new GoogleAccountStore.Profile(project, uid, subject, user.optString("email"));
        if (!providerMatches || !profile.valid()) throw new IOException("Google identity mismatch");
        return profile;
    }
    private static JSONObject claims(String token) throws Exception {
        if (token == null || token.length() > 32768) throw new IOException("Invalid token");
        String[] parts = token.split("\\."); if (parts.length != 3) throw new IOException("Invalid token");
        return new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE), StandardCharsets.UTF_8));
    }
    private JSONObject http(String action, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://identitytoolkit.googleapis.com/v1/accounts:" + action + "?key=" + apiKey).openConnection();
        try {
            connection.setRequestMethod("POST"); connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(12000); connection.setReadTimeout(12000);
            connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("X-Android-Package", context.getPackageName());
            connection.setRequestProperty("X-Android-Cert", signingSha1()); connection.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
            try (var output = connection.getOutputStream()) { output.write(bytes); }
            if (connection.getResponseCode() != 200) throw new IOException("Google account verification failed");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int size;
                while ((size = input.read(buffer)) != -1) {
                    if (output.size() + size > 131072) throw new IOException("Authentication response too large");
                    output.write(buffer, 0, size);
                }
                return new JSONObject(output.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
    @SuppressWarnings("deprecation") private String signingSha1() throws Exception {
        byte[] certificate;
        if (Build.VERSION.SDK_INT >= 28) certificate = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()[0].toByteArray();
        else certificate = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES).signatures[0].toByteArray();
        StringBuilder hex = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-1").digest(certificate)) hex.append(String.format(Locale.ROOT, "%02X", b & 255)); return hex.toString();
    }
}
