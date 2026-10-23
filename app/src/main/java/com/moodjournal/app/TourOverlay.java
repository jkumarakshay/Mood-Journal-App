package com.moodjournal.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A modal coach mark: the highlighted control stays visible but cannot be activated. */
@android.annotation.SuppressLint("ViewConstructor") // Coach marks require a target and callbacks; never inflated from XML.
final class TourOverlay extends FrameLayout {
    private final View target;
    private final LinearLayout bubble;
    private final RectF highlight = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect previous = new Rect();
    private final Rect tracked = new Rect(), visible = new Rect();
    private final int[] origin = new int[2];
    private final Path dim = new Path();
    private final ViewTreeObserver.OnPreDrawListener trackTarget = this::trackTarget;
    private boolean trackTarget() {
        if (target != null && target.getGlobalVisibleRect(tracked) && !tracked.equals(previous)) {
            previous.set(tracked); requestLayout();
            return false; // Reposition after scrolling, before the coach mark is drawn.
        }
        return true;
    }

    TourOverlay(Context context, View target, int step, Runnable next, Runnable skip) {
        super(context);
        this.target = target;
        setId(R.id.tour_overlay);
        setWillNotDraw(false);
        setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setClickable(true);
        setFocusableInTouchMode(true);
        bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(20), dp(18), dp(20), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Appearance.color(context, 0xFFFFFFFF)); background.setCornerRadius(dp(24));
        bubble.setBackground(background);
        bubble.setElevation(dp(12));
        TextView progress = label(context.getString(R.string.tour_progress, step + 1), 12);
        progress.setTextColor(Appearance.color(context, 0xFF705180));
        bubble.addView(progress);
        int[] titles = {R.string.tour_checkin_title, R.string.tour_emotion_title, R.string.tour_writing_title,
                R.string.tour_weather_title, R.string.tour_journal_title, R.string.tour_add_title,
                R.string.tour_analytics_title, R.string.mood_graph, R.string.mood_analysis};
        int[] messages = {R.string.tour_checkin_body, R.string.tour_emotion_body, R.string.tour_writing_body,
                R.string.tour_weather_body, R.string.tour_journal_body, R.string.tour_add_body,
                R.string.tour_analytics_body, R.string.tour_graph_body, R.string.tour_analysis_body};
        TextView title = label(context.getString(titles[step]), 24);
        title.setId(R.id.tour_title);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        ScrollView scroll = new ScrollView(context);
        LinearLayout copy = new LinearLayout(context); copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(title);
        TextView body = label(context.getString(messages[step]), 15);
        body.setId(R.id.tour_body);
        body.setPadding(0, 0, 0, dp(8));
        copy.addView(body, params(12));
        scroll.addView(copy);
        LinearLayout.LayoutParams content = params(12); content.weight = 1;
        bubble.addView(scroll, content);
        LinearLayout actions = new LinearLayout(context);
        Button leave = button(R.string.tour_skip, R.id.tour_skip, false);
        leave.setOnClickListener(v -> skip.run());
        actions.addView(leave, new LinearLayout.LayoutParams(0, -2, 1));
        Button advance = button(R.string.tour_next, R.id.tour_next, true);
        advance.setOnClickListener(v -> next.run());
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, -2, 1); nextParams.setMarginStart(dp(8));
        actions.addView(advance, nextParams);
        bubble.addView(actions, params(12));
        addView(bubble);
        if (android.os.Build.VERSION.SDK_INT >= 28) setAccessibilityPaneTitle(context.getString(titles[step]));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); getViewTreeObserver().addOnPreDrawListener(trackTarget);
    }
    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(trackTarget); super.onDetachedFromWindow();
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        bubble.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.min(dp(520), width - getPaddingLeft() - getPaddingRight() - dp(32))), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, height - getPaddingTop() - getPaddingBottom() - dp(32)), MeasureSpec.AT_MOST));
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        highlight.setEmpty();
        if (target != null && target.getGlobalVisibleRect(visible)) {
            getLocationOnScreen(origin);
            highlight.set(visible); highlight.offset(-origin[0], -origin[1]); highlight.inset(-dp(5), -dp(5));
        }
        int topRoom = Math.max(0, (int) highlight.top - getPaddingTop() - dp(36));
        int bottomRoom = Math.max(0, getHeight() - getPaddingBottom() - (int) highlight.bottom - dp(36));
        boolean above = topRoom > bottomRoom;
        int room = highlight.isEmpty() ? getHeight() - getPaddingTop() - getPaddingBottom() - dp(32) : Math.max(topRoom, bottomRoom);
        bubble.measure(MeasureSpec.makeMeasureSpec(bubble.getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, room), MeasureSpec.AT_MOST));
        int x = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - bubble.getMeasuredWidth()) / 2;
        int y = highlight.isEmpty() ? getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - bubble.getMeasuredHeight()) / 2
                : above ? (int) highlight.top - dp(20) - bubble.getMeasuredHeight() : (int) highlight.bottom + dp(20);
        y = Math.max(getPaddingTop() + dp(16), Math.min(y, getHeight() - getPaddingBottom() - bubble.getMeasuredHeight() - dp(16)));
        bubble.layout(x, y, x + bubble.getMeasuredWidth(), y + bubble.getMeasuredHeight());
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        dim.rewind(); dim.setFillType(Path.FillType.EVEN_ODD);
        dim.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        if (!highlight.isEmpty()) dim.addRoundRect(highlight, dp(12), dp(12), Path.Direction.CW);
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xB3312738); canvas.drawPath(dim, paint);
        if (highlight.isEmpty()) return;
        paint.setColor(0xFFE9D9F5); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
        canvas.drawRoundRect(highlight, dp(12), dp(12), paint);
        float x = Math.max(bubble.getLeft() + dp(22), Math.min(highlight.centerX(), bubble.getRight() - dp(22)));
        boolean above = bubble.getBottom() <= highlight.top;
        float startY = above ? bubble.getBottom() : bubble.getTop();
        float endY = above ? highlight.top - dp(3) : highlight.bottom + dp(3);
        float endX = highlight.centerX();
        canvas.drawLine(x, startY, endX, endY, paint);
        double angle = Math.atan2(endY - startY, endX - x);
        for (int side = 0; side < 2; side++) {
            double offset = side == 0 ? -.55 : .55;
            canvas.drawLine(endX, endY, endX - (float) Math.cos(angle + offset) * dp(9),
                    endY - (float) Math.sin(angle + offset) * dp(9), paint);
        }
    }
    private TextView label(String value, int size) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size);
        view.setTextColor(Appearance.color(getContext(), 0xFF312B35)); view.setLineSpacing(dp(3), 1); return view;
    }
    private Button button(int text, int id, boolean primary) {
        Button view = new Button(getContext()); view.setId(id); view.setText(text); view.setTextSize(15); view.setAllCaps(false);
        view.setGravity(Gravity.CENTER); view.setMinHeight(dp(48)); view.setMinimumWidth(0); view.setMinWidth(0);
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setTextColor(primary ? Appearance.onAccent(getContext()) : Appearance.color(getContext(), 0xFF705180));
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(14));
        shape.setColor(Appearance.color(getContext(), primary ? 0xFF705180 : 0xFFF5F0F7)); view.setBackground(shape); return view;
    }
    private LinearLayout.LayoutParams params(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
