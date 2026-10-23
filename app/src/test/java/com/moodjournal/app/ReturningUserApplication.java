package com.moodjournal.app;

/** Most feature tests start after the first-launch tutorial has been acknowledged. */
public final class ReturningUserApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        getSharedPreferences(UpdateController.PREFS, MODE_PRIVATE).edit().putBoolean("automatic", false).commit();
        getSharedPreferences(OnboardingState.PREFS, MODE_PRIVATE).edit()
                .putInt("step", OnboardingState.COMPLETE).commit();
    }
}
