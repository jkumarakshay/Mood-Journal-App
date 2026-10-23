package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
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
public class HeapScreenTest {
    private Context context;
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db");
        for (String name : new String[]{"draft", "journal_lock", "heap_notices", "journal_entry_draft", "backup_notices"})
            context.getSharedPreferences(name, 0).edit().clear().commit();
    }

    @Test public void firstHeapExplainsPurposeRequiresNameAndPersistsOptionalFields() {
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            add(c.get(), 1);
            assertTrue(text(dialog(), "compartmentalize"));
            assertTrue(text(dialog(), "topic, concern, or facet"));
            click(AlertDialog.BUTTON_POSITIVE);
            click(AlertDialog.BUTTON_POSITIVE);
            assertTrue(dialog().isShowing()); assertTrue(store.heaps().isEmpty());
            assertTrue(text(dialog(), "Give your Heap a name"));
            ((EditText) dialog().findViewById(R.id.heap_name)).setText("Work worries");
            ((EditText) dialog().findViewById(R.id.heap_category)).setText("Career");
            ((HeapColorPicker) dialog().findViewById(R.id.heap_color)).setColor(JournalHeap.COLORS[2]);
            click(AlertDialog.BUTTON_POSITIVE);
            JournalHeap heap = store.heaps().get(0);
            assertEquals("Work worries", heap.name()); assertEquals("Career", heap.category());
            assertEquals(Integer.valueOf(JournalHeap.COLORS[2]), heap.color());
            assertTrue(text(c.get(), "Journals in this Heap"));
            add(c.get(), 1);
            assertNotNull(dialog().findViewById(R.id.heap_name));
        }
    }

    @Test public void cancellingFirstExplanationDoesNotCreateHeapOrSuppressIt() {
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            add(c.get(), 1); click(AlertDialog.BUTTON_NEGATIVE); assertTrue(store.heaps().isEmpty());
            add(c.get(), 1); assertTrue(text(dialog(), "compartmentalize"));
        }
    }

    @Test public void newJournalCanBeSavedInHeapAndMovedOutsideIt() {
        JournalHeap heap;
        try (JournalStore store = new JournalStore(context)) { heap = store.addHeap("Family", "", null); }
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            View root = c.get().getWindow().getDecorView();
            findTag(root, heap.id()).performClick();
            add(c.get(), 0);
            assertEquals(1, ((Spinner) dialog().findViewById(R.id.entry_heap)).getSelectedItemPosition());
            click(AlertDialog.BUTTON_POSITIVE); assertTrue(store.all().isEmpty());
            ((Spinner) dialog().findViewById(R.id.journal_entry_mood)).setSelection(4);
            ((EditText) dialog().findViewById(R.id.journal_entry_event)).setText("Time together");
            click(AlertDialog.BUTTON_POSITIVE);
            assertEquals(heap.id(), store.all().get(0).heapId());
            findButton(c.get().getWindow().getDecorView(), "Read this entry  →").performClick();
            click(AlertDialog.BUTTON_NEGATIVE);
            ((Spinner) dialog().findViewById(R.id.entry_heap)).setSelection(0);
            click(AlertDialog.BUTTON_POSITIVE); assertNull(store.all().get(0).heapId());
            c.get().findViewById(R.id.heap_back).performClick();
            assertTrue(text(c.get(), "Time together"));
            assertTrue(text(c.get(), "Outside Heaps"));
        }
    }

    @Test public void categoryFilterCombinesHeapsAndUncategorizedIncludesLooseEntries() {
        String coloredId;
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap a = store.addHeap("Work", "Life", JournalHeap.COLORS[1]); coloredId = a.id();
            JournalHeap b = store.addHeap("Home", "life", JournalHeap.COLORS[2]);
            JournalHeap c = store.addHeap("Dreams", "", null);
            store.add(3, "Work entry", "", "", a.id()); store.add(4, "Home entry", "", "", b.id());
            store.add(2, "Uncategorized entry", "", "", c.id()); store.add(3, "Loose entry", "", "");
        }
        try (ActivityController<MainActivity> c = unlocked(); JournalStore store = new JournalStore(context)) {
            long coloredEntry = store.all().stream().filter(e -> coloredId.equals(e.heapId())).findFirst().get().id();
            View card = findTag(c.get().getWindow().getDecorView(), coloredEntry);
            assertEquals(JournalHeap.COLORS[1], ((GradientDrawable) card.getBackground()).getColor().getDefaultColor());
            assertTrue(text(c.get(), "Heap: Work"));
            Spinner category = c.get().findViewById(R.id.journal_category); assertEquals(3, category.getCount());
            category.setSelection(2); idle();
            assertTrue(text(c.get(), "Work entry")); assertTrue(text(c.get(), "Home entry"));
            assertFalse(text(c.get(), "Uncategorized entry")); assertFalse(text(c.get(), "Loose entry"));
            for (JournalStore.Entry entry : store.all()) {
                if (entry.heapId() == null || entry.event().equals("Uncategorized entry")) continue;
                JournalHeap heap = store.heaps().stream().filter(h -> h.id().equals(entry.heapId())).findFirst().get();
                View filteredCard = findTag(c.get().getWindow().getDecorView(), entry.id());
                assertEquals(heap.background(), ((GradientDrawable) filteredCard.getBackground()).getColor().getDefaultColor());
                assertTrue(text(filteredCard, "Heap: " + heap.name()));
            }
            ((Spinner) c.get().findViewById(R.id.journal_category)).setSelection(1); idle();
            assertFalse(text(c.get(), "Work entry")); assertFalse(text(c.get(), "Home entry"));
            assertTrue(text(c.get(), "Uncategorized entry")); assertTrue(text(c.get(), "Loose entry"));
            ((Spinner) c.get().findViewById(R.id.journal_category)).setSelection(0); idle();
            assertTrue(text(c.get(), "Work entry")); assertTrue(text(c.get(), "Loose entry"));
            c.get().findViewById(R.id.journal_location).performClick();
            assertFalse(text(c.get(), "Work entry")); assertTrue(text(c.get(), "Loose entry"));
        }
    }

    @Test public void selectingCategoryLeavesOutsideOnlyViewAndSearchesAllMatchingHeaps() {
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap work = store.addHeap("Work", "Life", JournalHeap.COLORS[1]);
            JournalHeap home = store.addHeap("Home", "Life", JournalHeap.COLORS[2]);
            JournalHeap exercise = store.addHeap("Exercise", "Health", JournalHeap.COLORS[3]);
            store.add(3, "Work reflection", "", "", work.id());
            store.add(4, "Home reflection", "", "", home.id());
            store.add(5, "Exercise reflection", "", "", exercise.id());
            store.add(2, "Loose reflection", "", "");
        }
        try (ActivityController<MainActivity> c = unlocked()) {
            c.get().findViewById(R.id.journal_location).performClick();
            assertTrue(text(c.get(), "Loose reflection")); assertFalse(text(c.get(), "Work reflection"));
            Spinner filter = c.get().findViewById(R.id.journal_category);
            int life = -1;
            for (int i = 0; i < filter.getCount(); i++) if (filter.getItemAtPosition(i).equals("Category: Life")) life = i;
            assertTrue(life >= 0); filter.setSelection(life); idle();
            assertTrue(text(c.get(), "Work reflection")); assertTrue(text(c.get(), "Home reflection"));
            assertFalse(text(c.get(), "Exercise reflection")); assertFalse(text(c.get(), "Loose reflection"));
            assertEquals("Show journals outside Heaps", ((Button) c.get().findViewById(R.id.journal_location)).getText().toString());
        }
    }

    @Test public void heapNamesAndDialogsStayLockedAndPrivateDraftSurvivesBackground() {
        try (JournalStore store = new JournalStore(context)) { store.addHeap("Private concern", "Private category", null); }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get(); a.findViewById(R.id.journal_tab).performClick();
            assertNull(a.findViewById(R.id.journal_add)); assertFalse(text(a, "Private concern"));
            JournalTest.unlockJournal(a); add(a, 0);
            AlertDialog editor = dialog();
            ((EditText) editor.findViewById(R.id.journal_entry_event)).setText("Private unfinished words");
            assertFalse(editor.findViewById(R.id.journal_entry_event).isSaveEnabled());
            assertTrue((editor.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            c.pause().stop(); idle(); assertFalse(editor.isShowing()); assertFalse(text(a, "Private concern"));
            c.start().resume().visible(); JournalTest.unlockJournal(a); add(a, 0);
            assertEquals("Private unfinished words", ((EditText) dialog().findViewById(R.id.journal_entry_event)).getText().toString());
        }
    }

    private ActivityController<MainActivity> unlocked() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        c.get().findViewById(R.id.journal_tab).performClick(); JournalTest.unlockJournal(c.get()); return c;
    }
    private void add(MainActivity a, int item) { a.findViewById(R.id.journal_add).performClick(); Shadows.shadowOf(dialog()).clickOnItem(item); idle(); }
    private AlertDialog dialog() { return ShadowAlertDialog.getLatestAlertDialog(); }
    private void click(int which) { dialog().getButton(which).performClick(); idle(); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private boolean text(MainActivity a, String value) { return text(a.getWindow().getDecorView(), value); }
    private boolean text(AlertDialog d, String value) { return text(d.getWindow().getDecorView(), value); }
    private boolean text(View root, String value) {
        if (root instanceof TextView t && t.getText().toString().contains(value)) return true;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (text(group.getChildAt(i), value)) return true;
        return false;
    }
    private View findTag(View root, Object tag) {
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View found = findTag(group.getChildAt(i), tag); if (found != null) return found; }
        return null;
    }
    private Button findButton(View root, String label) {
        if (root instanceof Button b && label.equals(b.getText().toString())) return b;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { Button found = findButton(group.getChildAt(i), label); if (found != null) return found; }
        return null;
    }
}
