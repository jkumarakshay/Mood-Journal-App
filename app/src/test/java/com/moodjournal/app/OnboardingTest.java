package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
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
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class OnboardingTest {
    private Context context;
    @Before public void freshInstall() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db");
        context.getSharedPreferences(UpdateController.PREFS, 0).edit().putBoolean("automatic", false).commit();
        for (String name : new String[]{OnboardingState.PREFS, "draft", "journal_lock", "appearance", "reminder", "weather"})
            context.getSharedPreferences(name, 0).edit().clear().commit();
    }
    @Test public void welcomeThanksIsOneTimeAndDefersNotificationPermission() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNotNull(c.get().findViewById(R.id.welcome_title));
            assertNull(c.get().findViewById(R.id.journal_tab));
            assertFalse(context.getSharedPreferences("reminder", 0).getBoolean("permission_requested", false));
            c.get().findViewById(R.id.welcome_thanks).performClick();
            assertFalse(new OnboardingState(context).active());
            assertNotNull(c.get().findViewById(R.id.entry_event));
            assertTrue(context.getSharedPreferences("reminder", 0).getBoolean("permission_requested", false));
        }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNull(c.get().findViewById(R.id.welcome_title)); assertNull(c.get().findViewById(R.id.tour_overlay));
        }
    }
    @Test public void nineStepsStayInOrderAndOnlyAdvanceWithNextWithoutCreatingPrivateData() {
        int[] titles = {R.string.tour_checkin_title, R.string.tour_emotion_title, R.string.tour_writing_title,
                R.string.tour_weather_title, R.string.tour_journal_title, R.string.tour_add_title,
                R.string.tour_analytics_title, R.string.mood_graph, R.string.mood_analysis};
        int[] ids = {R.id.checkin_tab, R.id.emotion_section, R.id.writing_section, R.id.weather_summary,
                R.id.journal_tab, R.id.journal_add, R.id.history_tab};
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get(); a.findViewById(R.id.welcome_tour).performClick();
            for (int step = 0; step < 9; step++) {
                assertEquals(context.getString(titles[step]), ((TextView) a.findViewById(R.id.tour_title)).getText().toString());
                TourOverlay overlay = a.findViewById(R.id.tour_overlay);
                View target = ReflectionHelpers.getField(overlay, "target");
                if (step < 7) assertEquals(ids[step], target.getId()); else assertNull(target);
                if (step == 5 || step == 6) assertTrue(a.findViewById(R.id.journal_tab).isSelected());
                if (step >= 7) assertTrue(a.findViewById(R.id.history_tab).isSelected());
                assertFalse(ReflectionHelpers.<Boolean>getField(a, "journalUnlocked"));
                assertNull(a.findViewById(R.id.unlock_pin));
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, a.findViewById(R.id.app_root).getImportantForAccessibility());
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10));
                a.getOnBackPressedDispatcher().onBackPressed();
                assertEquals(step, new OnboardingState(context).step());
                assertFalse(context.getSharedPreferences("reminder", 0).getBoolean("permission_requested", false));
                a.findViewById(R.id.tour_next).performClick();
            }
            assertNull(a.findViewById(R.id.tour_overlay));
            assertTrue(a.findViewById(R.id.checkin_tab).isSelected());
            assertFalse(new PinLock(context).hasPin());
            try (JournalStore store = new JournalStore(context)) { assertTrue(store.all().isEmpty()); assertTrue(store.heaps().isEmpty()); }
            a.findViewById(R.id.journal_tab).performClick(); assertNotNull(a.findViewById(R.id.confirm_pin));
        }
    }
    @Test public void tourRestoresExactStepAcrossBackgroundRecreationAndReminderIntent() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            c.get().findViewById(R.id.welcome_tour).performClick();
            for (int n = 0; n < 5; n++) c.get().findViewById(R.id.tour_next).performClick();
            c.pause().stop().start().resume();
            assertEquals(context.getString(R.string.tour_add_title), ((TextView)c.get().findViewById(R.id.tour_title)).getText().toString());
            c.recreate();
            assertEquals(5, new OnboardingState(context).step());
            assertNotNull(c.get().findViewById(R.id.journal_add));
            c.newIntent(new Intent(context, MainActivity.class).setAction(ReminderScheduler.OPEN_CHECK_IN));
            assertEquals(5, new OnboardingState(context).step()); assertNotNull(c.get().findViewById(R.id.tour_next));
        }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertEquals(context.getString(R.string.tour_add_title), ((TextView)c.get().findViewById(R.id.tour_title)).getText().toString());
        }
    }
    @Test public void skipDoesNotReplayWelcomeAndLeavesAuthenticationIntact() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            c.get().findViewById(R.id.welcome_tour).performClick(); c.get().findViewById(R.id.tour_skip).performClick();
            c.get().findViewById(R.id.history_tab).performClick();
            org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(R.id.analytics_graph, 0);
            assertNotNull(c.get().findViewById(R.id.unlock_pin));
        }
        assertEquals(OnboardingState.COMPLETE, new OnboardingState(context).step());
    }
    @Test public void existingJournalInstallSkipsWelcomeAndKeepsData() {
        try (JournalStore store = new JournalStore(context)) { store.add(4, "Existing private words", "", ""); }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNull(c.get().findViewById(R.id.welcome_title));
            assertNotNull(c.get().findViewById(R.id.entry_event));
            try (JournalStore store = new JournalStore(context)) { assertEquals("Existing private words", store.all().get(0).event()); }
        }
    }
    @Test public void existingDraftInstallSkipsWelcomeAndPreservesUnfinishedWriting() {
        context.getSharedPreferences("draft", 0).edit().putString("event", "Unfinished reflection").commit();
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNull(c.get().findViewById(R.id.welcome_title));
            assertEquals("Unfinished reflection", ((EditText)c.get().findViewById(R.id.entry_event)).getText().toString());
        }
    }
    @Test public void tourPreviewsNeverRevealJournalsAddedWhileTourIsPending() {
        context.getSharedPreferences(OnboardingState.PREFS, 0).edit().putInt("step", 5).commit();
        try (JournalStore store = new JournalStore(context)) { store.add(1, "Never show this in a preview", "", ""); }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNull(c.get().findViewById(R.id.entry_event)); assertNotNull(c.get().findViewById(R.id.journal_add));
            assertFalse(ReflectionHelpers.<Boolean>getField(c.get(), "journalUnlocked"));
            assertFalse(allText(c.get().findViewById(R.id.app_root)).contains("Never show this"));
            for (int n=5;n<9;n++) { assertFalse(allText(c.get().findViewById(R.id.app_root)).contains("Never show this")); c.get().findViewById(R.id.tour_next).performClick(); }
        }
    }
    @Test @Config(sdk = 26) public void welcomeAndTourWorkOnMinimumAndroidVersion() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            c.get().findViewById(R.id.welcome_tour).performClick();
            for (int n = 0; n < 9; n++) c.get().findViewById(R.id.tour_next).performClick();
            assertFalse(new OnboardingState(context).active());
        }
    }
    private String allText(View view) {
        if (view instanceof TextView text) return text.getText().toString();
        StringBuilder words = new StringBuilder();
        if (view instanceof android.view.ViewGroup group) for (int i=0;i<group.getChildCount();i++) words.append(allText(group.getChildAt(i)));
        return words.toString();
    }
}
