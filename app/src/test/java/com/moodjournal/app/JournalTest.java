package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.view.View;
import android.view.ViewGroup;
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
public class JournalTest {
    @Before public void clearJournal() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("journal.db");
        context.getSharedPreferences("draft", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("journal_lock", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void savesReflectionThroughUiAndPersistsAfterReopening() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            chooseMood(activity, R.id.mood_4);
            ((EditText) activity.findViewById(R.id.entry_event)).setText("  A walk with a friend 🌿  ");
            ((EditText) activity.findViewById(R.id.entry_thoughts)).setText("I felt listened to.");
            ((EditText) activity.findViewById(R.id.entry_needs)).setText("Make time to connect again.");
            saveOutsideHeaps(activity);
            assertNull(activity.findViewById(R.id.entry_event));
        }
        try (JournalStore reopened = new JournalStore(RuntimeEnvironment.getApplication())) {
            assertEquals(1, reopened.all().size());
            JournalStore.Entry entry = reopened.all().get(0);
            assertEquals(4, entry.mood());
            assertEquals("A walk with a friend 🌿", entry.event());
            assertEquals("I felt listened to.", entry.thoughts());
            assertEquals("Make time to connect again.", entry.needs());
        }
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertEquals("", ((EditText) controller.get().findViewById(R.id.entry_event)).getText().toString());
            assertFalse(controller.get().findViewById(R.id.mood_4).isSelected());
        }
    }

    @Test public void draftSurvivesPauseAndActivityRecreation() {
        try (ActivityController<MainActivity> first = Robolectric.buildActivity(MainActivity.class).setup()) {
            chooseMood(first.get(), R.id.mood_2);
            ((EditText) first.get().findViewById(R.id.entry_event)).setText("An unfinished thought");
            first.pause();
        }
        try (ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertTrue(second.get().findViewById(R.id.mood_2).isSelected());
            assertEquals("An unfinished thought", ((EditText) second.get().findViewById(R.id.entry_event)).getText().toString());
        }
    }

    @Test public void requiresMoodButAllowsMoodOnlyCheckIn() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
             JournalStore db = new JournalStore(controller.get())) {
            controller.get().findViewById(R.id.save_entry).performClick();
            assertTrue(db.all().isEmpty());
            chooseMood(controller.get(), R.id.mood_3);
            saveOutsideHeaps(controller.get());
            assertEquals(1, db.all().size());
            assertEquals("", db.all().get(0).event());
        }
    }

    @Test public void deletionRequiresConfirmationAndPreservesOtherEntries() {
        Context context = RuntimeEnvironment.getApplication();
        try (JournalStore db = new JournalStore(context)) {
            db.add(1, "Earlier entry", "", "");
            db.add(5, "Later entry", "", "");
        }
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
             JournalStore db = new JournalStore(context)) {
            MainActivity activity = controller.get();
            activity.findViewById(R.id.journal_tab).performClick();
            unlockJournal(activity);
            Button read = findButton(activity.getWindow().getDecorView(), "Read this entry  →");
            assertNotNull(read);
            read.performClick();
            clickDialogButton(AlertDialog.BUTTON_NEUTRAL);
            assertEquals("Delete this entry?", Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getTitle().toString());
            assertEquals(2, db.all().size());
            clickDialogButton(AlertDialog.BUTTON_NEGATIVE);
            assertEquals(2, db.all().size());
            clickDialogButton(AlertDialog.BUTTON_NEUTRAL);
            clickDialogButton(AlertDialog.BUTTON_POSITIVE);
            assertEquals(1, db.all().size());
            assertEquals("Earlier entry", db.all().get(0).event());
        }
    }

    @Test public void switchingTabsRetainsDraft() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ((EditText) activity.findViewById(R.id.entry_thoughts)).setText("Feeling a little uncertain");
            activity.findViewById(R.id.journal_tab).performClick();
            activity.findViewById(R.id.checkin_tab).performClick();
            assertEquals("Feeling a little uncertain", ((EditText) activity.findViewById(R.id.entry_thoughts)).getText().toString());
        }
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsInvalidMood() {
        try (JournalStore db = new JournalStore(RuntimeEnvironment.getApplication())) { db.add(0, "", "", ""); }
    }

    private Button findButton(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text)) return (Button) view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void clickDialogButton(int which) {
        ShadowAlertDialog.getLatestAlertDialog().getButton(which).performClick();
        // Android dispatches dialog button listeners through the main message queue.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    static View pinForm(MainActivity activity) {
        if (activity.findViewById(R.id.unlock_pin) != null) return activity.getWindow().getDecorView();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        if (dialog == null || !dialog.isShowing() || dialog.findViewById(R.id.unlock_pin) == null) {
            View action = activity.findViewById(R.id.unlock_use_pin);
            if (action == null) action = activity.findViewById(R.id.unlock_submit);
            assertNotNull("Expected an unlock action", action); action.performClick();
            dialog = ShadowAlertDialog.getLatestAlertDialog();
        }
        assertNotNull(dialog); assertTrue(dialog.isShowing()); return dialog.getWindow().getDecorView();
    }

    static void unlockJournal(MainActivity activity) {
        View form = pinForm(activity);
        ((EditText) form.findViewById(R.id.unlock_pin)).setText("642081");
        EditText confirm = form.findViewById(R.id.confirm_pin);
        if (confirm != null) confirm.setText("642081");
        View submit = form.findViewById(R.id.pin_dialog_submit);
        if (submit == null) submit = form.findViewById(R.id.unlock_submit);
        submit.performClick();
        await(() -> {
            AlertDialog prompt = ShadowAlertDialog.getLatestAlertDialog();
            if (prompt != null && prompt.isShowing() && prompt.findViewById(R.id.biometric_preference_message) != null)
                prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            return activity.findViewById(R.id.unlock_submit) == null && activity.findViewById(R.id.unlock_use_pin) == null;
        });
    }

    static void saveOutsideHeaps(MainActivity activity) {
        activity.findViewById(R.id.save_entry).performClick();
        AlertDialog prompt = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Save in a Heap?", Shadows.shadowOf(prompt).getTitle().toString());
        prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    static void chooseMood(MainActivity activity, int moodId) {
        activity.findViewById(moodId).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            try { Thread.sleep(10); } catch (InterruptedException e) { throw new AssertionError(e); }
        }
        assertTrue("Asynchronous authentication did not finish", condition.getAsBoolean());
    }
}
