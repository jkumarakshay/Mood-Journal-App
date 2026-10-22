package com.moodjournal.app;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.crypto.AEADBadTagException;
import org.junit.Test;

public class BackupArchiveTest {
    private static final char[] PASSWORD = "a long backup passphrase 🌿".toCharArray();
    private final List<BackupArchive.Entry> entries = Arrays.asList(
            new BackupArchive.Entry(1_792_605_000_000L, 4, "Private reflection 🌿", "I felt heard.\n我很安心", "More time outside"),
            new BackupArchive.Entry(1_792_600_000_000L, 1, "", "", ""));

    @Test public void encryptedArchiveRoundTripsAllJournalFieldsAndUnicode() throws Exception {
        byte[] archive = BackupArchive.encrypt(entries, PASSWORD);
        assertEquals(entries, BackupArchive.decrypt(archive, PASSWORD));
        String visible = new String(archive, StandardCharsets.ISO_8859_1);
        assertFalse(visible.contains("Private reflection"));
        assertFalse(visible.contains("I felt heard"));
        assertFalse(visible.contains("a long backup passphrase"));
    }

    @Test public void encryptionUsesFreshSaltAndNonceForEveryBackup() throws Exception {
        byte[] a = BackupArchive.encrypt(entries, PASSWORD), b = BackupArchive.encrypt(entries, PASSWORD);
        assertFalse(Arrays.equals(a, b));
        assertFalse(Arrays.equals(Arrays.copyOfRange(a, 8, 24), Arrays.copyOfRange(b, 8, 24)));
        assertFalse(Arrays.equals(Arrays.copyOfRange(a, 24, 36), Arrays.copyOfRange(b, 24, 36)));
        assertEquals(entries, BackupArchive.decrypt(b, PASSWORD));
    }

    @Test public void wrongPassphraseCannotDecryptAnArchive() throws Exception {
        byte[] archive = BackupArchive.encrypt(entries, PASSWORD);
        assertThrows(AEADBadTagException.class, () -> BackupArchive.decrypt(archive, "the wrong passphrase".toCharArray()));
    }

    @Test public void ciphertextTamperingIsDetected() throws Exception {
        byte[] archive = BackupArchive.encrypt(entries, PASSWORD);
        archive[archive.length - 1] ^= 1;
        assertThrows(AEADBadTagException.class, () -> BackupArchive.decrypt(archive, PASSWORD));
    }

    @Test public void headerTamperingCannotChangeTheEncryptionParameters() throws Exception {
        byte[] archive = BackupArchive.encrypt(entries, PASSWORD);
        archive[15] ^= 1;
        assertThrows(AEADBadTagException.class, () -> BackupArchive.decrypt(archive, PASSWORD));
        archive[0] ^= 1;
        assertThrows(IOException.class, () -> BackupArchive.decrypt(archive, PASSWORD));
    }

    @Test public void emptyArchiveAndMissingReflectionFieldsAreValid() throws Exception {
        assertTrue(BackupArchive.decrypt(BackupArchive.encrypt(Collections.emptyList(), PASSWORD), PASSWORD).isEmpty());
    }

    @Test public void malformedAndOversizedInputIsRejected() {
        assertThrows(IOException.class, () -> BackupArchive.decrypt(new byte[10], PASSWORD));
        assertThrows(IOException.class, () -> BackupArchive.decrypt(new byte[BackupArchive.MAX_BYTES + 1], PASSWORD));
        assertThrows(IOException.class, () -> BackupArchive.readBounded(new ByteArrayInputStream(new byte[BackupArchive.MAX_BYTES + 1])));
    }

    @Test public void appPinAndBlankPassphrasesCannotBeUsedForBackups() {
        assertFalse(BackupArchive.validPassphrase("642081".toCharArray()));
        assertFalse(BackupArchive.validPassphrase("                 ".toCharArray()));
        assertFalse(BackupArchive.validPassphrase(new char[257]));
        assertThrows(IllegalArgumentException.class, () -> BackupArchive.encrypt(entries, "short".toCharArray()));
    }

    @Test public void invalidJournalEntriesAreRejectedBeforeExport() {
        assertThrows(IOException.class, () -> BackupArchive.encrypt(Collections.singletonList(new BackupArchive.Entry(1, 0, "", "", "")), PASSWORD));
        assertThrows(IOException.class, () -> BackupArchive.encrypt(Collections.singletonList(new BackupArchive.Entry(1, 1, null, "", "")), PASSWORD));
    }

    @Test public void versionTwoEncryptsHeapMetadataAndVersionOneStillRestores() throws Exception {
        JournalHeap heap = new JournalHeap(java.util.UUID.randomUUID().toString(), "Private work concerns", "Career", JournalHeap.COLORS[3]);
        BackupArchive.Snapshot snapshot = new BackupArchive.Snapshot(List.of(heap), List.of(
                new BackupArchive.Entry(1234, 3, "Thoughts", "", "", heap.id()), entries.get(0)));
        byte[] archive = BackupArchive.encrypt(snapshot, PASSWORD);
        assertEquals("DPAUSE02", new String(archive, 0, 8, StandardCharsets.US_ASCII));
        assertEquals(snapshot, BackupArchive.decryptSnapshot(archive, PASSWORD));
        assertFalse(new String(archive, StandardCharsets.ISO_8859_1).contains(heap.name()));
        BackupArchive.Snapshot legacy = BackupArchive.decryptSnapshot(BackupArchive.encrypt(entries, PASSWORD), PASSWORD);
        assertTrue(legacy.heaps().isEmpty()); assertEquals(entries, legacy.entries());
    }

    @Test public void versionTwoRejectsMissingAndDuplicateHeapsAndDetectsTampering() throws Exception {
        JournalHeap heap = new JournalHeap(java.util.UUID.randomUUID().toString(), "Work", "", null);
        BackupArchive.Entry entry = new BackupArchive.Entry(1234, 3, "", "", "", heap.id());
        assertThrows(IOException.class, () -> BackupArchive.encrypt(new BackupArchive.Snapshot(List.of(), List.of(entry)), PASSWORD));
        assertThrows(IOException.class, () -> BackupArchive.encrypt(new BackupArchive.Snapshot(List.of(heap, heap), List.of()), PASSWORD));
        byte[] archive = BackupArchive.encrypt(new BackupArchive.Snapshot(List.of(heap), List.of(entry)), PASSWORD);
        archive[archive.length - 1] ^= 1;
        assertThrows(AEADBadTagException.class, () -> BackupArchive.decryptSnapshot(archive, PASSWORD));
    }
}
