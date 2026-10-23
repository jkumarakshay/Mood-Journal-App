package com.moodjournal.app;

import android.content.Context;

/** Legacy answers remain readable/exportable; new journal forms no longer ask this question. */
final class WeatherReflection {
    static final String QUESTION = "Did the weather today contribute to how you feel today?";
    static final String[] ANSWERS = {"", "Absolutely yes", "Kinda yeah", "Did not matter", "Not really", "Absolutely not"};
    static void validate(int answer) { if (answer < 0 || answer >= ANSWERS.length) throw new IllegalArgumentException("Choose one weather answer"); }
    static boolean fahrenheit(Context context) { return context.getSharedPreferences("weather", 0).getBoolean("fahrenheit", false); }
    static void setFahrenheit(Context context, boolean value) { context.getSharedPreferences("weather", 0).edit().putBoolean("fahrenheit", value).apply(); }
}
