package com.moodjournal.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 35)
public class UpdateInstallerTest {
    Context context; UpdateInstaller installer; File file; PackageInfo incoming; UpdateRelease release;
    @Before public void setup() throws Exception {
        clearProviderCache();
        context = RuntimeEnvironment.getApplication(); installer = new UpdateInstaller(context); release = UpdateClientTest.candidate();
        file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates/update-8-00000000-0000-0000-0000-000000000000.apk");
        file.getParentFile().mkdirs(); Files.write(file.toPath(), UpdateClientTest.APK);
        configureInstalled(context);
        incoming = archive(context, file, "abcd");
    }
    static void clearProviderCache() {
        // AndroidX caches filesystem roots; Robolectric gives each test a different app directory.
        java.util.Map<?, ?> cache = org.robolectric.util.ReflectionHelpers.getStaticField(androidx.core.content.FileProvider.class, "sCache");
        cache.clear();
    }
    static void configureInstalled(Context context) {
        PackageInfo current = Shadows.shadowOf(context.getPackageManager()).getInternalMutablePackageInfo(context.getPackageName());
        current.versionCode = 7; if (Build.VERSION.SDK_INT >= 28) current.setLongVersionCode(7); current.versionName = "1.5.0";
        sign(current, "abcd");
    }
    static PackageInfo archive(Context context, File file, String certificate) {
        PackageInfo info = new PackageInfo(); info.packageName = context.getPackageName(); info.versionCode = 8;
        if (Build.VERSION.SDK_INT >= 28) info.setLongVersionCode(8); info.versionName = "1.5.1";
        info.applicationInfo = new ApplicationInfo(); info.applicationInfo.minSdkVersion = 26;
        sign(info, certificate); Shadows.shadowOf(context.getPackageManager()).setPackageArchiveInfo(file.getAbsolutePath(), info); return info;
    }
    static void sign(PackageInfo info, String certificate) {
        info.signatures = new Signature[]{new Signature(certificate)};
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo = new SigningInfo(); Shadows.shadowOf(info.signingInfo).setSignatures(info.signatures);
        }
    }
    private void rejected() throws Exception { try { installer.verify(file, release); fail("Unsafe update accepted"); } catch (IOException expected) { } }
    @Test public void acceptsOnlyNewerPackageWithExactManifestAndSameSigner() throws Exception { installer.verify(file, release); assertEquals(7, installer.installedCode()); }
    @Test public void rejectsChangedOrTruncatedDownloadsBeforeReadingPackage() throws Exception {
        Files.write(file.toPath(), new byte[]{3, 2, 1}); rejected(); Files.write(file.toPath(), new byte[]{1}); rejected();
    }
    @Test public void rejectsWrongSigningCertificateAndUnsignedPackages() throws Exception {
        sign(incoming, "dcba"); rejected(); incoming.signingInfo = null; incoming.signatures = null; rejected();
    }
    @Test public void rejectsOtherPackagesMislabeledVersionsAndWrongMinimumSdk() throws Exception {
        incoming.packageName = "someone.else"; rejected(); incoming.packageName = context.getPackageName();
        incoming.versionName = "1.5.2"; rejected(); incoming.versionName = "1.5.1";
        incoming.applicationInfo.minSdkVersion = 35; rejected(); incoming.applicationInfo.minSdkVersion = 26;
        incoming.setLongVersionCode(9); rejected();
    }
    @Test public void installedVersionAndDowngradesCannotBeReinstalledAsUpdates() throws Exception {
        PackageInfo current = Shadows.shadowOf(context.getPackageManager()).getInternalMutablePackageInfo(context.getPackageName());
        current.setLongVersionCode(8); rejected(); current.setLongVersionCode(9); rejected();
    }
    @Test public void installerUsesNarrowReadOnlyContentUriAndSourcePermissionTargetsThisApp() throws Exception {
        Intent intent = installer.installIntent(file);
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.getAction()); assertEquals("content", intent.getData().getScheme());
        assertEquals(context.getPackageName() + ".updates", intent.getData().getAuthority());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        assertEquals(intent.getData(), intent.getClipData().getItemAt(0).getUri());
        assertEquals("package:" + context.getPackageName(), installer.sourcePermission().getDataString());
        try { installer.installIntent(new File(context.getFilesDir(), "journal.db")); fail("Private files exposed"); } catch (IllegalArgumentException expected) { }
    }
    @Test @Config(sdk = 26) public void minimumAndroidVersionUsesLegacySignatureVerification() throws Exception { installer.verify(file, release); }
}
