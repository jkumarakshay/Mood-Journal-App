package com.moodjournal.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        ReminderScheduler scheduler = new ReminderScheduler(context);
        String action = intent.getAction();
        if (ReminderScheduler.DELIVER.equals(action)) scheduler.receive(intent);
        else if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) scheduler.scheduleNext();
    }
}
