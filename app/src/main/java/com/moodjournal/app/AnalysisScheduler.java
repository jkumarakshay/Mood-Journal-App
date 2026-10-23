package com.moodjournal.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

final class AnalysisScheduler {
    static final int JOB_ID = 3100;
    static void next(Context context, MoodAnalysis.Report report) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (!report.eligible()) { jobs.cancel(JOB_ID); return; }
        JobInfo previous = jobs.getPendingJob(JOB_ID);
        if (previous != null && previous.getExtras().getLong("review_at", -1) == report.nextReviewAt()) return;
        PersistableBundle extras = new PersistableBundle(); extras.putLong("review_at", report.nextReviewAt());
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(context, AnalyticsJobService.class))
                .setMinimumLatency(Math.max(1000, report.nextReviewAt() - System.currentTimeMillis()))
                .setPersisted(true).setRequiresBatteryNotLow(true).setExtras(extras).build();
        jobs.schedule(info);
    }
}
