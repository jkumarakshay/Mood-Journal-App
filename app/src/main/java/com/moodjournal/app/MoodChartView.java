package com.moodjournal.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** A date-scaled graph with explicit breaks wherever a time bucket has no check-ins. */
public final class MoodChartView extends View {
    private static final String[] LABELS = {"Low", "Down", "Okay", "Good", "Great"};
    private final MoodHistory.Series series;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MoodChartView(Context context) {
        this(context, MoodHistory.summarize(java.util.Collections.emptyList(), LocalDate.now().minusDays(6),
                LocalDate.now(), java.time.ZoneId.systemDefault()));
    }

    MoodChartView(Context context, MoodHistory.Series series) {
        super(context);
        this.series = series;
        setMinimumHeight(dp(290));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("Mood graph, " + series.start() + " to " + series.end()
                + ". Low is 1, Down is 2, Okay is 3, Good is 4, Great is 5. "
                + series.responses() + " recorded check-ins. Missing periods are gaps. Detailed averages are below.");
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(dp(300), widthSpec), resolveSize(dp(290), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        float widest = 0;
        for (int mood = 1; mood <= 5; mood++) widest = Math.max(widest, paint.measureText(mood + " " + LABELS[mood - 1]));
        float left = widest + dp(12), right = getWidth() - dp(14);
        float top = dp(25), bottom = getHeight() - dp(48);
        if (right <= left || bottom <= top) return;
        paint.setStrokeWidth(dp(1));
        for (int mood = 1; mood <= 5; mood++) {
            float y = bottom - (mood - 1) * (bottom - top) / 4;
            paint.setColor(Color.rgb(232, 225, 234));
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(Color.rgb(109, 100, 112));
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(mood + " " + LABELS[mood - 1], left - dp(10), y - (paint.ascent() + paint.descent()) / 2, paint);
        }
        DateTimeFormatter format = DateTimeFormatter.ofPattern(series.totalDays() >= 365 ? "MMM yy" : "MMM d", Locale.getDefault());
        LocalDate middle = series.start().plusDays((series.totalDays() - 1) / 2);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(series.start().format(format), left, bottom + dp(24), paint);
        if (series.totalDays() > 3) {
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(middle.format(format), (left + right) / 2, bottom + dp(24), paint);
        }
        if (series.totalDays() > 1) {
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(series.end().format(format), right, bottom + dp(24), paint);
        }
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Date", (left + right) / 2, bottom + dp(43), paint);
        if (series.responses() == 0) {
            paint.setColor(Color.rgb(109, 100, 112));
            canvas.drawText("No check-ins in this range", (left + right) / 2, top + dp(30), paint);
            return;
        }
        boolean previous = false;
        float previousX = 0, previousY = 0;
        for (MoodHistory.Bucket bucket : series.buckets()) {
            if (bucket.average() == null) { previous = false; continue; }
            double midpoint = (bucket.start().toEpochDay() + bucket.end().toEpochDay()) / 2.0;
            float x = series.totalDays() == 1 ? (left + right) / 2
                    : left + (float) ((midpoint - series.start().toEpochDay()) / (series.totalDays() - 1)) * (right - left);
            float y = bottom - (float) (bucket.average() - 1) * (bottom - top) / 4;
            paint.setColor(Color.rgb(112, 81, 128));
            paint.setStrokeWidth(dp(2));
            if (previous) canvas.drawLine(previousX, previousY, x, y, paint);
            canvas.drawCircle(x, y, dp(4), paint);
            previousX = x;
            previousY = y;
            previous = true;
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
