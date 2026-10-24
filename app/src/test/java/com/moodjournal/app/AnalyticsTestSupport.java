package com.moodjournal.app;

import android.widget.ListPopupWindow;
import org.robolectric.util.ReflectionHelpers;

final class AnalyticsTestSupport {
    static void graph(MainActivity activity) { select(activity, R.id.analytics_graph); }
    static void analysis(MainActivity activity) { select(activity, R.id.analytics_analysis); }
    static ListPopupWindow menu(MainActivity activity) { return ReflectionHelpers.getField(activity, "analyticsMenu"); }
    static void choose(ListPopupWindow menu, int item) {
        int position = item == R.id.analytics_analysis ? 1 : 0;
        menu.performItemClick(position);
    }
    private static void select(MainActivity activity, int item) {
        activity.findViewById(R.id.history_tab).performClick();
        choose(menu(activity), item);
    }
}
