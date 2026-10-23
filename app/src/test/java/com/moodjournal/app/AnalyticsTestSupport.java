package com.moodjournal.app;

import android.widget.PopupMenu;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowPopupMenu;

final class AnalyticsTestSupport {
    static void graph(MainActivity activity) { select(activity, R.id.analytics_graph); }
    static void analysis(MainActivity activity) { select(activity, R.id.analytics_analysis); }
    private static void select(MainActivity activity, int item) {
        activity.findViewById(R.id.history_tab).performClick();
        PopupMenu menu = ShadowPopupMenu.getLatestPopupMenu();
        Shadows.shadowOf(menu).getOnMenuItemClickListener().onMenuItemClick(menu.getMenu().findItem(item)); menu.dismiss();
    }
}
