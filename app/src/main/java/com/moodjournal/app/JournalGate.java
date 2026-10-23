package com.moodjournal.app;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.DigitsKeyListener;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Gate lifetime and individual attempts are separate so switching to PIN invalidates biometric callbacks. */
final class JournalGate {
    record Failure(String message, boolean usePin, boolean terminal) { }
    interface Biometrics {
        boolean available();
        void authenticate(Runnable success, Consumer<Failure> error);
        void cancel();
    }

    static Biometrics systemBiometrics(FragmentActivity activity) {
        return new Biometrics() {
            private BiometricPrompt prompt;
            public boolean available() {
                return BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        == BiometricManager.BIOMETRIC_SUCCESS;
            }
            public void authenticate(Runnable success, Consumer<Failure> error) {
                prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { success.run(); }
                    @Override public void onAuthenticationError(int code, CharSequence message) {
                        error.accept(new Failure(activity.getString(R.string.auth_biometric_unavailable),
                                code == BiometricPrompt.ERROR_NEGATIVE_BUTTON, true));
                    }
                    @Override public void onAuthenticationFailed() {
                        error.accept(new Failure(activity.getString(R.string.auth_biometric_not_recognized), false, false));
                    }
                });
                prompt.authenticate(new BiometricPrompt.PromptInfo.Builder().setTitle(activity.getString(R.string.auth_biometric_title))
                        .setSubtitle(activity.getString(R.string.auth_biometric_subtitle))
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .setNegativeButtonText(activity.getString(R.string.auth_use_pin)).build());
            }
            public void cancel() { if (prompt != null) { prompt.cancelAuthentication(); prompt = null; } }
        };
    }

    private final FragmentActivity activity;
    private final PinLock pinLock;
    private final Biometrics biometrics;
    private final Runnable unlocked;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation, attempt;
    private boolean closed, pinBusy;
    private EditText pin, confirm;
    private LinearLayout content;
    private TextView status;
    private Button biometricButton;
    private AlertDialog dialog;

    JournalGate(FragmentActivity activity, PinLock pinLock, Biometrics biometrics, Runnable unlocked) {
        this.activity = activity; this.pinLock = pinLock; this.biometrics = biometrics; this.unlocked = unlocked;
    }

    void show(LinearLayout page) {
        lock();
        content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        page.addView(content, new LinearLayout.LayoutParams(-1, -2));
        render(generation, true);
    }
    private void render(int current, boolean automatic) {
        content.removeAllViews(); pin = confirm = null; biometricButton = null;
        boolean setup = !pinLock.hasPin(), primary = !setup && pinLock.biometricPrimary();
        add(content, label(activity.getString(R.string.auth_eyebrow), 11, true), 0);
        TextView title = label(activity.getString(R.string.auth_locked_title), 32, false);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL)); add(content, title, 14);
        add(content, label(activity.getString(setup ? R.string.auth_setup_description
                : primary ? R.string.auth_biometric_description : R.string.auth_pin_description), 16, false), 12);
        status = label("", 14, false); status.setId(R.id.unlock_status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (setup) {
            Button create = button(R.string.auth_set_pin, R.id.unlock_submit, true, android.R.drawable.ic_lock_lock);
            create.setOnClickListener(v -> { if (active(current)) showPinDialog(current, true, false); }); add(content, create, 26);
        } else if (!primary) {
            pin = field(R.string.auth_pin_hint, R.id.unlock_pin); add(content, pin, 24);
            Button submit = button(R.string.auth_unlock, R.id.unlock_submit, true, android.R.drawable.ic_lock_lock);
            EditText input = pin;
            submit.setOnClickListener(v -> submitPin(current, input, null, submit, status, false, null)); add(content, submit, 14);
        }
        if (setup || primary || biometrics.available()) {
            biometricButton = button(R.string.use_biometrics, R.id.unlock_biometric, primary, R.drawable.ic_fingerprint);
            biometricButton.setEnabled(biometrics.available());
            biometricButton.setOnClickListener(v -> {
                if (!active(current)) return;
                if (setup) showPinDialog(current, true, true); else authenticate(current);
            }); add(content, biometricButton, setup || !primary ? 12 : 26);
        }
        if (primary) {
            Button fallback = button(R.string.auth_use_pin, R.id.unlock_use_pin, false, android.R.drawable.ic_lock_lock);
            fallback.setOnClickListener(v -> { if (active(current)) showPinDialog(current, false, false); }); add(content, fallback, 12);
        }
        add(content, status, 12);
        if ((setup || primary) && !biometrics.available()) add(content, label(activity.getString(R.string.auth_biometric_setup_needed), 13, false), 8);
        if (primary && automatic && biometrics.available()) {
            int scheduledAttempt = attempt;
            main.post(() -> {
                if (active(current) && attempt == scheduledAttempt && dialog == null) authenticate(current);
            });
        }
    }

    private void showPinDialog(int current, boolean setup, boolean biometricRoute) {
        if (!active(current) || pinBusy || dialog != null) return;
        attempt++; biometrics.cancel();
        if (biometricButton != null) biometricButton.setEnabled(biometrics.available());
        LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), 0, dp(24), dp(12));
        if (setup) add(form, label(activity.getString(biometricRoute ? R.string.auth_backup_first : R.string.auth_create_pin_description), 15, false), 0);
        pin = field(setup ? R.string.auth_choose_pin : R.string.auth_pin_hint, R.id.unlock_pin); add(form, pin, 12);
        confirm = setup ? field(R.string.auth_confirm_pin, R.id.confirm_pin) : null;
        if (confirm != null) add(form, confirm, 12);
        if (setup) add(form, label(activity.getString(R.string.auth_remember_pin), 13, false), 12);
        TextView feedback = label("", 14, false); feedback.setId(R.id.pin_dialog_status);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(form, feedback, 10);
        EditText input = pin, confirmation = confirm;
        AlertDialog popup = AppDialogs.builder(activity).setTitle(setup ? R.string.auth_set_pin_title : R.string.auth_enter_pin_title)
                .setView(form).setNegativeButton(R.string.auth_cancel, null)
                .setPositiveButton(setup ? R.string.auth_set_pin : R.string.auth_unlock, null).create();
        popup.setOnDismissListener(d -> {
            input.setText(""); if (confirmation != null) confirmation.setText("");
            if (dialog == popup) { dialog = null; attempt++; pinBusy = false; }
            if (pin == input) pin = confirm = null;
            if (biometricButton != null && generation == current) biometricButton.setEnabled(biometrics.available());
        });
        showSecure(popup);
        Button submit = popup.getButton(AlertDialog.BUTTON_POSITIVE); submit.setId(R.id.pin_dialog_submit);
        submit.setOnClickListener(v -> submitPin(current, input, confirmation, submit, feedback, setup, popup));
        popup.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        input.requestFocus();
    }
    private void submitPin(int current, EditText input, EditText confirmation, Button submit, TextView feedback,
                           boolean setup, AlertDialog popup) {
        if (!active(current) || pinBusy) return;
        if (popup != null && (dialog != popup || !popup.isShowing())) return;
        if (!sixDigits(input)) { feedback.setText(R.string.pin_six_digits); return; }
        if (setup && !TextUtils.equals(input.getText(), confirmation.getText())) {
            feedback.setText(R.string.pin_mismatch); confirmation.setText(""); confirmation.requestFocus(); return;
        }
        if (!setup && pinLock.remainingLockMillis() > 0) { feedback.setText(retryMessage()); input.setText(""); return; }
        final int token = ++attempt;
        // Invalidate an older biometric success before starting any PIN work.
        biometrics.cancel();
        char[] secret = new char[input.length()]; input.getText().getChars(0, input.length(), secret, 0);
        input.setText(""); if (confirmation != null) confirmation.setText("");
        pinBusy = true; submit.setEnabled(false); input.setEnabled(false);
        if (confirmation != null) confirmation.setEnabled(false);
        if (biometricButton != null) biometricButton.setEnabled(false);
        if (popup != null) { popup.setCancelable(false); popup.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false); }
        feedback.setText(setup ? R.string.auth_securing_pin : R.string.auth_checking_pin);
        worker.execute(() -> {
            boolean success = false; String message = "";
            try {
                if (setup) { pinLock.setPin(secret); success = true; }
                else { success = pinLock.verify(secret); message = pinLock.remainingLockMillis() > 0 ? retryMessage() : activity.getString(R.string.auth_incorrect_pin); }
            } catch (Exception e) { message = activity.getString(R.string.auth_unlock_failed); }
            finally { Arrays.fill(secret, '\0'); }
            boolean accepted = success; String result = message;
            main.post(() -> {
                if (!active(current) || token != attempt) return;
                pinBusy = false; submit.setEnabled(true); input.setEnabled(true);
                if (confirmation != null) confirmation.setEnabled(true);
                if (biometricButton != null) biometricButton.setEnabled(biometrics.available());
                if (popup != null) { popup.setCancelable(true); popup.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true); }
                if (accepted) {
                    if (popup != null) popup.dismiss();
                    acceptedPin(current);
                } else if (setup && pinLock.hasPin()) {
                    if (popup != null) popup.dismiss();
                    render(current, false); status.setText(R.string.auth_pin_saved_elsewhere);
                } else feedback.setText(result);
            });
        });
    }
    private void acceptedPin(int current) {
        if (!active(current)) return;
        if (!pinLock.biometricChoiceMade() && biometrics.available()) {
            render(current, false);
            TextView message = label(activity.getString(R.string.auth_biometric_opt_in), 16, false);
            message.setId(R.id.biometric_preference_message); message.setPadding(dp(24), 0, dp(24), dp(16));
            AlertDialog popup = AppDialogs.builder(activity).setTitle(R.string.auth_biometric_preference_title).setView(message)
                    .setPositiveButton(R.string.auth_biometric_yes, null).setNegativeButton(R.string.auth_biometric_no, null).create();
            int choiceAttempt = ++attempt;
            popup.setOnDismissListener(d -> { if (dialog == popup) { dialog = null; attempt++; } });
            showSecure(popup);
            popup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> chooseBiometrics(current, choiceAttempt, popup, true, message));
            popup.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> chooseBiometrics(current, choiceAttempt, popup, false, message));
        } else finish(current);
    }
    private void chooseBiometrics(int current, int token, AlertDialog popup, boolean prefer, TextView feedback) {
        if (!active(current) || attempt != token || dialog != popup || !popup.isShowing()) return;
        try { pinLock.setBiometricPrimary(prefer); finish(current); }
        catch (java.security.GeneralSecurityException e) { feedback.setText(R.string.auth_preference_failed); }
    }
    private void authenticate(int current) {
        if (!active(current) || pinBusy || dialog != null) return;
        if (!biometrics.available()) { status.setText(R.string.auth_biometric_unavailable); return; }
        int token = ++attempt;
        biometrics.cancel();
        biometricButton.setEnabled(false);
        try {
            biometrics.authenticate(() -> {
                if (active(current) && token == attempt) finish(current);
            }, failure -> {
                if (!active(current) || token != attempt) return;
                status.setText(failure.message());
                if (failure.terminal()) {
                    attempt++; biometricButton.setEnabled(biometrics.available());
                    if (failure.usePin()) showPinDialog(current, false, false);
                }
            });
        } catch (RuntimeException e) {
            if (active(current) && token == attempt) { attempt++; biometricButton.setEnabled(biometrics.available()); status.setText(R.string.auth_biometric_unavailable); }
        }
    }
    private void finish(int current) { if (active(current)) { lock(); unlocked.run(); } }
    private boolean active(int current) {
        return !closed && generation == current && activity.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
    }
    private void showSecure(AlertDialog popup) {
        dialog = popup; popup.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); popup.show(); AppDialogs.decorate(popup);
    }
    void lock() {
        generation++; attempt++; pinBusy = false;
        biometrics.cancel();
        if (pin != null) pin.setText(""); if (confirm != null) confirm.setText("");
        if (dialog != null) {
            AlertDialog old = dialog; dialog = null;
            View focus = old.getCurrentFocus();
            if (focus != null) ((InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
            old.dismiss();
        }
        pin = confirm = null; biometricButton = null;
    }
    void close() { closed = true; lock(); worker.shutdown(); }
    private String retryMessage() {
        int seconds = (int) Math.max(1, (pinLock.remainingLockMillis() + 999) / 1000);
        return activity.getResources().getQuantityString(R.plurals.auth_retry_pin, seconds, seconds);
    }
    private boolean sixDigits(EditText input) {
        if (input.length() != 6) return false;
        for (int i = 0; i < input.length(); i++) if (input.getText().charAt(i) < '0' || input.getText().charAt(i) > '9') return false;
        return true;
    }
    private TextView label(String value, int size, boolean accent) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size); text.setLineSpacing(dp(4), 1);
        text.setTextColor(Appearance.color(activity, accent ? 0xFF705180 : 0xFF312B35)); return text;
    }
    private EditText field(int hint, int id) {
        EditText edit = new EditText(activity); edit.setId(id); edit.setHint(hint); edit.setContentDescription(activity.getString(hint));
        edit.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
        edit.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        edit.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)}); edit.setSaveEnabled(false);
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        edit.setMinHeight(dp(56)); edit.setTextSize(18); edit.setTextColor(Appearance.color(activity, 0xFF312B35));
        edit.setHintTextColor(Appearance.color(activity, 0xFF6D6470)); edit.setPadding(dp(16), dp(14), dp(16), dp(14));
        edit.setBackground(shape(0xFFF5F0F7, 14)); return edit;
    }
    private Button button(int label, int id, boolean primary, int icon) {
        Button button = new Button(activity); button.setId(id); button.setText(label); button.setAllCaps(false); button.setTextSize(16);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); button.setMinHeight(dp(60)); button.setMinimumHeight(dp(60));
        button.setPadding(dp(18), dp(14), dp(18), dp(14)); button.setMinimumWidth(0); button.setMinWidth(0); button.setBackgroundTintList(null);
        int ink = primary ? Appearance.onAccent(activity) : Appearance.color(activity, 0xFF705180);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Appearance.color(activity, 0xFF6D6470), ink}));
        StateListDrawable surface = new StateListDrawable();
        surface.addState(new int[]{-android.R.attr.state_enabled}, shape(0xFFEDE6F1, 18));
        surface.addState(new int[]{}, shape(primary ? 0xFF705180 : 0xFFEDE6F1, 18));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22705180), surface, shape(0xFFFFFFFF, 18)));
        Drawable symbol = activity.getDrawable(icon).mutate(); symbol.setTintList(button.getTextColors()); symbol.setBounds(0, 0, dp(24), dp(24));
        button.setCompoundDrawablesRelative(symbol, null, null, null); button.setCompoundDrawablePadding(dp(12)); return button;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(Appearance.color(activity, color)); shape.setCornerRadius(dp(radius)); return shape;
    }
    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(child, params);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
