package com.moodjournal.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;

/** Hue around the circle, saturation from its center; produces opaque RGB colors. */
final class ColorWheelView extends View {
    interface Listener { void changed(float hue, float saturation); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float hue, saturation, brightness = 1, cx, cy, radius;
    private Shader sweep, white;
    private boolean tracking;
    private Listener listener;

    ColorWheelView(Context context) {
        super(context); setFocusable(true); setSaveEnabled(false); describe();
    }
    void setListener(Listener listener) { this.listener = listener; }
    void setHsv(float[] hsv) { hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]; describe(); invalidate(); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = resolveSize(dp(250), widthSpec);
        setMeasuredDimension(width, resolveSize(Math.min(width, dp(250)), heightSpec));
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cx = w / 2f; cy = h / 2f; radius = Math.max(1, Math.min(w, h) / 2f - dp(12));
        sweep = new SweepGradient(cx, cy, new int[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED}, null);
        white = new RadialGradient(cx, cy, radius, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (sweep == null) return;
        paint.setStyle(Paint.Style.FILL); paint.setShader(sweep); canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(white); canvas.drawCircle(cx, cy, radius, paint); paint.setShader(null);
        paint.setColor(Color.argb(Math.round(255 * (1 - brightness)), 0, 0, 0)); canvas.drawCircle(cx, cy, radius, paint);
        double angle = Math.toRadians(hue);
        float x = cx + (float) Math.cos(angle) * saturation * radius, y = cy + (float) Math.sin(angle) * saturation * radius;
        paint.setStyle(Paint.Style.STROKE); paint.setColor(Color.BLACK); paint.setStrokeWidth(dp(6)); canvas.drawCircle(x, y, dp(7), paint);
        paint.setColor(Color.WHITE); paint.setStrokeWidth(dp(3)); canvas.drawCircle(x, y, dp(7), paint);
        if (isFocused()) { paint.setColor(Appearance.color(getContext(), 0xFF705180)); paint.setStrokeWidth(dp(2)); canvas.drawCircle(cx, cy, radius + dp(10), paint); }
        paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        float dx = event.getX() - cx, dy = event.getY() - cy;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (Math.hypot(dx, dy) > radius) return false;
                tracking = true; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true); choose(dx, dy); return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false; choose(dx, dy); return true;
            case MotionEvent.ACTION_UP:
                if (!tracking) return false;
                choose(dx, dy); tracking = false; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                tracking = false; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); return true;
            default: return tracking;
        }
    }
    private void choose(float dx, float dy) {
        hue = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360);
        saturation = Math.min(1, (float) Math.hypot(dx, dy) / radius); changed();
    }
    private void changed() { describe(); invalidate(); if (listener != null) listener.changed(hue, saturation); }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (!isEnabled()) return super.onKeyDown(code, event);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: hue = (hue + 355) % 360; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: hue = (hue + 5) % 360; break;
            case KeyEvent.KEYCODE_DPAD_UP: saturation = Math.min(1, saturation + .05f); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: saturation = Math.max(0, saturation - .05f); break;
            default: return super.onKeyDown(code, event);
        }
        changed(); return true;
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, 359, Math.min(359, hue)));
        if (isEnabled()) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }
    }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (isEnabled()) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                hue = (hue + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 5 : 355)) % 360;
            } else if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && args != null && args.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                float next = args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                if (!Float.isFinite(next)) return false; hue = Math.max(0, Math.min(359, next));
            } else return super.performAccessibilityAction(action, args);
            if (saturation == 0) saturation = .5f;
            changed(); return true;
        }
        return super.performAccessibilityAction(action, args);
    }
    private void describe() {
        String hex = String.format(Locale.ROOT, "#%06X", Color.HSVToColor(new float[]{hue, saturation, brightness}) & 0xFFFFFF);
        setContentDescription(getContext().getString(R.string.heap_wheel_description, Math.round(hue), Math.round(saturation * 100), hex));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
