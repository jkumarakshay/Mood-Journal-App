package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
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
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class JournalSecurityTest {
    private Context context;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("journal.db");
        context.getSharedPreferences("draft", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("journal_lock", Context.MODE_PRIVATE).edit().clear().commit();
        try (JournalStore store = new JournalStore(context)) { store.add(3, "Private journal words", "Private feelings", ""); }
    }

    @Test public void firstVisitRequiresPinConfirmationBeforeShowingAnyEntries() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get();
            a.findViewById(R.id.journal_tab).performClick();
            assertFalse(containsText(a.getWindow().getDecorView(), "Private journal words"));
            ((EditText) a.findViewById(R.id.unlock_pin)).setText("642081");
            ((EditText) a.findViewById(R.id.confirm_pin)).setText("123456");
            a.findViewById(R.id.unlock_submit).performClick();
            assertFalse(new PinLock(context).hasPin());
            assertFalse(containsText(a.getWindow().getDecorView(), "Private journal words"));
            JournalTest.unlockJournal(a);
            assertTrue(containsText(a.getWindow().getDecorView(), "Private journal words"));
        }
    }

    @Test public void wrongPinCannotUnlockAndEveryTabVisitLocksAgain() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get();
            a.findViewById(R.id.journal_tab).performClick();
            JournalTest.unlockJournal(a);
            a.findViewById(R.id.checkin_tab).performClick();
            a.findViewById(R.id.journal_tab).performClick();
            assertNull(a.findViewById(R.id.confirm_pin));
            assertFalse(containsText(a.getWindow().getDecorView(), "Private journal words"));
            ((EditText) a.findViewById(R.id.unlock_pin)).setText("111111");
            a.findViewById(R.id.unlock_submit).performClick();
            JournalTest.await(() -> a.findViewById(R.id.unlock_submit).isEnabled());
            assertFalse(containsText(a.getWindow().getDecorView(), "Private journal words"));
            assertTrue(((TextView) a.findViewById(R.id.unlock_status)).getText().toString().contains("wasn’t correct"));
            JournalTest.unlockJournal(a);
            assertTrue(containsText(a.getWindow().getDecorView(), "Private journal words"));
        }
    }

    @Test public void backgroundingHidesEntriesAndClosesPrivateDialogs() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get();
            a.findViewById(R.id.journal_tab).performClick();
            JournalTest.unlockJournal(a);
            findButton(a.getWindow().getDecorView(), "Read this entry  →").performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());
            assertTrue((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            c.pause().stop();
            assertFalse(dialog.isShowing());
            assertFalse(containsText(a.getWindow().getDecorView(), "Private journal words"));
            c.start().resume().visible();
            assertNotNull(a.findViewById(R.id.unlock_pin));
            JournalTest.unlockJournal(a);
            assertTrue(containsText(a.getWindow().getDecorView(), "Private journal words"));
        }
    }

    @Test public void recreationDoesNotRestoreUnlockedSession() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get();
            a.findViewById(R.id.journal_tab).performClick();
            JournalTest.unlockJournal(a);
            c.recreate();
            assertNotNull(c.get().findViewById(R.id.unlock_pin));
            assertFalse(containsText(c.get().getWindow().getDecorView(), "Private journal words"));
        }
    }

    @Test public void savingEntryDoesNotBypassGateAndSecureFlagIsSet() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = c.get();
            JournalTest.chooseMood(a, R.id.mood_3);
            a.findViewById(R.id.save_entry).performClick();
            assertNotNull(a.findViewById(R.id.confirm_pin));
            assertFalse(containsText(a.getWindow().getDecorView(), "Private journal words"));
            assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertFalse(a.findViewById(R.id.unlock_pin).isSaveEnabled());
            assertFalse(a.findViewById(R.id.confirm_pin).isSaveEnabled());
        }
    }

    @Test public void verifierUsesSaltAndPersistentRetryLimits() throws Exception {
        PinLock lock = new PinLock(context);
        lock.setPin("642081".toCharArray());
        SharedPreferences prefs = context.getSharedPreferences("journal_lock", Context.MODE_PRIVATE);
        String firstHash = prefs.getString("hash", "");
        assertNotEquals("642081", firstHash);
        assertFalse(prefs.getAll().toString().contains("642081"));
        for (int i = 0; i < 5; i++) assertFalse(lock.verify("000000".toCharArray()));
        PinLock reopened = new PinLock(context);
        assertTrue(reopened.remainingLockMillis() > 0);
        assertFalse(reopened.verify("642081".toCharArray()));
        prefs.edit().putLong("retry_after", System.currentTimeMillis() - 1).commit();
        assertTrue(reopened.verify("642081".toCharArray()));
        assertEquals(0, prefs.getInt("failures", 0));
        prefs.edit().clear().commit();
        new PinLock(context).setPin("642081".toCharArray());
        assertNotEquals(firstHash, prefs.getString("hash", ""));
    }

    @Test public void corruptedVerifierFailsClosedAndCannotBeReplaced() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("journal_lock", Context.MODE_PRIVATE);
        prefs.edit().putString("salt", "invalid").commit();
        PinLock lock = new PinLock(context);
        assertTrue(lock.hasPin());
        assertThrows(java.security.GeneralSecurityException.class, () -> lock.verify("642081".toCharArray()));
        assertThrows(java.security.GeneralSecurityException.class, () -> lock.setPin("642081".toCharArray()));
    }

    @Test public void biometricsMustSucceedAndStaleCallbacksCannotUnlock() throws Exception {
        new PinLock(context).setPin("642081".toCharArray());
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            FakeBiometrics biometrics = new FakeBiometrics();
            AtomicInteger unlocks = new AtomicInteger();
            JournalGate gate = new JournalGate(c.get(), new PinLock(context), biometrics, unlocks::incrementAndGet);
            try {
                LinearLayout page = new LinearLayout(c.get());
                gate.show(page);
                page.findViewById(R.id.unlock_biometric).performClick();
                biometrics.error.accept("Not recognized");
                assertEquals(0, unlocks.get());
                page.findViewById(R.id.unlock_biometric).performClick();
                Runnable staleSuccess = biometrics.success;
                gate.lock();
                staleSuccess.run();
                assertEquals(0, unlocks.get());
                page.removeAllViews();
                gate.show(page);
                page.findViewById(R.id.unlock_biometric).performClick();
                biometrics.success.run();
                assertEquals(1, unlocks.get());
                biometrics.success.run();
                assertEquals(1, unlocks.get());
            } finally { gate.close(); }
        }
    }

    private static final class FakeBiometrics implements JournalGate.Biometrics {
        Runnable success;
        Consumer<String> error;
        public boolean available() { return true; }
        public void authenticate(Runnable success, Consumer<String> error) { this.success = success; this.error = error; }
        public void cancel() {}
    }

    private boolean containsText(View root, String text) {
        if (root instanceof TextView view && view.getText().toString().contains(text)) return true;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++)
            if (containsText(group.getChildAt(i), text)) return true;
        return false;
    }

    private Button findButton(View root, String text) {
        if (root instanceof Button b && b.getText().toString().equals(text)) return b;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Button found = findButton(group.getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
}
