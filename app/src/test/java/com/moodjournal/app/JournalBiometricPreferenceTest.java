package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class JournalBiometricPreferenceTest {
    private Context context;
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("journal_lock", 0).edit().clear().commit();
    }

    @Test public void setupButtonsOpenSecureMaskedPopupAndCancelDoesNotCreatePin() {
        try (Session s = new Session()) {
            assertNull(s.page.findViewById(R.id.unlock_pin));
            Button set = s.page.findViewById(R.id.unlock_submit);
            assertEquals("Set PIN and unlock", set.getText().toString());
            assertNotNull(set.getCompoundDrawablesRelative()[0]);
            assertNotNull(s.page.findViewById(R.id.unlock_biometric));
            AlertDialog popup = s.open(R.id.unlock_submit);
            EditText pin = popup.findViewById(R.id.unlock_pin), confirm = popup.findViewById(R.id.confirm_pin);
            assertTrue((popup.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            for (EditText input : new EditText[]{pin, confirm}) {
                assertFalse(input.isSaveEnabled());
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, input.getImportantForAutofill());
                assertTrue(input.getTransformationMethod() instanceof PasswordTransformationMethod);
                input.setText("642081");
            }
            Button stale = popup.getButton(AlertDialog.BUTTON_POSITIVE);
            popup.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, pin.length()); assertEquals(0, confirm.length());
            stale.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(s.lock.hasPin()); assertEquals(0, s.unlocks.get());
        }
    }

    @Test public void biometricSetupStillRequiresConfirmedPinThenExplicitPreference() {
        try (Session s = new Session()) {
            AlertDialog form = s.open(R.id.unlock_biometric);
            assertEquals(0, s.bio.prompts);
            s.enter(form, "123", "123");
            assertFalse(s.lock.hasPin());
            s.enter(form, "642081", "123456");
            assertFalse(s.lock.hasPin()); assertEquals(0, s.unlocks.get());
            s.enter(form, "642081", "642081");
            AlertDialog choice = s.awaitChoice();
            assertFalse(s.lock.biometricChoiceMade()); assertEquals(0, s.unlocks.get());
            assertFalse(form.isShowing());
            assertTrue((choice.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            choice.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(new PinLock(context).biometricPrimary()); assertEquals(1, s.unlocks.get());
            assertEquals(0, s.bio.prompts);
            s.show(); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, s.bio.prompts); assertNull(s.page.findViewById(R.id.unlock_pin));
            assertEquals("Sign in using PIN instead", ((TextView)s.page.findViewById(R.id.unlock_use_pin)).getText().toString());
            s.bio.success.run(); s.bio.success.run(); assertEquals(2, s.unlocks.get());
        }
    }

    @Test public void decliningBiometricsPersistsPinFirstAndDoesNotAskAgain() {
        try (Session s = new Session()) {
            s.enter(s.open(R.id.unlock_submit), "642081", "642081");
            s.awaitChoice().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            assertTrue(s.lock.biometricChoiceMade()); assertFalse(s.lock.biometricPrimary());
            s.show(); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(s.page.findViewById(R.id.unlock_pin)); assertEquals(0, s.bio.prompts);
            ((EditText)s.page.findViewById(R.id.unlock_pin)).setText("642081");
            s.page.findViewById(R.id.unlock_submit).performClick();
            JournalTest.await(() -> s.unlocks.get() == 2);
            assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        }
    }

    @Test public void existingPinGetsOfferOnlyAfterSuccessfulVerificationWithoutReplacingVerifier() throws Exception {
        new PinLock(context).setPin("642081".toCharArray());
        String verifier = context.getSharedPreferences("journal_lock", 0).getString("hash", "");
        try (Session s = new Session()) {
            assertEquals(0, s.bio.prompts);
            ((EditText)s.page.findViewById(R.id.unlock_pin)).setText("000000");
            s.page.findViewById(R.id.unlock_submit).performClick();
            JournalTest.await(() -> s.page.findViewById(R.id.unlock_submit).isEnabled());
            assertFalse(s.lock.biometricChoiceMade()); assertEquals(0, s.unlocks.get());
            ((EditText)s.page.findViewById(R.id.unlock_pin)).setText("642081");
            s.page.findViewById(R.id.unlock_submit).performClick();
            s.awaitChoice().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals(verifier, context.getSharedPreferences("journal_lock", 0).getString("hash", ""));
            assertTrue(s.lock.biometricPrimary()); assertEquals(1, s.unlocks.get());
        }
    }

    @Test public void unavailableBiometricsAllowSetupWithoutSavingAnUnrequestedPreference() {
        try (Session s = new Session(false)) {
            assertFalse(s.page.findViewById(R.id.unlock_biometric).isEnabled());
            s.enter(s.open(R.id.unlock_submit), "642081", "642081");
            JournalTest.await(() -> s.unlocks.get() == 1);
            assertFalse(s.lock.biometricChoiceMade()); assertTrue(s.lock.hasPin());
        }
    }

    @Test public void unavailablePrimaryBiometricsKeepPinFallbackAndPreference() throws Exception {
        primary();
        try (Session s = new Session(false)) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, s.bio.prompts);
            assertFalse(s.page.findViewById(R.id.unlock_biometric).isEnabled());
            s.enter(s.open(R.id.unlock_use_pin), "642081", null);
            JournalTest.await(() -> s.unlocks.get() == 1); assertTrue(s.lock.biometricPrimary());
        }
    }

    @Test public void pinChoiceBeforeQueuedAutomaticPromptCancelsThatPrompt() throws Exception {
        primary();
        try (Session s = new Session()) {
            AlertDialog popup = s.open(R.id.unlock_use_pin);
            popup.dismiss(); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, s.bio.prompts); assertEquals(0, s.unlocks.get());
        }
    }

    @Test public void negativeBiometricActionOpensPinAndRejectsOldSuccess() throws Exception {
        primary();
        try (Session s = new Session()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Runnable old = s.bio.success;
            s.bio.error.accept(new JournalGate.Failure("Use PIN", true, true));
            AlertDialog popup = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(popup.findViewById(R.id.unlock_pin)); assertNull(popup.findViewById(R.id.confirm_pin));
            old.run(); assertEquals(0, s.unlocks.get());
            s.enter(popup, "642081", null); JournalTest.await(() -> s.unlocks.get() == 1);
            old.run(); assertEquals(1, s.unlocks.get()); assertTrue(s.lock.biometricPrimary());
        }
    }

    @Test public void failedScanKeepsPromptActiveButTerminalCancelAllowsRetry() throws Exception {
        primary();
        try (Session s = new Session()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            s.bio.error.accept(new JournalGate.Failure("Try again", false, false));
            assertFalse(s.page.findViewById(R.id.unlock_biometric).isEnabled());
            assertEquals(0, s.unlocks.get());
            Runnable old = s.bio.success;
            s.bio.error.accept(new JournalGate.Failure("Cancelled", false, true));
            assertTrue(s.page.findViewById(R.id.unlock_biometric).isEnabled()); old.run(); assertEquals(0, s.unlocks.get());
            s.page.findViewById(R.id.unlock_biometric).performClick(); assertEquals(2, s.bio.prompts);
            s.bio.success.run(); assertEquals(1, s.unlocks.get());
        }
    }

    @Test public void dismissedPreferenceCannotBeUsedByLateButtonCallback() {
        try (Session s = new Session()) {
            s.enter(s.open(R.id.unlock_submit), "642081", "642081");
            AlertDialog popup = s.awaitChoice(); Button stale = popup.getButton(AlertDialog.BUTTON_POSITIVE);
            popup.dismiss(); stale.performClick();
            assertFalse(s.lock.biometricChoiceMade()); assertEquals(0, s.unlocks.get());
            assertNotNull(s.page.findViewById(R.id.unlock_pin));
        }
    }

    @Test public void lockingClosesAndClearsPinAndPreferencePopups() throws Exception {
        try (Session s = new Session()) {
            AlertDialog popup = s.open(R.id.unlock_submit);
            EditText input = popup.findViewById(R.id.unlock_pin); input.setText("642081");
            s.gate.lock(); assertFalse(popup.isShowing()); assertEquals(0, input.length());
            assertFalse(s.lock.hasPin());
            s.show(); s.enter(s.open(R.id.unlock_submit), "642081", "642081");
            AlertDialog choice = s.awaitChoice();
            s.gate.lock(); choice.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertFalse(choice.isShowing()); assertFalse(s.lock.biometricChoiceMade()); assertEquals(0, s.unlocks.get());
        }
    }

    @Test public void backgroundBiometricSuccessCannotUnlockAndNextVisitTriesAgain() throws Exception {
        primary();
        try (Session s = new Session()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Runnable old = s.bio.success;
            s.controller.pause().stop(); old.run(); assertEquals(0, s.unlocks.get());
            s.gate.lock(); s.controller.start().resume().visible(); s.show();
            Shadows.shadowOf(Looper.getMainLooper()).idle(); old.run(); assertEquals(0, s.unlocks.get());
            assertEquals(2, s.bio.prompts); s.bio.success.run(); assertEquals(1, s.unlocks.get());
        }
    }

    @Test public void pinRetryDelayRemainsEffectiveInPopupAndBiometricsStillWork() throws Exception {
        primary();
        context.getSharedPreferences("journal_lock", 0).edit().putInt("failures", 5)
                .putLong("retry_after", System.currentTimeMillis() + 300000).commit();
        try (Session s = new Session()) {
            AlertDialog popup = s.open(R.id.unlock_use_pin);
            s.enter(popup, "642081", null);
            assertTrue(((TextView)popup.findViewById(R.id.pin_dialog_status)).getText().toString().contains("Try again"));
            assertEquals(0, s.unlocks.get()); assertEquals(0, ((EditText)popup.findViewById(R.id.unlock_pin)).length());
            popup.dismiss(); Shadows.shadowOf(Looper.getMainLooper()).idle();
            s.page.findViewById(R.id.unlock_biometric).performClick();
            s.bio.success.run(); assertEquals(1, s.unlocks.get());
        }
    }

    @Test public void biometricPreferenceCannotBeStoredWithoutBackupPin() {
        PinLock lock = new PinLock(context);
        assertThrows(java.security.GeneralSecurityException.class, () -> lock.setBiometricPrimary(true));
        assertFalse(lock.biometricPrimary()); assertFalse(lock.biometricChoiceMade());
    }

    @Test @Config(sdk = 26) public void androidEightSupportsPinPopupPreferenceAndBiometricFallback() {
        try (Session s = new Session()) {
            s.enter(s.open(R.id.unlock_submit), "642081", "642081");
            s.awaitChoice().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            s.show(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, s.bio.prompts);
            s.enter(s.open(R.id.unlock_use_pin), "642081", null);
            JournalTest.await(() -> s.unlocks.get() == 2); assertTrue(s.lock.biometricPrimary());
        }
    }

    private void primary() throws Exception {
        PinLock lock = new PinLock(context); lock.setPin("642081".toCharArray()); lock.setBiometricPrimary(true);
    }
    private final class Session implements AutoCloseable {
        final ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        final PinLock lock = new PinLock(context);
        final FakeBiometrics bio = new FakeBiometrics();
        final AtomicInteger unlocks = new AtomicInteger();
        final LinearLayout page = new LinearLayout(controller.get());
        final JournalGate gate = new JournalGate(controller.get(), lock, bio, unlocks::incrementAndGet);
        Session() { this(true); }
        Session(boolean available) { bio.available = available; show(); }
        void show() { page.removeAllViews(); gate.show(page); }
        AlertDialog open(int id) { page.findViewById(id).performClick(); return ShadowAlertDialog.getLatestAlertDialog(); }
        void enter(AlertDialog popup, String value, String confirmation) {
            ((EditText)popup.findViewById(R.id.unlock_pin)).setText(value);
            if (confirmation != null) ((EditText)popup.findViewById(R.id.confirm_pin)).setText(confirmation);
            popup.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        }
        AlertDialog awaitChoice() {
            JournalTest.await(() -> {
                AlertDialog popup = ShadowAlertDialog.getLatestAlertDialog();
                return popup != null && popup.isShowing() && popup.findViewById(R.id.biometric_preference_message) != null;
            });
            return ShadowAlertDialog.getLatestAlertDialog();
        }
        @Override public void close() { gate.close(); controller.close(); }
    }
    private static final class FakeBiometrics implements JournalGate.Biometrics {
        boolean available = true;
        int prompts;
        Runnable success;
        Consumer<JournalGate.Failure> error;
        public boolean available() { return available; }
        public void authenticate(Runnable ok, Consumer<JournalGate.Failure> failure) { prompts++; success = ok; error = failure; }
        public void cancel() { }
    }
}
