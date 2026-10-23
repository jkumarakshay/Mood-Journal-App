package com.moodjournal.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The OS can defer the weekly review while battery is low. No network or notification is used. */
public final class AnalyticsJobService extends JobService {
    private ExecutorService worker;
    private int generation;
    @Override public boolean onStartJob(JobParameters params) {
        final int token = ++generation;
        worker = Executors.newSingleThreadExecutor();
        worker.execute(() -> {
            try {
                MoodAnalysis.Report report = new AnalysisRepository(this).review(System.currentTimeMillis(), ZoneId.systemDefault());
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (token != generation) return;
                    jobFinished(params, false); AnalysisScheduler.next(this, report);
                });
            } catch (Exception failure) {
                new Handler(Looper.getMainLooper()).post(() -> { if (token == generation) jobFinished(params, true); });
            }
        });
        worker.shutdown(); return true;
    }
    @Override public boolean onStopJob(JobParameters params) { generation++; if (worker != null) worker.shutdownNow(); return true; }
    @Override public void onDestroy() { generation++; if (worker != null) worker.shutdownNow(); super.onDestroy(); }
}
