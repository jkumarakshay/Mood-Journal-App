package com.moodjournal.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Unauthenticated, bounded HTTPS requests to the public repository; no journal data is sent. */
final class UpdateClient {
    static final String LATEST = "https://api.github.com/repos/" + UpdateRelease.REPOSITORY + "/releases/latest";
    interface Transport { String get(String url, int limit) throws IOException; }
    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { super("Update HTTP " + status); this.status = status; }
    }
    private final Transport transport;
    UpdateClient() { this(UpdateClient::get); }
    UpdateClient(Transport transport) { this.transport = transport; }

    UpdateRelease latest() throws IOException {
        String response;
        try { response = transport.get(LATEST, 1024 * 1024); }
        catch (HttpFailure e) { if (e.status == 404) return null; throw e; }
        try {
            JSONObject release = new JSONObject(response);
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) return null;
            JSONArray assets = release.getJSONArray("assets");
            if (assets.length() > 100) throw new IOException("Too many release assets");
            JSONObject manifest = asset(assets, UpdateRelease.MANIFEST_NAME);
            if (manifest == null || manifest.getLong("size") <= 0 || manifest.getLong("size") > 16384)
                throw new IOException("Release is missing its update manifest");
            String manifestUrl = manifest.getString("browser_download_url");
            if (!UpdateRelease.assetUrl(manifestUrl, UpdateRelease.MANIFEST_NAME)) throw new IOException("Invalid manifest source");
            JSONObject json = new JSONObject(transport.get(manifestUrl, 16384));
            if (json.getInt("schemaVersion") != 1 || !"com.moodjournal.app".equals(json.getString("packageName")))
                throw new IOException("Unsupported update manifest");
            String name = json.getString("apkAssetName");
            JSONObject apk = asset(assets, name);
            if (apk == null || !"uploaded".equals(apk.getString("state"))) throw new IOException("APK is unavailable");
            // Both assets must come from the same release tag, even if metadata is malformed.
            String apkUrl = apk.getString("browser_download_url");
            if (!apkUrl.substring(0, apkUrl.lastIndexOf('/') + 1).equals(manifestUrl.substring(0, manifestUrl.lastIndexOf('/') + 1)))
                throw new IOException("Mismatched release assets");
            long size = json.getLong("size");
            if (size != apk.getLong("size")) throw new IOException("APK size mismatch");
            String notes = release.optString("body", "");
            if (notes.length() > 8000) notes = notes.substring(0, 8000);
            return new UpdateRelease(json.getLong("versionCode"), json.getString("versionName"), json.getInt("minSdk"),
                    name, apkUrl, size, json.getString("sha256"), notes, release.getString("html_url"));
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("Invalid release metadata", e); }
    }
    private static JSONObject asset(JSONArray assets, String name) throws JSONException, IOException {
        JSONObject found = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject item = assets.getJSONObject(i);
            if (name.equals(item.optString("name"))) {
                if (found != null) throw new IOException("Duplicate release asset");
                found = item;
            }
        }
        return found;
    }
    static boolean allowedRedirect(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                    && java.util.Arrays.asList("api.github.com", "github.com", "release-assets.githubusercontent.com",
                    "objects.githubusercontent.com", "github-releases.githubusercontent.com").contains(uri.getHost());
        } catch (IllegalArgumentException | NullPointerException e) { return false; }
    }
    private static String get(String address, int limit) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!allowedRedirect(address)) throw new IOException("Untrusted update redirect");
            HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
            connection.setConnectTimeout(10000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Daily-Pause-Android");
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/json");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing update redirect");
                    address = URI.create(address).resolve(location).toString(); continue;
                }
                if (status != 200) throw new HttpFailure(status);
                if (connection.getContentLengthLong() > limit) throw new IOException("Update metadata is too large");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int total = 0, count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Update check cancelled");
                        total += count; if (total > limit) throw new IOException("Update metadata is too large");
                        output.write(buffer, 0, count);
                    }
                    return output.toString(StandardCharsets.UTF_8.name());
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many update redirects");
    }
}
