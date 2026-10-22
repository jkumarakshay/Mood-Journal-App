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
    record Entry(long id, long createdAt, int mood, String event, String thoughts, String needs, String heapId) {}

    JournalStore(Context context) { super(context, "journal.db", null, 2); }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        createHeaps(db);
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, "
                + "mood INTEGER NOT NULL CHECK(mood BETWEEN 1 AND 5), event TEXT NOT NULL, "
                + "thoughts TEXT NOT NULL, needs TEXT NOT NULL, heap_id TEXT REFERENCES heaps(id))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            createHeaps(db);
            db.execSQL("ALTER TABLE entries ADD COLUMN heap_id TEXT REFERENCES heaps(id)");
        } else throw new IllegalStateException("A journal migration is required; never discard entries.");
    }

    private void createHeaps(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE heaps (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL CHECK(length(trim(name)) > 0), "
                + "category TEXT NOT NULL, color INTEGER)");
    }

    JournalHeap addHeap(String name, String category, Integer color) {
        JournalHeap heap = new JournalHeap(java.util.UUID.randomUUID().toString(), name, category, color);
        insertHeap(getWritableDatabase(), heap);
        return heap;
    }

    private void insertHeap(SQLiteDatabase db, JournalHeap heap) {
        ContentValues values = new ContentValues();
        values.put("id", heap.id()); values.put("name", heap.name()); values.put("category", heap.category());
        values.put("color", heap.color());
        db.insertOrThrow("heaps", null, values);
    }

    List<JournalHeap> heaps() {
        List<JournalHeap> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("heaps", null, null, null, null, null, "name COLLATE NOCASE, id")) {
            while (c.moveToNext()) result.add(new JournalHeap(c.getString(0), c.getString(1), c.getString(2), c.isNull(3) ? null : c.getInt(3)));
        }
        return result;
    }

    void move(long id, String heapId) {
        ContentValues values = new ContentValues(); values.put("heap_id", heapId);
        getWritableDatabase().update("entries", values, "id = ?", new String[]{Long.toString(id)});
    }

    long add(int mood, String event, String thoughts, String needs) {
        return add(mood, event, thoughts, needs, null);
    }

    long add(int mood, String event, String thoughts, String needs, String heapId) {
        if (mood < 1 || mood > 5) throw new IllegalArgumentException("Choose a mood");
        ContentValues values = new ContentValues();
        values.put("created_at", System.currentTimeMillis());
        values.put("mood", mood);
        values.put("event", event.trim());
        values.put("thoughts", thoughts.trim());
        values.put("needs", needs.trim());
        values.put("heap_id", heapId);
        return getWritableDatabase().insertOrThrow("entries", null, values);
    }

    List<Entry> all() {
        List<Entry> entries = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("entries", null, null, null, null, null, "created_at DESC, id DESC")) {
            while (c.moveToNext()) entries.add(new Entry(c.getLong(0), c.getLong(1), c.getInt(2),
                    c.getString(3), c.getString(4), c.getString(5), c.getString(6)));
        }
        return entries;
    }

    void delete(long id) { getWritableDatabase().delete("entries", "id = ?", new String[]{Long.toString(id)}); }

    List<BackupArchive.Entry> backupEntries() {
        List<BackupArchive.Entry> result = new ArrayList<>();
        for (Entry entry : all()) result.add(new BackupArchive.Entry(entry.createdAt(), entry.mood(), entry.event(), entry.thoughts(), entry.needs(), entry.heapId()));
        return result;
    }

    BackupArchive.Snapshot backupSnapshot() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            BackupArchive.Snapshot snapshot = new BackupArchive.Snapshot(heaps(), backupEntries());
            db.setTransactionSuccessful();
            return snapshot;
        } finally { db.endTransaction(); }
    }

    /** Merge without replacing local entries, IDs, drafts, or the journal lock. */
    int restoreEntries(List<BackupArchive.Entry> restored) {
        return restoreSnapshot(new BackupArchive.Snapshot(java.util.Collections.emptyList(), restored));
    }

    int restoreSnapshot(BackupArchive.Snapshot restored) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            java.util.Set<BackupArchive.Entry> known = new java.util.HashSet<>(backupEntries());
            java.util.Set<String> heapIds = new java.util.HashSet<>();
            for (JournalHeap heap : heaps()) heapIds.add(heap.id());
            for (JournalHeap heap : restored.heaps()) if (heapIds.add(heap.id())) insertHeap(db, heap);
            int added = 0;
            for (BackupArchive.Entry entry : restored.entries()) {
                if (!known.add(entry)) continue;
                ContentValues values = new ContentValues();
                values.put("created_at", entry.createdAt()); values.put("mood", entry.mood());
                values.put("event", entry.event()); values.put("thoughts", entry.thoughts()); values.put("needs", entry.needs());
                values.put("heap_id", entry.heapId());
                db.insertOrThrow("entries", null, values);
                added++;
            }
            db.setTransactionSuccessful();
            return added;
        } finally { db.endTransaction(); }
    }

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
