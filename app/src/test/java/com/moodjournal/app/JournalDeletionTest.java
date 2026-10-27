package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowPopupMenu;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class JournalDeletionTest {
    private Context context;
    private JournalHeap heap, empty;
    private long entry, outside;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db");
        for (String prefs : new String[]{"draft", "journal_lock", "appearance", "journal_entry_draft", "heap_notices"})
            context.getSharedPreferences(prefs, 0).edit().clear().commit();
        try (JournalStore store = new JournalStore(context)) {
            heap = store.addHeap("Work", "Career", JournalHeap.COLORS[1]);
            empty = store.addHeap("Empty", "Other", null);
            entry = store.add(3, "Work reflection", "", "", heap.id());
            outside = store.add(4, "Outside reflection", "", "");
        }
    }

    @Test public void menusOfferEditExportDeleteIncludingBlankJournalsAndEmptyHeaps() {
        try (JournalStore store = new JournalStore(context)) { store.updateEntry(entry, 3, "", "", "", heap.id()); }
        try (ActivityController<MainActivity> c = unlocked()) {
            for (Object id : new Object[]{entry, empty.id(), heap.id()}) {
                PopupMenu menu = menu(c.get(), id);
                assertEquals(3, menu.getMenu().size());
                assertEquals("Edit", menu.getMenu().getItem(0).getTitle());
                assertEquals("Export", menu.getMenu().getItem(1).getTitle());
                assertEquals("Delete", menu.getMenu().getItem(2).getTitle());
                assertTrue(menu.getMenu().findItem(R.id.delete_action).isEnabled());
                assertFalse(menu.getMenu().findItem(R.id.export_action).isEnabled()); menu.dismiss();
            }
        }
    }

    @Test public void journalNeedsConfirmationCancelKeepsItAndConfirmPreservesEverythingElse() {
        context.getSharedPreferences("journal_entry_draft", 0).edit().putString("event", "Keep draft").commit();
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            AlertDialog confirmation = delete(c.get(), entry);
            assertEquals("Delete this entry?", Shadows.shadowOf(confirmation).getTitle()); assertNotNull(store.entry(entry));
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle(); assertNotNull(store.entry(entry));
            confirmation = delete(c.get(), entry); Button old = confirmation.getButton(AlertDialog.BUTTON_POSITIVE);
            old.performClick(); idle(); assertNull(store.entry(entry)); assertFalse(confirmation.isShowing());
            assertNotNull(store.entry(outside)); assertEquals(2, store.heaps().size()); assertNull(findTag(c.get().getWindow().getDecorView(), entry));
            assertEquals("Keep draft", context.getSharedPreferences("journal_entry_draft", 0).getString("event", ""));
            assertEquals(1, store.backupEntries().size()); old.performClick(); assertNotNull(store.entry(outside));
        }
    }

    @Test public void readerMenuClosesReaderAndUsesTheSameConfirmation() {
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            findButton(findTag(c.get().getWindow().getDecorView(), entry), "Read this entry  →").performClick();
            AlertDialog reader = dialog(); reader.findViewById(R.id.export_more).performClick();
            ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(R.id.delete_action, 0);
            assertFalse(reader.isShowing()); assertNotNull(store.entry(entry));
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); assertNull(store.entry(entry));
        }
    }

    @Test public void evenAMoodOnlyJournalPreventsHeapDeletion() {
        try (JournalStore store = new JournalStore(context)) { store.updateEntry(entry, 3, "", "", "", heap.id()); }
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            AlertDialog blocked = delete(c.get(), heap.id());
            assertEquals("This Heap still has journals", Shadows.shadowOf(blocked).getTitle());
            assertTrue(((TextView) blocked.findViewById(android.R.id.message)).getText().toString().contains("Move or delete all journals"));
            assertTrue(blocked.getButton(AlertDialog.BUTTON_NEGATIVE) == null || blocked.getButton(AlertDialog.BUTTON_NEGATIVE).getVisibility() != View.VISIBLE); blocked.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertNotNull(store.heap(heap.id())); assertEquals(heap.id(), store.entry(entry).heapId());
        }
    }

    @Test public void emptyHeapCancelAndBackKeepItConfirmRemovesItAndResetsObsoleteCategory() {
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            AlertDialog confirm = delete(c.get(), empty.id()); confirm.cancel(); idle(); assertNotNull(store.heap(empty.id()));
            confirm = delete(c.get(), empty.id()); confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle(); assertNotNull(store.heap(empty.id()));
            Spinner filter = c.get().findViewById(R.id.journal_category); filter.setSelection(3); idle();
            assertEquals("Category: Other", ((Spinner)c.get().findViewById(R.id.journal_category)).getSelectedItem());
            confirm = delete(c.get(), empty.id()); confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertNull(store.heap(empty.id())); assertEquals(1, store.heaps().size()); assertEquals(2, store.all().size());
            assertEquals("All categories", ((Spinner)c.get().findViewById(R.id.journal_category)).getSelectedItem());
            assertNotNull(findTag(c.get().getWindow().getDecorView(), entry));
        }
    }

    @Test public void deletingAnOpenEmptyHeapReturnsToAllHeaps() {
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            findTag(c.get().getWindow().getDecorView(), empty.id()).performClick(); idle(); assertNotNull(c.get().findViewById(R.id.heap_back));
            c.get().findViewById(R.id.export_more).performClick(); ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(R.id.delete_action, 0);
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); assertNull(store.heap(empty.id()));
            assertNull(c.get().findViewById(R.id.heap_back)); assertNotNull(c.get().findViewById(R.id.journal_category));
        }
    }

    @Test public void newlyMovedJournalBlocksPreviouslyConfirmedEmptyHeap() {
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            AlertDialog confirm = delete(c.get(), empty.id()); store.move(outside, empty.id());
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertFalse(confirm.isShowing()); assertEquals("This Heap still has journals", Shadows.shadowOf(dialog()).getTitle());
            assertNotNull(store.heap(empty.id())); assertEquals(empty.id(), store.entry(outside).heapId());
        }
    }

    @Test public void movingOrDeletingAllJournalsAllowsDeletingTheirHeap() {
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            delete(c.get(), heap.id()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            store.move(entry, null); c.get().findViewById(R.id.journal_location).performClick(); c.get().findViewById(R.id.journal_location).performClick(); idle();
            AlertDialog confirm = delete(c.get(), heap.id()); assertEquals("Delete this Heap?", Shadows.shadowOf(confirm).getTitle());
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); assertNull(store.heap(heap.id())); assertNotNull(store.entry(entry));
        }
    }

    @Test public void backgroundDismissesSecureConfirmationsAndRejectsStaleClicksAfterUnlock() {
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            for (Object id : new Object[]{entry, empty.id()}) {
                AlertDialog confirm = delete(c.get(), id); Button stale = confirm.getButton(AlertDialog.BUTTON_POSITIVE);
                assertTrue((confirm.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                c.pause().stop(); idle(); assertFalse(confirm.isShowing());
                c.start().resume().visible(); JournalTest.unlockJournal(c.get()); stale.performClick(); idle();
                assertNotNull(store.entry(entry)); assertNotNull(store.heap(empty.id()));
            }
        }
    }

    @Test public void dismissedMenusCannotDeleteOrOpenConfirmationInANewSession() {
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            PopupMenu menu = menu(c.get(), entry); PopupMenu.OnMenuItemClickListener stale = Shadows.shadowOf(menu).getOnMenuItemClickListener();
            android.view.MenuItem item = menu.getMenu().findItem(R.id.delete_action); menu.dismiss(); idle(); assertFalse(stale.onMenuItemClick(item));
            menu = menu(c.get(), empty.id()); stale = Shadows.shadowOf(menu).getOnMenuItemClickListener(); item = menu.getMenu().findItem(R.id.delete_action);
            c.pause().stop(); c.start().resume().visible(); JournalTest.unlockJournal(c.get()); assertFalse(stale.onMenuItemClick(item));
            assertNotNull(store.heap(empty.id())); assertNotNull(store.entry(entry));
        }
    }

    @Test public void deleteFailuresPreserveDataAndKeepConfirmationAvailableForRetryInBothThemes() {
        for (boolean dark : new boolean[]{false, true}) {
            Appearance.setDark(context, dark);
            try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
                String table = dark ? "heaps" : "entries"; Object target = dark ? empty.id() : entry;
                store.getWritableDatabase().execSQL("CREATE TRIGGER reject_delete BEFORE DELETE ON " + table + " BEGIN SELECT RAISE(ABORT,'test failure'); END");
                AlertDialog confirm = delete(c.get(), target); confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
                assertTrue(confirm.isShowing()); assertNotNull(dark ? store.heap(empty.id()) : store.entry(entry)); assertNotNull(store.entry(outside));
                assertEquals(dark, Appearance.isDark(c.get()));
                assertTrue(((TextView)confirm.findViewById(android.R.id.message)).getText().toString().contains("Please try again"));
                store.getWritableDatabase().execSQL("DROP TRIGGER reject_delete"); confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); assertFalse(confirm.isShowing());
            }
        }
    }

    @Test public void storageNeverDeletesNonemptyHeapsAndInvalidatesAnalysisOnlyOnActualDeletion() {
        try (JournalStore store = new JournalStore(context)) {
            store.getWritableDatabase().execSQL("INSERT INTO analytics_cache(id,report) VALUES(1,'private analysis')");
            long before = destructive(store);
            assertEquals(JournalStore.HeapDeletion.NOT_EMPTY, store.deleteEmptyHeap(heap.id()));
            assertEquals(JournalStore.HeapDeletion.MISSING, store.deleteEmptyHeap("missing")); assertEquals(before, destructive(store));
            assertEquals(2, store.all().size()); assertEquals(heap.id(), store.entry(entry).heapId());
            assertEquals(JournalStore.HeapDeletion.DELETED, store.deleteEmptyHeap(empty.id())); assertTrue(destructive(store) > before);
            try (Cursor cache = store.getReadableDatabase().rawQuery("SELECT report FROM analytics_cache WHERE id=1", null)) { assertTrue(cache.moveToFirst()); assertTrue(cache.isNull(0)); }
            assertTrue(store.delete(entry)); assertFalse(store.delete(entry));
            assertEquals(JournalStore.HeapDeletion.DELETED, store.deleteEmptyHeap(heap.id())); assertEquals(1, store.all().size());
            assertTrue(store.backupSnapshot().heaps().isEmpty());
        }
    }

    private long destructive(JournalStore store) { try (Cursor c = store.getReadableDatabase().rawQuery("SELECT destructive_revision FROM analytics_meta WHERE id=1", null)) { c.moveToFirst(); return c.getLong(0); } }
    private ActivityController<MainActivity> unlocked() { ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); c.get().findViewById(R.id.journal_tab).performClick(); JournalTest.unlockJournal(c.get()); return c; }
    private PopupMenu menu(MainActivity activity, Object id) { View card = findTag(activity.getWindow().getDecorView(), id); assertNotNull(card); while (card.findViewById(R.id.export_more) == null) card = (View)card.getParent(); card.findViewById(R.id.export_more).performClick(); return ShadowPopupMenu.getLatestPopupMenu(); }
    private AlertDialog delete(MainActivity activity, Object id) { menu(activity, id).getMenu().performIdentifierAction(R.id.delete_action, 0); idle(); return dialog(); }
    private AlertDialog dialog() { return ShadowAlertDialog.getLatestAlertDialog(); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private View findTag(View root, Object tag) { if (tag.equals(root.getTag())) return root; if (root instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) { View found = findTag(group.getChildAt(i), tag); if (found != null) return found; } return null; }
    private Button findButton(View root, String title) { if (root instanceof Button b && title.contentEquals(b.getText())) return b; if (root instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) { Button found = findButton(group.getChildAt(i), title); if (found != null) return found; } return null; }
}
