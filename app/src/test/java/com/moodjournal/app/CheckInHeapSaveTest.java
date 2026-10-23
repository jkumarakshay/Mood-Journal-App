package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CheckInHeapSaveTest {
    private Context context;
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db");
        for (String name : new String[]{"draft", "journal_lock", "heap_notices", "journal_entry_draft", "backup_notices"})
            context.getSharedPreferences(name, 0).edit().clear().commit();
    }

    @Test public void promptDoesNotExposeHeapsAndNoSavesAllFieldsOutsideWithoutUnlocking() {
        try (JournalStore store = new JournalStore(context)) { store.addHeap("Private work worries", "Secret category", null); }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            startMoment(c.get());
            AlertDialog prompt = dialog();
            assertEquals("Save in a Heap?", Shadows.shadowOf(prompt).getTitle().toString());
            assertFalse(text(prompt.getWindow().getDecorView(), "Private work worries"));
            assertFalse(text(prompt.getWindow().getDecorView(), "Secret category"));
            assertTrue((prompt.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertTrue(store.all().isEmpty()); assertFalse(new PinLock(context).hasPin());
            click(AlertDialog.BUTTON_NEGATIVE);
            assertMoment(store, null); assertFalse(new PinLock(context).hasPin());
            assertNotNull(c.get().findViewById(R.id.unlock_pin));
            assertTrue(context.getSharedPreferences("draft", 0).getAll().isEmpty());
            prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            assertEquals(1, store.all().size());
        }
    }

    @Test public void existingHeapListRequiresUnlockAndSelectingOneSavesExactlyOnce() {
        JournalHeap selected;
        try (JournalStore store = new JournalStore(context)) {
            store.addHeap("Home", "Life", JournalHeap.COLORS[2]);
            selected = store.addHeap("Work", "Career", JournalHeap.COLORS[1]);
        }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            startMoment(c.get()); click(AlertDialog.BUTTON_POSITIVE);
            assertNotNull(c.get().findViewById(R.id.unlock_pin));
            assertFalse(text(c.get().getWindow().getDecorView(), "Career"));
            assertTrue(store.all().isEmpty()); assertDraftStored();
            JournalTest.unlockJournal(c.get());
            AlertDialog picker = dialog();
            assertEquals(3, picker.getListView().getCount());
            assertEquals("Home · Life", picker.getListView().getItemAtPosition(0));
            assertEquals("Work · Career", picker.getListView().getItemAtPosition(1));
            assertEquals("Create New", picker.getListView().getItemAtPosition(2));
            Shadows.shadowOf(picker).clickOnItem(1); idle();
            assertMoment(store, selected.id());
            assertTrue(text(c.get().getWindow().getDecorView(), "Journals in this Heap"));
            assertTrue(text(c.get().getWindow().getDecorView(), "A moment worth keeping"));
            Shadows.shadowOf(picker).clickOnItem(1); idle(); assertEquals(1, store.all().size());
        }
    }

    @Test public void createNewShowsExplanationValidatesNameAndSavesMomentInNewHeap() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            startMoment(c.get()); click(AlertDialog.BUTTON_NEUTRAL);
            assertNotNull(c.get().findViewById(R.id.unlock_pin)); JournalTest.unlockJournal(c.get());
            assertTrue(text(dialog().getWindow().getDecorView(), "compartmentalize")); click(AlertDialog.BUTTON_POSITIVE);
            click(AlertDialog.BUTTON_POSITIVE);
            assertTrue(dialog().isShowing()); assertTrue(store.heaps().isEmpty()); assertTrue(store.all().isEmpty()); assertDraftStored();
            ((EditText) dialog().findViewById(R.id.heap_name)).setText("Personal growth");
            ((EditText) dialog().findViewById(R.id.heap_category)).setText("Life");
            ((HeapColorPicker) dialog().findViewById(R.id.heap_color)).setColor(JournalHeap.COLORS[2]);
            click(AlertDialog.BUTTON_POSITIVE);
            JournalHeap heap = store.heaps().get(0);
            assertEquals("Personal growth", heap.name()); assertEquals("Life", heap.category());
            assertEquals(Integer.valueOf(JournalHeap.COLORS[2]), heap.color());
            assertMoment(store, heap.id());
            assertEquals(heap.id(), store.backupSnapshot().entries().get(0).heapId());
            assertTrue(context.getSharedPreferences("draft", 0).getAll().isEmpty());
        }
    }

    @Test public void emptyHeapListStillOffersCreateNewAndNo() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            startMoment(c.get()); click(AlertDialog.BUTTON_POSITIVE); JournalTest.unlockJournal(c.get());
            assertEquals(1, dialog().getListView().getCount());
            assertEquals("Create New", dialog().getListView().getItemAtPosition(0));
            assertEquals("No", dialog().getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            Shadows.shadowOf(dialog()).clickOnItem(0); idle();
            assertTrue(text(dialog().getWindow().getDecorView(), "compartmentalize"));
            click(AlertDialog.BUTTON_NEGATIVE);
            assertEquals("Save in a Heap?", Shadows.shadowOf(dialog()).getTitle().toString());
            assertTrue(store.heaps().isEmpty()); assertTrue(store.all().isEmpty());
            click(AlertDialog.BUTTON_NEGATIVE); assertMoment(store, null);
        }
    }

    @Test public void cancellingInitialPromptKeepsDraftAndInvalidatesItsOldButtons() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            startMoment(c.get()); AlertDialog cancelled = dialog(); cancelled.cancel(); idle();
            assertDraftVisible(c.get()); assertDraftStored(); assertTrue(store.all().isEmpty());
            cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle(); assertTrue(store.all().isEmpty());
            JournalTest.saveOutsideHeaps(c.get()); assertMoment(store, null);
        }
    }

    @Test public void cancellingUnlockedPickerReturnsToOriginalDraft() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            startMoment(c.get()); click(AlertDialog.BUTTON_POSITIVE); JournalTest.unlockJournal(c.get());
            click(AlertDialog.BUTTON_NEUTRAL);
            assertDraftVisible(c.get()); assertDraftStored(); assertTrue(store.all().isEmpty());
            JournalTest.saveOutsideHeaps(c.get()); assertMoment(store, null);
        }
    }

    @Test public void cancellingNewHeapFormReturnsToPickerWithoutSavingAnything() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            startMoment(c.get()); click(AlertDialog.BUTTON_NEUTRAL); JournalTest.unlockJournal(c.get());
            click(AlertDialog.BUTTON_POSITIVE);
            ((EditText) dialog().findViewById(R.id.heap_name)).setText("Unfinished Heap");
            click(AlertDialog.BUTTON_NEGATIVE);
            assertEquals("Save in a Heap?", Shadows.shadowOf(dialog()).getTitle().toString());
            assertTrue(store.heaps().isEmpty()); assertTrue(store.all().isEmpty()); assertDraftStored();
            click(AlertDialog.BUTTON_NEGATIVE); assertMoment(store, null);
        }
    }

    @Test public void backgroundingClosesHeapListAndRequiresFreshUnlockAndSelection() {
        JournalHeap heap;
        try (JournalStore store = new JournalStore(context)) { heap = store.addHeap("Private Heap", "", null); }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            startMoment(c.get()); click(AlertDialog.BUTTON_POSITIVE); JournalTest.unlockJournal(c.get());
            AlertDialog stale = dialog(); c.pause().stop(); idle();
            assertFalse(stale.isShowing()); assertFalse(text(c.get().getWindow().getDecorView(), "Private Heap"));
            Shadows.shadowOf(stale).clickOnItem(0); idle(); assertTrue(store.all().isEmpty()); assertDraftStored();
            c.start().resume().visible(); assertNotNull(c.get().findViewById(R.id.unlock_pin));
            JournalTest.unlockJournal(c.get()); Shadows.shadowOf(dialog()).clickOnItem(0); idle(); assertMoment(store, heap.id());
        }
    }

    @Test public void recreationWhileUnlockingRetainsMomentButNeverUnlockedSession() {
        JournalHeap heap;
        try (JournalStore store = new JournalStore(context)) { heap = store.addHeap("Work", "", null); }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            startMoment(c.get()); click(AlertDialog.BUTTON_POSITIVE); c.recreate();
            MainActivity a = c.get(); assertNotNull(a.findViewById(R.id.unlock_pin)); assertTrue(store.all().isEmpty());
            JournalTest.unlockJournal(a); Shadows.shadowOf(dialog()).clickOnItem(0); idle(); assertMoment(store, heap.id());
        }
    }

    @Test public void backToDraftWhileLockedCancelsPendingSaveAcrossLaterUnlock() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            startMoment(c.get()); click(AlertDialog.BUTTON_POSITIVE);
            c.get().findViewById(R.id.cancel_moment_save).performClick();
            assertDraftVisible(c.get()); assertTrue(store.all().isEmpty());
            c.get().findViewById(R.id.journal_tab).performClick(); JournalTest.unlockJournal(c.get());
            assertFalse(dialog().isShowing()); assertTrue(store.all().isEmpty()); assertDraftStored();
        }
    }

    @Test public void failedSaveLeavesDraftAndExistingHeapsIntactForRetry() {
        JournalHeap heap;
        try (JournalStore store = new JournalStore(context)) {
            heap = store.addHeap("Keep this Heap", "", null);
            store.getWritableDatabase().execSQL("CREATE TRIGGER reject_save BEFORE INSERT ON entries BEGIN SELECT RAISE(ABORT, 'Simulated storage failure'); END");
        }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            startMoment(c.get()); click(AlertDialog.BUTTON_POSITIVE); JournalTest.unlockJournal(c.get());
            Shadows.shadowOf(dialog()).clickOnItem(0); idle();
            assertEquals("Your entry couldn’t be saved", Shadows.shadowOf(dialog()).getTitle().toString());
            assertTrue(store.all().isEmpty()); assertEquals(heap, store.heaps().get(0)); assertDraftStored();
            click(AlertDialog.BUTTON_POSITIVE); assertDraftVisible(c.get());
            store.getWritableDatabase().execSQL("DROP TRIGGER reject_save");
            JournalTest.saveOutsideHeaps(c.get()); assertMoment(store, null);
        }
    }

    private void startMoment(MainActivity a) {
        JournalTest.chooseMood(a, R.id.mood_4);
        ((EditText) a.findViewById(R.id.entry_event)).setText("A moment worth keeping");
        ((EditText) a.findViewById(R.id.entry_thoughts)).setText("I felt heard");
        ((EditText) a.findViewById(R.id.entry_needs)).setText("More connection");
        a.findViewById(R.id.save_entry).performClick(); idle();
    }
    private void assertMoment(JournalStore store, String heapId) {
        assertEquals(1, store.all().size()); JournalStore.Entry entry = store.all().get(0);
        assertEquals(4, entry.mood()); assertEquals("A moment worth keeping", entry.event());
        assertEquals("I felt heard", entry.thoughts()); assertEquals("More connection", entry.needs()); assertEquals(heapId, entry.heapId());
    }
    private void assertDraftStored() { assertEquals("A moment worth keeping", context.getSharedPreferences("draft", 0).getString("event", "")); }
    private void assertDraftVisible(MainActivity a) {
        assertEquals("A moment worth keeping", ((EditText) a.findViewById(R.id.entry_event)).getText().toString());
        assertEquals("I felt heard", ((EditText) a.findViewById(R.id.entry_thoughts)).getText().toString());
        assertEquals("More connection", ((EditText) a.findViewById(R.id.entry_needs)).getText().toString());
        assertTrue(a.findViewById(R.id.mood_4).isSelected());
    }
    private boolean text(View root, String value) {
        if (root instanceof TextView t && t.getText().toString().contains(value)) return true;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (text(group.getChildAt(i), value)) return true;
        return false;
    }
    private AlertDialog dialog() { return ShadowAlertDialog.getLatestAlertDialog(); }
    private void click(int button) { dialog().getButton(button).performClick(); idle(); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
}
