package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.EditText;
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
public class MoodResponseTest {
    private Context context;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("journal.db");
        context.getSharedPreferences("draft", 0).edit().clear().commit();
        context.getSharedPreferences("journal_lock", 0).edit().clear().commit();
    }

    @Test public void eachMoodShowsItsOwnMessageAndInvitationWithoutRecordingAnEntry() {
        int[] ids = {R.id.mood_1, R.id.mood_2, R.id.mood_3, R.id.mood_4, R.id.mood_5};
        String[] names = {"Low", "Down", "Okay", "Good", "Great"};
        String[] phrases = {"just existing is a win", "Hard days spare no one", "oll korrect", "Keep that rhythm going", "No notes. Just. Awesome."};
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
             JournalStore store = new JournalStore(context)) {
            for (int i = 0; i < ids.length; i++) {
                c.get().findViewById(ids[i]).performClick();
                AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
                assertTrue(dialog.isShowing());
                assertTrue(Shadows.shadowOf(dialog).getTitle().toString().endsWith(names[i]));
                String message = ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
                assertTrue(message, message.contains(phrases[i]));
                assertTrue(message, message.contains("look back") || message.contains("Future you") || message.contains("future you"));
                assertEquals("Let’s journal", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                assertEquals("Maybe later", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
                assertTrue((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertTrue(store.all().isEmpty());
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(c.get().findViewById(ids[i]).isSelected());
            }
        }
    }

    @Test public void journalActionFocusesReflectionAndPreservesWritingAndChosenMood() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
             JournalStore store = new JournalStore(context)) {
            MainActivity a = c.get();
            EditText reflection = a.findViewById(R.id.entry_event);
            reflection.setText("A thought I started before choosing a mood");
            a.findViewById(R.id.mood_4).performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertSame(reflection, a.findViewById(R.id.entry_event));
            assertTrue(reflection.isFocused());
            assertTrue(a.findViewById(R.id.mood_4).isSelected());
            assertEquals("A thought I started before choosing a mood", reflection.getText().toString());
            assertTrue(store.all().isEmpty());
            JournalTest.saveOutsideHeaps(a);
            assertEquals(1, store.all().size());
            assertEquals(4, store.all().get(0).mood());
            assertNotNull(a.findViewById(R.id.unlock_pin));
        }
    }

    @Test public void maybeLaterAllowsMoodOnlySaveWithoutCreatingDuplicateCheckIns() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
             JournalStore store = new JournalStore(context)) {
            JournalTest.chooseMood(c.get(), R.id.mood_1);
            assertTrue(store.all().isEmpty());
            JournalTest.saveOutsideHeaps(c.get());
            assertEquals(1, store.all().size());
            assertEquals(1, store.all().get(0).mood());
            assertEquals("", store.all().get(0).event());
            assertNotNull(c.get().findViewById(R.id.unlock_pin));
        }
    }

    @Test public void backgroundingDismissesResponseAndPreservesDraftWithoutReplayingIt() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            c.get().findViewById(R.id.mood_2).performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            c.pause().stop();
            assertFalse(dialog.isShowing());
            c.start().resume().visible();
            assertTrue(c.get().findViewById(R.id.mood_2).isSelected());
            assertFalse(dialog.isShowing());
            c.recreate();
            assertTrue(c.get().findViewById(R.id.mood_2).isSelected());
            assertFalse(dialog.isShowing());
        }
    }

    @Test public void openingSavedDraftDoesNotShowAnotherResponse() {
        context.getSharedPreferences("draft", 0).edit().putInt("mood", 5).putString("event", "An unfinished reflection").commit();
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertTrue(c.get().findViewById(R.id.mood_5).isSelected());
            assertEquals("An unfinished reflection", ((EditText) c.get().findViewById(R.id.entry_event)).getText().toString());
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        }
    }
}
