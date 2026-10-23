package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import org.junit.After;
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
public class SettingsThemeTest {
    private Context context;
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db");
        for (String name : new String[]{"draft", "journal_lock", "appearance", "heap_notices", "journal_entry_draft", "backup_notices"})
            context.getSharedPreferences(name, 0).edit().clear().commit();
    }
    @After public void clearAppearance() { context.getSharedPreferences("appearance", 0).edit().clear().commit(); }

    @Test public void settingsOpensAboutAndThemeAndBackReturnsToCheckIn() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get(); assertFalse(Appearance.isDark(a));
            assertEquals("Settings", a.findViewById(R.id.settings_button).getContentDescription());
            a.findViewById(R.id.settings_button).performClick();
            assertEquals("Settings", title(a)); assertFalse(a.findViewById(R.id.checkin_tab).isSelected());
            a.findViewById(R.id.settings_about).performClick();
            assertEquals("About Daily Pause", title(a));
            assertTrue(text(a, "organize journals into Heaps")); assertTrue(text(a, "encrypted backup"));
            a.findViewById(R.id.settings_back).performClick();
            a.findViewById(R.id.settings_theme).performClick();
            assertEquals("Theme", title(a)); assertTrue(((RadioButton) a.findViewById(R.id.theme_light)).isChecked());
            a.getOnBackPressedDispatcher().onBackPressed();
            assertEquals("Settings", title(a));
            a.getOnBackPressedDispatcher().onBackPressed();
            assertNotNull(a.findViewById(R.id.entry_event)); assertTrue(a.findViewById(R.id.checkin_tab).isSelected());
        }
    }

    @Test public void themeSelectionPersistsAcrossRecreationAndRestartAndCanReturnToLight() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            themePage(c.get()); c.get().findViewById(R.id.theme_dark).performClick(); idle(); c.recreate();
            MainActivity a = c.get();
            assertEquals("Theme", title(a)); assertTrue(((RadioButton) a.findViewById(R.id.theme_dark)).isChecked());
            assertEquals(0xFF1C1920, ((ColorDrawable) a.findViewById(R.id.app_root).getBackground()).getColor());
            assertEquals(0xFF29252F, attribute(a, android.R.attr.colorBackgroundFloating));
            assertEquals(0, attribute(a, android.R.attr.windowLightStatusBar));
            assertEquals(0, attribute(a, android.R.attr.windowLightNavigationBar));
        }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get(); assertTrue(Appearance.isDark(a));
            assertEquals(0xFF1C1920, ((ColorDrawable) a.findViewById(R.id.app_root).getBackground()).getColor());
            themePage(a); a.findViewById(R.id.theme_light).performClick(); idle(); c.recreate();
            assertFalse(Appearance.isDark(c.get()));
            assertTrue(((RadioButton) c.get().findViewById(R.id.theme_light)).isChecked());
            assertEquals(0xFFFAF7F2, ((ColorDrawable) c.get().findViewById(R.id.app_root).getBackground()).getColor());
            assertNotEquals(0, attribute(c.get(), android.R.attr.windowLightStatusBar));
        }
    }

    @Test public void unfinishedCheckInSurvivesSettingsAndThemeChangeWithoutSavingAnEntry() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            MainActivity a = c.get(); JournalTest.chooseMood(a, R.id.mood_2);
            ((EditText) a.findViewById(R.id.entry_event)).setText("An unfinished reflection");
            ((EditText) a.findViewById(R.id.entry_thoughts)).setText("Feeling uncertain");
            themePage(a); a.findViewById(R.id.theme_dark).performClick(); idle(); c.recreate();
            c.get().findViewById(R.id.settings_back).performClick(); c.get().findViewById(R.id.settings_back).performClick();
            assertEquals("An unfinished reflection", ((EditText) c.get().findViewById(R.id.entry_event)).getText().toString());
            assertEquals("Feeling uncertain", ((EditText) c.get().findViewById(R.id.entry_thoughts)).getText().toString());
            assertTrue(c.get().findViewById(R.id.mood_2).isSelected()); assertTrue(store.all().isEmpty());
            assertEquals(Appearance.color(c.get(), 0xFF312B35), ((EditText) c.get().findViewById(R.id.entry_event)).getCurrentTextColor());
        }
    }

    @Test public void leavingPrivateJournalForSettingsHidesItAndRequiresFreshUnlock() {
        try (JournalStore store = new JournalStore(context)) { store.add(3, "Private journal words", "", ""); }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get(); a.findViewById(R.id.journal_tab).performClick(); JournalTest.unlockJournal(a);
            assertTrue(text(a, "Private journal words")); a.findViewById(R.id.settings_button).performClick();
            assertFalse(text(a, "Private journal words")); assertNull(a.findViewById(R.id.journal_add));
            a.findViewById(R.id.settings_about).performClick(); c.pause().stop().start().resume().visible();
            assertEquals("About Daily Pause", title(c.get()));
            a.findViewById(R.id.settings_back).performClick(); a.findViewById(R.id.settings_back).performClick();
            assertNotNull(a.findViewById(R.id.unlock_pin)); assertFalse(text(a, "Private journal words"));
            JournalTest.unlockJournal(a); assertTrue(text(a, "Private journal words"));
        }
    }

    @Test public void settingsFromHistoryRemainsOnThemeAfterRecreationAndRetainsHistoryRange() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get(); a.findViewById(R.id.history_tab).performClick(); JournalTest.unlockJournal(a);
            a.findViewById(R.id.range_month).performClick();
            themePage(a); a.findViewById(R.id.theme_dark).performClick(); idle(); c.recreate();
            assertEquals("Theme", title(c.get())); assertNull(c.get().findViewById(R.id.unlock_pin));
            c.get().findViewById(R.id.settings_back).performClick(); c.get().findViewById(R.id.settings_back).performClick();
            assertNotNull(c.get().findViewById(R.id.unlock_pin)); JournalTest.unlockJournal(c.get());
            assertTrue(c.get().findViewById(R.id.range_month).isSelected());
            assertEquals(Appearance.color(c.get(), 0xFF312B35), ((TextView) c.get().findViewById(R.id.history_summary)).getCurrentTextColor());
            assertEquals(Appearance.onAccent(c.get()), ((Button) c.get().findViewById(R.id.range_month)).getCurrentTextColor());
        }
    }

    @Test public void darkJournalCardsKeepHeapHueAndDialogsAndBackupsUseDarkAppearance() {
        Appearance.setDark(context, true);
        JournalHeap heap;
        try (JournalStore store = new JournalStore(context)) {
            heap = store.addHeap("Work", "Life", JournalHeap.COLORS[2]);
            store.add(4, "Dark journal words", "Private feelings", "Rest", heap.id());
        }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            MainActivity a = c.get(); a.findViewById(R.id.journal_tab).performClick();
            assertTrue(contrast(((EditText) a.findViewById(R.id.unlock_pin)).getCurrentTextColor(), 0xFF1C1920) >= 4.5);
            JournalTest.unlockJournal(a);
            long entryId = store.all().get(0).id();
            View card = findTag(a.getWindow().getDecorView(), entryId);
            View folder = findTag(a.getWindow().getDecorView(), heap.id());
            int expected = Appearance.color(a, heap.background());
            assertEquals(expected, ((GradientDrawable) card.getBackground()).getColor().getDefaultColor());
            assertEquals(expected, ((GradientDrawable) folder.getBackground()).getColor().getDefaultColor());
            assertEquals(Integer.valueOf(JournalHeap.COLORS[2]), store.heaps().get(0).color());
            assertEquals(Appearance.color(a, 0xFF312B35), ((TextView) a.findViewById(R.id.backup_status)).getCurrentTextColor());
            findButton(a.getWindow().getDecorView(), "Read this entry  →").performClick();
            AlertDialog entry = ShadowAlertDialog.getLatestAlertDialog();
            TextView body = findText(entry.getWindow().getDecorView(), "Dark journal words");
            assertEquals(Appearance.color(a, 0xFF312B35), body.getCurrentTextColor());
            assertTrue((entry.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            entry.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            a.findViewById(R.id.backup_export).performClick();
            EditText password = ShadowAlertDialog.getLatestAlertDialog().findViewById(R.id.backup_password);
            assertTrue(contrast(password.getCurrentTextColor(), 0xFF29252F) >= 4.5); assertFalse(password.isSaveEnabled());
        }
    }

    @Test public void darkPaletteHasReadableTextAcrossEveryHeapColorAndSelectedButtons() {
        Appearance.setDark(context, true);
        for (int color : JournalHeap.COLORS) {
            assertTrue(contrast(Appearance.color(context, 0xFF312B35), Appearance.color(context, color)) >= 4.5);
            assertTrue(contrast(Appearance.color(context, 0xFF705180), Appearance.color(context, color)) >= 4.5);
        }
        assertTrue(contrast(Appearance.onAccent(context), Appearance.color(context, 0xFF705180)) >= 4.5);
        assertTrue(contrast(Appearance.color(context, 0xFF6D6470), Appearance.color(context, 0xFFFAF7F2)) >= 4.5);
    }

    private void themePage(MainActivity a) { a.findViewById(R.id.settings_button).performClick(); a.findViewById(R.id.settings_theme).performClick(); }
    private String title(MainActivity a) { return ((TextView) a.findViewById(R.id.settings_title)).getText().toString(); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private int attribute(MainActivity a, int attr) { TypedValue value = new TypedValue(); assertTrue(a.getTheme().resolveAttribute(attr, value, true)); return value.data; }
    private boolean text(MainActivity a, String value) { return findText(a.getWindow().getDecorView(), value) != null; }
    private TextView findText(View root, String value) {
        if (root instanceof TextView t && t.getText().toString().contains(value)) return t;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { TextView found = findText(group.getChildAt(i), value); if (found != null) return found; }
        return null;
    }
    private View findTag(View root, Object tag) {
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View found = findTag(group.getChildAt(i), tag); if (found != null) return found; }
        return null;
    }
    private Button findButton(View root, String value) {
        TextView found = findText(root, value); return found instanceof Button b ? b : null;
    }
    private double contrast(int one, int two) { double a = luminance(one), b = luminance(two); return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05); }
    private double luminance(int color) {
        double[] weights = {0.2126, 0.7152, 0.0722}; int[] channels = {Color.red(color), Color.green(color), Color.blue(color)};
        double result = 0; for (int i = 0; i < 3; i++) { double value = channels[i] / 255.0; result += weights[i] * (value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4)); } return result;
    }
}
