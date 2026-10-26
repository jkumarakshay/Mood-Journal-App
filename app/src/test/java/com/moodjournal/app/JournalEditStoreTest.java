package com.moodjournal.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 35)
public class JournalEditStoreTest {
    private Context context;
    @Before public void reset() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db"); }

    @Test public void journalEditKeepsIdentityOriginalDateAndHistoricalWeather() {
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap heap = store.addHeap("Work", "Career", null);
            WeatherSnapshot weather = new WeatherSnapshot("2026-10-23", 24, 12, "Sunny", WeatherClient.ATTRIBUTION, 1234, 0);
            long id = store.add(2, "Before", "Tired", "Rest", heap.id(), 3, weather);
            JournalStore.Entry original = store.entry(id);
            assertTrue(store.updateEntry(id, 5, "  A win  ", "  Better  ", "  A walk  ", null));
            JournalStore.Entry changed = store.entry(id);
            assertEquals(1, store.all().size()); assertEquals(id, changed.id()); assertEquals(original.createdAt(), changed.createdAt());
            assertEquals(5, changed.mood()); assertEquals("A win", changed.event()); assertEquals("Better", changed.thoughts()); assertEquals("A walk", changed.needs());
            assertNull(changed.heapId()); assertEquals(weather, changed.weather()); assertEquals(3, changed.weatherAnswer());
            LocalDate date = java.time.Instant.ofEpochMilli(original.createdAt()).atZone(ZoneId.systemDefault()).toLocalDate();
            assertEquals(5, store.moodsBetween(date, date, ZoneId.systemDefault()).get(0).mood());
        }
    }

    @Test public void invalidOrMissingEditsNeverInsertOrPartiallyReplaceAJournal() {
        try (JournalStore store = new JournalStore(context)) {
            long id = store.add(3, "Original", "Feeling", "Rest"); JournalStore.Entry before = store.entry(id);
            assertFalse(store.updateEntry(9999, 4, "Missing", "", "", null));
            assertThrows(IllegalArgumentException.class, () -> store.updateEntry(id, 0, "Change", "", "", null));
            assertThrows(IllegalArgumentException.class, () -> store.updateEntry(id, 4, "x".repeat(20001), "", "", null));
            assertThrows(android.database.SQLException.class, () -> store.updateEntry(id, 4, "Change", "", "", "missing-heap"));
            assertEquals(before, store.entry(id)); assertEquals(1, store.all().size());
        }
    }

    @Test public void heapEditsKeepMembershipAndAllowClearingOptionalFields() {
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap heap = store.addHeap("Work", "Career", JournalHeap.COLORS[1], "Original purpose");
            long entry = store.add(3, "A thought", "", "", heap.id());
            assertTrue(store.updateHeap(heap.id(), "  Learning  ", "  Growth  ", 0xFF123456, "  New purpose\nMore space  "));
            JournalHeap updated = store.heap(heap.id());
            assertEquals("Learning", updated.name()); assertEquals("Growth", updated.category()); assertEquals("New purpose\nMore space", updated.description());
            assertEquals(Integer.valueOf(0xFF123456), updated.color()); assertEquals(heap.id(), store.entry(entry).heapId()); assertEquals(1, store.heaps().size());
            assertThrows(IllegalArgumentException.class, () -> store.updateHeap(heap.id(), " ", "", null, ""));
            assertThrows(IllegalArgumentException.class, () -> store.updateHeap(heap.id(), "Name", "", null, "x".repeat(2001)));
            assertEquals(updated, store.heap(heap.id()));
            assertTrue(store.updateHeap(heap.id(), "Learning", "", null, ""));
            assertEquals("", store.heap(heap.id()).description()); assertEquals("", store.heap(heap.id()).category()); assertNull(store.heap(heap.id()).color());
        }
    }

    @Test public void versionFourMigrationKeepsHeapsEntriesWeatherAndAnalyticsTables() {
        String id = "00000000-0000-0000-0000-000000000001";
        WeatherSnapshot weather = new WeatherSnapshot("2026-10-23", 24, 12, "Sunny", "Test", 1234, 0);
        try (SQLiteDatabase db = context.openOrCreateDatabase("journal.db", 0, null)) {
            db.execSQL("CREATE TABLE heaps(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,category TEXT NOT NULL,color INTEGER)");
            db.execSQL("INSERT INTO heaps VALUES(?,?,?,?)", new Object[]{id,"Work","Career",JournalHeap.COLORS[1]});
            db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT,created_at INTEGER NOT NULL,mood INTEGER NOT NULL,event TEXT NOT NULL,thoughts TEXT NOT NULL,needs TEXT NOT NULL,heap_id TEXT REFERENCES heaps(id),weather_answer INTEGER NOT NULL DEFAULT 0,weather TEXT)");
            db.execSQL("INSERT INTO entries VALUES(7,1234,2,'Keep me','Feeling','Rest',?,3,?)", new Object[]{id,weather.encode()});
            db.execSQL("CREATE TABLE analytics_meta(id INTEGER PRIMARY KEY,revision INTEGER NOT NULL,destructive_revision INTEGER NOT NULL)");
            db.execSQL("INSERT INTO analytics_meta VALUES(1,12,2)");
            db.execSQL("CREATE TABLE analytics_cache(id INTEGER PRIMARY KEY,report TEXT,revision INTEGER NOT NULL,zone TEXT,next_due INTEGER NOT NULL)");
            db.execSQL("INSERT INTO analytics_cache VALUES(1,'keep cache',2,'UTC',99999)"); db.setVersion(4);
        }
        try (JournalStore store = new JournalStore(context)) {
            assertEquals(5, store.getReadableDatabase().getVersion()); assertEquals("", store.heap(id).description());
            JournalStore.Entry entry = store.entry(7); assertEquals(1234,entry.createdAt()); assertEquals("Keep me",entry.event());
            assertEquals(id,entry.heapId()); assertEquals(weather,entry.weather()); assertEquals(3,entry.weatherAnswer());
            try (Cursor cache = store.getReadableDatabase().rawQuery("SELECT report FROM analytics_cache",null)) {
                assertTrue(cache.moveToFirst()); assertEquals("keep cache",cache.getString(0));
            }
            assertTrue(store.updateHeap(id,"Work","Career",JournalHeap.COLORS[1],"After migration"));
        }
        try (JournalStore reopened = new JournalStore(context)) { assertEquals("After migration",reopened.heap(id).description()); }
    }

    @Test public void descriptionAndEditedContentSurviveEncryptedBackupAndFreshRestore() throws Exception {
        BackupArchive.Snapshot original;
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap heap = store.addHeap("Life", "", 0xFF123456, "A private purpose 🌱");
            long id = store.add(2,"Before","","",heap.id()); store.updateEntry(id,4,"After","Hopeful","",heap.id());
            original = store.backupSnapshot();
        }
        byte[] archive = BackupArchive.encrypt(original,"a long passphrase".toCharArray());
        assertEquals("DPAUSE04",new String(archive,0,8,java.nio.charset.StandardCharsets.US_ASCII));
        assertFalse(new String(archive,java.nio.charset.StandardCharsets.ISO_8859_1).contains("private purpose"));
        BackupArchive.Snapshot restored = BackupArchive.decryptSnapshot(archive,"a long passphrase".toCharArray()); assertEquals(original,restored);
        context.deleteDatabase("journal.db");
        try (JournalStore store = new JournalStore(context)) {
            assertEquals(1,store.restoreSnapshot(restored)); assertEquals(0,store.restoreSnapshot(restored));
            assertEquals(original.heaps(),store.heaps()); assertEquals(original.entries(),store.backupEntries());
            JournalHeap heap=store.heaps().get(0); store.updateHeap(heap.id(),"Local edit","",heap.color(),"Keep local edit");
            store.restoreSnapshot(restored); assertEquals("Keep local edit",store.heaps().get(0).description());
        }
    }
}
