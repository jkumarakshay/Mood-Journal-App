package com.moodjournal.app;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.res.ColorStateList;
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
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ListPopupWindow;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import androidx.fragment.app.FragmentActivity;
import androidx.activity.OnBackPressedCallback;

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
    private boolean backupUnlocked;
    private JournalGate gate;
    private AlertDialog privateDialog;
    private HistoryScreen history;
    private BackupController backup;
    private JournalScreen journals;
    private boolean pendingMoment, createHeapForMoment;
    private int momentGeneration;
    private int settingsPage = SettingsScreen.NONE;
    private SettingsScreen settings;
    private OnBackPressedCallback settingsBack;
    private ReminderController reminders;
    private ExportController exports;
    private WeatherController weather;
    private UpdateController updates;
    private AnalysisScreen analysis;
    private int analyticsMode;
    private ListPopupWindow analyticsMenu;
    private OnboardingState onboarding;

    @Override public void onCreate(Bundle state) {
        Appearance.apply(this);
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        onboarding = new OnboardingState(this);
        store = new JournalStore(this);
        draft = getSharedPreferences("draft", MODE_PRIVATE);
        gate = new JournalGate(this, new PinLock(this), JournalGate.systemBiometrics(this), () -> {
            if (!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return;
            if (settingsPage == SettingsScreen.BACKUP) {
                backupUnlocked = true;
                hideKeyboard(); showSettings(); return;
            }
            if (settingsPage != SettingsScreen.NONE || currentTab == CHECK_IN) return;
            journalUnlocked = true;
            hideKeyboard();
            showPrivateTab();
        });
        history = new HistoryScreen(this, store, state, this::showHistory, this::showPrivateDialog,
                () -> settingsPage == SettingsScreen.NONE && currentTab == HISTORY && analyticsMode == 0 && journalUnlocked);
        analysis = new AnalysisScreen(this, () -> settingsPage == SettingsScreen.NONE && currentTab == HISTORY && analyticsMode == 1 && journalUnlocked, this::showHistory);
        analyticsMode = state == null ? 0 : Math.max(0, Math.min(1, state.getInt("analytics_mode", 0)));
        backup = new BackupController(this, state, this::showPrivateDialog,
                () -> settingsPage == SettingsScreen.BACKUP && backupUnlocked, this::showSettings);
        exports = new ExportController(this, state,
                () -> settingsPage == SettingsScreen.NONE && currentTab == JOURNAL && journalUnlocked,
                this::showPrivateDialog, this::showJournal);
        journals = new JournalScreen(this, store, state, () -> settingsPage == SettingsScreen.NONE && currentTab == JOURNAL && journalUnlocked,
                this::showPrivateDialog, this::showEntry, this::showJournal, exports);
        reminders = new ReminderController(this, this::showPrivateDialog,
                () -> settingsPage == SettingsScreen.NOTIFICATION, this::showSettings);
        weather = new WeatherController(this, this::showPrivateDialog,
                () -> settingsPage == SettingsScreen.WEATHER, this::showSettings, () -> openSettings(SettingsScreen.WEATHER));
        updates = new UpdateController(this, () -> settingsPage == SettingsScreen.UPDATES,
                () -> !onboarding.active() && settingsPage == SettingsScreen.NONE && currentTab == CHECK_IN
                        && (privateDialog == null || !privateDialog.isShowing()),
                this::showSettings, () -> openSettings(SettingsScreen.UPDATES), this::showPrivateDialog);
        settings = new SettingsScreen(this, this::openSettings, dark -> {
            saveDraft();
            Appearance.setDark(this, dark);
            recreate();
        }, reminders, weather, updates);
        settingsBack = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { backFromSettings(); }
        };
        getOnBackPressedDispatcher().addCallback(this, settingsBack);
        settingsPage = state == null ? SettingsScreen.NONE : state.getInt("settings_page", SettingsScreen.NONE);
        if (settingsPage < SettingsScreen.NONE || settingsPage > SettingsScreen.BACKUP) settingsPage = SettingsScreen.NONE;
        mood = draft.getInt("mood", 0);
        pendingMoment = state != null && state.getBoolean("pending_moment") && mood != 0;
        createHeapForMoment = pendingMoment && state.getBoolean("create_heap_for_moment");
        promptIndex = draft.getInt("prompt", 0) % PROMPTS.length;
        int restoredTab = state == null ? CHECK_IN : state.getInt("tab", state.getBoolean("journal") ? JOURNAL : CHECK_IN);
        if (ReminderScheduler.OPEN_CHECK_IN.equals(getIntent().getAction())) {
            settingsPage = SettingsScreen.NONE; restoredTab = CHECK_IN; getIntent().setAction(null);
        }
        if (restoredTab != JOURNAL) cancelPendingMoment();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(onboarding.active()) {
            @Override public void handleOnBackPressed() {
                // Keep progress until an explicit Next or Skip tour action.
                if (!onboarding.active()) { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
        if (onboarding.active()) { showOnboarding(); return; }
        currentTab = restoredTab;
        if (settingsPage != SettingsScreen.NONE) showSettings();
        else if (restoredTab == HISTORY) showHistory();
        else if (restoredTab == JOURNAL) showJournal();
        else showCheckIn();
    }

    private void frame(int tab) {
        currentTab = tab;
        settingsBack.setEnabled(settingsPage != SettingsScreen.NONE);
        LinearLayout root = column();
        root.setId(R.id.app_root);
        root.setBackgroundColor(Appearance.color(this, PAPER));
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
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(24), dp(8), dp(24), dp(4));
        if (settingsPage != SettingsScreen.NONE) {
            Button back = button("← " + getString(R.string.settings_back), false);
            back.setId(R.id.settings_back);
            back.setOnClickListener(v -> backFromSettings());
            toolbar.addView(back, new LinearLayout.LayoutParams(-2, dp(48)));
        }
        if (settingsPage == SettingsScreen.NONE && tab == CHECK_IN) toolbar.addView(weather.header(), new LinearLayout.LayoutParams(0, -2, 1));
        else toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        ImageButton gear = new ImageButton(this);
        gear.setId(R.id.settings_button);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setImageTintList(ColorStateList.valueOf(Appearance.color(this, PURPLE)));
        gear.setContentDescription(getString(R.string.settings_title));
        gear.setBackground(shape(Color.rgb(245, 240, 247), 16));
        gear.setOnClickListener(v -> openSettings(SettingsScreen.HOME));
        toolbar.addView(gear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(toolbar);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = column();
        page.setPadding(dp(24), dp(24), dp(24), dp(28));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(16), dp(8), dp(16), dp(8));
        nav.setBackgroundColor(Appearance.color(this, Color.WHITE));
        String[] names = {"Check in", "My Journal", "Analytics"};
        int[] ids = {R.id.checkin_tab, R.id.journal_tab, R.id.history_tab};
        for (int i = 0; i < names.length; i++) {
            final int target = i;
            boolean selected = settingsPage == SettingsScreen.NONE && tab == i;
            Button choice = button(names[i], selected);
            choice.setTextSize(13);
            choice.setId(ids[i]);
            choice.setSelected(selected);
            choice.setContentDescription(names[i] + (selected ? ", selected" : ""));
            choice.setOnClickListener(v -> { if (target == HISTORY) showAnalyticsMenu(v); else openTab(target); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) p.setMarginStart(dp(6));
            nav.addView(choice, p);
        }
        root.addView(nav);
    }

    private void openTab(int tab) {
        if (onboarding.active()) return;
        if (currentTab == tab && settingsPage == SettingsScreen.NONE) return;
        saveDraft();
        dismissAnalyticsMenu(); analysis.onLeave();
        settingsPage = SettingsScreen.NONE;
        cancelPendingMoment();
        if (privateDialog != null) {
            privateDialog.dismiss();
            privateDialog = null;
        }
        hideKeyboard();
        journalUnlocked = false;
        backupUnlocked = false;
        gate.lock();
        backup.onPause(); exports.onPause(); weather.onPause(); updates.onLeave();
        if (tab == CHECK_IN) showCheckIn();
        else if (tab == JOURNAL) showJournal();
        else showHistory();
    }

    private void showPrivateTab() {
        if (settingsPage != SettingsScreen.NONE) showSettings();
        else if (currentTab == HISTORY) showHistory(); else showJournal();
    }

    private void openSettings(int target) {
        if (onboarding.active()) return;
        saveDraft();
        dismissAnalyticsMenu(); analysis.onLeave();
        cancelPendingMoment();
        hideKeyboard();
        if (privateDialog != null) {
            privateDialog.dismiss();
            privateDialog = null;
        }
        journalUnlocked = false;
        backupUnlocked = false;
        gate.lock();
        backup.onPause(); exports.onPause(); weather.onPause(); updates.onLeave();
        settingsPage = target;
        showSettings();
    }

    private void showSettings() {
        frame(currentTab);
        settings.show(page, settingsPage);
        if (settingsPage == SettingsScreen.BACKUP) {
            if (!backupUnlocked) { gate.show(page); return; }
            try {
                long count = android.database.DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "entries")
                        + android.database.DatabaseUtils.queryNumEntries(store.getReadableDatabase(), "heaps");
                backup.addTo(page, count > 0 ? 1 : 0);
            } catch (android.database.SQLException e) {
                add(page, text(getString(R.string.backup_data_unavailable), 16, INK, false), 24);
            }
        }
    }

    private void backFromSettings() {
        if (settingsPage != SettingsScreen.HOME) {
            openSettings(SettingsScreen.HOME);
            return;
        }
        openTab(currentTab);
    }

    private void showHistory() {
        frame(HISTORY);
        if (!journalUnlocked) gate.show(page);
        else {
            Button selector = button(getString(analyticsMode == 0 ? R.string.mood_graph : R.string.mood_analysis) + "  ▾", false);
            selector.setId(R.id.analytics_selector); selector.setContentDescription(getString(R.string.analytics_choose_view));
            selector.setOnClickListener(this::showAnalyticsMenu); add(page, selector, 0);
            if (analyticsMode == 0) history.show(page); else analysis.show(page);
        }
    }

    private void showAnalyticsMenu(View anchor) {
        if (onboarding.active()) return;
        if (!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return;
        dismissAnalyticsMenu();
        int selected = currentTab == HISTORY && settingsPage == SettingsScreen.NONE ? analyticsMode : -1;
        View root = findViewById(R.id.app_root);
        ListPopupWindow menu = AnalyticsMenu.create(this, anchor, selected,
                root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels);
        analyticsMenu = menu;
        menu.setOnItemClickListener((parent, view, position, id) -> {
            if (analyticsMenu != menu || !menu.isShowing()
                    || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return;
            openAnalytics(position == 1 ? 1 : 0);
        });
        menu.setOnDismissListener(() -> { if (analyticsMenu == menu) analyticsMenu = null; });
        menu.show(); AnalyticsMenu.decorate(menu);
    }
    private void openAnalytics(int mode) {
        dismissAnalyticsMenu(); analysis.onLeave();
        if (privateDialog != null) {
            privateDialog.dismiss();
            privateDialog = null;
        }
        analyticsMode = mode;
        if (currentTab == HISTORY && settingsPage == SettingsScreen.NONE) showHistory(); else openTab(HISTORY);
    }
    private void dismissAnalyticsMenu() { if (analyticsMenu != null) { analyticsMenu.dismiss(); analyticsMenu = null; } }

    private void showCheckIn() {
        journalUnlocked = false;
        backupUnlocked = false;
        gate.lock();
        frame(CHECK_IN);
        add(page, text("DAILY PAUSE  /  A MOMENT FOR YOU", 11, PURPLE, true), 0);
        add(page, text(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(new Date()), 13, MUTED, false), 10);
        TextView title = text("A little space\nfor you.", 36, INK, false);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        add(page, title, 16);
        add(page, text("You don’t have to make sense of everything.\nStart with how you feel.", 15, MUTED, false), 10);

        LinearLayout moodCard = card(Color.rgb(237, 230, 241));
        TextView emotionHeading = text("How are you feeling?", 20, INK, true);
        emotionHeading.setId(R.id.emotion_section);
        add(moodCard, emotionHeading, 0);
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
        TextView writingHeading = text("Let it out", 23, INK, true);
        writingHeading.setId(R.id.writing_section);
        add(writing, writingHeading, 0);
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
        TextView privacy = text("Journal entries stay on this device unless you export or back them up.\nYour unfinished writing is saved as a draft.", 12, MUTED, false);
        privacy.setGravity(Gravity.CENTER);
        add(page, privacy, 16);
        updates.maybeCheckAutomatically();
    }

    private void updateMoods() {
        for (int i = 0; i < moodButtons.length; i++) {
            Button b = moodButtons[i];
            if (b == null) continue;
            boolean chosen = mood == i + 1;
            b.setSelected(chosen);
            b.setTextColor(chosen ? Appearance.onAccent(this) : Appearance.color(this, INK));
            b.setBackground(shape(chosen ? PURPLE : Color.rgb(248, 244, 250), 16));
            b.setContentDescription(MOODS[i] + (chosen ? ", selected" : ", tap to select"));
        }
    }

    private void showMoodResponse(int selected) {
        EditText reflection = event;
        showPrivateDialog(AppDialogs.builder(this)
                .setTitle(getString(selected <= 3 ? R.string.mood_response_hug_title
                        : R.string.mood_response_high_five_title, MOODS[selected - 1]))
                .setMessage(MOOD_RESPONSES[selected - 1])
                .setNegativeButton(R.string.mood_response_later, null)
                .setPositiveButton(R.string.mood_response_journal, (dialog, which) -> reflection.post(() -> {
                    if (settingsPage != SettingsScreen.NONE || currentTab != CHECK_IN || event != reflection || mood != selected
                            || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        return;
                    }
                    reflection.requestFocus();
                    reflection.requestRectangleOnScreen(new android.graphics.Rect(0, 0, reflection.getWidth(), reflection.getHeight()), true);
                    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(reflection, InputMethodManager.SHOW_IMPLICIT);
                })));
    }

    private void saveEntry() {
        if (onboarding.active()) return;
        if (settingsPage != SettingsScreen.NONE || currentTab != CHECK_IN || event == null) return;
        if (mood == 0) {
            Toast.makeText(this, "Choose the mood that feels closest first.", Toast.LENGTH_LONG).show();
            moodButtons[0].requestFocus();
            return;
        }
        saveDraft();
        hideKeyboard();
        WeatherSnapshot snapshot = new WeatherRepository(this).current(System.currentTimeMillis());
        draft.edit().putString("moment_weather", snapshot == null ? null : snapshot.encode()).apply();
        final int token = ++momentGeneration;
        showPrivateDialog(AppDialogs.builder(this).setTitle(R.string.moment_heap_prompt)
                .setMessage(R.string.moment_heap_message)
                .setPositiveButton(R.string.moment_choose_heap, (d, w) -> requestMomentHeap(token, false))
                .setNeutralButton(R.string.moment_create_heap, (d, w) -> requestMomentHeap(token, true))
                .setNegativeButton(R.string.moment_save_outside, (d, w) -> {
                    if (publicMomentActive(token)) saveMoment(null, token);
                }).setOnCancelListener(d -> { if (publicMomentActive(token)) momentGeneration++; }));
    }

    private boolean publicMomentActive(int token) {
        return token == momentGeneration && settingsPage == SettingsScreen.NONE && !pendingMoment && currentTab == CHECK_IN && mood != 0
                && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
    }

    private boolean privateMomentActive(int token) {
        return token == momentGeneration && settingsPage == SettingsScreen.NONE && pendingMoment && currentTab == JOURNAL && journalUnlocked
                && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
    }

    private void requestMomentHeap(int token, boolean create) {
        if (!publicMomentActive(token)) return;
        pendingMoment = true;
        createHeapForMoment = create;
        journalUnlocked = false;
        gate.lock();
        showJournal();
    }

    private void showMomentDestinations() {
        final int token = momentGeneration;
        if (!privateMomentActive(token)) return;
        if (createHeapForMoment) {
            journals.createHeap(heap -> {
                if (!privateMomentActive(token)) return;
                createHeapForMoment = false;
                saveMoment(heap.id(), token);
            }, () -> {
                if (!privateMomentActive(token)) return;
                createHeapForMoment = false;
                showMomentDestinations();
            });
            return;
        }
        List<JournalHeap> heaps;
        try { heaps = store.heaps(); }
        catch (android.database.SQLException e) {
            showPrivateDialog(AppDialogs.builder(this).setTitle(R.string.moment_heap_prompt)
                    .setMessage(R.string.moment_heaps_unavailable)
                    .setPositiveButton(R.string.moment_back_to_draft, (d, w) -> returnToMomentDraft(token)));
            return;
        }
        List<String> choices = new ArrayList<>();
        for (JournalHeap heap : heaps) choices.add(heap.name() + (heap.category().isEmpty() ? "" : " · " + heap.category()));
        choices.add(getString(R.string.moment_create_heap));
        showPrivateDialog(AppDialogs.builder(this).setTitle(R.string.moment_heap_prompt)
                .setItems(choices.toArray(new String[0]), (d, position) -> {
                    if (!privateMomentActive(token)) return;
                    if (position == heaps.size()) {
                        createHeapForMoment = true;
                        showMomentDestinations();
                    } else saveMoment(heaps.get(position).id(), token);
                })
                .setNegativeButton(R.string.moment_save_outside, (d, w) -> {
                    if (privateMomentActive(token)) saveMoment(null, token);
                })
                .setNeutralButton(R.string.moment_cancel, (d, w) -> returnToMomentDraft(token))
                .setOnCancelListener(d -> returnToMomentDraft(token)));
    }

    private void returnToMomentDraft(int token) {
        if (!privateMomentActive(token)) return;
        cancelPendingMoment();
        showCheckIn();
    }

    private void cancelPendingMoment() {
        momentGeneration++;
        pendingMoment = false;
        createHeapForMoment = false;
    }

    private void saveMoment(String heapId, int token) {
        if (!publicMomentActive(token) && !privateMomentActive(token)) return;
        int savedMood = draft.getInt("mood", 0);
        if (savedMood < 1 || savedMood > 5) return;
        try {
            store.add(savedMood, draft.getString("event", ""), draft.getString("thoughts", ""), draft.getString("needs", ""), heapId,
                    draft.getInt("weather_answer", 0), WeatherSnapshot.decode(draft.getString("moment_weather", null)));
            cancelPendingMoment();
            draft.edit().clear().apply();
            mood = 0;
            event = thoughts = needs = null;
            hideKeyboard();
            journals.openDestination(heapId);
            showJournal();
            Toast.makeText(this, "A moment for yourself, saved.", Toast.LENGTH_SHORT).show();
        } catch (android.database.SQLException e) {
            showPrivateDialog(AppDialogs.builder(this).setTitle("Your entry couldn’t be saved")
                    .setMessage("Your draft is still here. Check that your device has free storage, then try again.")
                    .setPositiveButton("Okay", (d, w) -> {
                        if (privateMomentActive(token)) returnToMomentDraft(token);
                    }));
        }
    }

    private void showJournal() {
        frame(JOURNAL);
        if (!journalUnlocked) {
            gate.show(page);
            if (pendingMoment) {
                add(page, text(getString(R.string.moment_unlock_notice), 14, MUTED, false), 16);
                Button back = button(getString(R.string.moment_back_to_draft), false);
                back.setId(R.id.cancel_moment_save);
                back.setOnClickListener(v -> { cancelPendingMoment(); showCheckIn(); });
                add(page, back, 12);
            }
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
            exports.addStatus(page);
        } catch (android.database.SQLException e) {
            add(page, text("Your journal couldn’t be opened. Try reopening the app. Your entries have not been removed.", 16, INK, false), 24);
        }
        add(page, text(getString(R.string.journal_backup_hint), 12, MUTED, false), 24);
        if (pendingMoment) showMomentDestinations();
    }

    private void showEntry(JournalStore.Entry entry) {
        if (settingsPage != SettingsScreen.NONE || !journalUnlocked || currentTab != JOURNAL) return;
        LinearLayout content = column();
        content.setPadding(dp(24), dp(12), dp(24), dp(12));
        JournalHeap heap = store.heaps().stream().filter(h -> h.id().equals(entry.heapId())).findFirst().orElse(null);
        add(content, journals.optionsRow(text(new SimpleDateFormat("MMMM d, yyyy · h:mm a", Locale.getDefault()).format(new Date(entry.createdAt())), 13, MUTED, false),
                exports.entryMenu(entry, heap, () -> journals.editEntry(entry))), 0);
        detail(content, "What was on your mind", entry.event());
        detail(content, "Thoughts & feelings", entry.thoughts());
        detail(content, "What you needed", entry.needs());
        if (entry.weather() != null) {
            detail(content, "Weather on " + entry.weather().date(), entry.weather().summary(WeatherReflection.fahrenheit(this)));
            detail(content, "Weather source", entry.weather().attribution());
        }
        if (entry.weatherAnswer() != 0) detail(content, WeatherReflection.QUESTION, WeatherReflection.ANSWERS[entry.weatherAnswer()]);
        if (entry.event().isEmpty() && entry.thoughts().isEmpty() && entry.needs().isEmpty() && entry.weatherAnswer() == 0)
            add(content, text("You took a moment to notice how you felt.", 16, INK, false), 20);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        showPrivateDialog(AppDialogs.builder(this).setTitle(FACES[entry.mood() - 1] + "  Feeling " + MOODS[entry.mood() - 1].toLowerCase(Locale.ROOT))
                .setView(scroll).setPositiveButton("Close", null)
                .setNegativeButton("Move to Heap", (d, w) -> journals.moveEntry(entry))
                .setNeutralButton("Delete", (d, w) -> {
                    if (!journalUnlocked || currentTab != JOURNAL) return;
                    showPrivateDialog(AppDialogs.builder(this)
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
        if (privateDialog != null && privateDialog != dialog) privateDialog.dismiss();
        privateDialog = dialog;
        privateDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        privateDialog.show();
        AppDialogs.decorate(privateDialog);
    }

    private void detail(LinearLayout parent, String label, String value) {
        if (value.isEmpty()) return;
        add(parent, text(label, 13, PURPLE, true), 22);
        TextView body = text(value, 16, INK, false);
        body.setTextIsSelectable(true);
        add(parent, body, 8);
    }

    private void saveDraft() {
        if (onboarding.active() || settingsPage != SettingsScreen.NONE || currentTab != CHECK_IN || event == null) return;
        draft.edit().putInt("mood", mood).putInt("prompt", promptIndex)
                .putString("event", event.getText().toString())
                .putString("thoughts", thoughts.getText().toString())
                .putString("needs", needs.getText().toString()).apply();
    }

    @Override protected void onPause() {
        saveDraft();
        dismissAnalyticsMenu(); analysis.onLeave();
        momentGeneration++;
        backup.onPause();
        exports.onPause();
        weather.onPause();
        updates.onLeave();
        journalUnlocked = false;
        backupUnlocked = false;
        gate.lock();
        if (privateDialog != null) {
            privateDialog.dismiss();
            privateDialog = null;
        }
        if (!onboarding.active()) {
            if (settingsPage == SettingsScreen.BACKUP) showSettings();
            else if (settingsPage == SettingsScreen.NONE && currentTab != CHECK_IN) showPrivateTab();
        }
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (onboarding.active()) return;
        if (settingsPage == SettingsScreen.BACKUP && !backupUnlocked) showSettings();
        else if (settingsPage == SettingsScreen.NONE && currentTab != CHECK_IN && !journalUnlocked) showPrivateTab();
        reminders.onResume();
        weather.onResume();
        updates.onResume();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!ReminderScheduler.OPEN_CHECK_IN.equals(intent.getAction())) return;
        saveDraft(); cancelPendingMoment(); hideKeyboard();
        dismissAnalyticsMenu(); analysis.onLeave();
        if (privateDialog != null) {
            privateDialog.dismiss();
            privateDialog = null;
        }
        journalUnlocked = false; backupUnlocked = false; gate.lock(); backup.onPause(); exports.onPause(); weather.onPause(); updates.onLeave(); settingsPage = SettingsScreen.NONE;
        intent.setAction(null);
        if (onboarding.active()) showOnboarding(); else showCheckIn();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        saveDraft();
        state.putInt("tab", currentTab);
        state.putInt("settings_page", settingsPage);
        state.putInt("analytics_mode", analyticsMode);
        state.putBoolean("pending_moment", pendingMoment);
        state.putBoolean("create_heap_for_moment", createHeapForMoment);
        history.saveState(state);
        journals.saveState(state);
        backup.saveState(state);
        exports.saveState(state);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { gate.close(); backup.close(); exports.close(); weather.close(); updates.close(); analysis.close(); store.close(); super.onDestroy(); }

    private void showOnboarding() {
        settingsPage = SettingsScreen.NONE;
        journalUnlocked = false;
        backupUnlocked = false;
        gate.lock();
        hideKeyboard();
        final int step = onboarding.step();
        if (step == OnboardingState.WELCOME) { showWelcome(); return; }
        if (step <= 4) showCheckIn();
        else showTourPreview(step);
        View root = findViewById(R.id.app_root);
        // A modal overlay also hides the underlying controls from TalkBack navigation.
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ((LinearLayout) root).setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        int[] targets = {R.id.checkin_tab, R.id.emotion_section, R.id.writing_section,
                R.id.weather_summary, R.id.journal_tab, R.id.journal_add, R.id.history_tab};
        View target = step < targets.length ? findViewById(targets[step]) : null;
        if (step == 1 || step == 2) {
            LinearLayout tourPage = page;
            // Run before drawing, after section sizes and window insets are available.
            root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (target != null && page == tourPage && onboarding.step() == step) {
                        ScrollView scroll = (ScrollView) tourPage.getParent();
                        android.graphics.Rect rect = new android.graphics.Rect();
                        target.getDrawingRect(rect); tourPage.offsetDescendantRectToMyCoords(target, rect);
                        scroll.scrollTo(0, Math.max(0, rect.top - dp(20)));
                    }
                    return true;
                }
            });
        }
        TourOverlay overlay = new TourOverlay(this, target, step, () -> {
            if (onboarding.step() != step) return; // Ignore a stale or double-tapped Next.
            onboarding.next();
            if (onboarding.active()) showOnboarding(); else finishOnboarding();
        }, this::finishOnboarding);
        addContentView(overlay, new android.view.ViewGroup.LayoutParams(-1, -1));
        overlay.requestFocus();
    }

    private void showWelcome() {
        // Reuse the app frame's insets, but remove navigation for this first-launch page.
        frame(CHECK_IN);
        LinearLayout root = findViewById(R.id.app_root);
        root.removeViewAt(2); root.removeViewAt(0);
        page.removeAllViews();
        add(page, text(getString(R.string.welcome_eyebrow), 11, PURPLE, true), 16);
        TextView heading = text(getString(R.string.welcome_title), 38, INK, false);
        heading.setId(R.id.welcome_title); heading.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        add(page, heading, 24);
        add(page, text(getString(R.string.welcome_overview), 17, MUTED, false), 16);
        LinearLayout purpose = card(Color.rgb(237, 230, 241));
        add(purpose, text(getString(R.string.welcome_checkin), 18, INK, true), 0);
        add(purpose, text(getString(R.string.welcome_journal), 16, INK, false), 18);
        add(purpose, text(getString(R.string.welcome_patterns), 16, INK, false), 18);
        add(page, purpose, 28);
        add(page, text(getString(R.string.welcome_privacy), 13, MUTED, false), 22);
        LinearLayout actions = column(); actions.setPadding(dp(24), dp(12), dp(24), dp(20));
        Button tour = button(getString(R.string.welcome_tour), true); tour.setId(R.id.welcome_tour);
        tour.setOnClickListener(v -> { onboarding.start(); showOnboarding(); }); add(actions, tour, 0);
        Button thanks = button(getString(R.string.welcome_thanks), false); thanks.setId(R.id.welcome_thanks);
        thanks.setOnClickListener(v -> finishOnboarding()); add(actions, thanks, 8);
        root.addView(actions);
    }

    /** Empty, inert previews never read journal data or change the authentication state. */
    private void showTourPreview(int step) {
        frame(step <= 6 ? JOURNAL : HISTORY);
        add(page, text(getString(R.string.tour_preview), 12, PURPLE, true), 0);
        TextView title = text(getString(step <= 6 ? R.string.tour_journal_title
                : step == 7 ? R.string.mood_graph : R.string.mood_analysis), 36, INK, false);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL)); add(page, title, 16);
        if (step <= 6) {
            add(page, text(getString(R.string.tour_preview_private), 15, MUTED, false), 12);
            Button plus = button("+", false); plus.setId(R.id.journal_add);
            plus.setContentDescription(getString(R.string.tour_add_title)); add(page, plus, 20);
            LinearLayout empty = card(Color.rgb(232, 237, 225));
            add(empty, text(getString(R.string.tour_preview_empty), 20, INK, true), 0);
            add(page, empty, 24);
        } else if (step == 7) {
            MoodChartView graph = new MoodChartView(this); graph.setId(R.id.mood_chart); add(page, graph, 24);
            add(page, text(getString(R.string.tour_preview_graph), 15, MUTED, false), 16);
        } else {
            for (int label : new int[]{R.string.tour_preview_weather, R.string.tour_preview_responses, R.string.tour_preview_time}) {
                LinearLayout section = card(Color.WHITE);
                add(section, text(getString(label), 18, INK, true), 0); add(page, section, 16);
            }
            add(page, text(getString(R.string.tour_preview_analysis), 15, MUTED, false), 16);
        }
    }

    private void finishOnboarding() {
        onboarding.finish();
        // Clear preview navigation and return to the user's first real check-in.
        analyticsMode = 0; settingsPage = SettingsScreen.NONE;
        showCheckIn();
        if (getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            reminders.onResume(); weather.onResume();
        }
    }

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
        view.setTextColor(Appearance.color(this, color));
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
        button.setTextColor(primary ? Appearance.onAccent(this) : Appearance.color(this, PURPLE));
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
        edit.setTextColor(Appearance.color(this, INK));
        edit.setHintTextColor(Appearance.color(this, MUTED));
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
        drawable.setColor(Appearance.color(this, color));
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
