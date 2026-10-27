package com.moodjournal.app;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Full-screen account setup/recovery. It never reveals journals or grants a private-tab unlock. */
final class GoogleAccountScreen {
    private enum Mode { NONE, INITIAL, LINK_LOCAL, RECOVERY, NEW_PIN }
    private final FragmentActivity activity;
    private final GoogleAccountStore store;
    private final GoogleSignInSession signIn;
    private final PinLock pinLock;
    private final Consumer<LinearLayout> localGate;
    private final Runnable ready;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Mode mode;
    private GoogleAccountStore.Profile candidate;
    private long candidateExpires, resetExpires;
    private String expectedOwner, expectedPin;
    private int generation;
    private int message;
    private AlertDialog pinDialog;
    private EditText pin, confirmation;
    private boolean pinBusy;

    GoogleAccountScreen(FragmentActivity activity, GoogleAccountStore store, GoogleSignInSession signIn,
                        Consumer<LinearLayout> localGate, Runnable ready) {
        this.activity = activity; this.store = store; this.signIn = signIn; this.localGate = localGate; this.ready = ready;
        pinLock = new PinLock(activity); mode = store.linked() ? Mode.NONE : Mode.INITIAL;
    }
    boolean blocking() { return mode != Mode.NONE; }
    boolean awaitingLocalLink() { return mode == Mode.LINK_LOCAL; }
    void startRecovery() {
        if (!store.linked() || !pinLock.hasPin()) return;
        signIn.cancel(); generation++; mode = Mode.RECOVERY; candidate = null; message = 0;
        expectedOwner = store.identity(); expectedPin = pinLock.verifierIdentity(); render();
    }
    void render() {
        if (!blocking()) return;
        if (mode == Mode.NEW_PIN && pinDialog != null && pinDialog.isShowing()) return;
        LinearLayout root = column(); root.setId(R.id.account_screen); root.setBackgroundColor(Appearance.color(activity, 0xFFFAF7F2));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) { android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()); v.setPadding(i.left, i.top, i.right, i.bottom); }
            else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true); LinearLayout page = column(); page.setPadding(dp(24), dp(40), dp(24), dp(32));
        scroll.addView(page); root.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); activity.setContentView(root); root.requestApplyInsets();
        add(page, label(activity.getString(R.string.auth_eyebrow), 12, false), 0);
        TextView title = label(activity.getString(mode == Mode.INITIAL || mode == Mode.LINK_LOCAL ? R.string.google_welcome : R.string.google_recover_title), 34, true);
        title.setId(R.id.account_title); add(page, title, 24);
        add(page, label(activity.getString(mode == Mode.LINK_LOCAL ? R.string.google_link_existing
                : mode == Mode.INITIAL ? R.string.google_welcome_description : R.string.google_recover_description), 17, false), 18);
        if (mode == Mode.LINK_LOCAL) {
            add(page, label(candidate == null ? "" : candidate.email(), 15, false), 16);
            if (candidate == null || android.os.SystemClock.elapsedRealtime() >= candidateExpires) {
                candidate = null; mode = Mode.INITIAL; message = R.string.google_expired; render(); return;
            }
            localGate.accept(page);
        } else {
            Button google = action(R.string.google_sign_in, R.id.google_sign_in, true); google.setEnabled(signIn.configured() && !signIn.busy());
            google.setOnClickListener(v -> beginSignIn()); add(page, google, 28);
            if (!signIn.configured()) add(page, label(activity.getString(R.string.google_not_configured), 15, false), 16);
            if (signIn.busy()) add(page, label(activity.getString(R.string.google_checking), 15, false), 16);
        }
        if (message != 0) { TextView status = label(activity.getString(message), 15, false); status.setId(R.id.account_status); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(page, status, 18); }
        add(page, label(activity.getString(R.string.google_local_privacy), 14, false), 28);
        if (mode == Mode.RECOVERY || mode == Mode.NEW_PIN || mode == Mode.LINK_LOCAL) {
            Button back = action(R.string.google_back, R.id.account_back, false); back.setOnClickListener(v -> back()); add(page, back, 20);
        }
        if (mode == Mode.NEW_PIN) showNewPin();
    }
    private void beginSignIn() {
        if ((mode != Mode.INITIAL && mode != Mode.RECOVERY) || signIn.busy() || !resumed()) return;
        final Mode requested = mode; final int token = ++generation; message = 0;
        signIn.start(profile -> {
            if (!resumed() || generation != token || mode != requested) return;
            if (requested == Mode.RECOVERY) {
                if (!store.matches(profile) || !store.identity().equals(expectedOwner) || !pinLock.verifierIdentity().equals(expectedPin)) {
                    message = R.string.google_wrong_account; render(); return;
                }
                resetExpires = android.os.SystemClock.elapsedRealtime() + 120_000; mode = Mode.NEW_PIN; render();
            } else {
                candidate = profile; candidateExpires = android.os.SystemClock.elapsedRealtime() + 120_000;
                if (pinLock.hasPin()) { mode = Mode.LINK_LOCAL; render(); }
                else completeLink();
            }
        }, () -> { if (generation == token && mode == requested) { message = R.string.google_failed; render(); } });
        if (mode == Mode.INITIAL || mode == Mode.RECOVERY) render();
    }
    void completeLink() {
        if (!resumed() || candidate == null || (mode != Mode.INITIAL && mode != Mode.LINK_LOCAL)
                || android.os.SystemClock.elapsedRealtime() >= candidateExpires) {
            candidate = null; mode = Mode.INITIAL; message = R.string.google_expired; render(); return;
        }
        try { store.link(candidate); candidate = null; mode = Mode.NONE; generation++; ready.run(); }
        catch (java.security.GeneralSecurityException e) { candidate = null; mode = Mode.INITIAL; message = R.string.google_link_failed; render(); }
    }
    private void showNewPin() {
        LinearLayout form = column(); form.setPadding(dp(24), 0, dp(24), dp(12));
        add(form, label(activity.getString(R.string.google_new_pin_description), 15, false), 0);
        EditText input = pinField(R.string.auth_choose_pin, R.id.unlock_pin), confirm = pinField(R.string.auth_confirm_pin, R.id.confirm_pin);
        pin = input; confirmation = confirm; add(form, input, 12); add(form, confirm, 12);
        TextView feedback = label("", 14, false); feedback.setId(R.id.pin_dialog_status); add(form, feedback, 10);
        AlertDialog popup = AppDialogs.builder(activity).setTitle(R.string.google_new_pin_title).setView(form)
                .setNegativeButton(R.string.auth_cancel, null).setPositiveButton(R.string.google_save_pin, null).create();
        pinDialog = popup; popup.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        popup.setOnDismissListener(d -> {
            input.setText(""); confirm.setText("");
            if (pinDialog == popup) {
                pinDialog = null; pin = confirmation = null; pinBusy = false; generation++;
                if (mode == Mode.NEW_PIN) { mode = Mode.RECOVERY; resetExpires = 0; if (resumed()) render(); }
            }
        });
        popup.show(); AppDialogs.decorate(popup); Button save = popup.getButton(AlertDialog.BUTTON_POSITIVE); save.setId(R.id.pin_reset_save);
        save.setOnClickListener(v -> {
            if (!resumed() || pinDialog != popup || !popup.isShowing() || mode != Mode.NEW_PIN || pinBusy) return;
            if (android.os.SystemClock.elapsedRealtime() >= resetExpires) { message = R.string.google_expired; popup.dismiss(); return; }
            if (!input.getText().toString().matches("[0-9]{6}")) { feedback.setText(R.string.pin_six_digits); return; }
            if (!TextUtils.equals(input.getText(), confirm.getText())) { feedback.setText(R.string.pin_mismatch); confirm.setText(""); return; }
            char[] secret = new char[input.length()]; input.getText().getChars(0, input.length(), secret, 0); input.setText(""); confirm.setText("");
            int token = ++generation; pinBusy = true; input.setEnabled(false); confirm.setEnabled(false); save.setEnabled(false); feedback.setText(R.string.auth_securing_pin);
            worker.execute(() -> {
                PinLock.PreparedReset prepared = null;
                try { prepared = pinLock.prepareReset(secret, expectedPin); } catch (Exception ignored) { }
                finally { Arrays.fill(secret, '\0'); }
                PinLock.PreparedReset result = prepared;
                main.post(() -> {
                    try {
                        if (!resumed() || token != generation || mode != Mode.NEW_PIN || pinDialog != popup || !popup.isShowing()) return;
                        pinBusy = false;
                        if (result == null || android.os.SystemClock.elapsedRealtime() >= resetExpires || !store.linked() || !store.identity().equals(expectedOwner))
                            throw new java.security.GeneralSecurityException();
                        pinLock.commitReset(result); mode = Mode.NONE; generation++; popup.dismiss();
                        android.widget.Toast.makeText(activity, R.string.google_pin_saved, android.widget.Toast.LENGTH_LONG).show(); ready.run();
                    } catch (java.security.GeneralSecurityException e) {
                        feedback.setText(R.string.google_reset_failed); input.setEnabled(true); confirm.setEnabled(true); save.setEnabled(true);
                    } finally { if (result != null) result.close(); }
                });
            });
        });
        popup.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); input.requestFocus();
    }
    void pause() {
        if (mode == Mode.NEW_PIN) {
            generation++; resetExpires = 0; mode = Mode.RECOVERY;
            if (pin != null) pin.setText(""); if (confirmation != null) confirmation.setText("");
            if (pinDialog != null) pinDialog.dismiss(); pinBusy = false;
        }
    }
    void resume() { signIn.resume(); if (blocking()) render(); }
    void back() {
        if (mode == Mode.NEW_PIN && pinDialog != null) { pinDialog.dismiss(); return; }
        signIn.cancel(); generation++; candidate = null;
        if (mode == Mode.RECOVERY) { mode = Mode.NONE; ready.run(); }
        else if (mode == Mode.LINK_LOCAL) { mode = Mode.INITIAL; message = 0; render(); }
        else activity.finish();
    }
    void close() { generation++; signIn.close(); pause(); worker.shutdown(); }
    private boolean resumed() { return activity.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView label(String value, int size, boolean serif) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(Appearance.color(activity, 0xFF312B35)); if (serif) view.setTypeface(Typeface.create("serif", Typeface.NORMAL)); view.setLineSpacing(dp(4), 1); return view; }
    private Button action(int text, int id, boolean primary) {
        Button button = new Button(activity); button.setId(id); button.setText(text); button.setAllCaps(false); button.setTextSize(17); button.setMinHeight(dp(60));
        button.setMinimumWidth(0); button.setMinWidth(0); button.setPadding(dp(20), dp(16), dp(20), dp(16)); button.setBackgroundTintList(null);
        // Google branding uses a neutral surface; app-owned navigation uses the app palette.
        int surface = id == R.id.google_sign_in ? 0xFFFFFFFF : primary ? 0xFF705180 : 0xFFEDE6F1;
        GradientDrawable shape = new GradientDrawable(); shape.setColor(Appearance.color(activity, surface)); shape.setCornerRadius(dp(18));
        shape.setStroke(dp(1), Appearance.color(activity, 0xFFD9D0DD)); button.setBackground(shape);
        button.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Appearance.color(activity, 0xFF6D6470), Appearance.color(activity, id == R.id.google_sign_in ? 0xFF312B35 : 0xFF705180)}));
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22705180), shape, null));
        if (id == R.id.google_sign_in) {
            android.graphics.drawable.Drawable logo = activity.getDrawable(R.drawable.ic_google_g); logo.setBounds(0, 0, dp(24), dp(24));
            button.setCompoundDrawablesRelative(logo, null, null, null); button.setCompoundDrawablePadding(dp(12));
        }
        return button;
    }
    private EditText pinField(int hint, int id) {
        EditText field = new EditText(activity); field.setId(id); field.setHint(hint); field.setContentDescription(activity.getString(hint));
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        field.setSaveEnabled(false); field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); field.setMinHeight(dp(56)); return field;
    }
    private void add(LinearLayout parent, View child, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(child, p); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
