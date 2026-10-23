package com.moodjournal.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

/** Robolectric has no PdfDocument native backend. Only that platform boundary is substituted;
 * the production StaticLayout/Canvas pagination runs with real Android native graphics. */
@Implements(PdfDocument.class)
public class RecordingPdfDocument {
    static final List<RecordingPdfDocument> documents = Collections.synchronizedList(new ArrayList<>());
    final List<PdfDocument.PageInfo> pages = new ArrayList<>();
    final List<Bitmap> images = new ArrayList<>();
    boolean closed, written;
    private PdfDocument.Page current;

    @Implementation protected void __constructor__() { documents.add(this); }
    @Implementation protected PdfDocument.Page startPage(PdfDocument.PageInfo info) {
        if (closed || current != null) throw new IllegalStateException("Invalid page lifecycle");
        Bitmap bitmap = Bitmap.createBitmap(info.getPageWidth(), info.getPageHeight(), Bitmap.Config.ARGB_8888);
        current = ReflectionHelpers.newInstance(PdfDocument.Page.class);
        ReflectionHelpers.setField(current, "mCanvas", new Canvas(bitmap));
        ReflectionHelpers.setField(current, "mPageInfo", info);
        pages.add(info); images.add(bitmap); return current;
    }
    @Implementation protected void finishPage(PdfDocument.Page page) {
        if (closed || current != page) throw new IllegalStateException("Invalid page lifecycle");
        current = null;
    }
    @Implementation protected void writeTo(OutputStream output) throws IOException {
        if (closed || current != null) throw new IllegalStateException("Unfinished document");
        // Deliberately not a PDF: tests distinguish platform output from application behavior.
        output.write(payload(pages.size())); written = true;
    }
    @Implementation protected List<PdfDocument.PageInfo> getPages() { return Collections.unmodifiableList(pages); }
    @Implementation protected void close() {
        if (current != null) throw new IllegalStateException("Unfinished page");
        closed = true;
    }
    static byte[] payload(int pages) { return ("TEST_PLATFORM_PDF_OUTPUT;PAGES=" + pages).getBytes(StandardCharsets.UTF_8); }
    static RecordingPdfDocument last() { return documents.get(documents.size() - 1); }
}
