package com.moodjournal.app;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
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

/** All asynchronous results are scoped to the current, visible unlock attempt. */
final class JournalGate {
    interface Biometrics {
        boolean available();
        void authenticate(Runnable success, Consumer<String> error);
        void cancel();
    }

    static Biometrics systemBiometrics(FragmentActivity activity) {
        return new Biometrics() {
            private BiometricPrompt prompt;
            public boolean available() {
                return BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        == BiometricManager.BIOMETRIC_SUCCESS;
            }
            public void authenticate(Runnable success, Consumer<String> error) {
                prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { success.run(); }
                    @Override public void onAuthenticationError(int code, CharSequence message) { error.accept("Biometric unlock was cancelled or unavailable. You can use your PIN."); }
                    @Override public void onAuthenticationFailed() { error.accept("Biometric not recognized. Try again or use your PIN."); }
                });
                prompt.authenticate(new BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Daily Pause")
                        .setSubtitle("Your journal and mood history stay private")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .setNegativeButtonText("Use PIN").build());
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
    private int generation;
    private EditText pin, confirm;

    JournalGate(FragmentActivity activity, PinLock pinLock, Biometrics biometrics, Runnable unlocked) {
        this.activity = activity;
        this.pinLock = pinLock;
        this.biometrics = biometrics;
        this.unlocked = unlocked;
    }

    void show(LinearLayout page) {
        lock();
        final int current = generation;
        boolean setup = !pinLock.hasPin();
        page.addView(label("Your private space is locked", 28));
        page.addView(label(setup ? "Create a six-digit PIN to protect your journal and mood history. You’ll confirm it below."
                : "Enter your PIN to open your journal or mood history.", 16));
        pin = field(setup ? "Choose a six-digit PIN" : "Six-digit PIN", R.id.unlock_pin);
        page.addView(pin);
        confirm = null;
        if (setup) {
            confirm = field("Confirm your PIN", R.id.confirm_pin);
            page.addView(confirm);
            page.addView(label("Remember this PIN. It cannot be recovered. Clearing app data to reset it also deletes your journal.", 13));
        }
        TextView status = label("", 14);
        status.setId(R.id.unlock_status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        Button submit = new Button(activity);
        submit.setId(R.id.unlock_submit);
        submit.setText(setup ? "Set PIN and unlock" : "Unlock");
        submit.setAllCaps(false);
        page.addView(submit);
        submit.setOnClickListener(v -> {
            if (generation != current) return;
            if (pin.length() != 6) { status.setText(R.string.pin_six_digits); return; }
            if (setup && !pin.getText().toString().equals(confirm.getText().toString())) {
                status.setText(R.string.pin_mismatch);
                confirm.setText("");
                return;
            }
            if (!setup && pinLock.remainingLockMillis() > 0) { status.setText(retryMessage()); pin.setText(""); return; }
            char[] attempt = new char[pin.length()];
            pin.getText().getChars(0, pin.length(), attempt, 0);
            pin.setText("");
            if (confirm != null) confirm.setText("");
            submit.setEnabled(false);
            status.setText(setup ? "Securing your PIN…" : "Checking PIN…");
            worker.execute(() -> {
                boolean success = false;
                String message = "";
                try {
                    if (setup) { pinLock.setPin(attempt); success = true; }
                    else { success = pinLock.verify(attempt); message = pinLock.remainingLockMillis() > 0 ? retryMessage() : "That PIN wasn’t correct. Try again."; }
                } catch (Exception e) { message = "Couldn’t unlock your journal. Please try again. Your entries have not been removed."; }
                finally { Arrays.fill(attempt, '\0'); }
                boolean accepted = success;
                String result = message;
                main.post(() -> {
                    if (generation != current) return;
                    submit.setEnabled(true);
                    if (accepted) { lock(); unlocked.run(); }
                    else status.setText(result);
                });
            });
        });
        if (!setup && biometrics.available()) {
            Button biometric = new Button(activity);
            biometric.setId(R.id.unlock_biometric);
            biometric.setText(R.string.use_biometrics);
            biometric.setAllCaps(false);
            page.addView(biometric);
            biometric.setOnClickListener(v -> {
                if (generation != current) return;
                biometric.setEnabled(false);
                biometrics.authenticate(() -> {
                    if (generation != current) return;
                    lock();
                    unlocked.run();
                }, message -> {
                    if (generation == current) { status.setText(message); biometric.setEnabled(true); }
                });
            });
        }
    }

    void lock() {
        generation++;
        biometrics.cancel();
        if (pin != null) pin.setText("");
        if (confirm != null) confirm.setText("");
    }

    void close() { lock(); worker.shutdown(); }

    private String retryMessage() { return "Too many attempts. Try again in " + Math.max(1, (pinLock.remainingLockMillis() + 999) / 1000) + " seconds, or use biometrics."; }

    private TextView label(String value, int size) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Appearance.color(activity, Color.rgb(49, 43, 53)));
        text.setPadding(0, dp(12), 0, dp(12));
        return text;
    }

    private EditText field(String hint, int id) {
        EditText edit = new EditText(activity);
        edit.setId(id);
        edit.setHint(hint);
        edit.setContentDescription(hint);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        edit.setSaveEnabled(false);
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        edit.setMinHeight(dp(56));
        return edit;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
