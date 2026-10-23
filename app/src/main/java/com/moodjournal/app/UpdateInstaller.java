package com.moodjournal.app;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/** Verify every install attempt, including after returning from Android's source-permission page. */
final class UpdateInstaller {
    private final Context context;
    UpdateInstaller(Context context) { this.context = context; }
    long installedCode() {
        try { return version(context.getPackageManager().getPackageInfo(context.getPackageName(), 0)); }
        catch (PackageManager.NameNotFoundException e) { throw new IllegalStateException(e); }
    }
    String installedName() {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { throw new IllegalStateException(e); }
    }
    void verify(File file, UpdateRelease release) throws IOException {
        if (release.versionCode() <= installedCode() || release.minSdk() > Build.VERSION.SDK_INT
                || !file.isFile() || file.length() != release.size()) throw new IOException("Incompatible update");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Verification cancelled");
                    digest.update(buffer, 0, count);
                }
            }
            if (!hex(digest.digest()).equals(release.sha256())) throw new IOException("APK checksum mismatch");
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageManager pm = context.getPackageManager();
            PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
            PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
            if (archive == null || !context.getPackageName().equals(archive.packageName)
                    || version(archive) != release.versionCode() || !release.versionName().equals(archive.versionName)
                    || archive.applicationInfo == null || archive.applicationInfo.minSdkVersion != release.minSdk())
                throw new IOException("APK metadata mismatch");
            Set<String> current = signers(installed), incoming = signers(archive);
            if (current.isEmpty() || !current.equals(incoming)) throw new IOException("APK signing certificate mismatch");
        } catch (NoSuchAlgorithmException | PackageManager.NameNotFoundException e) { throw new IOException("Cannot verify APK", e); }
    }
    boolean canInstall() { return context.getPackageManager().canRequestPackageInstalls(); }
    Intent sourcePermission() { return new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName())); }
    Intent installIntent(File file) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", file);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Daily Pause update", uri));
        return intent;
    }
    private static long version(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    @SuppressWarnings("deprecation") private static Set<String> signers(PackageInfo info) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        else signatures = info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures) result.add(signature.toCharsString());
        return result;
    }
    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
