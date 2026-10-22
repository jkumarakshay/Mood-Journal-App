package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ContentValues;
import android.content.Context;
import android.os.Looper;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.ZoneId;
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
public class HistoryScreenTest {
    private Context context;
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("journal.db");
        context.getSharedPreferences("draft", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("journal_lock", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void historyIsGatedAndSwitchingToJournalRequiresAnotherUnlock() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get();
            a.findViewById(R.id.history_tab).performClick();
            assertNull(a.findViewById(R.id.mood_chart));
            assertNotNull(a.findViewById(R.id.confirm_pin));
            JournalTest.unlockJournal(a);
            assertNotNull(a.findViewById(R.id.mood_chart));
            assertTrue(a.findViewById(R.id.history_tab).isSelected());
            a.findViewById(R.id.journal_tab).performClick();
            assertNotNull(a.findViewById(R.id.unlock_pin));
            assertNull(a.findViewById(R.id.mood_chart));
        }
    }

    @Test public void allPresetRangesUpdateTheGraphAndAveraging() {
        try (JournalStore store = new JournalStore(context)) { store.add(5, "", "", ""); }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get();
            a.findViewById(R.id.history_tab).performClick();
            JournalTest.unlockJournal(a);
            assertTrue(label(a, R.id.history_grouping).startsWith("Daily"));
            assertTrue(label(a, R.id.history_summary).contains("1 recorded check-ins"));
            a.findViewById(R.id.range_month).performClick();
            assertTrue(label(a, R.id.history_grouping).startsWith("Daily"));
            a.findViewById(R.id.range_three_months).performClick();
            assertTrue(label(a, R.id.history_grouping).startsWith("Weekly"));
            a.findViewById(R.id.range_year).performClick();
            assertTrue(label(a, R.id.history_grouping).startsWith("Monthly"));
            a.findViewById(R.id.range_week).performClick();
            assertTrue(label(a, R.id.history_summary).contains("1 of 7 days recorded"));
            assertTrue(a.findViewById(R.id.mood_chart).getContentDescription().toString().contains("Low is 1"));
        }
    }

    @Test public void customDatesValidateOrderThenApplyBothBoundaries() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get();
            a.findViewById(R.id.history_tab).performClick();
            JournalTest.unlockJournal(a);
            a.findViewById(R.id.range_custom).performClick();
            LocalDate today = LocalDate.now();
            chooseDate(a, R.id.history_from, today);
            chooseDate(a, R.id.history_to, today.minusDays(2));
            a.findViewById(R.id.history_apply).performClick();
            assertTrue(label(a, R.id.history_date_error).contains("end date"));
            chooseDate(a, R.id.history_from, today.minusDays(9));
            a.findViewById(R.id.history_apply).performClick();
            assertTrue(label(a, R.id.history_date_error).isEmpty());
            assertTrue(label(a, R.id.history_summary).contains("of 8 days recorded"));
            assertTrue(label(a, R.id.history_grouping).startsWith("Daily"));
        }
    }

    @Test public void backgroundAndRecreationRelockHistoryAndPreserveSelectedRange() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get();
            a.findViewById(R.id.history_tab).performClick();
            JournalTest.unlockJournal(a);
            a.findViewById(R.id.range_year).performClick();
            c.pause().stop().start().resume().visible();
            assertNull(a.findViewById(R.id.mood_chart));
            JournalTest.unlockJournal(a);
            assertTrue(a.findViewById(R.id.range_year).isSelected());
            c.recreate();
            assertNotNull(c.get().findViewById(R.id.unlock_pin));
            assertNull(c.get().findViewById(R.id.mood_chart));
            JournalTest.unlockJournal(c.get());
            assertTrue(c.get().findViewById(R.id.range_year).isSelected());
        }
    }

    @Test public void sqliteDateFilteringUsesInclusiveLocalDatesAcrossDaylightSaving() {
        LocalDate day = LocalDate.of(2026, 3, 8);
        ZoneId zone = ZoneId.of("America/New_York");
        long from = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long until = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        try (JournalStore store = new JournalStore(context)) {
            for (long time : new long[]{from - 1, from, until - 1, until}) {
                ContentValues v = new ContentValues();
                v.put("created_at", time); v.put("mood", 4); v.put("event", ""); v.put("thoughts", ""); v.put("needs", "");
                store.getWritableDatabase().insertOrThrow("entries", null, v);
            }
            assertEquals(23 * 60 * 60 * 1000L, until - from);
            assertEquals(2, store.moodsBetween(day, day, zone).size());
            MoodHistory.Series series = MoodHistory.summarize(store.moodsBetween(day, day, zone), day, day, zone);
            assertEquals(1, series.recordedDays());
            assertEquals(4.0, series.buckets().get(0).average(), 0.001);
        }
    }

    private String label(MainActivity a, int id) { return ((TextView) a.findViewById(id)).getText().toString(); }

    private void chooseDate(MainActivity a, int id, LocalDate date) {
        a.findViewById(id).performClick();
        DatePickerDialog picker = (DatePickerDialog) ShadowAlertDialog.getLatestAlertDialog();
        picker.getDatePicker().updateDate(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
