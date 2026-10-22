package com.moodjournal.app;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import androidx.fragment.app.FragmentActivity;

public final class MainActivity extends FragmentActivity {
    private static final int CHECK_IN = 0, JOURNAL = 1, HISTORY = 2;
    private static final int INK = Color.rgb(49, 43, 53);
    private static final int MUTED = Color.rgb(109, 100, 112);
    private static final int PURPLE = Color.rgb(112, 81, 128);
    private static final int PAPER = Color.rgb(250, 247, 242);
    private static final String[] MOODS = {"Low", "Down", "Okay", "Good", "Great"};
    private static final String[] FACES = {"☁", "☂", "◒", "❀", "☀"};
    private static final int[] MOOD_IDS = {R.id.mood_1, R.id.mood_2, R.id.mood_3, R.id.mood_4, R.id.mood_5};
    private static final int[] MOOD_RESPONSES = {R.string.mood_response_low, R.string.mood_response_down,
            R.string.mood_response_okay, R.string.mood_response_good, R.string.mood_response_great};
    private static final String[] PROMPTS = {
            "What has been on your mind today?",
            "What moment would you like to understand better?",
            "What felt heavy, and what felt lighter?",
            "What is one small thing you appreciated today?"
    };
    private JournalStore store;
    private SharedPreferences draft;
    private LinearLayout page;
    private EditText event, thoughts, needs;
    private final Button[] moodButtons = new Button[5];
    private int mood;
    private int promptIndex;
    private int currentTab = CHECK_IN;
    private boolean journalUnlocked;
    private JournalGate gate;
    private AlertDialog privateDialog;
    private HistoryScreen history;
    private BackupController backup;
    private JournalScreen journals;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new JournalStore(this);
        draft = getSharedPreferences("draft", MODE_PRIVATE);
        gate = new JournalGate(this, new PinLock(this), JournalGate.systemBiometrics(this), () -> {
            if (currentTab == CHECK_IN || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return;
            journalUnlocked = true;
            hideKeyboard();
            showPrivateTab();
        });
        history = new HistoryScreen(this, store, state, this::showHistory, this::showPrivateDialog,
                () -> currentTab == HISTORY && journalUnlocked);
        backup = new BackupController(this, state, this::showPrivateDialog,
                () -> currentTab == JOURNAL && journalUnlocked, this::showJournal);
        journals = new JournalScreen(this, store, state, () -> currentTab == JOURNAL && journalUnlocked,
                this::showPrivateDialog, this::showEntry, this::showJournal);
        mood = draft.getInt("mood", 0);
        promptIndex = draft.getInt("prompt", 0) % PROMPTS.length;
        int restoredTab = state == null ? CHECK_IN : state.getInt("tab", state.getBoolean("journal") ? JOURNAL : CHECK_IN);
        if (restoredTab == HISTORY) showHistory();
        else if (restoredTab == JOURNAL) showJournal();
        else showCheckIn();
    }

    private void frame(int tab) {
        currentTab = tab;
        LinearLayout root = column();
        root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = column();
        page.setPadding(dp(24), dp(24), dp(24), dp(28));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(16), dp(8), dp(16), dp(8));
        nav.setBackgroundColor(Color.WHITE);
        String[] names = {"Check in", "My Journal", "History"};
        int[] ids = {R.id.checkin_tab, R.id.journal_tab, R.id.history_tab};
        for (int i = 0; i < names.length; i++) {
            final int target = i;
            Button choice = button(names[i], tab == i);
            choice.setTextSize(13);
            choice.setId(ids[i]);
            choice.setSelected(tab == i);
            choice.setContentDescription(names[i] + (tab == i ? ", selected" : ""));
            choice.setOnClickListener(v -> openTab(target));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) p.setMarginStart(dp(6));
            nav.addView(choice, p);
        }
        root.addView(nav);
    }

    private void openTab(int tab) {
        if (currentTab == tab) return;
        saveDraft();
        hideKeyboard();
        journalUnlocked = false;
        gate.lock();
        backup.onPause();
        if (tab == CHECK_IN) showCheckIn();
        else if (tab == JOURNAL) showJournal();
        else showHistory();
    }

    private void showPrivateTab() {
        if (currentTab == HISTORY) showHistory(); else showJournal();
    }

    private void showHistory() {
        frame(HISTORY);
        if (!journalUnlocked) gate.show(page);
        else history.show(page);
    }

    private void showCheckIn() {
        journalUnlocked = false;
        gate.lock();
        frame(CHECK_IN);
        add(page, text("DAILY PAUSE  /  A MOMENT FOR YOU", 11, PURPLE, true), 0);
        add(page, text(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(new Date()), 13, MUTED, false), 10);
        TextView title = text("A little space\nfor you.", 36, INK, false);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        add(page, title, 16);
        add(page, text("You don’t have to make sense of everything.\nStart with how you feel.", 15, MUTED, false), 10);

        LinearLayout moodCard = card(Color.rgb(237, 230, 241));
        add(moodCard, text("How are you feeling?", 20, INK, true), 0);
        add(moodCard, text("Every feeling is welcome here.", 13, MUTED, false), 6);
        LinearLayout choices = new LinearLayout(this);
        for (int i = 0; i < 5; i++) {
            final int selected = i + 1;
            Button b = button(FACES[i] + "\n" + MOODS[i], mood == selected);
            b.setId(MOOD_IDS[i]);
            b.setTextSize(12);
            b.setPadding(0, dp(8), 0, dp(8));
            b.setOnClickListener(v -> { mood = selected; updateMoods(); saveDraft(); showMoodResponse(selected); });
            moodButtons[i] = b;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(76), 1);
            if (i > 0) params.setMarginStart(dp(4));
            choices.addView(b, params);
        }
        add(moodCard, choices, 18);
        add(page, moodCard, 24);
        updateMoods();

        LinearLayout writing = card(Color.WHITE);
        add(writing, text("Let it out", 23, INK, true), 0);
        add(writing, text("Write a little or a lot. These prompts are here to help; every field is optional.", 14, MUTED, false), 8);
        TextView prompt = text(PROMPTS[promptIndex], 16, INK, true);
        add(writing, prompt, 22);
        Button another = button("Try another prompt ↻", false);
        another.setTextSize(12);
        another.setOnClickListener(v -> { promptIndex = (promptIndex + 1) % PROMPTS.length; prompt.setText(PROMPTS[promptIndex]); saveDraft(); });
        add(writing, another, 8);
        event = input("Start anywhere…", R.id.entry_event, draft.getString("event", ""));
        prompt.setLabelFor(R.id.entry_event);
        add(writing, event, 10);
        TextView thoughtsLabel = text("What thoughts or feelings came up?", 16, INK, true);
        thoughtsLabel.setLabelFor(R.id.entry_thoughts);
        add(writing, thoughtsLabel, 22);
        thoughts = input("Name them without judging them…", R.id.entry_thoughts, draft.getString("thoughts", ""));
        add(writing, thoughts, 10);
        TextView needsLabel = text("What would help you right now?", 16, INK, true);
        needsLabel.setLabelFor(R.id.entry_needs);
        add(writing, needsLabel, 22);
        needs = input("A small next step, a little rest, someone to talk to…", R.id.entry_needs, draft.getString("needs", ""));
        add(writing, needs, 10);
        add(page, writing, 20);
        Button save = button("Save this moment", true);
        save.setId(R.id.save_entry);
        save.setOnClickListener(v -> saveEntry());
        add(page, save, 20);
        TextView privacy = text("Entries stay on this device unless you choose an encrypted backup.\nYour unfinished writing is saved as a draft.", 12, MUTED, false);
        privacy.setGravity(Gravity.CENTER);
        add(page, privacy, 16);
    }

    private void updateMoods() {
        for (int i = 0; i < moodButtons.length; i++) {
            Button b = moodButtons[i];
            if (b == null) continue;
            boolean chosen = mood == i + 1;
            b.setSelected(chosen);
            b.setTextColor(chosen ? Color.WHITE : INK);
            b.setBackground(shape(chosen ? PURPLE : Color.rgb(248, 244, 250), 16));
            b.setContentDescription(MOODS[i] + (chosen ? ", selected" : ", tap to select"));
        }
    }

    private void showMoodResponse(int selected) {
        EditText reflection = event;
        showPrivateDialog(new AlertDialog.Builder(this)
                .setTitle(getString(selected <= 3 ? R.string.mood_response_hug_title
                        : R.string.mood_response_high_five_title, MOODS[selected - 1]))
                .setMessage(MOOD_RESPONSES[selected - 1])
                .setNegativeButton(R.string.mood_response_later, null)
                .setPositiveButton(R.string.mood_response_journal, (dialog, which) -> reflection.post(() -> {
                    if (currentTab != CHECK_IN || event != reflection || mood != selected
                            || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return;
                    reflection.requestFocus();
                    reflection.requestRectangleOnScreen(new android.graphics.Rect(0, 0, reflection.getWidth(), reflection.getHeight()), true);
                    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(reflection, InputMethodManager.SHOW_IMPLICIT);
                })));
    }

    private void saveEntry() {
        if (mood == 0) {
            Toast.makeText(this, "Choose the mood that feels closest first.", Toast.LENGTH_LONG).show();
            moodButtons[0].requestFocus();
            return;
        }
        try {
            store.add(mood, event.getText().toString(), thoughts.getText().toString(), needs.getText().toString());
            draft.edit().clear().apply();
            mood = 0;
            event = thoughts = needs = null;
            hideKeyboard();
            showJournal();
            Toast.makeText(this, "A moment for yourself, saved.", Toast.LENGTH_SHORT).show();
        } catch (android.database.SQLException e) {
            saveDraft();
            new AlertDialog.Builder(this).setTitle("Your entry couldn’t be saved")
                    .setMessage("Your draft is still here. Check that your device has free storage, then try again.")
                    .setPositiveButton("Okay", null).show();
        }
    }

    private void showJournal() {
        frame(JOURNAL);
        if (!journalUnlocked) {
            gate.show(page);
            return;
        }
        add(page, text("YOUR WORDS, YOUR JOURNEY", 11, PURPLE, true), 0);
        TextView heading = text("My journal", 36, INK, false);
        heading.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        add(page, heading, 16);
        add(page, text("A place to notice, reflect, and begin again.", 15, MUTED, false), 8);
        List<JournalStore.Entry> entries;
        try { entries = store.all(); }
        catch (android.database.SQLException e) {
            add(page, text("Your journal couldn’t be opened. Try reopening the app. Your entries have not been removed.", 16, INK, false), 24);
            return;
        }
        LinearLayout note = card(Color.rgb(232, 237, 225));
        add(note, text(entries.isEmpty() ? "Your story starts here" : entries.size() + (entries.size() == 1 ? " moment of reflection" : " moments of reflection"), 19, INK, true), 0);
        add(note, text(entries.isEmpty() ? "A mood is enough. A few words are enough.\nMake room for whatever today brings."
                : "There’s no right way to feel. Look back with curiosity and kindness.", 14, MUTED, false), 8);
        add(page, note, 24);
        try {
            journals.show(page, entries);
            backup.addTo(page, entries.size() + store.heaps().size());
        } catch (android.database.SQLException e) {
            add(page, text("Your journal couldn’t be opened. Try reopening the app. Your entries have not been removed.", 16, INK, false), 24);
        }
        add(page, text("Uninstalling the app or clearing its data removes local entries. Keep an encrypted backup and its passphrase to restore them later.", 12, MUTED, false), 24);
    }

    private void showEntry(JournalStore.Entry entry) {
        if (!journalUnlocked || currentTab != JOURNAL) return;
        LinearLayout content = column();
        content.setPadding(dp(24), dp(12), dp(24), dp(12));
        add(content, text(new SimpleDateFormat("MMMM d, yyyy · h:mm a", Locale.getDefault()).format(new Date(entry.createdAt())), 13, MUTED, false), 0);
        detail(content, "What was on your mind", entry.event());
        detail(content, "Thoughts & feelings", entry.thoughts());
        detail(content, "What you needed", entry.needs());
        if (entry.event().isEmpty() && entry.thoughts().isEmpty() && entry.needs().isEmpty())
            add(content, text("You took a moment to notice how you felt.", 16, INK, false), 20);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        showPrivateDialog(new AlertDialog.Builder(this).setTitle(FACES[entry.mood() - 1] + "  Feeling " + MOODS[entry.mood() - 1].toLowerCase(Locale.ROOT))
                .setView(scroll).setPositiveButton("Close", null)
                .setNegativeButton("Move to Heap", (d, w) -> journals.moveEntry(entry))
                .setNeutralButton("Delete", (d, w) -> {
                    if (!journalUnlocked || currentTab != JOURNAL) return;
                    showPrivateDialog(new AlertDialog.Builder(this)
                        .setTitle("Delete this entry?").setMessage("This removes the entry permanently from your device.")
                        .setNegativeButton("Keep entry", (dialog, which) -> showEntry(entry))
                        .setPositiveButton("Delete entry", (dialog, which) -> {
                            if (!journalUnlocked || currentTab != JOURNAL) return;
                            try { store.delete(entry.id()); showJournal(); }
                            catch (android.database.SQLException e) { Toast.makeText(this, "Couldn’t delete this entry. Please try again.", Toast.LENGTH_LONG).show(); }
                        }));
                }));
    }

    private void showPrivateDialog(AlertDialog.Builder builder) {
        showPrivateDialog(builder.create());
    }

    private void showPrivateDialog(AlertDialog dialog) {
        privateDialog = dialog;
        privateDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        privateDialog.show();
    }

    private void detail(LinearLayout parent, String label, String value) {
        if (value.isEmpty()) return;
        add(parent, text(label, 13, PURPLE, true), 22);
        TextView body = text(value, 16, INK, false);
        body.setTextIsSelectable(true);
        add(parent, body, 8);
    }

    private void saveDraft() {
        if (currentTab != CHECK_IN || event == null) return;
        draft.edit().putInt("mood", mood).putInt("prompt", promptIndex)
                .putString("event", event.getText().toString())
                .putString("thoughts", thoughts.getText().toString())
                .putString("needs", needs.getText().toString()).apply();
    }

    @Override protected void onPause() {
        saveDraft();
        backup.onPause();
        journalUnlocked = false;
        gate.lock();
        if (privateDialog != null) { privateDialog.dismiss(); privateDialog = null; }
        if (currentTab != CHECK_IN) showPrivateTab();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (currentTab != CHECK_IN && !journalUnlocked) showPrivateTab();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        saveDraft();
        state.putInt("tab", currentTab);
        history.saveState(state);
        journals.saveState(state);
        backup.saveState(state);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { gate.close(); backup.close(); store.close(); super.onDestroy(); }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout card(int color) {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(20), dp(18), dp(20));
        card.setBackground(shape(color, 24));
        return card;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }
    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setTextColor(primary ? Color.WHITE : PURPLE);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setBackground(shape(primary ? PURPLE : Color.rgb(245, 240, 247), 16));
        return button;
    }
    private EditText input(String hint, int id, String value) {
        EditText edit = new EditText(this);
        edit.setId(id);
        edit.setHint(hint);
        edit.setText(value);
        edit.setTextSize(15);
        edit.setTextColor(INK);
        edit.setHintTextColor(MUTED);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setMinLines(3);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(20000)});
        edit.setPadding(dp(14), dp(14), dp(14), dp(14));
        edit.setBackground(shape(PAPER, 12));
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return edit;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
