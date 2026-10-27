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
    record Entry(long id, long createdAt, int mood, String event, String thoughts, String needs, String heapId,
                 int weatherAnswer, WeatherSnapshot weather) {
        Entry(long id, long createdAt, int mood, String event, String thoughts, String needs, String heapId) {
            this(id, createdAt, mood, event, thoughts, needs, heapId, 0, null);
        }
    }

    JournalStore(Context context) { super(context, "journal.db", null, 5); }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        createHeaps(db);
        addHeapDescription(db);
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, "
                + "mood INTEGER NOT NULL CHECK(mood BETWEEN 1 AND 5), event TEXT NOT NULL, "
                + "thoughts TEXT NOT NULL, needs TEXT NOT NULL, heap_id TEXT REFERENCES heaps(id), "
                + "weather_answer INTEGER NOT NULL DEFAULT 0 CHECK(weather_answer BETWEEN 0 AND 5), weather TEXT)");
        createAnalysis(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || newVersion > 5 || oldVersion >= newVersion) throw new IllegalStateException("A journal migration is required; never discard entries.");
        if (oldVersion < 2) {
            createHeaps(db);
            db.execSQL("ALTER TABLE entries ADD COLUMN heap_id TEXT REFERENCES heaps(id)");
        }
        if (oldVersion < 3 && newVersion >= 3) {
            db.execSQL("ALTER TABLE entries ADD COLUMN weather_answer INTEGER NOT NULL DEFAULT 0 CHECK(weather_answer BETWEEN 0 AND 5)");
            db.execSQL("ALTER TABLE entries ADD COLUMN weather TEXT");
        }
        if (oldVersion < 4 && newVersion >= 4) createAnalysis(db);
        if (oldVersion < 5 && newVersion >= 5) addHeapDescription(db);
    }

    private void addHeapDescription(SQLiteDatabase db) {
        try (Cursor columns = db.rawQuery("PRAGMA table_info(heaps)", null)) {
            while (columns.moveToNext()) if ("description".equals(columns.getString(1))) return;
        }
        db.execSQL("ALTER TABLE heaps ADD COLUMN description TEXT NOT NULL DEFAULT ''");
    }

    private void createAnalysis(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE analytics_meta (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL DEFAULT 0, destructive_revision INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT INTO analytics_meta(id) VALUES(1)");
        db.execSQL("CREATE TABLE analytics_cache (id INTEGER PRIMARY KEY CHECK(id=1), report TEXT, revision INTEGER NOT NULL DEFAULT 0, zone TEXT, next_due INTEGER NOT NULL DEFAULT 0)");
        for (String table : new String[]{"entries", "heaps"}) {
            db.execSQL("CREATE TRIGGER analytics_" + table + "_insert AFTER INSERT ON " + table + " BEGIN UPDATE analytics_meta SET revision=revision+1 WHERE id=1; END");
            for (String operation : new String[]{"UPDATE", "DELETE"})
                db.execSQL("CREATE TRIGGER analytics_" + table + "_" + operation.toLowerCase(java.util.Locale.ROOT) + " AFTER " + operation + " ON " + table
                        + " BEGIN UPDATE analytics_meta SET revision=revision+1,destructive_revision=destructive_revision+1 WHERE id=1; UPDATE analytics_cache SET report=NULL WHERE id=1; END");
        }
    }

    private void createHeaps(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE heaps (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL CHECK(length(trim(name)) > 0), "
                + "category TEXT NOT NULL, color INTEGER)");
    }

    JournalHeap addHeap(String name, String category, Integer color) {
        return addHeap(name, category, color, "");
    }

    JournalHeap addHeap(String name, String category, Integer color, String description) {
        JournalHeap heap = new JournalHeap(java.util.UUID.randomUUID().toString(), name, category, color, description);
        insertHeap(getWritableDatabase(), heap);
        return heap;
    }

    private void insertHeap(SQLiteDatabase db, JournalHeap heap) {
        ContentValues values = new ContentValues();
        values.put("id", heap.id()); values.put("name", heap.name()); values.put("category", heap.category());
        values.put("color", heap.color());
        values.put("description", heap.description());
        db.insertOrThrow("heaps", null, values);
    }

    List<JournalHeap> heaps() {
        List<JournalHeap> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("heaps", new String[]{"id", "name", "category", "color", "description"}, null, null, null, null, "name COLLATE NOCASE, id")) {
            while (c.moveToNext()) result.add(new JournalHeap(c.getString(0), c.getString(1), c.getString(2), c.isNull(3) ? null : c.getInt(3), c.getString(4)));
        }
        return result;
    }

    JournalHeap heap(String id) {
        for (JournalHeap heap : heaps()) if (heap.id().equals(id)) return heap;
        return null;
    }

    boolean updateHeap(String id, String name, String category, Integer color, String description) {
        JournalHeap heap = new JournalHeap(id, name, category, color, description);
        ContentValues values = new ContentValues();
        values.put("name", heap.name()); values.put("category", heap.category());
        values.put("color", heap.color()); values.put("description", heap.description());
        return getWritableDatabase().update("heaps", values, "id = ?", new String[]{id}) == 1;
    }

    void move(long id, String heapId) {
        ContentValues values = new ContentValues(); values.put("heap_id", heapId);
        getWritableDatabase().update("entries", values, "id = ?", new String[]{Long.toString(id)});
    }

    long add(int mood, String event, String thoughts, String needs) {
        return add(mood, event, thoughts, needs, null);
    }

    long add(int mood, String event, String thoughts, String needs, String heapId) {
        return add(mood, event, thoughts, needs, heapId, 0, null);
    }

    long add(int mood, String event, String thoughts, String needs, String heapId, int weatherAnswer, WeatherSnapshot weather) {
        if (mood < 1 || mood > 5) throw new IllegalArgumentException("Choose a mood");
        WeatherReflection.validate(weatherAnswer);
        ContentValues values = new ContentValues();
        values.put("created_at", System.currentTimeMillis());
        values.put("mood", mood);
        values.put("event", event.trim());
        values.put("thoughts", thoughts.trim());
        values.put("needs", needs.trim());
        values.put("heap_id", heapId);
        values.put("weather_answer", weatherAnswer); values.put("weather", weather == null ? null : weather.encode());
        return getWritableDatabase().insertOrThrow("entries", null, values);
    }

    List<Entry> all() {
        List<Entry> entries = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("entries", null, null, null, null, null, "created_at DESC, id DESC")) {
            while (c.moveToNext()) entries.add(new Entry(c.getLong(0), c.getLong(1), c.getInt(2),
                    c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7), WeatherSnapshot.decode(c.getString(8))));
        }
        return entries;
    }

    Entry entry(long id) {
        try (Cursor c = getReadableDatabase().query("entries", null, "id = ?", new String[]{Long.toString(id)}, null, null, null)) {
            return c.moveToFirst() ? new Entry(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3), c.getString(4),
                    c.getString(5), c.getString(6), c.getInt(7), WeatherSnapshot.decode(c.getString(8))) : null;
        }
    }

    /** Retain identity, original timestamp and recorded weather when editing a reflection. */
    boolean updateEntry(long id, int mood, String event, String thoughts, String needs, String heapId) {
        if (mood < 1 || mood > 5) throw new IllegalArgumentException("Choose a mood");
        for (String text : new String[]{event, thoughts, needs})
            if (text == null || text.length() > 20_000) throw new IllegalArgumentException("Use up to 20,000 characters per answer.");
        ContentValues values = new ContentValues();
        values.put("mood", mood); values.put("event", event.trim()); values.put("thoughts", thoughts.trim());
        values.put("needs", needs.trim()); values.put("heap_id", heapId);
        return getWritableDatabase().update("entries", values, "id = ?", new String[]{Long.toString(id)}) == 1;
    }

    boolean delete(long id) { return getWritableDatabase().delete("entries", "id = ?", new String[]{Long.toString(id)}) == 1; }

    boolean heapHasEntries(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM entries WHERE heap_id = ? LIMIT 1", new String[]{id})) {
            return c.moveToFirst();
        }
    }

    enum HeapDeletion { DELETED, NOT_EMPTY, MISSING }

    /** Check and delete under one write transaction; never cascade or detach journals. */
    HeapDeletion deleteEmptyHeap(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            HeapDeletion result;
            if (heapHasEntries(id)) result = HeapDeletion.NOT_EMPTY;
            else result = db.delete("heaps", "id = ? AND NOT EXISTS (SELECT 1 FROM entries WHERE heap_id = ?)",
                    new String[]{id, id}) == 1 ? HeapDeletion.DELETED : HeapDeletion.MISSING;
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }

    List<BackupArchive.Entry> backupEntries() {
        List<BackupArchive.Entry> result = new ArrayList<>();
        for (Entry entry : all()) result.add(new BackupArchive.Entry(entry.createdAt(), entry.mood(), entry.event(), entry.thoughts(), entry.needs(), entry.heapId(), entry.weatherAnswer(), entry.weather()));
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
                values.put("weather_answer", entry.weatherAnswer()); values.put("weather", entry.weather() == null ? null : entry.weather().encode());
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
