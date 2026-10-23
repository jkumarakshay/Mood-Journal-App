package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.SeekBar;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class HeapColorPickerTest {
    private Context context;
    @Before public void reset() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("journal.db");
        for(String p:new String[]{"journal_lock","draft","heap_notices","appearance"})context.getSharedPreferences(p,0).edit().clear().commit();
    }
    private HeapColorPicker picker() {
        HeapColorPicker picker=new HeapColorPicker(context);picker.findViewById(R.id.heap_color_toggle).performClick();
        picker.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        picker.layout(0,0,320,picker.getMeasuredHeight());return picker;
    }
    @Test public void recommendationsAndDefaultAreOptionalAndStaySynchronized() {
        HeapColorPicker picker=picker();assertNull(picker.getColor());
        picker.findViewById(R.id.heap_color_sage).performClick();assertEquals(Integer.valueOf(JournalHeap.COLORS[2]),picker.getColor());
        assertEquals("#E8EDE1",((EditText)picker.findViewById(R.id.heap_color_hex)).getText().toString());
        assertTrue(picker.findViewById(R.id.heap_color_sage).isSelected());
        picker.findViewById(R.id.heap_color_default).performClick();assertNull(picker.getColor());assertFalse(picker.findViewById(R.id.heap_color_sage).isSelected());
    }
    @Test public void wheelSupportsHueSaturationAndKeyboardSelection() {
        HeapColorPicker picker=picker();ColorWheelView wheel=picker.findViewById(R.id.heap_color_wheel);
        float cx=wheel.getWidth()/2f,cy=wheel.getHeight()/2f,r=Math.min(wheel.getWidth(),wheel.getHeight())/2f-12;
        touch(wheel,MotionEvent.ACTION_DOWN,cx+r,cy);touch(wheel,MotionEvent.ACTION_UP,cx+r,cy);assertEquals(Integer.valueOf(Color.RED),picker.getColor());
        touch(wheel,MotionEvent.ACTION_DOWN,cx,cy);touch(wheel,MotionEvent.ACTION_UP,cx,cy);assertEquals(Integer.valueOf(Color.WHITE),picker.getColor());
        assertTrue(wheel.onKeyDown(KeyEvent.KEYCODE_DPAD_UP,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP)));
        assertEquals(255,Color.red(picker.getColor()));assertTrue(Color.green(picker.getColor())<255);
        picker.setColor(Color.RED);wheel.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));
        assertTrue(Color.green(picker.getColor())>0);assertEquals(0,Color.blue(picker.getColor()));
    }
    @Test public void brightnessIncludesBlackWhiteAndDarkShades() {
        HeapColorPicker picker=picker();SeekBar brightness=picker.findViewById(R.id.heap_color_brightness);
        picker.setColor(Color.WHITE);brightness.setProgress(0);assertEquals(Integer.valueOf(Color.BLACK),picker.getColor());
        brightness.setProgress(100);assertEquals(Integer.valueOf(Color.WHITE),picker.getColor());
        picker.setColor(Color.RED);brightness.setProgress(50);assertEquals(0,Color.green(picker.getColor()));assertEquals(0,Color.blue(picker.getColor()));assertTrue(Color.red(picker.getColor())>=127&&Color.red(picker.getColor())<=128);
    }
    @Test public void hexInputValidatesAndAcceptsAnyOpaqueRgb() {
        HeapColorPicker picker=picker();EditText hex=picker.findViewById(R.id.heap_color_hex);
        hex.setText("123456");assertEquals(Integer.valueOf(0xFF123456),picker.getColor());
        hex.setText("#ff00ff");assertEquals(Integer.valueOf(Color.MAGENTA),picker.getColor());
        hex.setText("#12GGFF");assertFalse(picker.isValid());assertThrows(IllegalArgumentException.class,picker::getColor);assertEquals(View.VISIBLE,picker.findViewById(R.id.heap_color_error).getVisibility());
        hex.setText("");assertTrue(picker.isValid());assertNull(picker.getColor());
        assertThrows(IllegalArgumentException.class,()->picker.setColor(0x80123456));
    }
    @Test public void accessibilityCanSetHueWithoutTouchingTheCircle() {
        HeapColorPicker picker=picker();ColorWheelView wheel=picker.findViewById(R.id.heap_color_wheel);
        Bundle args=new Bundle();args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,120);
        assertTrue(wheel.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args));
        assertEquals(255,Color.green(picker.getColor()));assertTrue(Color.red(picker.getColor())<255);
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,Float.NaN);
        assertFalse(wheel.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args));
    }
    @Test public void outsideTouchesAndCanceledDragsLeaveScrollingAvailable() {
        HeapColorPicker picker=picker();ColorWheelView wheel=picker.findViewById(R.id.heap_color_wheel);
        assertFalse(touch(wheel,MotionEvent.ACTION_DOWN,0,0));assertNull(picker.getColor());
        assertTrue(touch(wheel,MotionEvent.ACTION_DOWN,wheel.getWidth()/2f,wheel.getHeight()/2f));
        assertTrue(touch(wheel,MotionEvent.ACTION_CANCEL,0,0));assertFalse(touch(wheel,MotionEvent.ACTION_MOVE,0,0));
    }
    @Test public void createBlocksInvalidCodeThenPersistsCustomColorInSecureDialog() {
        context.getSharedPreferences("heap_notices",0).edit().putBoolean("created",true).commit();
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();JournalStore store=new JournalStore(context)) {
            c.get().findViewById(R.id.journal_tab).performClick();JournalTest.unlockJournal(c.get());c.get().findViewById(R.id.journal_add).performClick();
            Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertTrue((dialog.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
            ((EditText)dialog.findViewById(R.id.heap_name)).setText("Custom color");dialog.findViewById(R.id.heap_color_toggle).performClick();
            EditText hex=dialog.findViewById(R.id.heap_color_hex);hex.setText("#BADHEX");dialog.findViewById(R.id.heap_color_toggle).performClick();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing());assertTrue(store.heaps().isEmpty());assertTrue(hex.isShown());
            hex.setText("#103A6F");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(Integer.valueOf(0xFF103A6F),store.heaps().get(0).color());
        }
    }
    @Test public void cancelingCustomSelectionDoesNotCreateAHeap() {
        context.getSharedPreferences("heap_notices",0).edit().putBoolean("created",true).commit();
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();JournalStore store=new JournalStore(context)) {
            c.get().findViewById(R.id.journal_tab).performClick();JournalTest.unlockJournal(c.get());c.get().findViewById(R.id.journal_add).performClick();Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();((EditText)dialog.findViewById(R.id.heap_name)).setText("Not saved");((HeapColorPicker)dialog.findViewById(R.id.heap_color)).setColor(Color.BLACK);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertTrue(store.heaps().isEmpty());assertTrue(store.all().isEmpty());
        }
    }
    private boolean touch(ColorWheelView wheel,int action,float x,float y) {
        MotionEvent event=MotionEvent.obtain(0,10,action,x,y,0);try{return wheel.onTouchEvent(event);}finally{event.recycle();}
    }
}
