package com.moodjournal.app;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import java.time.LocalDate;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class WeatherScreenTest {
    private Context context;
    private WeatherSnapshot forecast;
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db");
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        for (String name : new String[]{"draft", "journal_lock", "appearance", "weather", "journal_entry_draft", "heap_notices", "backup_notices"})
            context.getSharedPreferences(name, 0).edit().clear().commit();
        long now = System.currentTimeMillis();
        forecast = new WeatherSnapshot(LocalDate.now().toString(), 28, 10, "Sunny", WeatherClient.ATTRIBUTION, now, 0);
        WeatherRepository repository = new WeatherRepository(context);
        repository.setPlace(new WeatherClient.Place("My city", 1, 2, java.time.ZoneId.systemDefault().getId())); repository.cache(forecast); repository.attempted(now);
    }

    @Test public void homeHeaderDefaultsToCelsiusAndWeatherSettingsPersistFahrenheit() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            TextView summary = c.get().findViewById(R.id.weather_summary); assertTrue(summary.getText().toString().contains("28°C"));
            assertTrue(summary.getText().toString().contains("Sunny")); assertNull(Shadows.shadowOf(c.get()).getLastRequestedPermission());
            summary.performClick(); assertTrue(c.get().findViewById(R.id.weather_celsius).isSelected() || ((android.widget.RadioButton) c.get().findViewById(R.id.weather_celsius)).isChecked());
            c.get().findViewById(R.id.weather_fahrenheit).performClick(); c.recreate();
            assertTrue(((android.widget.RadioButton) c.get().findViewById(R.id.weather_fahrenheit)).isChecked());
            c.get().findViewById(R.id.checkin_tab).performClick();
            String changed = ((TextView) c.get().findViewById(R.id.weather_summary)).getText().toString();
            assertTrue(changed, changed.contains("82°F")); assertTrue(changed.contains("50°F"));
        }
    }

    @Test public void weatherQuestionIsRemovedWhileLegacyDraftSurvivesSettingsAndRecreation() {
        context.getSharedPreferences("draft", 0).edit().putInt("weather_answer", 2).commit();
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertFalse(text(c.get().getWindow().getDecorView(), WeatherReflection.QUESTION));
            assertNull(c.get().findViewById(R.id.weather_answers));
            c.get().findViewById(R.id.settings_button).performClick(); c.get().findViewById(R.id.settings_weather).performClick();
            c.recreate(); c.get().findViewById(R.id.checkin_tab).performClick();
            assertNull(c.get().findViewById(R.id.weather_answers));
            assertEquals(2, context.getSharedPreferences("draft", 0).getInt("weather_answer", 0));
            try (JournalStore store = new JournalStore(context)) { assertTrue(store.all().isEmpty()); }
        }
    }

    @Test public void saveOutsideHeapsIncludesAnswerAndForecastAndDetailsShowBoth() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            JournalTest.chooseMood(c.get(), R.id.mood_4);
            context.getSharedPreferences("draft", 0).edit().putInt("weather_answer", 1).commit();
            ((EditText) c.get().findViewById(R.id.entry_event)).setText("A walk in the sun"); JournalTest.saveOutsideHeaps(c.get());
            JournalStore.Entry saved = store.all().get(0); assertEquals(1, saved.weatherAnswer()); assertEquals(forecast, saved.weather());
            assertNull(c.get().findViewById(R.id.export_more)); JournalTest.unlockJournal(c.get());
            findButton(c.get().getWindow().getDecorView(), "Read this entry  →").performClick();
            View detail = ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();
            assertTrue(text(detail, "28°C")); assertTrue(text(detail, "Sunny")); assertTrue(text(detail, WeatherReflection.QUESTION)); assertTrue(text(detail, "Absolutely yes"));
        }
    }

    @Test public void heapChoiceKeepsTheForecastCapturedBeforeUnlockRatherThanLaterLiveWeather() {
        JournalHeap heap;
        try (JournalStore store = new JournalStore(context)) { heap = store.addHeap("Outside", "Life", null); }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            JournalTest.chooseMood(c.get(), R.id.mood_3);
            context.getSharedPreferences("draft", 0).edit().putInt("weather_answer", 2).commit();
            c.get().findViewById(R.id.save_entry).performClick(); ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            new WeatherRepository(context).cache(new WeatherSnapshot(forecast.date(), 5, 0, "Rainy", WeatherClient.ATTRIBUTION, System.currentTimeMillis(), 0));
            JournalTest.unlockJournal(c.get()); Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0); idle();
            JournalStore.Entry saved = store.all().get(0); assertEquals(heap.id(), saved.heapId()); assertEquals(forecast, saved.weather()); assertEquals(2, saved.weatherAnswer());
        }
    }

    @Test public void offlineAnswerOnlyReflectionCanBeSavedAndExportedWithoutInventingWeather() {
        context.getSharedPreferences("weather", 0).edit().clear().commit();
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            JournalTest.chooseMood(c.get(), R.id.mood_2); context.getSharedPreferences("draft", 0).edit().putInt("weather_answer", 5).commit(); JournalTest.saveOutsideHeaps(c.get());
            JournalStore.Entry entry = store.all().get(0); assertNull(entry.weather()); assertEquals(5, entry.weatherAnswer()); assertTrue(JournalExport.hasWriting(entry));
            assertFalse(JournalExport.hasWriting(new JournalStore.Entry(10, 1, 3, "", "", "", null, 0, forecast)));
        }
    }

    @Test public void locationPermissionOnlyFollowsUserActionAndDenialLeavesCityChoiceAvailable() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNull(Shadows.shadowOf(c.get()).getLastRequestedPermission());
            c.get().findViewById(R.id.settings_button).performClick(); c.get().findViewById(R.id.settings_weather).performClick();
            c.get().findViewById(R.id.weather_use_location).performClick();
            ShadowActivity.PermissionsRequest request = Shadows.shadowOf(c.get()).getLastRequestedPermission();
            assertArrayEquals(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, request.requestedPermissions);
            c.get().onRequestPermissionsResult(request.requestCode, request.requestedPermissions, new int[]{PackageManager.PERMISSION_DENIED}); idle();
            assertTrue(((TextView) c.get().findViewById(R.id.weather_status)).getText().toString().contains("choose a city"));
            assertTrue(c.get().findViewById(R.id.weather_choose_city).isEnabled());
        }
    }

    @Test public void privateJournalComposerKeepsWeatherAnswerDraftAndSavesItInTheEntry() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            c.get().findViewById(R.id.journal_tab).performClick(); JournalTest.unlockJournal(c.get());
            addJournal(c.get()); AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
            assertNull(editor.findViewById(R.id.weather_answers));
            context.getSharedPreferences("journal_entry_draft", 0).edit().putInt("weather_answer", 3).commit();
            ((android.widget.Spinner) editor.findViewById(R.id.journal_entry_mood)).setSelection(3);
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            addJournal(c.get()); editor = ShadowAlertDialog.getLatestAlertDialog();
            assertNull(editor.findViewById(R.id.weather_answers));
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertEquals(3, store.all().get(0).weatherAnswer()); assertEquals(forecast, store.all().get(0).weather());
        }
    }

    private void addJournal(MainActivity a) { a.findViewById(R.id.journal_add).performClick(); Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0); idle(); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private boolean text(View root, String value) {
        if (root instanceof TextView text && text.getText().toString().contains(value)) return true;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (text(group.getChildAt(i), value)) return true;
        return false;
    }
    private Button findButton(View root, String label) {
        if (root instanceof Button button && label.equals(button.getText().toString())) return button;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { Button found = findButton(group.getChildAt(i), label); if (found != null) return found; }
        return null;
    }
}
