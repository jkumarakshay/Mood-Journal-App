package com.moodjournal.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,qualifiers="mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HeapColorWheelRenderingTest {
    @Test public void renderedWheelMatchesRgbColorsAndBrightness() {
        ColorWheelView wheel=new ColorWheelView(RuntimeEnvironment.getApplication());
        int exact=View.MeasureSpec.makeMeasureSpec(250,View.MeasureSpec.EXACTLY);wheel.measure(exact,exact);wheel.layout(0,0,250,250);
        for(float brightness:new float[]{1,.5f}) {
            // Put the marker at blue so it does not cover these independent sample points.
            wheel.setHsv(new float[]{240,1,brightness});Bitmap image=Bitmap.createBitmap(250,250,Bitmap.Config.ARGB_8888);wheel.draw(new Canvas(image));
            near(Color.HSVToColor(new float[]{0,0,brightness}),image.getPixel(125,125),2);
            near(Color.HSVToColor(new float[]{0,1,brightness}),image.getPixel(235,125),10);
            near(Color.HSVToColor(new float[]{180,1,brightness}),image.getPixel(15,125),10);
        }
    }
    private void near(int expected,int actual,int tolerance) {
        assertEquals(255,Color.alpha(actual));assertTrue(Math.abs(Color.red(expected)-Color.red(actual))<=tolerance);
        assertTrue(Math.abs(Color.green(expected)-Color.green(actual))<=tolerance);assertTrue(Math.abs(Color.blue(expected)-Color.blue(actual))<=tolerance);
    }
}
