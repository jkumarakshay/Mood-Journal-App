package com.moodjournal.app;

import static org.junit.Assert.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 35)
public class UpdateClientTest {
    static final byte[] APK = new byte[]{1, 2, 3};
    static final String PREFIX = "https://github.com/jkumarakshay/Mood-Journal-App/releases/download/v1.5.1/";
    static JSONObject manifest() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("packageName", "com.moodjournal.app").put("versionCode", 8)
                .put("versionName", "1.5.1").put("minSdk", 26).put("apkAssetName", "Daily-Pause-v1.12.3.apk")
                .put("size", APK.length).put("sha256", UpdateInstaller.hex(MessageDigest.getInstance("SHA-256").digest(APK)));
    }
    static JSONObject release() throws Exception {
        JSONArray assets = new JSONArray();
        assets.put(new JSONObject().put("name", UpdateRelease.MANIFEST_NAME).put("size", 300)
                .put("browser_download_url", PREFIX + UpdateRelease.MANIFEST_NAME).put("state", "uploaded"));
        assets.put(new JSONObject().put("name", "Daily-Pause-v1.12.3.apk").put("size", APK.length)
                .put("browser_download_url", PREFIX + "Daily-Pause-v1.12.3.apk").put("state", "uploaded"));
        return new JSONObject().put("draft", false).put("prerelease", false).put("assets", assets)
                .put("body", "A little better, every day.\nNew features and fixes.")
                .put("html_url", "https://github.com/jkumarakshay/Mood-Journal-App/releases/tag/v1.5.1");
    }
    static UpdateRelease candidate() throws Exception { return client(release(), manifest()).latest(); }
    static UpdateClient client(JSONObject release, JSONObject manifest) {
        return new UpdateClient((url, limit) -> url.equals(UpdateClient.LATEST) ? release.toString() : manifest.toString());
    }
    private void rejected(JSONObject release, JSONObject manifest) throws Exception {
        try { client(release, manifest).latest(); fail("Malformed update accepted"); } catch (IOException expected) { }
    }
    @Test public void readsStablePublicReleaseAndRoundTripsCacheWithoutFilenameVersionGuessing() throws Exception {
        List<String> requests = new ArrayList<>(); JSONObject source = release(), metadata = manifest();
        UpdateRelease result = new UpdateClient((url, limit) -> {
            requests.add(url); assertTrue(limit <= 1024 * 1024);
            return url.equals(UpdateClient.LATEST) ? source.toString() : metadata.toString();
        }).latest();
        assertEquals(8, result.versionCode()); assertEquals("1.5.1", result.versionName());
        assertEquals("Daily-Pause-v1.12.3.apk", result.assetName()); assertEquals(2, requests.size());
        assertEquals(result, UpdateRelease.decode(result.encode())); assertEquals(26, result.minSdk());
        assertTrue(result.notes().contains("New features"));
    }
    @Test public void noReleaseIsNormalButAuthorizationAndRateLimitsRemainErrors() throws Exception {
        assertNull(new UpdateClient((url, limit) -> { throw new UpdateClient.HttpFailure(404); }).latest());
        for (int status : new int[]{403, 429, 500}) try {
            new UpdateClient((url, limit) -> { throw new UpdateClient.HttpFailure(status); }).latest(); fail();
        } catch (UpdateClient.HttpFailure e) { assertEquals(status, e.status); }
    }
    @Test public void counterBasedNamesWorkWithTheExistingUpdateProtocol() throws Exception {
        for (int code : new int[]{10, 17}) {
            String version = "1." + code + ".0", name = "Daily-Pause-v" + version + ".apk";
            String prefix = "https://github.com/jkumarakshay/Mood-Journal-App/releases/download/v" + version + "/";
            JSONObject source = release(), metadata = manifest().put("versionCode", code).put("versionName", version).put("apkAssetName", name);
            source.put("html_url", "https://github.com/jkumarakshay/Mood-Journal-App/releases/tag/v" + version);
            source.getJSONArray("assets").getJSONObject(0).put("browser_download_url", prefix + UpdateRelease.MANIFEST_NAME);
            source.getJSONArray("assets").getJSONObject(1).put("name", name).put("browser_download_url", prefix + name);
            UpdateRelease result = client(source, metadata).latest();
            assertEquals(code, result.versionCode()); assertEquals(version, result.versionName()); assertEquals(name, result.assetName());
        }
    }
    @Test public void draftAndPrereleaseNeverBecomeUserUpdates() throws Exception {
        assertNull(client(release().put("draft", true), manifest()).latest());
        assertNull(client(release().put("prerelease", true), manifest()).latest());
    }
    @Test public void rejectsMissingDuplicateAndIncompleteAssets() throws Exception {
        rejected(release().put("assets", new JSONArray()), manifest());
        JSONObject duplicate = release(); duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").getJSONObject(0)); rejected(duplicate, manifest());
        JSONObject incomplete = release(); incomplete.getJSONArray("assets").getJSONObject(1).put("state", "new"); rejected(incomplete, manifest());
    }
    @Test public void rejectsWrongPackageSchemaChecksumSizesAndVersions() throws Exception {
        rejected(release(), manifest().put("packageName", "someone.else"));
        rejected(release(), manifest().put("schemaVersion", 2));
        rejected(release(), manifest().put("sha256", "bad"));
        rejected(release(), manifest().put("size", 4));
        rejected(release(), manifest().put("versionCode", 0));
        rejected(release(), manifest().put("versionName", "1.5.1-beta"));
        JSONObject large = release(); large.getJSONArray("assets").getJSONObject(1).put("size", UpdateRelease.MAX_APK_BYTES + 1);
        rejected(large, manifest().put("size", UpdateRelease.MAX_APK_BYTES + 1));
    }
    @Test public void rejectsOtherRepositoriesHttpCrossReleaseAndUntrustedRedirects() throws Exception {
        for (String url : new String[]{"http://github.com/jkumarakshay/Mood-Journal-App/releases/download/v1.5.1/Daily-Pause-v1.12.3.apk",
                "https://github.com/other/repo/releases/download/v1.5.1/Daily-Pause-v1.12.3.apk",
                PREFIX.replace("v1.5.1", "v9.9.9") + "Daily-Pause-v1.12.3.apk"}) {
            JSONObject source = release(); source.getJSONArray("assets").getJSONObject(1).put("browser_download_url", url); rejected(source, manifest());
        }
        assertFalse(UpdateClient.allowedRedirect("http://release-assets.githubusercontent.com/file"));
        assertFalse(UpdateClient.allowedRedirect("https://github.com.evil.example/file"));
        assertFalse(UpdateClient.allowedRedirect("https://user:password@github.com/file"));
        assertTrue(UpdateClient.allowedRedirect("https://release-assets.githubusercontent.com/file?signature=example"));
        assertFalse(UpdateRelease.assetUrl(PREFIX + "../" + UpdateRelease.MANIFEST_NAME, UpdateRelease.MANIFEST_NAME));
    }
    @Test public void dailyScheduleSurvivesClockChangesAndInvalidCacheFailsClosed() throws Exception {
        assertTrue(UpdateController.due(100, 0)); assertFalse(UpdateController.due(200, 100));
        assertTrue(UpdateController.due(100 + UpdateController.CHECK_INTERVAL, 100));
        assertTrue(UpdateController.due(100, 200)); assertNull(UpdateRelease.decode("{}"));
        assertNull(UpdateRelease.decode(candidate().encode().replace("github.com", "evil.example")));
    }
}
