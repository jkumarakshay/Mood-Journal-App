package com.moodjournal.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.fragment.app.FragmentActivity;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** All folder names, filters and the journal composer remain behind the journal gate. */
final class JournalScreen {
    private static final int INK = 0xFF312B35, MUTED = 0xFF6D6470, PURPLE = 0xFF705180;
    private static final String[] MOODS = {"Choose a mood", "☁  Low", "☂  Down", "◒  Okay", "❀  Good", "☀  Great"};
    private final FragmentActivity activity;
    private final JournalStore store;
    private final BooleanSupplier authorized;
    private final Consumer<AlertDialog> showDialog;
    private final Consumer<JournalStore.Entry> openEntry;
    private final Runnable refresh;
    private String activeHeap, category;
    private boolean outsideOnly;

    JournalScreen(FragmentActivity activity, JournalStore store, Bundle state, BooleanSupplier authorized,
                  Consumer<AlertDialog> showDialog, Consumer<JournalStore.Entry> openEntry, Runnable refresh) {
        this.activity = activity; this.store = store; this.authorized = authorized;
        this.showDialog = showDialog; this.openEntry = openEntry; this.refresh = refresh;
        if (state != null) {
            activeHeap = state.getString("journal_heap"); category = state.getString("journal_category");
            outsideOnly = state.getBoolean("journal_outside");
        }
    }

    void saveState(Bundle state) {
        state.putString("journal_heap", activeHeap); state.putString("journal_category", category);
        state.putBoolean("journal_outside", outsideOnly);
    }

    void openDestination(String heapId) {
        activeHeap = heapId; category = null; outsideOnly = false;
    }

    void show(LinearLayout page, List<JournalStore.Entry> entries) {
        if (!authorized.getAsBoolean()) return;
        List<JournalHeap> heaps = store.heaps();
        Map<String, JournalHeap> byId = new HashMap<>();
        for (JournalHeap heap : heaps) byId.put(heap.id(), heap);
        if (activeHeap != null && !byId.containsKey(activeHeap)) activeHeap = null;
        Button plus = button("+", R.id.journal_add);
        plus.setContentDescription("Add a journal entry or a Heap");
        plus.setOnClickListener(v -> addMenu());
        add(page, plus, 20);

        if (activeHeap != null) {
            JournalHeap heap = byId.get(activeHeap);
            Button back = button("← All Heaps & journals", R.id.heap_back);
            back.setOnClickListener(v -> { activeHeap = null; category = null; outsideOnly = false; refresh.run(); });
            add(page, back, 12);
            LinearLayout title = card(heap.background());
            add(title, text(heap.name(), 24, true), 0);
            add(title, text(heap.category().isEmpty() ? "Uncategorized" : heap.category(), 14, false), 8);
            add(page, title, 16);
        } else {
            TreeSet<String> categories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (JournalHeap heap : heaps) if (!heap.category().isEmpty()) categories.add(heap.category());
            List<String> values = new ArrayList<>(); values.add(null); values.add(""); values.addAll(categories);
            List<String> labels = new ArrayList<>(); labels.add("All categories"); labels.add("Uncategorized");
            for (String value : categories) labels.add("Category: " + value);
            int selected = 0;
            for (int i = 1; i < values.size(); i++) if (values.get(i).equalsIgnoreCase(category)) selected = i;
            category = values.get(selected);
            add(page, text("Filter by category", 14, true), 16);
            Spinner filter = spinner(labels, R.id.journal_category);
            filter.setContentDescription("Filter journals across all Heaps by category");
            filter.setSelection(selected);
            filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    String next = values.get(position);
                    if (java.util.Objects.equals(category, next) || !authorized.getAsBoolean()) return;
                    category = next; outsideOnly = false; refresh.run();
                }
            });
            add(page, filter, 4);
            if (!outsideOnly) {
                add(page, text("Heaps", 23, true), 20);
                int shown = 0;
                for (JournalHeap heap : heaps) {
                    if (!matchesCategory(heap, category)) continue;
                    shown++;
                    long count = entries.stream().filter(e -> heap.id().equals(e.heapId())).count();
                    Button folder = button("▣  " + heap.name() + "\n" + (heap.category().isEmpty() ? "Uncategorized" : heap.category())
                            + " · " + count + (count == 1 ? " entry" : " entries"), View.NO_ID);
                    folder.setBackground(shape(heap.background()));
                    folder.setContentDescription("Open Heap " + heap.name() + ", " + count + " entries");
                    folder.setTag(heap.id());
                    folder.setOnClickListener(v -> { activeHeap = heap.id(); refresh.run(); });
                    add(page, folder, 10);
                }
                if (shown == 0) add(page, text(heaps.isEmpty() ? "Use + to give a topic or concern its own space." : "No Heaps in this category.", 14, false), 10);
            }
            Button location = button(outsideOnly ? "Show all journals" : "Show journals outside Heaps", R.id.journal_location);
            location.setOnClickListener(v -> { outsideOnly = !outsideOnly; refresh.run(); });
            add(page, location, 18);
        }

        add(page, text(activeHeap != null ? "Journals in this Heap" : outsideOnly ? "Outside Heaps" : "All journals", 23, true), 22);
        int shown = 0;
        for (JournalStore.Entry entry : entries) {
            JournalHeap heap = byId.get(entry.heapId());
            if (activeHeap != null ? !activeHeap.equals(entry.heapId())
                    : (outsideOnly && entry.heapId() != null) || !matchesCategory(heap, category)) continue;
            shown++;
            LinearLayout card = card(heap == null ? Color.WHITE : heap.background());
            card.setTag(entry.id());
            add(card, text(heap == null ? "Outside Heaps" : "Heap: " + heap.name(), 14, true), 0);
            add(card, text(new SimpleDateFormat("MMM d, yyyy  ·  h:mm a", Locale.getDefault()).format(new Date(entry.createdAt())), 12, false), 8);
            add(card, text(MOODS[entry.mood()], 21, true), 12);
            String excerpt = !entry.event().isEmpty() ? entry.event() : !entry.thoughts().isEmpty() ? entry.thoughts() : entry.needs();
            TextView preview = text(excerpt.isEmpty() ? "A quiet check-in. Sometimes naming a feeling is enough." : excerpt, 15, false);
            preview.setMaxLines(3); preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            add(card, preview, 10);
            Button read = button("Read this entry  →", View.NO_ID);
            read.setContentDescription("Read " + MOODS[entry.mood()] + " entry from " + new Date(entry.createdAt()));
            read.setOnClickListener(v -> { if (authorized.getAsBoolean()) openEntry.accept(entry); });
            add(card, read, 14); add(page, card, 16);
        }
        if (shown == 0) add(page, text("No journals here yet. Use + to add a journal entry.", 15, false), 14);
    }

    private static boolean matchesCategory(JournalHeap heap, String category) {
        return category == null || (heap == null ? "" : heap.category()).equalsIgnoreCase(category);
    }

    private void addMenu() {
        if (!authorized.getAsBoolean()) return;
        showDialog.accept(new AlertDialog.Builder(activity).setTitle("Add to My Journal")
                .setItems(new String[]{"Journal entry", "Heap"}, (d, which) -> {
                    if (!authorized.getAsBoolean()) return;
                    if (which == 0) newEntry(); else explainHeap();
                }).setNegativeButton("Cancel", null).create());
    }

    private void explainHeap() {
        createHeap(heap -> { openDestination(heap.id()); refresh.run(); }, () -> {});
    }

    void createHeap(Consumer<JournalHeap> saved, Runnable cancelled) {
        if (!authorized.getAsBoolean()) return;
        if (activity.getSharedPreferences("heap_notices", Context.MODE_PRIVATE).getBoolean("created", false)) { newHeap(saved, cancelled); return; }
        showDialog.accept(new AlertDialog.Builder(activity).setTitle("A little space for each part of life")
                .setMessage("A Heap is a folder for journals about a particular topic, concern, or facet of your life—such as work, relationships, or personal growth.\n\nCreating Heaps helps compartmentalize your thoughts, which can help clear your mind further. You can also keep journals outside all Heaps.\n\nGive your Heap a name. A category and color profile are optional.")
                .setNegativeButton("Maybe later", (d, w) -> cancelled.run())
                .setOnCancelListener(d -> cancelled.run())
                .setPositiveButton("Create a Heap", (d, w) -> { if (authorized.getAsBoolean()) newHeap(saved, cancelled); }).create());
    }

    private void newHeap(Consumer<JournalHeap> saved, Runnable cancelled) {
        LinearLayout form = form();
        EditText name = field(form, "Heap name (required)", R.id.heap_name, "", 100, false);
        EditText categoryField = field(form, "Category (optional)", R.id.heap_category, "", 60, false);
        add(form, text("Color profile (optional)", 14, true), 12);
        Spinner color = spinner(java.util.Arrays.asList(JournalHeap.COLOR_NAMES), R.id.heap_color);
        add(form, color, 4);
        TextView error = error(form);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("New Heap").setView(scroll(form))
                .setNegativeButton("Cancel", (d, w) -> cancelled.run())
                .setOnCancelListener(d -> cancelled.run()).setPositiveButton("Create Heap", null).create();
        showDialog.accept(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!authorized.getAsBoolean()) { dialog.dismiss(); return; }
            try {
                int choice = color.getSelectedItemPosition();
                JournalHeap heap = store.addHeap(name.getText().toString(), categoryField.getText().toString(),
                        choice == 0 ? null : JournalHeap.COLORS[choice]);
                activity.getSharedPreferences("heap_notices", Context.MODE_PRIVATE).edit().putBoolean("created", true).apply();
                dialog.dismiss(); saved.accept(heap);
            } catch (IllegalArgumentException e) { error.setText(e.getMessage()); name.requestFocus(); }
            catch (android.database.SQLException e) { error.setText("Couldn’t save this Heap. Check device storage and try again."); }
        });
    }

    private void newEntry() {
        SharedPreferences draft = activity.getSharedPreferences("journal_entry_draft", Context.MODE_PRIVATE);
        LinearLayout form = form();
        add(form, text("How are you feeling?", 16, true), 0);
        Spinner mood = spinner(java.util.Arrays.asList(MOODS), R.id.journal_entry_mood);
        mood.setSelection(Math.max(0, Math.min(5, draft.getInt("mood", 0))));
        add(form, mood, 4);
        EditText event = field(form, "What’s on your mind? (optional)", R.id.journal_entry_event, draft.getString("event", ""), 20000, true);
        EditText thoughts = field(form, "Thoughts & feelings (optional)", R.id.journal_entry_thoughts, draft.getString("thoughts", ""), 20000, true);
        EditText needs = field(form, "What would help? (optional)", R.id.journal_entry_needs, draft.getString("needs", ""), 20000, true);
        List<JournalHeap> heaps = store.heaps();
        add(form, text("Save in", 14, true), 12);
        Spinner destination = destinations(heaps, draft.getString("heap", activeHeap));
        add(form, destination, 4);
        add(form, text("Your unfinished journal is kept as a private draft when you close this window.", 12, false), 10);
        TextView error = error(form);
        boolean[] saved = {false};
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("New journal entry").setView(scroll(form))
                .setNegativeButton("Keep draft", null).setPositiveButton("Save entry", null).create();
        dialog.setOnDismissListener(d -> {
            if (saved[0]) return;
            draft.edit().putInt("mood", mood.getSelectedItemPosition()).putString("event", event.getText().toString())
                    .putString("thoughts", thoughts.getText().toString()).putString("needs", needs.getText().toString())
                    .putString("heap", destinationId(destination, heaps)).apply();
        });
        showDialog.accept(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!authorized.getAsBoolean()) { dialog.dismiss(); return; }
            if (mood.getSelectedItemPosition() == 0) { error.setText("Choose the mood that feels closest first."); return; }
            try {
                store.add(mood.getSelectedItemPosition(), event.getText().toString(), thoughts.getText().toString(),
                        needs.getText().toString(), destinationId(destination, heaps));
                saved[0] = true; draft.edit().clear().apply(); dialog.dismiss(); refresh.run();
            } catch (android.database.SQLException e) { error.setText("Couldn’t save your journal. Your writing is still here. Please try again."); }
        });
    }

    void moveEntry(JournalStore.Entry entry) {
        if (!authorized.getAsBoolean()) return;
        List<JournalHeap> heaps = store.heaps();
        LinearLayout form = form();
        add(form, text("Choose a Heap, or keep this journal outside all Heaps.", 15, false), 0);
        Spinner destination = destinations(heaps, entry.heapId());
        add(form, destination, 12);
        showDialog.accept(new AlertDialog.Builder(activity).setTitle("Move journal entry").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Move entry", (d, w) -> {
                    if (!authorized.getAsBoolean()) return;
                    try { store.move(entry.id(), destinationId(destination, heaps)); refresh.run(); }
                    catch (android.database.SQLException e) { Toast.makeText(activity, "Couldn’t move this entry. Please try again.", Toast.LENGTH_LONG).show(); }
                }).create());
    }

    private Spinner destinations(List<JournalHeap> heaps, String selected) {
        List<String> labels = new ArrayList<>(); labels.add("Outside Heaps");
        int index = 0;
        for (int i = 0; i < heaps.size(); i++) {
            JournalHeap heap = heaps.get(i);
            labels.add(heap.name() + (heap.category().isEmpty() ? "" : " · " + heap.category()));
            if (heap.id().equals(selected)) index = i + 1;
        }
        Spinner spinner = spinner(labels, R.id.entry_heap); spinner.setSelection(index);
        spinner.setContentDescription("Journal destination");
        return spinner;
    }

    private String destinationId(Spinner spinner, List<JournalHeap> heaps) {
        int selected = spinner.getSelectedItemPosition();
        return selected == 0 ? null : heaps.get(selected - 1).id();
    }

    private Spinner spinner(List<String> labels, int id) {
        Spinner spinner = new Spinner(activity); spinner.setId(id); spinner.setMinimumHeight(dp(52));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSaveEnabled(false);
        return spinner;
    }

    private EditText field(LinearLayout form, String label, int id, String value, int limit, boolean multiline) {
        TextView heading = text(label, 14, true); heading.setLabelFor(id); add(form, heading, 14);
        EditText field = new EditText(activity); field.setId(id); field.setText(value);
        field.setHint(label); field.setTextSize(15); field.setTextColor(INK); field.setHintTextColor(MUTED);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        field.setSingleLine(!multiline); if (multiline) field.setMinLines(3);
        field.setMinHeight(dp(52)); field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setSaveEnabled(false); field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        add(form, field, 4); return field;
    }

    private TextView error(LinearLayout form) {
        TextView error = text("", 14, false); error.setId(R.id.heap_form_error);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(form, error, 12); return error;
    }
    private ScrollView scroll(LinearLayout form) { ScrollView scroll = new ScrollView(activity); scroll.addView(form); return scroll; }
    private LinearLayout form() { LinearLayout form = column(); form.setPadding(dp(24), dp(12), dp(24), dp(12)); return form; }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout card(int color) { LinearLayout card = column(); card.setPadding(dp(18), dp(20), dp(18), dp(20)); card.setBackground(shape(color)); return card; }
    private GradientDrawable shape(int color) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(20)); return shape; }
    private TextView text(String value, int size, boolean bold) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size); text.setTextColor(INK);
        text.setLineSpacing(dp(3), 1); if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return text;
    }
    private Button button(String value, int id) {
        Button button = new Button(activity); button.setId(id); button.setText(value); button.setAllCaps(false);
        button.setTextSize(15); button.setTextColor(PURPLE); button.setMinHeight(dp(52));
        button.setPadding(dp(12), dp(12), dp(12), dp(12)); button.setBackground(shape(0xFFF5F0F7)); return button;
    }
    private void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(margin); parent.addView(child, params);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
