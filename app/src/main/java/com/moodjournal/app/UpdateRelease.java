package com.moodjournal.app;

import java.net.URI;
import org.json.JSONException;
import org.json.JSONObject;

/** Public release metadata only. Version codes, not filenames or commit counts, order updates. */
record UpdateRelease(long versionCode, String versionName, int minSdk, String assetName,
                     String apkUrl, long size, String sha256, String notes, String releaseUrl) {
    static final String REPOSITORY = "jkumarakshay/Mood-Journal-App";
    static final String RELEASES_URL = "https://github.com/" + REPOSITORY + "/releases";
    static final String MANIFEST_NAME = "daily-pause-update.json";
    static final long MAX_APK_BYTES = 150L * 1024 * 1024;

    UpdateRelease {
        if (versionCode <= 0 || versionCode > Integer.MAX_VALUE || versionName == null
                || !versionName.matches("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}") || minSdk < 1 || minSdk > 1000
                || assetName == null || !assetName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.apk")
                || !assetUrl(apkUrl, assetName) || size <= 0 || size > MAX_APK_BYTES
                || sha256 == null || !sha256.matches("[a-fA-F0-9]{64}") || notes == null || notes.length() > 8000
                || !releasePage(releaseUrl)) throw new IllegalArgumentException("Invalid update metadata");
        sha256 = sha256.toLowerCase(java.util.Locale.ROOT);
    }

    static boolean assetUrl(String value, String name) {
        try {
            if (value == null || value.length() > 4096) return false;
            URI uri = URI.create(value);
            String prefix = "/" + REPOSITORY + "/releases/download/";
            return trustedGithub(uri) && uri.getPath().startsWith(prefix) && uri.getPath().endsWith("/" + name)
                    && uri.getPath().length() > prefix.length() + name.length() + 1
                    && uri.normalize().equals(uri) && !uri.getPath().contains("/../") && !uri.getPath().contains("/./");
        } catch (IllegalArgumentException | NullPointerException e) { return false; }
    }
    private static boolean releasePage(String value) {
        try {
            if (value == null || value.length() > 4096) return false;
            URI uri = URI.create(value);
            return trustedGithub(uri) && uri.getPath().startsWith("/" + REPOSITORY + "/releases/tag/")
                    && uri.normalize().equals(uri) && !uri.getPath().contains("/../");
        } catch (IllegalArgumentException | NullPointerException e) { return false; }
    }
    private static boolean trustedGithub(URI uri) {
        return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost()) && uri.getPort() == -1
                && uri.getUserInfo() == null && uri.getFragment() == null && uri.getQuery() == null;
    }
    String encode() {
        try {
            return new JSONObject().put("versionCode", versionCode).put("versionName", versionName).put("minSdk", minSdk)
                    .put("apkAssetName", assetName).put("apkUrl", apkUrl).put("size", size).put("sha256", sha256)
                    .put("notes", notes).put("releaseUrl", releaseUrl).toString();
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    static UpdateRelease decode(String value) {
        if (value == null || value.length() > 65536) return null;
        try {
            JSONObject json = new JSONObject(value);
            return new UpdateRelease(json.getLong("versionCode"), json.getString("versionName"), json.getInt("minSdk"),
                    json.getString("apkAssetName"), json.getString("apkUrl"), json.getLong("size"), json.getString("sha256"),
                    json.getString("notes"), json.getString("releaseUrl"));
        } catch (JSONException | IllegalArgumentException e) { return null; }
    }
}
