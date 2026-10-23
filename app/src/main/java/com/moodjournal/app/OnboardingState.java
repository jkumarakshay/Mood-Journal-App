package com.moodjournal.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/** Installation-local progress; never included in journal backups. */
final class OnboardingState {
    static final String PREFS = "onboarding";
    static final int WELCOME = -1, COMPLETE = 9;
    private final SharedPreferences prefs;

    OnboardingState(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains("step")) {
            // An update should not interrupt an existing user's journal session.
            boolean existing = context.getDatabasePath("journal.db").exists();
            for (String name : new String[]{"draft", "journal_lock", "appearance", "reminder", "weather"})
                existing |= !context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty();
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                existing |= info.firstInstallTime > 0 && info.lastUpdateTime > info.firstInstallTime;
            } catch (PackageManager.NameNotFoundException ignored) { /* Our own package is normally present. */ }
            set(existing ? COMPLETE : WELCOME);
        }
    }

    int step() { return Math.max(WELCOME, Math.min(COMPLETE, prefs.getInt("step", WELCOME))); }
    boolean active() { return step() != COMPLETE; }
    void start() { set(0); }
    void next() { if (step() >= 0 && step() < COMPLETE) set(step() + 1); }
    void finish() { set(COMPLETE); }
    private void set(int step) { prefs.edit().putInt("step", step).apply(); }
}
