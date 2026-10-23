package com.moodjournal.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.FragmentActivity;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A save action is authorized before the picker opens; returning from it keeps the journal locked. */
final class ExportController {
    private record Request(long entryId, String heapId, boolean zip) {}
    private final FragmentActivity activity;
    private final Context context;
    private final BooleanSupplier authorized;
    private final Consumer<AlertDialog> showDialog;
    private final Runnable refresh;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> pdfPicker, zipPicker;
    private Request pending;
    private PopupMenu menu;
    private boolean busy, closed;
    private int status;

    ExportController(FragmentActivity activity, Bundle state, BooleanSupplier authorized,
                     Consumer<AlertDialog> showDialog, Runnable refresh) {
        this.activity = activity; context = activity.getApplicationContext();
        this.authorized = authorized; this.showDialog = showDialog; this.refresh = refresh;
        if (state != null && state.getBoolean("export_picker_pending")) {
            long entry = state.getLong("export_entry", -1);
            String heap = state.getString("export_heap");
            if (entry > 0 || heap != null && heap.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                pending = new Request(entry, heap, state.getBoolean("export_zip"));
        }
        pdfPicker = activity.registerForActivityResult(new ActivityResultContracts.CreateDocument("application/pdf"), this::saveSelected);
        zipPicker = activity.registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), this::saveSelected);
    }

    ImageButton entryMenu(JournalStore.Entry entry, JournalHeap heap) {
        return options(R.string.journal_more, JournalExport.hasWriting(entry), () ->
                choose(new Request(entry.id(), null, false), JournalExport.entryFilename(entry)));
    }

    ImageButton heapMenu(JournalHeap heap, List<JournalStore.Entry> entries) {
        boolean available = entries.stream().anyMatch(e -> heap.id().equals(e.heapId()) && JournalExport.hasWriting(e));
        return options(R.string.heap_more, available, () -> showDialog.accept(new AlertDialog.Builder(activity)
                .setTitle(R.string.export_heap_title)
                .setItems(new String[]{activity.getString(R.string.export_zip), activity.getString(R.string.export_collated)}, (dialog, which) -> {
                    if (!authorized.getAsBoolean()) return;
                    choose(new Request(-1, heap.id(), which == 0), JournalExport.heapFilename(heap, which == 0));
                }).setNegativeButton(android.R.string.cancel, null).create()));
    }

    private ImageButton options(int description, boolean available, Runnable action) {
        ImageButton more = new ImageButton(activity); more.setId(R.id.export_more);
        more.setContentDescription(activity.getString(description)); more.setImageResource(R.drawable.ic_more_vert);
        more.setImageTintList(ColorStateList.valueOf(Appearance.color(activity, 0xFF705180)));
        more.setBackgroundResource(android.R.drawable.list_selector_background);
        more.setOnClickListener(v -> {
            if (!authorized.getAsBoolean()) return;
            onPause();
            menu = new PopupMenu(activity, more, Gravity.END);
            menu.getMenu().add(0, R.id.export_action, 0, R.string.export_action).setEnabled(available && !busy && pending == null);
            menu.setOnMenuItemClickListener(item -> {
                if (!authorized.getAsBoolean() || !available || busy || pending != null) return false;
                action.run(); return true;
            });
            menu.setOnDismissListener(popup -> { if (menu == popup) menu = null; });
            menu.show();
        });
        return more;
    }

    private void choose(Request request, String filename) {
        if (!authorized.getAsBoolean() || busy || pending != null) return;
        pending = request;
        try { (request.zip() ? zipPicker : pdfPicker).launch(filename); }
        catch (ActivityNotFoundException e) { pending = null; finish(R.string.export_no_picker); }
    }

    void addStatus(LinearLayout page) {
        if (!authorized.getAsBoolean()) return;
        TextView note = new TextView(activity); note.setText(R.string.export_readable_note); note.setTextSize(12);
        note.setTextColor(Appearance.color(activity, 0xFF6D6470));
        note.setPadding(0, dp(22), 0, dp(8)); page.addView(note);
        TextView message = new TextView(activity); message.setId(R.id.export_status); message.setTextSize(14);
        message.setTextColor(Appearance.color(activity, 0xFF312B35));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (busy) message.setText(R.string.export_saving); else if (status != 0) message.setText(status);
        page.addView(message);
    }

    private void saveSelected(Uri uri) {
        Request request = pending; pending = null;
        if (request == null || closed) return;
        if (uri == null) { finish(R.string.export_cancelled); return; }
        if (!"content".equals(uri.getScheme())) { finish(R.string.export_failed); return; }
        busy = true; refreshIfAuthorized();
        boolean fahrenheit = WeatherReflection.fahrenheit(activity);
        worker.execute(() -> {
            int result = R.string.export_saved;
            try (JournalStore store = new JournalStore(context)) {
                List<JournalStore.Entry> entries = new ArrayList<>();
                JournalHeap heap = null;
                if (request.heapId() != null) {
                    for (JournalHeap h : store.heaps()) if (request.heapId().equals(h.id())) heap = h;
                    if (heap == null) throw new IOException("Heap no longer exists");
                    for (JournalStore.Entry e : store.all()) if (request.heapId().equals(e.heapId()) && JournalExport.hasWriting(e)) entries.add(e);
                    entries.sort(Comparator.comparingLong(JournalStore.Entry::createdAt).thenComparingLong(JournalStore.Entry::id));
                } else {
                    for (JournalStore.Entry e : store.all()) if (request.entryId() == e.id() && JournalExport.hasWriting(e)) {
                        entries.add(e);
                        for (JournalHeap h : store.heaps()) if (h.id().equals(e.heapId())) heap = h;
                    }
                }
                if (entries.isEmpty()) throw new IOException("Nothing to export");
                try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("No output stream");
                    if (request.zip()) JournalExport.writeZip(output, entries, heap, fahrenheit);
                    else JournalExport.writePdf(output, entries, heap, fahrenheit);
                    output.flush();
                }
            } catch (Exception e) {
                try { DocumentsContract.deleteDocument(context.getContentResolver(), uri); } catch (Exception ignored) { /* Provider may require manual removal. */ }
                result = R.string.export_failed;
            }
            int message = result;
            main.post(() -> { if (!closed) { busy = false; finish(message); } });
        });
    }

    private void finish(int message) { status = message; refreshIfAuthorized(); }
    private void refreshIfAuthorized() { if (!closed && authorized.getAsBoolean()) refresh.run(); }
    void onPause() { if (menu != null) { menu.dismiss(); menu = null; } }
    void saveState(Bundle state) {
        if (pending == null) return;
        state.putBoolean("export_picker_pending", true); state.putLong("export_entry", pending.entryId());
        state.putString("export_heap", pending.heapId()); state.putBoolean("export_zip", pending.zip());
    }
    void close() { onPause(); closed = true; worker.shutdown(); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
