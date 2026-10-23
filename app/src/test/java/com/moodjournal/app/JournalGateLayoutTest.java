package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class JournalGateLayoutTest {
    @Test public void setupActionsAndPopupRemainReachableInBothThemes() { checkLayout(360, 800, 1); }
    @Test public void setupActionsAndPopupSupportLargeTextOnSmallScreen() { checkLayout(320, 568, 1.6f); }

    private void checkLayout(int width, int height, float fontScale) {
        RuntimeEnvironment.setQualifiers("w" + width + "dp-h" + height + "dp-port-mdpi");
        RuntimeEnvironment.setFontScale(fontScale);
        Context context = RuntimeEnvironment.getApplication();
        for (boolean dark : new boolean[]{false, true}) {
            context.getSharedPreferences("journal_lock", 0).edit().clear().commit();
            Appearance.setDark(context, dark);
            try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
                MainActivity activity = c.get(); activity.findViewById(R.id.journal_tab).performClick();
                TourLayoutTest.layout(activity, width, height);
                for (int id : new int[]{R.id.unlock_submit, R.id.unlock_biometric}) {
                    Button action = activity.findViewById(id);
                    View ancestor = action;
                    while (!(ancestor instanceof ScrollView)) ancestor = (View)ancestor.getParent();
                    ((ScrollView)ancestor).scrollTo(0, action.getTop());
                    Rect bounds = new Rect(); assertTrue(action.getGlobalVisibleRect(bounds));
                    assertEquals(action.getHeight(), bounds.height());
                    assertTrue(action.getHeight() >= 60);
                    assertTrue(action.getLayout().getEllipsisCount(0) == 0);
                }
                activity.findViewById(R.id.unlock_submit).performClick();
                AlertDialog popup = ShadowAlertDialog.getLatestAlertDialog();
                View decor = popup.getWindow().getDecorView();
                int dialogWidth = width - 32;
                decor.measure(View.MeasureSpec.makeMeasureSpec(dialogWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height - 32, View.MeasureSpec.AT_MOST));
                decor.layout(0, 0, dialogWidth, decor.getMeasuredHeight());
                assertTrue(decor.getHeight() <= height - 32);
                for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                    Button action = popup.getButton(which);
                    assertTrue(action.getHeight() >= 48);
                    assertTrue(action.getBottom() <= ((View)action.getParent()).getHeight());
                }
                EditText pin = popup.findViewById(R.id.unlock_pin); pin.setText("642081");
                c.pause().stop();
                assertFalse(popup.isShowing()); assertEquals(0, pin.length());
                c.start().resume().visible();
                assertNull(activity.findViewById(R.id.unlock_pin));
                assertNotNull(activity.findViewById(R.id.unlock_submit));
            }
        }
    }
}
