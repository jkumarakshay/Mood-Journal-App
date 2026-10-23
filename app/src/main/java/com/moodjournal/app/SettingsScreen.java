package com.moodjournal.app;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class SettingsScreen {
    static final int NONE = 0, HOME = 1, ABOUT = 2, THEME = 3, NOTIFICATION = 4, WEATHER = 5, UPDATES = 6;
    private final FragmentActivity activity;
    private final IntConsumer navigate;
    private final Consumer<Boolean> changeTheme;
    private final ReminderController reminders;
    private final WeatherController weather;
    private final UpdateController updates;

    SettingsScreen(FragmentActivity activity, IntConsumer navigate, Consumer<Boolean> changeTheme, ReminderController reminders, WeatherController weather, UpdateController updates) {
        this.activity = activity; this.navigate = navigate; this.changeTheme = changeTheme; this.reminders = reminders;
        this.weather = weather; this.updates = updates;
    }

    void show(LinearLayout page, int screen) {
        TextView title = label(activity.getString(screen == ABOUT ? R.string.settings_about_title
                : screen == THEME ? R.string.settings_theme : screen == NOTIFICATION ? R.string.settings_notification : screen == WEATHER ? R.string.settings_weather : screen == UPDATES ? R.string.settings_updates : R.string.settings_title), 36);
        title.setId(R.id.settings_title);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        add(page, title, 0);
        if (screen == ABOUT) {
            add(page, label(activity.getString(R.string.settings_about_overview), 17), 22);
            add(page, label(activity.getString(R.string.settings_about_features), 16), 18);
            add(page, label(activity.getString(R.string.settings_about_privacy), 15), 18);
        } else if (screen == UPDATES) {
            updates.show(page);
        } else if (screen == WEATHER) {
            weather.show(page);
        } else if (screen == NOTIFICATION) {
            reminders.show(page);
        } else if (screen == THEME) {
            add(page, label(activity.getString(R.string.settings_theme_description), 16), 16);
            RadioGroup choices = new RadioGroup(activity);
            choices.setOrientation(LinearLayout.VERTICAL);
            boolean dark = Appearance.isDark(activity);
            int[] ids = {R.id.theme_light, R.id.theme_dark};
            int[] strings = {R.string.theme_light, R.string.theme_dark};
            for (int i = 0; i < ids.length; i++) {
                RadioButton option = new RadioButton(activity);
                option.setId(ids[i]); option.setText(strings[i]); option.setTextSize(17);
                option.setTextColor(Appearance.color(activity, 0xFF312B35));
                option.setButtonTintList(ColorStateList.valueOf(Appearance.color(activity, 0xFF705180)));
                option.setMinHeight(dp(64)); option.setPadding(dp(12), dp(8), dp(12), dp(8));
                choices.addView(option);
            }
            choices.check(dark ? R.id.theme_dark : R.id.theme_light);
            choices.setOnCheckedChangeListener((group, checked) -> {
                boolean next = checked == R.id.theme_dark;
                if (next != Appearance.isDark(activity)) changeTheme.accept(next);
            });
            add(page, choices, 20);
            add(page, label(activity.getString(R.string.settings_theme_saved), 14), 18);
        } else {
            add(page, label(activity.getString(R.string.settings_description), 16), 12);
            add(page, link(R.string.settings_about, R.id.settings_about, ABOUT), 24);
            add(page, label(activity.getString(R.string.settings_about_summary), 14), 8);
            add(page, link(R.string.settings_theme, R.id.settings_theme, THEME), 24);
            add(page, label(activity.getString(Appearance.isDark(activity) ? R.string.theme_dark : R.string.theme_light), 14), 8);
            add(page, link(R.string.settings_notification, R.id.settings_notification, NOTIFICATION), 24);
            add(page, label(activity.getString(R.string.reminder_settings_summary), 14), 8);
            add(page, link(R.string.settings_weather, R.id.settings_weather, WEATHER), 24);
            add(page, label(activity.getString(WeatherReflection.fahrenheit(activity) ? R.string.weather_fahrenheit : R.string.weather_celsius), 14), 8);
            add(page, link(R.string.settings_updates, R.id.settings_updates, UPDATES), 24);
            add(page, label(activity.getString(R.string.updates_settings_summary), 14), 8);
        }
    }

    private Button link(int text, int id, int screen) {
        Button button = new Button(activity); button.setId(id); button.setText(text); button.setAllCaps(false);
        button.setTextSize(18); button.setMinHeight(dp(64));
        button.setTextColor(Appearance.color(activity, 0xFF705180));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Appearance.color(activity, 0xFFFFFFFF)); background.setCornerRadius(dp(20));
        button.setBackground(background); button.setOnClickListener(v -> navigate.accept(screen)); return button;
    }
    private TextView label(String value, int size) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size);
        text.setTextColor(Appearance.color(activity, 0xFF312B35)); text.setLineSpacing(dp(4), 1); return text;
    }
    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(child, params);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
