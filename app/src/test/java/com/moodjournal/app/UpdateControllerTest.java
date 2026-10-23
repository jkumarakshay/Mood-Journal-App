package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDownloadManager;
import org.robolectric.shadows.ShadowDownloadManager.ShadowRequest;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 35)
public class UpdateControllerTest {
    static UpdateClient.Transport transport;
    static boolean settingsVisible, promptAllowed;
    Context context;
    @Before public void reset() throws Exception {
        UpdateInstallerTest.clearProviderCache();
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("journal.db");
        context.getSharedPreferences("journal_lock", 0).edit().clear().commit();
        context.getSharedPreferences("draft", 0).edit().clear().commit();
        // Robolectric starts DownloadManager IDs at zero; Android's database IDs start at one.
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long seed = manager.enqueue(new DownloadManager.Request(android.net.Uri.parse("https://github.com/example/fixture")));
        manager.remove(seed);
        context.getSharedPreferences(UpdateController.PREFS, 0).edit().clear().putBoolean("automatic", false).commit();
        context.getSharedPreferences("appearance", 0).edit().clear().commit();
        settingsVisible = true; promptAllowed = false;
        String source = UpdateClientTest.release().toString(), manifest = UpdateClientTest.manifest().toString();
        transport = (url, limit) -> url.equals(UpdateClient.LATEST) ? source : manifest;
        UpdateInstallerTest.configureInstalled(context);
    }
    @Test public void manualChecksWorkWithAutomaticOffAndDisplayVersionAndNotes() {
        AtomicInteger calls = new AtomicInteger(); UpdateClient.Transport delegate = transport;
        transport = (url, limit) -> { calls.incrementAndGet(); return delegate.get(url, limit); };
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            assertFalse(((Switch)c.get().findViewById(R.id.updates_automatic)).isChecked()); assertEquals(0, calls.get());
            check(c.get()); assertEquals(2, calls.get()); assertTrue(c.get().status().contains("Update available"));
            assertTrue(allText(c.get().findViewById(android.R.id.content)).contains("1.5.1"));
            assertTrue(allText(c.get().findViewById(android.R.id.content)).contains("New features and fixes"));
            assertNotNull(c.get().findViewById(R.id.updates_download)); assertEquals(0, id());
        }
    }
    @Test public void defaultsToAutomaticAndLimitsCompletedChecksToOncePerDay() {
        prefs().edit().remove("automatic").commit(); settingsVisible = false; promptAllowed = true;
        AtomicInteger calls = new AtomicInteger(); transport = (url, limit) -> { calls.incrementAndGet(); throw new UpdateClient.HttpFailure(404); };
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            JournalTest.await(() -> prefs().getLong("last_checked", 0) > 0); assertTrue(c.get().updates.automaticEnabled());
            c.get().updates.onResume(); c.get().updates.onResume(); idle(); assertEquals(1, calls.get());
            prefs().edit().putLong("last_auto_attempt", System.currentTimeMillis() - UpdateController.CHECK_INTERVAL).commit();
            c.get().updates.onResume(); JournalTest.await(() -> calls.get() == 2 && !working(c.get()));
        }
    }
    @Test public void automaticOfferWaitsForUserAndOnlyPromptsOncePerVersion() {
        prefs().edit().putBoolean("automatic", true).commit(); settingsVisible = false; promptAllowed = true;
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            JournalTest.await(() -> c.get().dialog != null); assertEquals(0, id());
            assertTrue((c.get().dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            c.get().dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle(); assertFalse(c.get().dialog.isShowing());
            prefs().edit().putLong("last_auto_attempt", 1).commit(); c.get().updates.onResume();
            JournalTest.await(() -> !working(c.get())); assertFalse(c.get().dialog.isShowing()); assertEquals(0, id());
        }
    }
    @Test public void privateViewsAndDisabledAutomaticChecksMakeNoAutomaticRequests() {
        settingsVisible = false; promptAllowed = false; AtomicInteger calls = new AtomicInteger();
        transport = (url, limit) -> { calls.incrementAndGet(); throw new UpdateClient.HttpFailure(404); };
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            c.get().updates.maybeCheckAutomatically(); idle(); assertEquals(0, calls.get());
            prefs().edit().putBoolean("automatic", true).commit(); c.get().updates.maybeCheckAutomatically(); idle(); assertEquals(0, calls.get());
            settingsVisible = true; c.get().findViewById(R.id.updates_check).performClick();
            JournalTest.await(() -> c.get().status().contains("No newer")); assertEquals(1, calls.get());
        }
    }
    @Test public void networkFailuresAreRetryableAndDoNotClaimSuccess() {
        transport = (url, limit) -> { throw new IOException("offline"); };
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            c.get().findViewById(R.id.updates_check).performClick(); JournalTest.await(() -> c.get().status().contains("Could not check"));
            assertTrue(c.get().findViewById(R.id.updates_check).isEnabled()); assertEquals(0, prefs().getLong("last_checked", 0));
            transport = (url, limit) -> { throw new UpdateClient.HttpFailure(429); };
            c.get().findViewById(R.id.updates_check).performClick(); JournalTest.await(() -> c.get().status().contains("limiting"));
        }
    }
    @Test public void canceledCheckCannotUpdateUiOrPromptAfterBackgrounding() throws Exception {
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        UpdateClient.Transport delegate = transport;
        transport = (url, limit) -> {
            if (url.equals(UpdateClient.LATEST)) {
                started.countDown();
                try { finish.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            return delegate.get(url, limit);
        };
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            c.get().findViewById(R.id.updates_check).performClick(); assertTrue(started.await(2, TimeUnit.SECONDS));
            c.pause().stop(); finish.countDown(); c.start().resume();
            idle(); assertEquals(0, prefs().getLong("last_checked", 0)); assertNull(c.get().dialog);
            assertFalse(c.get().status().contains("Checking"));
        }
    }
    @Test public void downloadSurvivesActivityRecreationAndExplicitCancelRemovesIt() throws Exception {
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            startDownload(c.get()); long original = id(); assertTrue(original > 0);
            DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = Shadows.shadowOf(manager).getRequest(original);
            ShadowRequest shadow = Shadow.extract(request); shadow.setStatus(DownloadManager.STATUS_RUNNING);
            assertTrue(shadow.getUri().toString().startsWith(UpdateClientTest.PREFIX));
            assertEquals(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED, shadow.getNotificationVisibility());
            c.recreate(); assertEquals(original, id()); assertFalse(c.get().findViewById(R.id.updates_check).isEnabled());
            c.get().findViewById(R.id.updates_cancel_download).performClick(); assertEquals(0, id());
            assertTrue(c.get().findViewById(R.id.updates_check).isEnabled());
        }
    }
    @Test public void completedAndVerifiedUpdateOpensInstallerAndPreservesJournalData() throws Exception {
        try (JournalStore store = new JournalStore(context)) { store.add(4, "Keep my words", "", ""); }
        Shadows.shadowOf(context.getPackageManager()).setCanRequestPackageInstalls(true);
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            readyDownload(c.get(), "abcd"); c.get().findViewById(R.id.updates_download).performClick();
            JournalTest.await(() -> Shadows.shadowOf(c.get()).peekNextStartedActivity() != null);
            Intent intent = Shadows.shadowOf(c.get()).getNextStartedActivity(); assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.getAction());
            assertEquals("content", intent.getData().getScheme()); assertNotNull(intent.getClipData());
            try (JournalStore store = new JournalStore(context)) { assertEquals("Keep my words", store.all().get(0).event()); }
        }
    }
    @Test public void mismatchedSignerRemovesDownloadAndNeverOpensInstaller() throws Exception {
        Shadows.shadowOf(context.getPackageManager()).setCanRequestPackageInstalls(true);
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            File file = readyDownload(c.get(), "dcba"); c.get().findViewById(R.id.updates_download).performClick();
            JournalTest.await(() -> c.get().status().contains("could not be verified"));
            assertFalse(file.exists()); assertEquals(0, id()); assertNull(Shadows.shadowOf(c.get()).peekNextStartedActivity());
        }
    }
    @Test public void sourcePermissionReturnReverifiesBytesBeforeLaunchingInstaller() throws Exception {
        Shadows.shadowOf(context.getPackageManager()).setCanRequestPackageInstalls(false);
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            File file = readyDownload(c.get(), "abcd"); c.get().findViewById(R.id.updates_download).performClick();
            JournalTest.await(() -> c.get().dialog != null); assertNull(Shadows.shadowOf(c.get()).peekNextStartedActivity());
            c.get().dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            Intent permission = Shadows.shadowOf(c.get()).getNextStartedActivityForResult().intent;
            assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.getAction());
            Shadows.shadowOf(c.get()).getNextStartedActivity(); // Drain the separate startActivity recording queue.
            c.pause().stop(); Files.write(file.toPath(), new byte[]{3, 2, 1});
            Shadows.shadowOf(context.getPackageManager()).setCanRequestPackageInstalls(true); c.start().resume();
            JournalTest.await(() -> c.get().status().contains("could not be verified"));
            assertNull(Shadows.shadowOf(c.get()).peekNextStartedActivity()); assertFalse(file.exists());
        }
    }
    @Test @Config(sdk = 26) public void minimumAndroidVersionCanDownloadAndOpenInstaller() throws Exception {
        completedAndVerifiedUpdateOpensInstallerAndPreservesJournalData();
    }
    @Test public void mainUpdatesPagePersistsToggleThemeAndPrivateLock() throws Exception {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            UpdateController updates = org.robolectric.util.ReflectionHelpers.getField(c.get(), "updates");
            java.util.function.BooleanSupplier eligible = org.robolectric.util.ReflectionHelpers.getField(updates, "canPrompt");
            c.get().findViewById(R.id.entry_event).requestFocus();
            assertTrue("A focused draft must not disable automatic checks", eligible.getAsBoolean());
            c.get().findViewById(R.id.journal_tab).performClick(); JournalTest.unlockJournal(c.get());
            assertFalse("Private journals must not trigger automatic checks", eligible.getAsBoolean());
            c.get().findViewById(R.id.settings_button).performClick(); c.get().findViewById(R.id.settings_updates).performClick();
            assertEquals("Updates", ((TextView)c.get().findViewById(R.id.settings_title)).getText().toString());
            Switch toggle = c.get().findViewById(R.id.updates_automatic); toggle.setChecked(true); assertTrue(prefs().getBoolean("automatic", false));
            toggle.setChecked(false); c.recreate(); assertFalse(((Switch)c.get().findViewById(R.id.updates_automatic)).isChecked());
            assertNotNull(c.get().findViewById(R.id.updates_check));
            c.get().findViewById(R.id.journal_tab).performClick(); assertNotNull(c.get().findViewById(R.id.unlock_pin));
        }
    }
    private void check(Host host) { host.findViewById(R.id.updates_check).performClick(); JournalTest.await(() -> !working(host)); }
    private boolean working(Host host) { return org.robolectric.util.ReflectionHelpers.getField(host.updates, "working"); }
    private void startDownload(Host host) { check(host); host.findViewById(R.id.updates_download).performClick(); }
    private File readyDownload(Host host, String certificate) throws Exception {
        startDownload(host);
        File file = new File(new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates"), prefs().getString("download_file", ""));
        file.getParentFile().mkdirs(); Files.write(file.toPath(), UpdateClientTest.APK); UpdateInstallerTest.archive(context, file, certificate);
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        ShadowRequest request = Shadow.extract(Shadows.shadowOf(manager).getRequest(id()));
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL); request.setBytesSoFar(UpdateClientTest.APK.length);
        host.updates.onResume(); return file;
    }
    private android.content.SharedPreferences prefs() { return context.getSharedPreferences(UpdateController.PREFS, 0); }
    private long id() { return prefs().getLong("download_id", 0); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private String allText(android.view.View view) {
        if (view instanceof TextView text) return text.getText().toString(); StringBuilder words = new StringBuilder();
        if (view instanceof android.view.ViewGroup group) for (int i=0;i<group.getChildCount();i++) words.append(allText(group.getChildAt(i)));
        return words.toString();
    }
    public static class Host extends FragmentActivity {
        UpdateController updates; AlertDialog dialog;
        @Override public void onCreate(Bundle state) {
            Appearance.apply(this); super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            updates = new UpdateController(this, () -> settingsVisible, () -> promptAllowed, this::show, () -> { settingsVisible = true; show(); },
                    value -> { dialog = value; value.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); value.show(); AppDialogs.decorate(value); },
                    new UpdateClient((url, limit) -> transport.get(url, limit))); show();
        }
        private void show() { LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); setContentView(page); updates.show(page); }
        String status() { return ((TextView)findViewById(R.id.updates_status)).getText().toString(); }
        @Override public void onResume() { super.onResume(); updates.onResume(); }
        @Override public void onPause() { updates.onLeave(); if (dialog != null) dialog.dismiss(); super.onPause(); }
        @Override public void onDestroy() { updates.close(); super.onDestroy(); }
    }
}
