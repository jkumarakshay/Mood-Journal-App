package com.moodjournal.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** Journal content is kept in the app's private database, with backup disabled. */
final class JournalStore extends SQLiteOpenHelper {
    record Entry(long id, long createdAt, int mood, String event, String thoughts, String needs) {}

    JournalStore(Context context) { super(context, "journal.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, "
                + "mood INTEGER NOT NULL CHECK(mood BETWEEN 1 AND 5), event TEXT NOT NULL, "
                + "thoughts TEXT NOT NULL, needs TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("A journal migration is required; never discard entries.");
    }

    long add(int mood, String event, String thoughts, String needs) {
        if (mood < 1 || mood > 5) throw new IllegalArgumentException("Choose a mood");
        ContentValues values = new ContentValues();
        values.put("created_at", System.currentTimeMillis());
        values.put("mood", mood);
        values.put("event", event.trim());
        values.put("thoughts", thoughts.trim());
        values.put("needs", needs.trim());
        return getWritableDatabase().insertOrThrow("entries", null, values);
    }

    List<Entry> all() {
        List<Entry> entries = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("entries", null, null, null, null, null, "created_at DESC, id DESC")) {
            while (c.moveToNext()) entries.add(new Entry(c.getLong(0), c.getLong(1), c.getInt(2),
                    c.getString(3), c.getString(4), c.getString(5)));
        }
        return entries;
    }

    void delete(long id) { getWritableDatabase().delete("entries", "id = ?", new String[]{Long.toString(id)}); }

    List<MoodHistory.Reading> moodsBetween(java.time.LocalDate start, java.time.LocalDate end, java.time.ZoneId zone) {
        if (end.isBefore(start)) throw new IllegalArgumentException("Invalid date range");
        long from = start.atStartOfDay(zone).toInstant().toEpochMilli();
        long until = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        List<MoodHistory.Reading> readings = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("entries", new String[]{"created_at", "mood"},
                "created_at >= ? AND created_at < ?", new String[]{Long.toString(from), Long.toString(until)},
                null, null, "created_at ASC, id ASC")) {
            while (c.moveToNext()) readings.add(new MoodHistory.Reading(c.getLong(0), c.getInt(1)));
        }
        return readings;
    }
}
