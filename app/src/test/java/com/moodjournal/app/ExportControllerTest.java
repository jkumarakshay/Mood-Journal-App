package com.moodjournal.app;

import static org.junit.Assert.*;
import android.Manifest;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.PopupMenu;
import android.widget.TextView;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowPopupMenu;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = RecordingPdfDocument.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ExportControllerTest {
    private Context context;
    private File destination;
    private Provider provider;
    private long journalId, blankId;
    private JournalHeap heap, emptyHeap, blankHeap;

    @Before public void reset() {
        RecordingPdfDocument.documents.clear();
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db");
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        for (String name : new String[]{"draft", "journal_lock", "appearance", "heap_notices", "backup_notices"})
            context.getSharedPreferences(name, 0).edit().clear().commit();
        destination = new File(context.getFilesDir(), "selected-export"); destination.delete();
        provider = new Provider(destination); ProviderInfo info = new ProviderInfo(); info.authority = "daily.pause.exports";
        provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal(info.authority, provider);
        try (JournalStore store = new JournalStore(context)) {
            heap = store.addHeap("Work", "Life", JournalHeap.COLORS[1]);
            emptyHeap = store.addHeap("Empty", "", null); blankHeap = store.addHeap("Only blank entries", "", null);
            journalId = store.add(4, "A little time to reflect.", "Feeling hopeful.", "Some rest.", heap.id());
            blankId = store.add(3, "", "", "", blankHeap.id());
        }
    }

    @Test public void exportIsHiddenWhileLockedAndBlankJournalsAndHeapsHaveDisabledAction() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            c.get().findViewById(R.id.journal_tab).performClick(); assertNull(c.get().findViewById(R.id.export_more));
            JournalTest.unlockJournal(c.get());
            openMenu(c.get(), blankId); assertFalse(action().isEnabled());
            openHeapMenu(c.get(), emptyHeap); assertFalse(action().isEnabled());
            openHeapMenu(c.get(), blankHeap); assertFalse(action().isEnabled());
            openMenu(c.get(), journalId); assertTrue(action().isEnabled());
        }
    }

    @Test public void journalExportUsesPdfPickerWritesPlatformOutputAndReturnsToLockedJournal() throws Exception {
        try (ActivityController<MainActivity> c = unlocked()) {
            openMenu(c.get(), journalId); export();
            ShadowActivity.IntentForResult launch = picker(c.get());
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, launch.intent.getAction()); assertEquals("application/pdf", launch.intent.getType());
            assertTrue(launch.intent.getStringExtra(Intent.EXTRA_TITLE).endsWith(".pdf"));
            returnFromPicker(c, launch, Uri.parse("content://daily.pause.exports/document/journal"));
            assertNotNull(c.get().findViewById(R.id.unlock_pin)); assertNull(c.get().findViewById(R.id.export_more));
            JournalTest.unlockJournal(c.get()); JournalTest.await(() -> status(c.get()).startsWith("Export saved"));
            assertArrayEquals(RecordingPdfDocument.payload(1), Files.readAllBytes(destination.toPath()));
            assertNoPlaintextCache();
        }
    }

    @Test public void heapMenuOffersZipAndCollatedPdfWithMatchingPickerTypesIncludingInsideHeap() {
        try (ActivityController<MainActivity> c = unlocked()) {
            openHeapMenu(c.get(), heap); export();
            AlertDialog choice = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue((choice.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertEquals("Export as zip", choice.getListView().getAdapter().getItem(0));
            assertEquals("Export as collated PDF", choice.getListView().getAdapter().getItem(1));
            Shadows.shadowOf(choice).clickOnItem(0); idle();
            ShadowActivity.IntentForResult launch = picker(c.get()); assertEquals("application/zip", launch.intent.getType());
            returnFromPicker(c, launch, null); JournalTest.unlockJournal(c.get());
            findTag(c.get().getWindow().getDecorView(), heap.id()).performClick();
            c.get().findViewById(R.id.export_more).performClick(); export();
            Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1); idle();
            launch = picker(c.get()); assertEquals("application/pdf", launch.intent.getType());
            returnFromPicker(c, launch, null);
        }
    }

    @Test public void cancelledPickerCreatesNoFileAndKeepsJournalUnchanged() {
        try (ActivityController<MainActivity> c = unlocked()) {
            openMenu(c.get(), journalId); export(); returnFromPicker(c, picker(c.get()), null);
            JournalTest.unlockJournal(c.get()); assertEquals("Export cancelled.", status(c.get()));
            assertFalse(destination.exists()); assertNoPlaintextCache();
            try (JournalStore store = new JournalStore(context)) { assertEquals(2, store.all().size()); }
        }
    }

    @Test public void pickerRequestSurvivesRecreationWithoutSerializingJournalTextOrUnlocking() throws Exception {
        try (ActivityController<MainActivity> c = unlocked()) {
            openMenu(c.get(), journalId); export(); ShadowActivity.IntentForResult launch = picker(c.get());
            c.recreate(); assertNotNull(c.get().findViewById(R.id.unlock_pin));
            c.get().getActivityResultRegistry().dispatchResult(launch.requestCode, Activity.RESULT_OK,
                    new Intent().setData(Uri.parse("content://daily.pause.exports/document/restored"))); idle();
            JournalTest.unlockJournal(c.get()); JournalTest.await(() -> status(c.get()).startsWith("Export saved"));
            assertArrayEquals(RecordingPdfDocument.payload(1), Files.readAllBytes(destination.toPath())); assertNoPlaintextCache();
        }
    }

    @Test public void providerFailureIsReportedWithoutClaimingSuccess() {
        provider.fail = true;
        try (ActivityController<MainActivity> c = unlocked()) {
            openMenu(c.get(), journalId); export();
            returnFromPicker(c, picker(c.get()), Uri.parse("content://daily.pause.exports/document/broken"));
            JournalTest.unlockJournal(c.get()); JournalTest.await(() -> status(c.get()).startsWith("Couldn’t save"));
            assertFalse(destination.exists()); assertNoPlaintextCache();
        }
    }

    @Test public void staleMenuCannotStartExportAfterBackgroundRelockAndMenusRespectDarkTheme() {
        Appearance.setDark(context, true);
        try (ActivityController<MainActivity> c = unlocked()) {
            openMenu(c.get(), journalId); PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
            c.pause().stop(); assertFalse(Shadows.shadowOf(popup).isShowing());
            assertFalse(Shadows.shadowOf(popup).getOnMenuItemClickListener().onMenuItemClick(popup.getMenu().findItem(R.id.export_action)));
            assertNull(Shadows.shadowOf(c.get()).peekNextStartedActivityForResult());
            c.start().resume().visible(); assertNotNull(c.get().findViewById(R.id.unlock_pin));
            JournalTest.unlockJournal(c.get()); openMenu(c.get(), journalId); assertTrue(action().isEnabled());
        }
        Appearance.setDark(context, false);
    }

    private ActivityController<MainActivity> unlocked() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        c.get().findViewById(R.id.journal_tab).performClick(); JournalTest.unlockJournal(c.get()); return c;
    }
    private void openMenu(MainActivity a, long id) { findTag(a.getWindow().getDecorView(), id).findViewById(R.id.export_more).performClick(); }
    private void openHeapMenu(MainActivity a, JournalHeap h) { ((View) findTag(a.getWindow().getDecorView(), h.id()).getParent()).findViewById(R.id.export_more).performClick(); }
    private android.view.MenuItem action() { return ShadowPopupMenu.getLatestPopupMenu().getMenu().findItem(R.id.export_action); }
    private void export() {
        PopupMenu menu = ShadowPopupMenu.getLatestPopupMenu(); assertTrue(action().isEnabled());
        assertTrue(Shadows.shadowOf(menu).getOnMenuItemClickListener().onMenuItemClick(action())); menu.dismiss(); idle();
    }
    private ShadowActivity.IntentForResult picker(MainActivity a) {
        ShadowActivity.IntentForResult result = Shadows.shadowOf(a).getNextStartedActivityForResult(); assertNotNull(result); return result;
    }
    private void returnFromPicker(ActivityController<MainActivity> c, ShadowActivity.IntentForResult launch, Uri uri) {
        c.pause().stop(); Shadows.shadowOf(c.get()).receiveResult(launch.intent, uri == null ? Activity.RESULT_CANCELED : Activity.RESULT_OK,
                uri == null ? null : new Intent().setData(uri)); idle(); c.start().resume().visible(); idle();
    }
    private String status(MainActivity a) { TextView view = a.findViewById(R.id.export_status); return view == null ? "" : view.getText().toString(); }
    private void assertNoPlaintextCache() {
        File[] files = context.getCacheDir().listFiles((dir, name) -> name.endsWith(".pdf") || name.endsWith(".zip"));
        assertTrue(files == null || files.length == 0);
    }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private View findTag(View root, Object tag) {
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View found = findTag(group.getChildAt(i), tag); if (found != null) return found; }
        return null;
    }
    private static final class Provider extends ContentProvider {
        private final File file; boolean fail;
        Provider(File file) { this.file = file; }
        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (fail) throw new FileNotFoundException("Full disk");
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }
        @Override public String getType(Uri uri) { return "application/pdf"; }
        @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String s, String[] a) { return file.delete() ? 1 : 0; }
        @Override public int update(Uri uri, ContentValues values, String s, String[] a) { return 0; }
    }
}
