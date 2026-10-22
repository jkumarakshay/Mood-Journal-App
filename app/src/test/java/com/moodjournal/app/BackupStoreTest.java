package com.moodjournal.app;

import static org.junit.Assert.*;
import android.content.Context;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BackupStoreTest {
    private Context context;
    @Before public void reset() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db"); }

    @Test public void restoreMergesWithoutDeletingExistingEntriesOrChangingDraftAndPin() {
        context.getSharedPreferences("draft", 0).edit().putString("event", "unfinished").commit();
        context.getSharedPreferences("journal_lock", 0).edit().putString("hash", "existing verifier").commit();
        try (JournalStore store = new JournalStore(context)) {
            store.add(4, "Existing local entry", "", "");
            BackupArchive.Entry incoming = new BackupArchive.Entry(1234, 2, "Restored entry 🌿", "Feelings", "Rest");
            assertEquals(1, store.restoreEntries(Collections.singletonList(incoming)));
            assertEquals(2, store.all().size());
            assertTrue(store.backupEntries().contains(incoming));
            assertEquals("Existing local entry", store.all().get(0).event());
        }
        assertEquals("unfinished", context.getSharedPreferences("draft", 0).getString("event", ""));
        assertEquals("existing verifier", context.getSharedPreferences("journal_lock", 0).getString("hash", ""));
    }

    @Test public void restoringSameBackupTwiceSkipsExactDuplicates() {
        try (JournalStore store = new JournalStore(context)) {
            BackupArchive.Entry one = new BackupArchive.Entry(1000, 3, "One", "", "");
            BackupArchive.Entry two = new BackupArchive.Entry(1000, 3, "Two", "", "");
            List<BackupArchive.Entry> entries = Arrays.asList(one, one, two);
            assertEquals(2, store.restoreEntries(entries));
            assertEquals(0, store.restoreEntries(entries));
            assertEquals(2, store.all().size());
        }
    }

    @Test public void restoreRollsBackAllNewEntriesIfAnyInsertFails() {
        try (JournalStore store = new JournalStore(context)) {
            store.add(5, "Original", "", "");
            List<BackupArchive.Entry> entries = Arrays.asList(new BackupArchive.Entry(1, 3, "Valid", "", ""),
                    new BackupArchive.Entry(2, 0, "Invalid mood", "", ""));
            assertThrows(android.database.SQLException.class, () -> store.restoreEntries(entries));
            assertEquals(1, store.all().size());
            assertEquals("Original", store.all().get(0).event());
        }
    }
}
