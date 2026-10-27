package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TourLayoutTest {
    @Test public void allCoachMarksRemainVisibleAndNextIsReachableInBothThemes() {
        checkLayout(360, 800, 1f);
    }
    @Test public void coachMarksKeepActionsReachableWithLargeTextOnSmallScreen() {
        checkLayout(320, 568, 1.6f);
    }
    @Test public void coachMarksFitLandscapeWithoutCoveringTheirTargets() {
        checkLayout(640, 360, 1f);
    }
    private void checkLayout(int width, int height, float scale) {
        RuntimeEnvironment.setQualifiers("w" + width + "dp-h" + height + "dp-" + (width > height ? "land" : "port") + "-mdpi");
        RuntimeEnvironment.setFontScale(scale);
        Context context = RuntimeEnvironment.getApplication();
        ReturningUserApplication.linkTestAccount(context);
        context.getSharedPreferences(UpdateController.PREFS, 0).edit().putBoolean("automatic", false).commit();
        for (boolean dark : new boolean[]{false, true}) {
            context.getSharedPreferences(OnboardingState.PREFS, 0).edit().putInt("step", -1).commit();
            Appearance.setDark(context, dark);
            try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
                layout(c.get(), width, height);
                c.get().findViewById(R.id.welcome_tour).performClick();
                for (int step=0;step<9;step++) {
                    View content = layout(c.get(), width, height);
                    View next = c.get().findViewById(R.id.tour_next);
                    Rect bounds = new Rect(); assertTrue("Next visible at step " + step, next.getGlobalVisibleRect(bounds));
                    assertEquals("Next not clipped at step " + step, next.getHeight(), bounds.height());
                    assertTrue(next.getHeight() >= 48);
                    View overlay = c.get().findViewById(R.id.tour_overlay);
                    View bubble = ((ViewGroup)overlay).getChildAt(0);
                    assertTrue(bubble.getTop() >= 0); assertTrue(bubble.getBottom() <= height);
                    View target = ReflectionHelpers.getField(overlay, "target");
                    if (target != null) {
                        Rect targetBounds = new Rect(); assertTrue("Target visible at step " + step, target.getGlobalVisibleRect(targetBounds));
                        Rect bubbleBounds = new Rect(); assertTrue(bubble.getGlobalVisibleRect(bubbleBounds));
                        assertTrue("Target should have space beside bubble at step " + step + ": target=" + targetBounds + ", bubble=" + bubbleBounds,
                                targetBounds.bottom <= bubbleBounds.top || targetBounds.top >= bubbleBounds.bottom);
                    }
                    next.performClick();
                }
                assertNull(c.get().findViewById(R.id.tour_overlay));
            }
        }
    }
    static View layout(MainActivity activity, int width, int height) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        View content = activity.findViewById(android.R.id.content);
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        content.layout(0,0,width,height);
        content.getViewTreeObserver().dispatchOnPreDraw();
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        content.layout(0,0,width,height);
        return content;
    }
}
