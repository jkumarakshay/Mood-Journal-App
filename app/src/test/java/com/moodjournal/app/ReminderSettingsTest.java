package com.moodjournal.app;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ReminderSettingsTest {
    private Context context;
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db");
        for (String name : new String[]{"reminder", "draft", "appearance", "journal_lock", "heap_notices", "backup_notices"})
            context.getSharedPreferences(name, 0).edit().clear().commit();
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Shadows.shadowOf(context.getSystemService(NotificationManager.class)).setNotificationsEnabled(true);
        Settings.System.putString(context.getContentResolver(), Settings.System.TIME_12_24, "12");
    }
    @After public void clearAppearance() { context.getSharedPreferences("appearance", 0).edit().clear().commit(); }

    @Test public void notificationPageDefaultsToNinePmAndContainsNoOffSwitch() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            notificationPage(c.get());
            assertEquals("Notification", ((TextView) c.get().findViewById(R.id.settings_title)).getText().toString());
            String time = ((TextView) c.get().findViewById(R.id.reminder_time)).getText().toString();
            assertTrue(time, time.contains("9:00")); assertTrue(time, time.contains("PM"));
            assertEquals(21, new ReminderScheduler(context).hour());
            assertFalse(hasToggle(c.get().getWindow().getDecorView()));
            assertNotNull(c.get().findViewById(R.id.reminder_change_time)); assertNull(c.get().findViewById(R.id.reminder_allow));
            assertTrue(((TextView) c.get().findViewById(R.id.reminder_status)).getText().toString().contains("scheduled"));
        }
    }

    @Test public void changedTimePersistsAcrossRecreationAndRestartAndCancelKeepsPreviousTime() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            notificationPage(c.get()); c.get().findViewById(R.id.reminder_change_time).performClick();
            TimePickerDialog picker = (TimePickerDialog) ShadowAlertDialog.getLatestAlertDialog(); picker.updateTime(8, 35);
            picker.getButton(TimePickerDialog.BUTTON_NEGATIVE).performClick(); idle(); assertEquals(21, new ReminderScheduler(context).hour());
            c.get().findViewById(R.id.reminder_change_time).performClick();
            picker = (TimePickerDialog) ShadowAlertDialog.getLatestAlertDialog(); picker.updateTime(8, 35);
            picker.getButton(TimePickerDialog.BUTTON_POSITIVE).performClick(); idle();
            assertEquals(8, new ReminderScheduler(context).hour()); assertEquals(35, new ReminderScheduler(context).minute());
            assertTrue(((TextView) c.get().findViewById(R.id.reminder_time)).getText().toString().contains("8:35"));
            c.recreate(); assertNotNull(c.get().findViewById(R.id.reminder_time));
            assertTrue(((TextView) c.get().findViewById(R.id.reminder_time)).getText().toString().contains("8:35"));
        }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            notificationPage(c.get()); assertTrue(((TextView) c.get().findViewById(R.id.reminder_time)).getText().toString().contains("8:35"));
        }
    }

    @Test public void firstLaunchRequestsAndroidPermissionOnceAndGrantUpdatesStatus() {
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            ShadowActivity.PermissionsRequest request = Shadows.shadowOf(c.get()).getLastRequestedPermission();
            assertNotNull(request); assertArrayEquals(new String[]{Manifest.permission.POST_NOTIFICATIONS}, request.requestedPermissions);
            notificationPage(c.get()); assertNotNull(c.get().findViewById(R.id.reminder_allow));
            Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
            c.get().onRequestPermissionsResult(request.requestCode, request.requestedPermissions, new int[]{PackageManager.PERMISSION_GRANTED}); idle();
            assertNull(c.get().findViewById(R.id.reminder_allow));
            assertTrue(((TextView) c.get().findViewById(R.id.reminder_status)).getText().toString().contains("scheduled"));
        }
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNull(Shadows.shadowOf(c.get()).getLastRequestedPermission());
            notificationPage(c.get()); c.get().findViewById(R.id.reminder_allow).performClick();
            Intent settings = Shadows.shadowOf(c.get()).getNextStartedActivity();
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.getAction());
            assertEquals(context.getPackageName(), settings.getStringExtra(Settings.EXTRA_APP_PACKAGE));
        }
    }

    @Test public void reminderTapReturnsToCheckInRetainsDraftAndLocksPrivateJournal() {
        try (JournalStore store = new JournalStore(context)) { store.add(3, "Private entry", "", ""); }
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup(); JournalStore store = new JournalStore(context)) {
            MainActivity a = c.get(); JournalTest.chooseMood(a, R.id.mood_4);
            ((EditText) a.findViewById(R.id.entry_event)).setText("Unfinished words");
            a.findViewById(R.id.journal_tab).performClick(); JournalTest.unlockJournal(a);
            a.onNewIntent(new Intent(a, MainActivity.class).setAction(ReminderScheduler.OPEN_CHECK_IN)); idle();
            assertNotNull(a.findViewById(R.id.entry_event));
            assertEquals("Unfinished words", ((EditText) a.findViewById(R.id.entry_event)).getText().toString());
            assertTrue(a.findViewById(R.id.mood_4).isSelected()); assertNull(a.findViewById(R.id.journal_add)); assertEquals(1, store.all().size());
            a.findViewById(R.id.journal_tab).performClick(); assertNotNull(a.findViewById(R.id.unlock_pin));
        }
    }

    @Test public void coldReminderIntentOpensCheckInAndDoesNotHijackLaterThemeRecreation() {
        Intent open = new Intent(context, MainActivity.class).setAction(ReminderScheduler.OPEN_CHECK_IN);
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class, open).setup()) {
            assertNotNull(c.get().findViewById(R.id.entry_event)); assertNull(c.get().getIntent().getAction());
            c.get().findViewById(R.id.settings_button).performClick(); c.get().findViewById(R.id.settings_theme).performClick();
            c.recreate(); assertNotNull(c.get().findViewById(R.id.theme_light));
        }
    }

    @Test public void notificationSettingsAndTimePickerRespectDarkTheme() {
        Appearance.setDark(context, true);
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            notificationPage(c.get());
            assertEquals(Appearance.color(c.get(), 0xFF312B35), ((TextView) c.get().findViewById(R.id.reminder_time)).getCurrentTextColor());
            c.get().findViewById(R.id.reminder_change_time).performClick();
            assertTrue(ShadowAlertDialog.getLatestAlertDialog() instanceof TimePickerDialog);
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
            c.pause().stop(); assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        }
    }

    private void notificationPage(MainActivity a) { a.findViewById(R.id.settings_button).performClick(); a.findViewById(R.id.settings_notification).performClick(); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private boolean hasToggle(View root) {
        if (root instanceof CompoundButton) return true;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (hasToggle(group.getChildAt(i))) return true;
        return false;
    }
}
