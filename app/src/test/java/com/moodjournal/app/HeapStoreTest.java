package com.moodjournal.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class HeapStoreTest {
    private Context context;
    @Before public void reset() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db"); }

    @Test public void migrationPreservesExistingEntryAndLeavesItOutsideHeaps() {
        try (SQLiteDatabase db = context.openOrCreateDatabase("journal.db", 0, null)) {
            db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, "
                    + "mood INTEGER NOT NULL CHECK(mood BETWEEN 1 AND 5), event TEXT NOT NULL, thoughts TEXT NOT NULL, needs TEXT NOT NULL)");
            db.execSQL("INSERT INTO entries VALUES (7, 1234, 4, 'Keep me', 'Feelings', 'Rest')");
            db.setVersion(1);
        }
        try (JournalStore store = new JournalStore(context)) {
            JournalStore.Entry entry = store.all().get(0);
            assertEquals(7, entry.id()); assertEquals(1234, entry.createdAt()); assertEquals("Keep me", entry.event());
            assertEquals("Feelings", entry.thoughts()); assertEquals("Rest", entry.needs()); assertNull(entry.heapId());
            assertTrue(store.heaps().isEmpty());
            JournalHeap heap = store.addHeap("Work", "Career", null);
            store.move(entry.id(), heap.id());
            assertEquals(heap.id(), store.all().get(0).heapId());
        }
    }

    @Test public void heapsAndMembershipPersistAndMovingDoesNotChangeMoodHistory() {
        String heapId; long entryId;
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap heap = store.addHeap("  Family  ", "  Relationships  ", JournalHeap.COLORS[2]);
            heapId = heap.id(); entryId = store.add(4, "A visit", "", "", heap.id());
            store.add(2, "Loose thought", "", "");
            ZoneId zone = ZoneId.systemDefault(); LocalDate today = LocalDate.now(zone);
            List<MoodHistory.Reading> before = store.moodsBetween(today, today, zone);
            store.move(entryId, null);
            assertEquals(before, store.moodsBetween(today, today, zone));
            store.move(entryId, heapId);
        }
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap heap = store.heaps().get(0);
            assertEquals("Family", heap.name()); assertEquals("Relationships", heap.category());
            assertEquals(Integer.valueOf(JournalHeap.COLORS[2]), heap.color());
            assertEquals(heapId, store.all().stream().filter(e -> e.id() == entryId).findFirst().get().heapId());
            store.delete(entryId); assertEquals(1, store.heaps().size()); assertEquals(1, store.all().size());
        }
    }

    @Test public void nameIsRequiredAndOptionalFieldsHaveDefaultsAndReferencesAreEnforced() {
        try (JournalStore store = new JournalStore(context)) {
            assertThrows(IllegalArgumentException.class, () -> store.addHeap("   ", "", null));
            JournalHeap heap = store.addHeap("Quiet space", "", null);
            assertEquals("", heap.category()); assertNull(heap.color());
            assertThrows(android.database.SQLException.class, () -> store.add(3, "", "", "", "missing"));
            assertTrue(store.all().isEmpty());
        }
    }

    @Test public void encryptedBackupRestoresEmptyHeapsAndMembershipWithoutDuplicates() throws Exception {
        BackupArchive.Snapshot snapshot;
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap one = store.addHeap("Same name", "Work", JournalHeap.COLORS[1]);
            store.addHeap("Same name", "", null);
            store.add(4, "Inside", "Feelings", "Rest", one.id()); store.add(3, "Outside", "", "");
            snapshot = BackupArchive.decryptSnapshot(BackupArchive.encrypt(store.backupSnapshot(), "a long passphrase".toCharArray()), "a long passphrase".toCharArray());
        }
        context.deleteDatabase("journal.db");
        try (JournalStore store = new JournalStore(context)) {
            assertEquals(2, store.restoreSnapshot(snapshot)); assertEquals(0, store.restoreSnapshot(snapshot));
            assertEquals(snapshot.heaps(), store.heaps());
            assertEquals(new java.util.HashSet<>(snapshot.entries()), new java.util.HashSet<>(store.backupEntries()));
        }
    }

    @Test public void failedRestoreRollsBackHeapsAndEntriesTogether() {
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap heap = new JournalHeap(java.util.UUID.randomUUID().toString(), "Incoming", "", null);
            BackupArchive.Snapshot bad = new BackupArchive.Snapshot(List.of(heap), List.of(
                    new BackupArchive.Entry(1, 3, "Valid", "", "", heap.id()), new BackupArchive.Entry(2, 0, "Invalid", "", "")));
            assertThrows(android.database.SQLException.class, () -> store.restoreSnapshot(bad));
            assertTrue(store.heaps().isEmpty()); assertTrue(store.all().isEmpty());
        }
    }
}
