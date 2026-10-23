package com.moodjournal.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.FragmentActivity;
import java.util.Calendar;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class ReminderController {
    private final FragmentActivity activity;
    private final ReminderScheduler scheduler;
    private final SharedPreferences prefs;
    private final Consumer<AlertDialog> showDialog;
    private final BooleanSupplier visible;
    private final Runnable refresh;
    private final ActivityResultLauncher<String> permission;

    ReminderController(FragmentActivity activity, Consumer<AlertDialog> showDialog, BooleanSupplier visible, Runnable refresh) {
        this.activity = activity; this.showDialog = showDialog; this.visible = visible; this.refresh = refresh;
        scheduler = new ReminderScheduler(activity);
        prefs = activity.getSharedPreferences("reminder", Context.MODE_PRIVATE);
        permission = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            scheduler.scheduleNext(); refreshIfVisible();
        });
    }

    void onResume() {
        scheduler.scheduleNext();
        refreshIfVisible();
        if (Build.VERSION.SDK_INT >= 33 && !scheduler.permissionGranted() && !prefs.getBoolean("permission_requested", false)) requestPermission();
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT < 33) { openSystemSettings(); return; }
        prefs.edit().putBoolean("permission_requested", true).apply();
        permission.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void refreshIfVisible() {
        if (visible.getAsBoolean() && activity.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) refresh.run();
    }

    void show(LinearLayout page) {
        add(page, label(activity.getString(R.string.reminder_description), 16), 16);
        Calendar time = Calendar.getInstance(); time.set(Calendar.HOUR_OF_DAY, scheduler.hour()); time.set(Calendar.MINUTE, scheduler.minute());
        String formatted = DateFormat.getTimeFormat(activity).format(time.getTime());
        TextView summary = label(activity.getString(R.string.reminder_daily_time, formatted), 22);
        summary.setId(R.id.reminder_time); add(page, summary, 24);
        Button change = button(R.string.reminder_change_time, R.id.reminder_change_time);
        change.setOnClickListener(v -> {
            if (!visible.getAsBoolean()) return;
            TimePickerDialog picker = new TimePickerDialog(activity, (view, hour, minute) -> {
                if (!visible.getAsBoolean()) return;
                scheduler.setTime(hour, minute); refresh.run();
            }, scheduler.hour(), scheduler.minute(), DateFormat.is24HourFormat(activity));
            showDialog.accept(picker);
        });
        add(page, change, 16);
        TextView status = label(activity.getString(scheduler.canNotify() ? R.string.reminder_allowed : R.string.reminder_permission_needed), 14);
        status.setId(R.id.reminder_status); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(page, status, 20);
        if (!scheduler.canNotify()) {
            Button allow = button(R.string.reminder_allow, R.id.reminder_allow);
            allow.setOnClickListener(v -> {
                if (!visible.getAsBoolean()) return;
                if (Build.VERSION.SDK_INT >= 33 && !scheduler.permissionGranted()
                        && (!prefs.getBoolean("permission_requested", false) || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) requestPermission();
                else openSystemSettings();
            });
            add(page, allow, 12);
        }
        add(page, label(activity.getString(R.string.reminder_clock_note), 13), 18);
    }

    private void openSystemSettings() {
        activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
    }
    private TextView label(String value, int size) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size);
        text.setTextColor(Appearance.color(activity, 0xFF312B35)); text.setLineSpacing(dp(4), 1); return text;
    }
    private Button button(int string, int id) {
        Button button = new Button(activity); button.setId(id); button.setText(string); button.setAllCaps(false); button.setTextSize(16); button.setMinHeight(dp(56));
        button.setTextColor(Appearance.color(activity, 0xFF705180));
        GradientDrawable background = new GradientDrawable(); background.setColor(Appearance.color(activity, 0xFFFFFFFF)); background.setCornerRadius(dp(18));
        button.setBackground(background); return button;
    }
    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(child, params);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
