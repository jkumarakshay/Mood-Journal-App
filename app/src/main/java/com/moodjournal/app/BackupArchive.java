package com.moodjournal.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Versioned, authenticated backup; plaintext and passphrases are never written to disk. */
final class BackupArchive {
    private static final byte[] MAGIC = "DPAUSE01".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEAPS_MAGIC = "DPAUSE02".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_SIZE = 36, TAG_SIZE = 16, ITERATIONS = 600_000;
    static final int MAX_BYTES = 20 * 1024 * 1024;
    static final int MAX_ENTRIES = 50_000;
    record Entry(long createdAt, int mood, String event, String thoughts, String needs, String heapId) {
        Entry(long createdAt, int mood, String event, String thoughts, String needs) {
            this(createdAt, mood, event, thoughts, needs, null);
        }
    }
    record Snapshot(List<JournalHeap> heaps, List<Entry> entries) {}

    static boolean validPassphrase(char[] password) {
        if (password.length < 12 || password.length > 256) return false;
        for (char c : password) if (!Character.isWhitespace(c)) return true;
        return false;
    }

    static byte[] encrypt(List<Entry> entries, char[] password) throws IOException, GeneralSecurityException {
        return encrypt(new Snapshot(java.util.Collections.emptyList(), entries), password, false);
    }

    static byte[] encrypt(Snapshot snapshot, char[] password) throws IOException, GeneralSecurityException {
        return encrypt(snapshot, password, true);
    }

    private static byte[] encrypt(Snapshot snapshot, char[] password, boolean withHeaps) throws IOException, GeneralSecurityException {
        List<Entry> entries = snapshot.entries();
        if (!validPassphrase(password)) throw new IllegalArgumentException("Use a passphrase of 12 to 256 characters");
        if (entries.size() > MAX_ENTRIES) throw new IOException("Backup has too many entries");
        if (snapshot.heaps().size() > MAX_ENTRIES) throw new IOException("Backup has too many Heaps");
        java.util.Set<String> heapIds = new java.util.HashSet<>();
        for (JournalHeap heap : snapshot.heaps()) if (!heapIds.add(heap.id())) throw new IOException("Duplicate Heap ID");
        byte[] header = new byte[HEADER_SIZE];
        new SecureRandom().nextBytes(header);
        System.arraycopy(withHeaps ? HEAPS_MAGIC : MAGIC, 0, header, 0, MAGIC.length);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, password, header);
        try (WipingBuffer buffer = new WipingBuffer(); DataOutputStream out = new DataOutputStream(buffer)) {
            if (withHeaps) {
                out.writeInt(snapshot.heaps().size());
                for (JournalHeap heap : snapshot.heaps()) {
                    writeText(out, heap.id()); writeText(out, heap.name()); writeText(out, heap.category());
                    out.writeInt(heap.color() == null ? 0 : heap.color());
                    if (buffer.size() > MAX_BYTES - HEADER_SIZE - TAG_SIZE) throw new IOException("Backup exceeds 20 MB");
                }
            }
            out.writeInt(entries.size());
            for (Entry entry : entries) {
                validate(entry);
                if (entry.heapId() != null && (!withHeaps || !heapIds.contains(entry.heapId()))) throw new IOException("Unknown Heap");
                out.writeLong(entry.createdAt());
                out.writeByte(entry.mood());
                writeText(out, entry.event()); writeText(out, entry.thoughts()); writeText(out, entry.needs());
                if (withHeaps) writeText(out, entry.heapId() == null ? "" : entry.heapId());
                if (buffer.size() > MAX_BYTES - HEADER_SIZE - TAG_SIZE) throw new IOException("Backup exceeds 20 MB");
            }
            byte[] plain = buffer.toByteArray();
            try {
                byte[] encrypted = cipher.doFinal(plain);
                byte[] archive = Arrays.copyOf(header, header.length + encrypted.length);
                System.arraycopy(encrypted, 0, archive, header.length, encrypted.length);
                return archive;
            } finally { Arrays.fill(plain, (byte) 0); }
        }
    }

    static List<Entry> decrypt(byte[] archive, char[] password) throws IOException, GeneralSecurityException {
        return decryptSnapshot(archive, password).entries();
    }

    static Snapshot decryptSnapshot(byte[] archive, char[] password) throws IOException, GeneralSecurityException {
        if (!validPassphrase(password)) throw new IllegalArgumentException("Invalid passphrase length");
        if (archive.length < HEADER_SIZE + TAG_SIZE || archive.length > MAX_BYTES) throw new IOException("Invalid backup size");
        boolean withHeaps = Arrays.equals(Arrays.copyOf(archive, 8), HEAPS_MAGIC);
        if (!withHeaps && !Arrays.equals(Arrays.copyOf(archive, 8), MAGIC)) throw new IOException("Unsupported backup format");
        byte[] header = Arrays.copyOf(archive, HEADER_SIZE);
        byte[] plain = cipher(Cipher.DECRYPT_MODE, password, header).doFinal(archive, HEADER_SIZE, archive.length - HEADER_SIZE);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
            List<JournalHeap> heaps = new ArrayList<>();
            java.util.Set<String> heapIds = new java.util.HashSet<>();
            if (withHeaps) {
                int heapCount = in.readInt();
                if (heapCount < 0 || heapCount > MAX_ENTRIES || heapCount > in.available() / 16) throw new IOException("Invalid Heap count");
                for (int i = 0; i < heapCount; i++) {
                    String id = readText(in), name = readText(in), category = readText(in);
                    int color = in.readInt();
                    try { heaps.add(new JournalHeap(id, name, category, color == 0 ? null : color)); }
                    catch (IllegalArgumentException e) { throw new IOException("Invalid Heap", e); }
                    if (!heapIds.add(id)) throw new IOException("Duplicate Heap ID");
                }
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES || count > plain.length / 21) throw new IOException("Invalid entry count");
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                long createdAt = in.readLong(); int mood = in.readUnsignedByte();
                String event = readText(in), thoughts = readText(in), needs = readText(in);
                String heapId = withHeaps ? readText(in) : "";
                if (!heapId.isEmpty() && !heapIds.contains(heapId)) throw new IOException("Unknown Heap");
                Entry entry = new Entry(createdAt, mood, event, thoughts, needs, heapId.isEmpty() ? null : heapId);
                validate(entry);
                entries.add(entry);
            }
            if (in.read() != -1) throw new IOException("Unexpected backup data");
            return new Snapshot(heaps, entries);
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    static byte[] readBounded(InputStream stream) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                if (out.size() + count > MAX_BYTES) throw new IOException("Backup exceeds 20 MB");
                out.write(chunk, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static Cipher cipher(int mode, char[] password, byte[] header) throws GeneralSecurityException {
        byte[] salt = Arrays.copyOfRange(header, 8, 24), nonce = Arrays.copyOfRange(header, 24, 36);
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        byte[] key = null;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header);
            return cipher;
        } finally { spec.clearPassword(); if (key != null) Arrays.fill(key, (byte) 0); }
    }

    private static void validate(Entry entry) throws IOException {
        if (entry.createdAt() < 0 || entry.mood() < 1 || entry.mood() > 5) throw new IOException("Invalid journal entry");
        for (String text : new String[]{entry.event(), entry.thoughts(), entry.needs()})
            if (text == null || text.length() > 20_000) throw new IOException("Invalid journal text");
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
        out.writeInt(encoded.remaining());
        while (encoded.hasRemaining()) out.writeByte(encoded.get());
    }

    private static String readText(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 80_000 || size > in.available()) throw new IOException("Invalid text length");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    private static final class WipingBuffer extends ByteArrayOutputStream {
        @Override public void close() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
