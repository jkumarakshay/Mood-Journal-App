package com.moodjournal.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import java.util.function.IntConsumer;

final class WeatherReflection {
    static final String QUESTION = "Did the weather today contribute to how you feel today?";
    static final String[] ANSWERS = {"", "Absolutely yes", "Kinda yeah", "Did not matter", "Not really", "Absolutely not"};
    static final int[] IDS = {0, R.id.weather_answer_1, R.id.weather_answer_2, R.id.weather_answer_3, R.id.weather_answer_4, R.id.weather_answer_5};

    static void validate(int answer) { if (answer < 0 || answer >= ANSWERS.length) throw new IllegalArgumentException("Choose one weather answer"); }

    static RadioGroup add(Context context, LinearLayout parent, int selected, IntConsumer changed) {
        validate(selected);
        TextView label = new TextView(context); label.setText(R.string.weather_question); label.setTextSize(16);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); label.setTextColor(Appearance.color(context, 0xFF312B35));
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.topMargin = dp(context, 22); parent.addView(label, spacing);
        RadioGroup group = new RadioGroup(context); group.setId(R.id.weather_answers); group.setOrientation(LinearLayout.VERTICAL); group.setSaveEnabled(false);
        String[] labels = context.getResources().getStringArray(R.array.weather_answers);
        for (int i = 1; i < ANSWERS.length; i++) {
            RadioButton option = new RadioButton(context); option.setId(IDS[i]); option.setText(labels[i - 1]); option.setTextSize(15);
            option.setSaveEnabled(false); option.setMinHeight(dp(context, 48)); option.setPadding(dp(context, 4), dp(context, 4), 0, dp(context, 4));
            option.setTextColor(Appearance.color(context, 0xFF312B35));
            option.setButtonTintList(ColorStateList.valueOf(Appearance.color(context, 0xFF705180)));
            group.addView(option);
        }
        if (selected != 0) group.check(IDS[selected]);
        group.setOnCheckedChangeListener((view, id) -> changed.accept(answer(group)));
        parent.addView(group); return group;
    }

    static int answer(RadioGroup group) {
        if (group == null) return 0;
        for (int i = 1; i < IDS.length; i++) if (group.getCheckedRadioButtonId() == IDS[i]) return i;
        return 0;
    }
    static boolean fahrenheit(Context context) { return context.getSharedPreferences("weather", 0).getBoolean("fahrenheit", false); }
    static void setFahrenheit(Context context, boolean value) { context.getSharedPreferences("weather", 0).edit().putBoolean("fahrenheit", value).apply(); }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
