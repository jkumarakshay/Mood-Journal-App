package com.moodjournal.app;

/** Most feature tests start after the first-launch tutorial has been acknowledged. */
public final class ReturningUserApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        linkTestAccount(this);
        getSharedPreferences(UpdateController.PREFS, MODE_PRIVATE).edit().putBoolean("automatic", false).commit();
        getSharedPreferences(OnboardingState.PREFS, MODE_PRIVATE).edit()
                .putInt("step", OnboardingState.COMPLETE).commit();
    }
    static void linkTestAccount(android.content.Context context) {
        try { new GoogleAccountStore(context).link(new GoogleAccountStore.Profile(context.getString(R.string.firebase_project_id), "test-fixture-owner", "12345678901", "fixture@example.org")); }
        catch (java.security.GeneralSecurityException e) { throw new AssertionError(e); }
    }
}
