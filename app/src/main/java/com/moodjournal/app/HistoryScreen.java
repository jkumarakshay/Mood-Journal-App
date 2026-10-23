package com.moodjournal.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class HistoryScreen {
    private final FragmentActivity activity;
    private final JournalStore store;
    private final Runnable render;
    private final Consumer<AlertDialog> showDialog;
    private final BooleanSupplier authorized;
    private MoodHistory.Period period = MoodHistory.Period.WEEK;
    private LocalDate customStart = MoodHistory.Period.MONTH.start(LocalDate.now()), customEnd = LocalDate.now();
    private LocalDate pendingStart = customStart, pendingEnd = customEnd;
    private final DateTimeFormatter dates = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault());

    HistoryScreen(FragmentActivity activity, JournalStore store, Bundle state, Runnable render,
                  Consumer<AlertDialog> showDialog, BooleanSupplier authorized) {
        this.activity = activity;
        this.store = store;
        this.render = render;
        this.showDialog = showDialog;
        this.authorized = authorized;
        if (state != null) {
            period = MoodHistory.Period.valueOf(state.getString("history_period", "WEEK"));
            customStart = LocalDate.parse(state.getString("history_from", customStart.toString()));
            customEnd = LocalDate.parse(state.getString("history_to", customEnd.toString()));
            pendingStart = LocalDate.parse(state.getString("history_pending_from", customStart.toString()));
            pendingEnd = LocalDate.parse(state.getString("history_pending_to", customEnd.toString()));
        }
    }

    void saveState(Bundle state) {
        state.putString("history_period", period.name());
        state.putString("history_from", customStart.toString());
        state.putString("history_to", customEnd.toString());
        state.putString("history_pending_from", pendingStart.toString());
        state.putString("history_pending_to", pendingEnd.toString());
    }

    void show(LinearLayout page) {
        if (!authorized.getAsBoolean()) return;
        add(page, label("DAILY PAUSE  /  LOOKING BACK", 11, true), 0);
        TextView title = label("History", 36, false);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        add(page, title, 16);
        add(page, label("Notice how your feelings move over time.", 15, false), 8);
        LinearLayout ranges = column();
        int[] rangeIds = {R.id.range_week, R.id.range_month, R.id.range_three_months, R.id.range_year, R.id.range_custom};
        LinearLayout row = null;
        for (MoodHistory.Period option : MoodHistory.Period.values()) {
            if (option.ordinal() == 0 || option.ordinal() == 3) {
                row = new LinearLayout(activity);
                add(ranges, row, option.ordinal() == 0 ? 0 : 8);
            }
            Button choice = button(option.label, option == period);
            choice.setId(rangeIds[option.ordinal()]);
            choice.setSelected(option == period);
            choice.setContentDescription(option.label + (option == period ? ", selected" : ""));
            choice.setOnClickListener(v -> {
                if (!authorized.getAsBoolean()) return;
                period = option;
                render.run();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (row.getChildCount() > 0) p.setMarginStart(dp(6));
            row.addView(choice, p);
        }
        add(page, ranges, 24);
        if (period == MoodHistory.Period.CUSTOM) {
            LinearLayout custom = card();
            TextView error = label("", 14, false);
            error.setId(R.id.history_date_error);
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            Button from = button(activity.getString(R.string.history_from_label, pendingStart.format(dates)), false);
            from.setId(R.id.history_from);
            Button to = button(activity.getString(R.string.history_to_label, pendingEnd.format(dates)), false);
            to.setId(R.id.history_to);
            from.setOnClickListener(v -> pickDate(pendingStart, date -> {
                pendingStart = date;
                from.setText(activity.getString(R.string.history_from_label, date.format(dates)));
                error.setText("");
            }));
            to.setOnClickListener(v -> pickDate(pendingEnd, date -> {
                pendingEnd = date;
                to.setText(activity.getString(R.string.history_to_label, date.format(dates)));
                error.setText("");
            }));
            add(custom, from, 0);
            add(custom, to, 8);
            add(custom, error, 4);
            Button apply = button("Apply dates", true);
            apply.setId(R.id.history_apply);
            apply.setOnClickListener(v -> {
                if (!authorized.getAsBoolean()) return;
                if (pendingEnd.isBefore(pendingStart)) {
                    error.setText(R.string.history_invalid_range);
                    return;
                }
                customStart = pendingStart;
                customEnd = pendingEnd;
                render.run();
            });
            add(custom, apply, 8);
            add(page, custom, 16);
        }
        LocalDate today = LocalDate.now();
        LocalDate from = period == MoodHistory.Period.CUSTOM ? customStart : period.start(today);
        LocalDate to = period == MoodHistory.Period.CUSTOM ? customEnd : today;
        MoodHistory.Series series;
        try { series = MoodHistory.summarize(store.moodsBetween(from, to, ZoneId.systemDefault()), from, to, ZoneId.systemDefault()); }
        catch (android.database.SQLException e) {
            add(page, label("Your mood history couldn’t be opened. Please try again. Your entries have not been removed.", 16, false), 24);
            return;
        }
        TextView dateRange = label(from.format(dates) + " – " + to.format(dates), 14, true);
        dateRange.setId(R.id.history_date_range);
        add(page, dateRange, 24);
        String grouping = switch (series.grouping()) { case DAILY -> "Daily"; case WEEKLY -> "Weekly"; case MONTHLY -> "Monthly"; };
        TextView averaging = label(grouping + " averages of recorded check-ins", 14, false);
        averaging.setId(R.id.history_grouping);
        add(page, averaging, 6);
        LinearLayout chartCard = card();
        MoodChartView graph = new MoodChartView(activity, series);
        graph.setId(R.id.mood_chart);
        add(chartCard, graph, 0);
        add(page, chartCard, 14);
        add(page, label("Missing days are gaps, never zero. Multiple check-ins in a period are averaged; no missing values are filled in.", 13, false), 12);
        TextView summary = label(series.responses() + " recorded check-ins · " + series.recordedDays()
                + " of " + series.totalDays() + " days recorded", 15, true);
        summary.setId(R.id.history_summary);
        add(page, summary, 20);
        add(page, label("Mood scale: Low = 1 · Down = 2 · Okay = 3 · Good = 4 · Great = 5", 13, false), 10);
        Button details = button("View plotted averages", false);
        details.setId(R.id.history_values_toggle);
        LinearLayout values = column();
        values.setVisibility(View.GONE);
        details.setOnClickListener(v -> {
            if (!authorized.getAsBoolean()) return;
            if (values.getChildCount() == 0) for (MoodHistory.Bucket bucket : series.buckets()) {
                String span = bucket.start().format(dates) + (bucket.end().equals(bucket.start()) ? "" : " – " + bucket.end().format(dates));
                String value = bucket.average() == null ? "No check-ins" : String.format(Locale.getDefault(), "%.2f", bucket.average())
                        + " · " + bucket.responses() + " check-ins";
                add(values, label(span + "\n" + value, 14, false), 14);
            }
            values.setVisibility(values.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        add(page, details, 20);
        add(page, values, 0);
    }

    private void pickDate(LocalDate date, Consumer<LocalDate> selected) {
        if (!authorized.getAsBoolean()) return;
        DatePickerDialog picker = new DatePickerDialog(activity, (view, year, month, day) -> {
            if (authorized.getAsBoolean()) selected.accept(LocalDate.of(year, month + 1, day));
        }, date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        showDialog.accept(picker);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(12), dp(16), dp(12), dp(16));
        view.setBackground(shape(Color.WHITE));
        return view;
    }
    private TextView label(String value, int size, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Appearance.color(activity, Color.rgb(49, 43, 53)));
        view.setLineSpacing(dp(3), 1);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }
    private Button button(String value, boolean selected) {
        Button view = new Button(activity);
        view.setText(value);
        view.setTextSize(13);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        view.setMinimumWidth(0);
        view.setMinWidth(0);
        view.setPadding(dp(8), dp(10), dp(8), dp(10));
        view.setTextColor(selected ? Appearance.onAccent(activity) : Appearance.color(activity, Color.rgb(112, 81, 128)));
        view.setBackground(shape(selected ? Color.rgb(112, 81, 128) : Color.rgb(237, 230, 241)));
        return view;
    }
    private GradientDrawable shape(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Appearance.color(activity, color));
        drawable.setCornerRadius(dp(18));
        return drawable;
    }
    private void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(margin);
        parent.addView(child, p);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
