package com.moodjournal.app;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ReminderSchedulerTest {
    private Context context;
    private ReminderScheduler scheduler;
    private NotificationManager notifications;
    private AtomicReference<Clock> clock;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication(); context.getSharedPreferences("reminder", 0).edit().clear().commit();
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        notifications = context.getSystemService(NotificationManager.class); Shadows.shadowOf(notifications).setNotificationsEnabled(true);
        clock = new AtomicReference<>(Clock.fixed(Instant.parse("2026-10-23T20:00:00Z"), ZoneOffset.UTC));
        scheduler = new ReminderScheduler(context, clock::get);
    }

    @Test public void defaultIsNinePmAndSchedulesOneImmutableIdleAllowedWallClockAlarm() throws Exception {
        scheduler.scheduleNext();
        assertEquals(21, scheduler.hour()); assertEquals(0, scheduler.minute());
        assertEquals(Instant.parse("2026-10-23T21:00:00Z").toEpochMilli(), scheduler.scheduledAt());
        ShadowAlarmManager.ScheduledAlarm alarm = alarms().getScheduledAlarms().get(0);
        assertEquals(1, alarms().getScheduledAlarms().size()); assertEquals(AlarmManager.RTC_WAKEUP, alarm.type);
        assertTrue(alarm.allowWhileIdle); assertEquals(0, alarm.interval);
        assertTrue(Shadows.shadowOf(alarm.operation).isImmutable());
        assertEquals(ReminderScheduler.DELIVER, Shadows.shadowOf(alarm.operation).getSavedIntent().getAction());
        assertFalse(context.getPackageManager().getReceiverInfo(Shadows.shadowOf(alarm.operation).getSavedIntent().getComponent(), 0).exported);
    }

    @Test public void changedTimePersistsAndReplacesPreviousAlarmInsteadOfAddingAnother() {
        scheduler.scheduleNext();
        android.app.PendingIntent old = alarms().getScheduledAlarms().get(0).operation;
        scheduler.setTime(22, 15);
        assertTrue(Shadows.shadowOf(old).isCanceled()); assertEquals(1, alarms().getScheduledAlarms().size());
        assertEquals(Instant.parse("2026-10-23T22:15:00Z").toEpochMilli(), scheduler.scheduledAt());
        ReminderScheduler reopened = new ReminderScheduler(context, clock::get);
        assertEquals(22, reopened.hour()); assertEquals(15, reopened.minute());
        assertThrows(IllegalArgumentException.class, () -> scheduler.setTime(24, 0));
        assertThrows(IllegalArgumentException.class, () -> scheduler.setTime(9, 60));
        assertEquals(22, reopened.hour());
    }

    @Test public void beforeAtAndAfterChosenTimeUseTheNextFutureLocalOccurrence() {
        ZoneId utc = ZoneOffset.UTC;
        assertEquals(Instant.parse("2026-10-23T21:00:00Z"), ReminderScheduler.nextOccurrence(Instant.parse("2026-10-23T20:59:00Z"), utc, 21, 0));
        assertEquals(Instant.parse("2026-10-24T21:00:00Z"), ReminderScheduler.nextOccurrence(Instant.parse("2026-10-23T21:00:00Z"), utc, 21, 0));
        assertEquals(Instant.parse("2026-10-24T00:15:00Z"), ReminderScheduler.nextOccurrence(Instant.parse("2026-10-23T23:59:00Z"), utc, 0, 15));
        assertEquals(Instant.parse("2026-10-23T15:30:00Z"), ReminderScheduler.nextOccurrence(Instant.parse("2026-10-23T15:00:00Z"), ZoneId.of("Asia/Kolkata"), 21, 0));
    }

    @Test public void daylightSavingGapAndOverlapDoNotDriftOrCreateTwoReminders() {
        ZoneId ny = ZoneId.of("America/New_York");
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), ReminderScheduler.nextOccurrence(Instant.parse("2026-03-08T06:00:00Z"), ny, 2, 30));
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"), ReminderScheduler.nextOccurrence(Instant.parse("2026-03-08T07:31:00Z"), ny, 2, 30));
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), ReminderScheduler.nextOccurrence(Instant.parse("2026-11-01T05:45:00Z"), ny, 1, 30));
        assertEquals(Instant.parse("2026-11-02T02:00:00Z"), ReminderScheduler.nextOccurrence(Instant.parse("2026-11-01T01:00:00Z"), ny, 21, 0));
    }

    @Test public void twentyDistinctMessagesCycleBeforeRepeatingAndNotificationOpensCheckIn() {
        String[] messages = context.getResources().getStringArray(R.array.check_in_reminders);
        assertEquals(20, messages.length); assertEquals(20, new HashSet<>(Arrays.asList(messages)).size());
        for (String message : messages) assertFalse(message.trim().isEmpty());
        scheduler.scheduleNext(); HashSet<String> delivered = new HashSet<>(); String first = null;
        for (int i = 0; i < 21; i++) {
            long due = scheduler.scheduledAt(); clock.set(Clock.fixed(Instant.ofEpochMilli(due), ZoneOffset.UTC));
            scheduler.receive(delivery(due));
            Notification notice = Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID);
            String text = notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString();
            if (i == 0) first = text;
            if (i < 20) assertTrue(delivered.add(text)); else assertEquals(first, text);
            assertTrue(scheduler.scheduledAt() > due); assertEquals(1, alarms().getScheduledAlarms().size());
        }
        assertEquals(1, Shadows.shadowOf(notifications).size());
        Notification notice = Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID);
        assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility);
        assertEquals(ReminderScheduler.CHANNEL, notice.getChannelId());
        assertNotNull(notice.publicVersion);
        assertEquals(context.getString(R.string.reminder_public_text), notice.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue(Shadows.shadowOf(notice.contentIntent).isImmutable());
        Intent open = Shadows.shadowOf(notice.contentIntent).getSavedIntent();
        assertEquals(MainActivity.class.getName(), open.getComponent().getClassName()); assertEquals(ReminderScheduler.OPEN_CHECK_IN, open.getAction());
    }

    @Test public void staleAlarmsAndSameDayTimeChangesCannotSendDuplicateMessages() {
        scheduler.scheduleNext(); long old = scheduler.scheduledAt(); scheduler.setTime(22, 0);
        long current = scheduler.scheduledAt(); clock.set(Clock.fixed(Instant.ofEpochMilli(old), ZoneOffset.UTC));
        scheduler.receive(delivery(old)); assertEquals(0, Shadows.shadowOf(notifications).size()); assertEquals(current, scheduler.scheduledAt());
        clock.set(Clock.fixed(Instant.ofEpochMilli(current), ZoneOffset.UTC)); scheduler.receive(delivery(current));
        scheduler.setTime(23, 0); long later = scheduler.scheduledAt();
        clock.set(Clock.fixed(Instant.ofEpochMilli(later), ZoneOffset.UTC)); scheduler.receive(delivery(later));
        assertEquals(1, context.getSharedPreferences("reminder", 0).getInt("next_message", -1));
        assertEquals(Instant.parse("2026-10-24T23:00:00Z").toEpochMilli(), scheduler.scheduledAt());
    }

    @Test public void deniedPermissionOrBlockedNotificationsStillScheduleTomorrowWithoutAdvancingMessages() {
        scheduler.scheduleNext(); Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        fireNext(); assertEquals(0, Shadows.shadowOf(notifications).size());
        assertEquals(0, context.getSharedPreferences("reminder", 0).getInt("next_message", 0));
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Shadows.shadowOf(notifications).setNotificationsEnabled(false); fireNext();
        assertEquals(0, Shadows.shadowOf(notifications).size());
        Shadows.shadowOf(notifications).setNotificationsEnabled(true); fireNext();
        assertEquals(1, Shadows.shadowOf(notifications).size());
        assertEquals(1, context.getSharedPreferences("reminder", 0).getInt("next_message", -1));
    }

    @Test public void delayedYesterdayReminderIsSkippedInsteadOfSendingOutdatedMessage() {
        scheduler.scheduleNext(); long old = scheduler.scheduledAt();
        clock.set(Clock.fixed(Instant.parse("2026-10-24T01:00:00Z"), ZoneOffset.UTC)); scheduler.receive(delivery(old));
        assertEquals(0, Shadows.shadowOf(notifications).size());
        assertEquals(Instant.parse("2026-10-24T21:00:00Z").toEpochMilli(), scheduler.scheduledAt());
    }

    @Test public void bootClockTimezoneAndUpdateBroadcastsRestoreTheSchedule() {
        ReminderReceiver receiver = new ReminderReceiver(); ReminderScheduler local = new ReminderScheduler(context);
        local.setTime(10, 35);
        for (String action : new String[]{Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED}) {
            context.getSharedPreferences("reminder", 0).edit().putLong(ReminderScheduler.SCHEDULED_AT, 1).commit();
            receiver.onReceive(context, new Intent(action));
            assertTrue(local.scheduledAt() > System.currentTimeMillis()); assertEquals(1, alarms().getScheduledAlarms().size());
            java.time.ZonedDateTime next = Instant.ofEpochMilli(local.scheduledAt()).atZone(ZoneId.systemDefault());
            assertEquals(10, next.getHour()); assertEquals(35, next.getMinute());
        }
        long scheduled = local.scheduledAt(); receiver.onReceive(context, new Intent("unrelated")); receiver.onReceive(context, null);
        assertEquals(scheduled, local.scheduledAt());
    }

    private void fireNext() { long due = scheduler.scheduledAt(); clock.set(Clock.fixed(Instant.ofEpochMilli(due), ZoneOffset.UTC)); scheduler.receive(delivery(due)); }
    private Intent delivery(long due) { return new Intent(context, ReminderReceiver.class).setAction(ReminderScheduler.DELIVER).putExtra(ReminderScheduler.SCHEDULED_AT, due); }
    private ShadowAlarmManager alarms() { return Shadows.shadowOf(context.getSystemService(AlarmManager.class)); }
}
