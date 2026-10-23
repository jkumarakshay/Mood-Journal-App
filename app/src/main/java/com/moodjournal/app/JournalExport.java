package com.moodjournal.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Print-friendly, searchable PDFs. No plaintext export files are cached by the app. */
final class JournalExport {
    private static final String[] MOODS = {"", "Low", "Down", "Okay", "Good", "Great"};

    static boolean hasWriting(JournalStore.Entry entry) {
        return !entry.event().trim().isEmpty() || !entry.thoughts().trim().isEmpty() || !entry.needs().trim().isEmpty();
    }

    static String entryFilename(JournalStore.Entry entry) {
        return "Journal-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(entry.createdAt()))
                + "-" + entry.id() + ".pdf";
    }

    static String heapFilename(JournalHeap heap, boolean zip) {
        String name = heap.name().replaceAll("[^\\p{L}\\p{N} _-]", "").trim().replaceAll("\\s+", "-");
        if (name.isEmpty()) name = "Heap";
        return "Daily-Pause-" + name.substring(0, Math.min(60, name.length())) + (zip ? ".zip" : ".pdf");
    }

    static void writeZip(OutputStream output, List<JournalStore.Entry> entries, JournalHeap heap) throws IOException {
        if (entries.stream().noneMatch(JournalExport::hasWriting)) throw new IOException("Nothing to export");
        try (ZipOutputStream zip = new ZipOutputStream(new java.io.FilterOutputStream(output) {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); }
            @Override public void close() throws IOException { flush(); }
        })) {
            for (JournalStore.Entry entry : entries) {
                if (!hasWriting(entry)) continue;
                zip.putNextEntry(new ZipEntry(entryFilename(entry)));
                writePdf(zip, java.util.Collections.singletonList(entry), heap);
                zip.closeEntry();
            }
        }
    }

    static void writePdf(OutputStream output, List<JournalStore.Entry> entries, JournalHeap heap) throws IOException {
        if (entries.stream().noneMatch(JournalExport::hasWriting)) throw new IOException("Nothing to export");
        PdfDocument document = new PdfDocument();
        try {
            Pages pages = new Pages(document, heap);
            try {
                for (JournalStore.Entry entry : entries) {
                    if (!hasWriting(entry)) continue;
                    pages.start(); // Each journal, even after a long one, starts on a fresh page.
                    pages.text("A moment of reflection", 27, true, true, 0xFF312B35);
                    pages.gap(12);
                    pages.text(new SimpleDateFormat("EEEE, MMMM d, yyyy  ·  h:mm a", Locale.getDefault())
                            .format(new Date(entry.createdAt())), 12, false, false, 0xFF6D6470);
                    pages.gap(10);
                    pages.text("Feeling " + MOODS[entry.mood()].toLowerCase(Locale.ROOT), 14, true, false, 0xFF705180);
                    pages.gap(12);
                    pages.text(heap == null ? "Outside Heaps" : "Heap: " + heap.name(), 12, true, false, 0xFF312B35);
                    if (heap != null) {
                        pages.gap(4);
                        pages.text("Category: " + (heap.category().isEmpty() ? "Uncategorized" : heap.category()), 11, false, false, 0xFF6D6470);
                    }
                    pages.gap(12);
                    pages.section("What was on your mind", entry.event());
                    pages.section("Thoughts & feelings", entry.thoughts());
                    pages.section("What you needed", entry.needs());
                }
            } finally { pages.finish(); }
            document.writeTo(output);
        } finally { document.close(); }
    }

    private static final class Pages {
        private static final int WIDTH = 595, HEIGHT = 842, LEFT = 48, TEXT_WIDTH = 499, BOTTOM = 778;
        private final PdfDocument document;
        private final JournalHeap heap;
        private PdfDocument.Page page;
        private Canvas canvas;
        private int pageNumber, y;

        Pages(PdfDocument document, JournalHeap heap) { this.document = document; this.heap = heap; }

        void start() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Export interrupted");
            finish();
            page = document.startPage(new PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, ++pageNumber).create());
            canvas = page.getCanvas(); canvas.drawColor(Color.WHITE);
            Paint accent = new Paint(); accent.setColor(heap == null ? 0xFFEDE6F1 : heap.background());
            canvas.drawRect(LEFT, 42, WIDTH - LEFT, 48, accent);
            TextPaint brand = paint(10, true, false, 0xFF705180);
            canvas.drawText("DAILY PAUSE  /  YOUR WORDS, YOUR JOURNEY", LEFT, 68, brand);
            TextPaint footer = paint(9, false, false, 0xFF6D6470);
            canvas.drawText("Daily Pause · Journal export", LEFT, 810, footer);
            String number = Integer.toString(pageNumber);
            canvas.drawText(number, WIDTH - LEFT - footer.measureText(number), 810, footer);
            y = 94;
        }

        void finish() {
            if (page != null) { document.finishPage(page); page = null; }
        }

        void gap(int amount) { y += amount; }

        void section(String title, String value) throws IOException {
            if (value.trim().isEmpty()) return;
            // Keep the section label with at least the first two lines of its answer.
            if (y + 90 > BOTTOM) start();
            gap(18); text(title, 12, true, false, 0xFF705180);
            gap(7); text(value, 14, false, false, 0xFF312B35);
        }

        void text(String value, int size, boolean bold, boolean serif, int color) throws IOException {
            StaticLayout layout = StaticLayout.Builder.obtain(value, 0, value.length(), paint(size, bold, serif, color), TEXT_WIDTH)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(4, 1).build();
            int first = 0;
            while (first < layout.getLineCount()) {
                int top = layout.getLineTop(first), last = first;
                while (last < layout.getLineCount() && y + layout.getLineBottom(last) - top <= BOTTOM) last++;
                if (last == first) { start(); continue; }
                int height = layout.getLineBottom(last - 1) - top;
                canvas.save(); canvas.clipRect(LEFT, y, LEFT + TEXT_WIDTH, y + height);
                canvas.translate(LEFT, y - top); layout.draw(canvas); canvas.restore();
                y += height; first = last;
                if (first < layout.getLineCount()) start();
            }
        }

        private TextPaint paint(int size, boolean bold, boolean serif, int color) {
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG); paint.setTextSize(size); paint.setColor(color);
            paint.setTypeface(Typeface.create(serif ? "serif" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            return paint;
        }
    }
}
