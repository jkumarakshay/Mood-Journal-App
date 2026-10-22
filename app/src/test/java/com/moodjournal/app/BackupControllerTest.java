package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BackupControllerTest {
    private static final String PASSPHRASE = "a separate backup passphrase";
    private static final Uri FILE_URI = Uri.parse("content://daily.pause.fake.drive/document/backup");
    private Context context;
    private File providerFile;
    private FakeDriveProvider provider;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("journal.db");
        for (String name : new String[]{"draft", "journal_lock", "backup_notices"}) context.getSharedPreferences(name, 0).edit().clear().commit();
        File[] cache = context.getCacheDir().listFiles();
        if (cache != null) for (File file : cache) if (file.getName().startsWith("daily-pause-")) file.delete();
        providerFile = new File(context.getFilesDir(), "fake-drive.dpa");
        providerFile.delete();
        provider = new FakeDriveProvider(providerFile);
        ProviderInfo info = new ProviderInfo(); info.authority = "daily.pause.fake.drive";
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        try (JournalStore store = new JournalStore(context)) { store.add(4, "Only encrypted text may leave the app", "A private feeling", "Rest"); }
    }

    @Test public void backupActionsRequireJournalUnlock() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get();
            assertNull(a.findViewById(R.id.backup_export));
            a.findViewById(R.id.journal_tab).performClick();
            assertNull(a.findViewById(R.id.backup_restore));
            JournalTest.unlockJournal(a);
            assertTrue(a.findViewById(R.id.backup_export).isEnabled());
            assertNotNull(a.findViewById(R.id.backup_restore));
        }
    }

    @Test public void backupPassphraseMustBeLongAndConfirmedAndIsNotSavedInViewState() {
        try (ActivityController<MainActivity> c = unlocked()) {
            c.get().findViewById(R.id.backup_export).performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            EditText password = dialog.findViewById(R.id.backup_password);
            EditText confirm = dialog.findViewById(R.id.backup_password_confirm);
            assertFalse(password.isSaveEnabled()); assertFalse(confirm.isSaveEnabled());
            password.setText("642081"); confirm.setText("642081");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(((TextView) dialog.findViewById(R.id.backup_password_error)).getText().toString().contains("12"));
            password.setText(PASSPHRASE); confirm.setText("different passphrase");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(((TextView) dialog.findViewById(R.id.backup_password_error)).getText().toString().contains("match"));
            assertNull(Shadows.shadowOf(c.get()).peekNextStartedActivityForResult());
            c.pause().stop();
            assertFalse(dialog.isShowing());
            assertEquals("", password.getText().toString());
            assertEquals("", confirm.getText().toString());
        }
    }

    @Test public void createDocumentSavesOnlyEncryptedBytesAndReturnsToLockedJournal() throws Exception {
        List<BackupArchive.Entry> expected;
        List<JournalHeap> expectedHeaps;
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap heap = store.addHeap("Private work concerns", "Career", JournalHeap.COLORS[2]);
            store.addHeap("An empty Heap", "", null);
            store.add(3, "A journal inside a Heap", "", "", heap.id());
            expected = store.backupEntries(); expectedHeaps = store.heaps();
        }
        try (ActivityController<MainActivity> c = unlocked()) {
            MainActivity a = c.get();
            startExport(a);
            ShadowActivity.IntentForResult launch = awaitPicker(a);
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, launch.intent.getAction());
            assertEquals("application/octet-stream", launch.intent.getType());
            assertTrue(launch.intent.getStringExtra(Intent.EXTRA_TITLE).endsWith(".dpa"));
            returnFromPicker(c, launch, FILE_URI);
            assertNotNull(a.findViewById(R.id.unlock_pin));
            assertNull(a.findViewById(R.id.backup_export));
            JournalTest.await(() -> status().startsWith("Encrypted backup saved"));
            byte[] saved = Files.readAllBytes(providerFile.toPath());
            assertEquals(expected, BackupArchive.decrypt(saved, PASSPHRASE.toCharArray()));
            assertEquals(expectedHeaps, BackupArchive.decryptSnapshot(saved, PASSPHRASE.toCharArray()).heaps());
            assertFalse(new String(saved, java.nio.charset.StandardCharsets.ISO_8859_1).contains("Only encrypted text"));
            assertFalse(new String(saved, java.nio.charset.StandardCharsets.ISO_8859_1).contains("Private work concerns"));
            assertEquals(0, encryptedCacheFiles());
            JournalTest.unlockJournal(a);
            assertTrue(((TextView) a.findViewById(R.id.backup_status)).getText().toString().contains("syncing when online"));
        }
    }

    @Test public void cancelledSaveDeletesTemporaryEncryptedArchive() {
        try (ActivityController<MainActivity> c = unlocked()) {
            startExport(c.get());
            returnFromPicker(c, awaitPicker(c.get()), null);
            assertEquals(0, encryptedCacheFiles());
            assertFalse(providerFile.exists());
            assertEquals("Backup cancelled.", status());
        }
    }

    @Test public void encryptedPendingExportSurvivesActivityRecreationWithoutUnlockingJournal() throws Exception {
        try (ActivityController<MainActivity> c = unlocked()) {
            startExport(c.get());
            ShadowActivity.IntentForResult launch = awaitPicker(c.get());
            c.recreate();
            MainActivity recreated = c.get();
            assertNotNull(recreated.findViewById(R.id.unlock_pin));
            recreated.getActivityResultRegistry().dispatchResult(launch.requestCode, Activity.RESULT_OK, new Intent().setData(FILE_URI));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            JournalTest.await(() -> status().startsWith("Encrypted backup saved"));
            assertEquals(1, BackupArchive.decrypt(Files.readAllBytes(providerFile.toPath()), PASSPHRASE.toCharArray()).size());
            assertNull(recreated.findViewById(R.id.backup_export));
        }
    }

    @Test public void providerWriteFailureDoesNotClaimSuccessOrLeavePrivateCache() {
        provider.failWrites = true;
        try (ActivityController<MainActivity> c = unlocked()) {
            startExport(c.get());
            returnFromPicker(c, awaitPicker(c.get()), FILE_URI);
            JournalTest.await(() -> status().startsWith("Couldn’t save"));
            assertEquals(0, encryptedCacheFiles());
            assertFalse(providerFile.exists());
        }
    }

    @Test public void restoreNeedsPassphraseAndExplicitConfirmationAndMergesWithoutDuplicates() throws Exception {
        List<BackupArchive.Entry> entries;
        try (JournalStore store = new JournalStore(context)) { entries = new ArrayList<>(store.backupEntries()); }
        entries.add(new BackupArchive.Entry(1234, 2, "A restored reflection 🌿", "Thoughts", "Needs"));
        Files.write(providerFile.toPath(), BackupArchive.encrypt(entries, PASSPHRASE.toCharArray()));
        try (ActivityController<MainActivity> c = unlocked()) {
            MainActivity a = c.get();
            a.findViewById(R.id.backup_restore).performClick();
            ShadowActivity.IntentForResult launch = awaitPicker(a);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.getAction());
            returnFromPicker(c, launch, FILE_URI);
            assertNotNull(a.findViewById(R.id.unlock_pin));
            JournalTest.unlockJournal(a);
            a.findViewById(R.id.backup_restore).performClick();
            enterPassphrase(ShadowAlertDialog.getLatestAlertDialog(), false, PASSPHRASE);
            JournalTest.await(() -> Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getTitle().toString().startsWith("Restore 2 entries"));
            try (JournalStore store = new JournalStore(context)) { assertEquals(1, store.all().size()); }
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            JournalTest.await(() -> status().startsWith("Restore complete"));
            assertTrue(status().contains("1 entries added"));
            try (JournalStore store = new JournalStore(context)) {
                assertEquals(2, store.all().size());
                assertEquals(0, store.restoreEntries(entries));
                assertTrue(store.backupEntries().containsAll(entries));
            }
        }
    }

    @Test public void wrongRestorePassphraseLeavesJournalUnchanged() throws Exception {
        Files.write(providerFile.toPath(), BackupArchive.encrypt(java.util.Collections.singletonList(
                new BackupArchive.Entry(1234, 2, "Incoming", "", "")), PASSPHRASE.toCharArray()));
        try (ActivityController<MainActivity> c = unlocked()) {
            MainActivity a = c.get();
            a.findViewById(R.id.backup_restore).performClick();
            returnFromPicker(c, awaitPicker(a), FILE_URI);
            JournalTest.unlockJournal(a);
            a.findViewById(R.id.backup_restore).performClick();
            enterPassphrase(ShadowAlertDialog.getLatestAlertDialog(), false, "a wrong but long passphrase");
            JournalTest.await(() -> status().startsWith("Couldn’t open"));
            try (JournalStore store = new JournalStore(context)) { assertEquals(1, store.all().size()); }
        }
    }

    private ActivityController<MainActivity> unlocked() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        c.get().findViewById(R.id.journal_tab).performClick(); JournalTest.unlockJournal(c.get());
        return c;
    }
    private void startExport(MainActivity a) {
        a.findViewById(R.id.backup_export).performClick();
        enterPassphrase(ShadowAlertDialog.getLatestAlertDialog(), true, PASSPHRASE);
    }
    private void enterPassphrase(AlertDialog dialog, boolean confirm, String value) {
        ((EditText) dialog.findViewById(R.id.backup_password)).setText(value);
        if (confirm) ((EditText) dialog.findViewById(R.id.backup_password_confirm)).setText(value);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    }
    private ShadowActivity.IntentForResult awaitPicker(MainActivity a) {
        JournalTest.await(() -> Shadows.shadowOf(a).peekNextStartedActivityForResult() != null);
        return Shadows.shadowOf(a).getNextStartedActivityForResult();
    }
    private void returnFromPicker(ActivityController<MainActivity> c, ShadowActivity.IntentForResult launch, Uri uri) {
        c.pause().stop();
        Shadows.shadowOf(c.get()).receiveResult(launch.intent, uri == null ? Activity.RESULT_CANCELED : Activity.RESULT_OK,
                uri == null ? null : new Intent().setData(uri));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        c.start().resume().visible();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private String status() { return context.getSharedPreferences("backup_notices", 0).getString("status", ""); }
    private int encryptedCacheFiles() {
        File[] files = context.getCacheDir().listFiles((directory, name) -> name.startsWith("daily-pause-") && name.endsWith(".dpa"));
        return files == null ? 0 : files.length;
    }

    private static final class FakeDriveProvider extends ContentProvider {
        private final File file;
        boolean failWrites;
        FakeDriveProvider(File file) { this.file = file; }
        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (failWrites && mode.contains("w")) throw new FileNotFoundException("Simulated provider failure");
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }
        @Override public String getType(Uri uri) { return "application/octet-stream"; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
