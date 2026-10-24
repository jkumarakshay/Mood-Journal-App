package com.moodjournal.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.PopupWindow;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AnalyticsMenuLayoutTest {
    @Test public void choicesFitBothThemesAndKeepNavigationProtected() { checkLayout(360, 800, 1); }
    @Test public void choicesWrapWithoutClippingOnSmallScreensWithLargeText() { checkLayout(320, 568, 1.6f); }

    private void checkLayout(int width, int height, float fontScale) {
        RuntimeEnvironment.setQualifiers("w" + width + "dp-h" + height + "dp-port-mdpi"); RuntimeEnvironment.setFontScale(fontScale);
        Context context = RuntimeEnvironment.getApplication();
        for (boolean dark : new boolean[]{false, true}) {
            Appearance.setDark(context, dark);
            context.getSharedPreferences("journal_lock", 0).edit().clear().commit();
            try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
                MainActivity activity = c.get(); TourLayoutTest.layout(activity, width, height);
                activity.findViewById(R.id.history_tab).performClick(); ListPopupWindow menu = AnalyticsTestSupport.menu(activity);
                assertTrue(menu.isShowing()); assertEquals(2, menu.getListView().getAdapter().getCount());
                assertTrue(menu.getWidth() <= width - 32);
                for (int i = 0; i < 2; i++) {
                    LinearLayout row = (LinearLayout) menu.getListView().getAdapter().getView(i, null, menu.getListView());
                    row.measure(View.MeasureSpec.makeMeasureSpec(menu.getWidth() - 24, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
                    row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
                    assertTrue(row.getHeight() >= 84); assertFalse(row.isActivated());
                    LinearLayout words = (LinearLayout) row.getChildAt(1);
                    for (int j = 0; j < words.getChildCount(); j++) {
                        TextView text = (TextView) words.getChildAt(j);
                        assertTrue(text.getWidth() > 0); assertTrue(text.getBottom() <= words.getHeight());
                        for (int line = 0; line < text.getLineCount(); line++) assertEquals(0, text.getLayout().getEllipsisCount(line));
                    }
                }
                PopupWindow window = ReflectionHelpers.getField(menu, "mPopup");
                View decor = window.getContentView().getRootView();
                decor.measure(View.MeasureSpec.makeMeasureSpec(menu.getWidth(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(window.getHeight(), View.MeasureSpec.EXACTLY));
                decor.layout(0, 0, menu.getWidth(), window.getHeight());
                assertEquals(2, menu.getListView().getChildCount());
                menu.performItemClick(1);
                assertFalse(menu.isShowing());
                assertNotNull(activity.findViewById(R.id.unlock_submit));
                assertNull(activity.findViewById(R.id.analysis_title));
                JournalTest.unlockJournal(activity);
                TourLayoutTest.layout(activity, width, height);
                activity.findViewById(R.id.analytics_selector).performClick(); menu = AnalyticsTestSupport.menu(activity);
                View selected = menu.getListView().getAdapter().getView(1, null, menu.getListView());
                View other = menu.getListView().getAdapter().getView(0, null, menu.getListView());
                assertTrue(selected.isActivated()); assertFalse(other.isActivated());
                assertTrue(selected.getContentDescription().toString().contains("Current view"));
                c.pause().stop(); assertFalse(menu.isShowing());
            }
        }
    }
}
