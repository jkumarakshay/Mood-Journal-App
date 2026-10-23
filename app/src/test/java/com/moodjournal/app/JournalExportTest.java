package com.moodjournal.app;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = RecordingPdfDocument.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class JournalExportTest {
    @Before public void resetPdfBoundary() { RecordingPdfDocument.documents.clear(); }
    private final JournalHeap heap = new JournalHeap("00000000-0000-0000-0000-000000000001", "Work & life", "Reflection", JournalHeap.COLORS[2]);
    private JournalStore.Entry entry(long id, String text) { return new JournalStore.Entry(id, 1792713600000L, 4, text, "Feeling hopeful.", "Some rest.", heap.id()); }
    private JournalStore.Entry empty() { return new JournalStore.Entry(10, 1792713600000L, 3, " \n", "", "\t", heap.id()); }

    @Test public void singleJournalRendersOneA4PageAndCompletesTheDocument() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        JournalExport.writePdf(output, List.of(entry(1, "A small win today.")), heap);
        assertArrayEquals(RecordingPdfDocument.payload(1), output.toByteArray());
        RecordingPdfDocument document = RecordingPdfDocument.last();
        assertEquals(1, document.pages.size()); assertTrue(document.closed); assertTrue(document.written);
        assertEquals(595, document.pages.get(0).getPageWidth()); assertEquals(842, document.pages.get(0).getPageHeight());
        int ink = 0;
        for (int y = 90; y < 600; y++) for (int x = 48; x < 547; x++)
            if (document.images.get(0).getPixel(x, y) != android.graphics.Color.WHITE) ink++;
        assertTrue("Journal content must actually be drawn", ink > 1000);
    }

    @Test public void longAnswersWrapAcrossPagesAndNextJournalGetsAFreshPage() throws Exception {
        String longText = "A thought to give a little space. ".repeat(620) + "\nThe final thought.";
        JournalStore.Entry first = new JournalStore.Entry(1, 1, 3, longText, "", "", heap.id());
        ByteArrayOutputStream one = new ByteArrayOutputStream(); JournalExport.writePdf(one, List.of(first), heap);
        int longPages = RecordingPdfDocument.last().pages.size(); assertTrue(longPages > 3);
        ByteArrayOutputStream collated = new ByteArrayOutputStream();
        JournalExport.writePdf(collated, List.of(first, empty(), entry(2, "A fresh journal.")), heap);
        assertEquals(longPages + 1, RecordingPdfDocument.last().pages.size());
        assertArrayEquals(RecordingPdfDocument.payload(longPages + 1), collated.toByteArray());
    }

    @Test public void twoShortJournalsStartOnSeparatePagesAndBlankEntriesAreExcluded() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        JournalExport.writePdf(output, List.of(entry(1, "One."), empty(), entry(2, "Two.")), null);
        assertEquals(2, RecordingPdfDocument.last().pages.size());
    }

    @Test public void zipContainsOneCompletePdfPerNonemptyJournalWithUniqueSafeNames() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        JournalExport.writeZip(output, Arrays.asList(entry(1, "One."), entry(2, "Two."), empty()), heap);
        HashSet<String> names = new HashSet<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry file;
            while ((file = input.getNextEntry()) != null) {
                assertTrue(names.add(file.getName())); assertFalse(file.getName().contains("/"));
                assertTrue(file.getName().endsWith(".pdf"));
                ByteArrayOutputStream pdf = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) pdf.write(buffer, 0, count);
                assertArrayEquals(RecordingPdfDocument.payload(1), pdf.toByteArray());
            }
        }
        assertEquals(2, names.size());
    }

    @Test public void emptyContentIsNotExportedAndHeapFilenamesCannotCreatePaths() throws Exception {
        assertFalse(JournalExport.hasWriting(empty()));
        assertThrows(IOException.class, () -> JournalExport.writePdf(new ByteArrayOutputStream(), List.of(empty()), heap));
        assertThrows(IOException.class, () -> JournalExport.writeZip(new ByteArrayOutputStream(), List.of(), heap));
        JournalHeap unsafe = new JournalHeap(heap.id(), "../../folder\\name\n", "", null);
        String name = JournalExport.heapFilename(unsafe, true);
        assertFalse(name.contains("/")); assertFalse(name.contains("\\")); assertFalse(name.contains("..")); assertFalse(name.contains("\n"));
        assertTrue(name.endsWith(".zip"));
    }

    @Test public void providerWriteErrorsPropagateInsteadOfReportingSuccess() {
        OutputStream broken = new OutputStream() { @Override public void write(int b) throws IOException { throw new IOException("Full disk"); } };
        assertThrows(IOException.class, () -> JournalExport.writePdf(broken, List.of(entry(1, "One.")), heap));
    }

    @Test public void weatherAnswerOnlyEntriesAreExportableAndBothUnitsRenderWithoutChangingSnapshot() throws Exception {
        WeatherSnapshot weather = new WeatherSnapshot("2026-10-23", 28, 10, "Sunny", WeatherClient.ATTRIBUTION, 1, 0);
        JournalStore.Entry reflection = new JournalStore.Entry(1, 1, 4, "", "", "", heap.id(), 2, weather);
        assertTrue(JournalExport.hasWriting(reflection));
        ByteArrayOutputStream celsius = new ByteArrayOutputStream(); JournalExport.writePdf(celsius, List.of(reflection), heap, false);
        android.graphics.Bitmap first = RecordingPdfDocument.last().images.get(0);
        ByteArrayOutputStream fahrenheit = new ByteArrayOutputStream(); JournalExport.writePdf(fahrenheit, List.of(reflection), heap, true);
        android.graphics.Bitmap second = RecordingPdfDocument.last().images.get(0);
        assertFalse("Converted temperatures must change rendered output", first.sameAs(second));
        assertEquals(28, weather.highC(), 0); assertEquals(10, weather.lowC(), 0);
        ByteArrayOutputStream zip = new ByteArrayOutputStream(); JournalExport.writeZip(zip, List.of(reflection), heap, true);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip.toByteArray()))) {
            assertNotNull(input.getNextEntry()); assertTrue(input.getNextEntry() == null);
        }
    }

}
