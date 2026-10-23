package com.moodjournal.app;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

final class AnalysisScreen {
    private final FragmentActivity activity;
    private final BooleanSupplier authorized;
    private final Runnable refresh;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MoodAnalysis.Report report;
    private boolean loading, failed, closed;
    private int generation;
    private Runnable due;

    AnalysisScreen(FragmentActivity activity, BooleanSupplier authorized, Runnable refresh) {
        this.activity = activity; this.authorized = authorized; this.refresh = refresh;
    }
    void show(LinearLayout page) {
        if (!authorized.getAsBoolean()) return;
        add(page, label(activity.getString(R.string.analysis_brand), 11, true), 0);
        TextView title = label(activity.getString(R.string.mood_analysis), 36, false); title.setTypeface(Typeface.create("serif", Typeface.NORMAL)); title.setId(R.id.analysis_title); add(page, title, 16);
        add(page, label(activity.getString(R.string.analysis_intro), 16, false), 12);
        if (report == null) {
            TextView status = label(activity.getString(failed ? R.string.analysis_failed : R.string.analysis_loading), 16, false);
            status.setId(R.id.analysis_status); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(page, status, 24);
            if (failed) {
                Button retry = new Button(activity); retry.setText(R.string.analysis_retry); retry.setAllCaps(false); retry.setMinHeight(dp(48));
                retry.setOnClickListener(v -> { if (authorized.getAsBoolean()) { failed = false; request(); refresh.run(); } }); add(page, retry, 12);
            } else request();
            return;
        }
        DateTimeFormatter dates = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault());
        if (!report.eligible()) {
            LinearLayout card = card(0xFFEDE6F1);
            add(card, label(activity.getString(R.string.analysis_gathering_title), 23, true), 0);
            TextView progress = label(activity.getString(R.string.analysis_progress, report.recordedDays(), MoodAnalysis.REQUIRED_DAYS), 17, false);
            progress.setId(R.id.analysis_status); add(card, progress, 14);
            add(card, label(activity.getString(R.string.analysis_gathering_body), 15, false), 12); add(page, card, 24);
            for (String heading : new String[]{"Weather-related patterns", "Response-related patterns", "Time-related patterns"}) add(page, label(heading, 19, true), 22);
            return;
        }
        LinearLayout summary = card(0xFFE8EDE1);
        add(summary, label(activity.getString(R.string.analysis_date_range, report.from().format(dates), report.through().format(dates)), 16, true), 0);
        add(summary, label(activity.getString(R.string.analysis_days, report.recordedDays()), 15, false), 10);
        String generated = Instant.ofEpochMilli(report.generatedAt()).atZone(ZoneId.systemDefault()).toLocalDate().format(dates);
        String next = Instant.ofEpochMilli(report.nextReviewAt()).atZone(ZoneId.systemDefault()).toLocalDate().format(dates);
        TextView reviewed = label(activity.getString(R.string.analysis_review_dates, generated, next), 13, false); reviewed.setId(R.id.analysis_status); add(summary, reviewed, 10); add(page, summary, 22);
        int[] colors = {0xFFE1EDF5, 0xFFEDE6F1, 0xFFFAE6D5}; int[] ids = {R.id.analysis_weather, R.id.analysis_responses, R.id.analysis_time};
        for (int i = 0; i < report.sections().size(); i++) {
            MoodAnalysis.Section section = report.sections().get(i); LinearLayout card = card(colors[i]); card.setId(ids[i]);
            add(card, label(section.title(), 24, true), 0); add(card, label(section.context(), 14, false), 12);
            for (MoodAnalysis.Insight insight : section.insights()) {
                add(card, label(insight.title(), 18, true), 22); add(card, label(insight.detail(), 16, false), 10);
            }
            add(page, card, 22);
        }
        add(page, label(activity.getString(R.string.analysis_limits), 13, false), 22);
        if (due != null) main.removeCallbacks(due);
        due = () -> { if (authorized.getAsBoolean()) { report = null; refresh.run(); } };
        main.postDelayed(due, Math.max(1000, report.nextReviewAt() - System.currentTimeMillis()));
    }

    private void request() {
        if (loading || closed || !authorized.getAsBoolean()) return;
        loading = true; final int token = ++generation;
        worker.execute(() -> {
            try {
                MoodAnalysis.Report ready = new AnalysisRepository(activity).review(System.currentTimeMillis(), ZoneId.systemDefault());
                AnalysisScheduler.next(activity, ready);
                main.post(() -> { if (!closed && token == generation && authorized.getAsBoolean()) { loading = false; report = ready; failed = false; refresh.run(); } });
            } catch (Exception failure) {
                main.post(() -> { if (!closed && token == generation && authorized.getAsBoolean()) { loading = false; failed = true; refresh.run(); } });
            }
        });
    }
    void onLeave() { generation++; report = null; loading = false; failed = false; if (due != null) { main.removeCallbacks(due); due = null; } }
    void close() { onLeave(); closed = true; worker.shutdown(); }
    private TextView label(String value, int size, boolean bold) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size); text.setLineSpacing(dp(4), 1); text.setTextColor(Appearance.color(activity, 0xFF312B35));
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return text;
    }
    private LinearLayout card(int color) {
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(20), dp(24), dp(20), dp(24));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(Appearance.color(activity, color)); shape.setCornerRadius(dp(22)); card.setBackground(shape); return card;
    }
    private void add(LinearLayout parent, View view, int margin) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(margin); parent.addView(view, params); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
