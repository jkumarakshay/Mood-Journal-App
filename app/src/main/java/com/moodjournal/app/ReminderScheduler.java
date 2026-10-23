package com.moodjournal.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.function.Supplier;

/** One local wall-clock reminder per day. No server, journal content, or off switch. */
final class ReminderScheduler {
    static final String DELIVER = "com.moodjournal.app.DAILY_CHECK_IN";
    static final String OPEN_CHECK_IN = "com.moodjournal.app.OPEN_CHECK_IN";
    static final String CHANNEL = "daily_check_in";
    static final String SCHEDULED_AT = "scheduled_at";
    static final int NOTIFICATION_ID = 2100;
    private final Context context;
    private final SharedPreferences prefs;
    private final Supplier<Clock> clocks;

    ReminderScheduler(Context context) { this(context, Clock::systemDefaultZone); }
    ReminderScheduler(Context context, Supplier<Clock> clocks) {
        this.context = context.getApplicationContext(); this.clocks = clocks;
        prefs = this.context.getSharedPreferences("reminder", Context.MODE_PRIVATE);
    }

    int hour() { return prefs.getInt("hour", 21); }
    int minute() { return prefs.getInt("minute", 0); }
    long scheduledAt() { return prefs.getLong(SCHEDULED_AT, 0); }

    void setTime(int hour, int minute) {
        validateTime(hour, minute);
        prefs.edit().putInt("hour", hour).putInt("minute", minute).apply();
        scheduleNext();
    }

    static Instant nextOccurrence(Instant now, ZoneId zone, int hour, int minute) {
        validateTime(hour, minute);
        LocalDate date = now.atZone(zone).toLocalDate();
        LocalTime time = LocalTime.of(hour, minute);
        Instant next = date.atTime(time).atZone(zone).toInstant();
        if (!next.isAfter(now)) next = date.plusDays(1).atTime(time).atZone(zone).toInstant();
        return next;
    }

    private static void validateTime(int hour, int minute) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new IllegalArgumentException("Invalid reminder time");
    }

    void scheduleNext() {
        Clock clock = clocks.get(); scheduleNext(clock.instant(), clock.getZone());
    }

    private void scheduleNext(Instant now, ZoneId zone) {
        ensureChannel();
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(DELIVER);
        PendingIntent previous = PendingIntent.getBroadcast(context, NOTIFICATION_ID, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (previous != null) { alarms.cancel(previous); previous.cancel(); }
        long at = nextOccurrence(now, zone, hour(), minute()).toEpochMilli();
        intent.putExtra(SCHEDULED_AT, at);
        PendingIntent operation = PendingIntent.getBroadcast(context, NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Inexact idle-allowed scheduling needs no special exact-alarm permission.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation);
        prefs.edit().putLong(SCHEDULED_AT, at).apply();
    }

    void ensureChannel() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.reminder_channel_description));
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }

    boolean permissionGranted() {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    boolean canNotify() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return permissionGranted() && manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    void receive(Intent intent) {
        if (!DELIVER.equals(intent.getAction()) || intent.getLongExtra(SCHEDULED_AT, -1) != scheduledAt()) return;
        Clock clock = clocks.get(); Instant now = clock.instant(); ZoneId zone = clock.getZone();
        try {
            long at = scheduledAt();
            LocalDate today = now.atZone(zone).toLocalDate();
            if (now.isBefore(Instant.ofEpochMilli(at)) || !today.equals(Instant.ofEpochMilli(at).atZone(zone).toLocalDate())) return;
            if (today.toString().equals(prefs.getString("last_delivered_date", "")) || !canNotify()) return;
            ensureChannel();
            String[] messages = context.getResources().getStringArray(R.array.check_in_reminders);
            int index = Math.floorMod(prefs.getInt("next_message", 0), messages.length);
            String message = messages[index];
            Intent checkIn = new Intent(context, MainActivity.class).setAction(OPEN_CHECK_IN)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open = PendingIntent.getActivity(context, NOTIFICATION_ID, checkIn,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification publicNotice = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.reminder_public_text)).build();
            Notification notification = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.reminder_title)).setContentText(message)
                    .setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(open)
                    .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicNotice).setAutoCancel(true).build();
            context.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
            prefs.edit().putString("last_delivered_date", today.toString()).putInt("next_message", (index + 1) % messages.length).apply();
        } catch (SecurityException ignored) {
            // Android permission can be revoked between the check and notify(). Keep tomorrow scheduled.
        } finally { scheduleNext(now, zone); }
    }
}
