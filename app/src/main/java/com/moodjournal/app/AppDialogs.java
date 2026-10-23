package com.moodjournal.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/** Shared visual treatment; existing dialog actions, validation and privacy remain with their owners. */
final class AppDialogs {
    static AlertDialog.Builder builder(Context context) { return new Builder(context); }

    private static final class Builder extends AlertDialog.Builder {
        private CharSequence title;
        Builder(Context context) { super(context); }

        @Override public AlertDialog.Builder setTitle(int title) { return setTitle(getContext().getText(title)); }

        @Override public AlertDialog.Builder setTitle(CharSequence title) {
            this.title = title;
            super.setTitle(title);
            Context context = getContext();
            LinearLayout heading = new LinearLayout(context);
            heading.setOrientation(LinearLayout.VERTICAL);
            heading.setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 14));
            TextView brand = new TextView(context);
            brand.setText(R.string.popup_brand); brand.setTextSize(11); brand.setLetterSpacing(.12f);
            brand.setTextColor(Appearance.color(context, 0xFF705180));
            brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            brand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            heading.addView(brand);
            TextView label = new TextView(context); label.setId(R.id.popup_title); label.setText(title);
            label.setTextSize(27); label.setTextColor(Appearance.color(context, 0xFF312B35));
            label.setTypeface(Typeface.create("serif", Typeface.NORMAL)); label.setLineSpacing(dp(context, 3), 1);
            if (Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2); space.topMargin = dp(context, 10);
            heading.addView(label, space);
            return super.setCustomTitle(heading);
        }

        @Override public AlertDialog create() {
            AlertDialog dialog = super.create();
            // Keep the semantic/window title as well as the visible custom heading.
            if (title != null) dialog.setTitle(title);
            return dialog;
        }

        @Override public AlertDialog.Builder setView(View view) {
            if (view instanceof ScrollView) return super.setView(view);
            ScrollView scroll = new ScrollView(getContext()); scroll.setClipToPadding(false);
            scroll.addView(view); return super.setView(scroll);
        }
    }

    /** Call after show(), once native buttons and picker contents have been created. */
    static void decorate(AlertDialog dialog) {
        Context context = dialog.getContext();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(context.getDrawable(R.drawable.popup_dialog_background));
            window.setDimAmount(.42f);
            if (!(dialog instanceof DatePickerDialog) && !(dialog instanceof TimePickerDialog))
                window.setLayout(Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(context, 32), dp(context, 520)), ViewGroup.LayoutParams.WRAP_CONTENT);
            window.getDecorView().setClipToOutline(true);
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextColor(Appearance.color(context, 0xFF312B35));
            message.setTextSize(16); message.setLineSpacing(dp(context, 5), 1);
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null) button(context, button, which == AlertDialog.BUTTON_POSITIVE);
        }
        // Native picker editors have tightly sized numeric fields; keep their native geometry.
        if (window != null && !(dialog instanceof DatePickerDialog) && !(dialog instanceof TimePickerDialog))
            fields(context, window.getDecorView());
    }

    private static void button(Context context, Button button, boolean primary) {
        int accent = Appearance.color(context, 0xFF705180);
        int soft = Appearance.color(context, 0xFFEDE6F1);
        int ink = primary ? Appearance.onAccent(context) : accent;
        int muted = Appearance.color(context, 0xFF6D6470);
        button.setAllCaps(false); button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{muted, ink}));
        button.setMinHeight(dp(context, 48)); button.setMinimumHeight(dp(context, 48));
        button.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        button.setBackgroundTintList(null); button.setStateListAnimator(null);
        StateListDrawable surface = new StateListDrawable();
        surface.addState(new int[]{-android.R.attr.state_enabled}, shape(context, soft, 0, 0));
        surface.addState(new int[]{}, shape(context, primary ? accent : soft, 0, 0));
        int ripple = primary ? (Appearance.isDark(context) ? 0x242C1737 : 0x33FFFFFF) : (accent & 0x00FFFFFF) | 0x22000000;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), surface, shape(context, Color.WHITE, 0, 0)));
        if (button.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) {
            margins.setMarginStart(dp(context, 4)); margins.setMarginEnd(dp(context, 4));
            margins.topMargin = dp(context, 4); margins.bottomMargin = dp(context, 8);
            button.setLayoutParams(margins);
        }
    }

    private static void fields(Context context, View view) {
        if (view instanceof EditText field) {
            int fill = Appearance.color(context, 0xFFF5F0F7);
            int border = Appearance.color(context, 0xFFE8E1EA);
            StateListDrawable background = new StateListDrawable();
            background.addState(new int[]{android.R.attr.state_focused}, shape(context, fill, Appearance.color(context, 0xFF705180), 2));
            background.addState(new int[]{}, shape(context, fill, border, 1));
            field.setBackgroundTintList(null); field.setBackground(background);
            field.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
            field.setTextSize(16);
            field.setTextColor(Appearance.color(context, 0xFF312B35)); field.setHintTextColor(Appearance.color(context, 0xFF6D6470));
            if (field.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) {
                margins.bottomMargin = Math.max(margins.bottomMargin, dp(context, 8)); field.setLayoutParams(margins);
            }
        } else if (view instanceof Spinner spinner) {
            spinner.setPopupBackgroundResource(R.drawable.popup_menu_background);
        } else if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) fields(context, group.getChildAt(i));
        }
    }

    private static Drawable shape(Context context, int fill, int stroke, int width) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(context, 14));
        if (width != 0) shape.setStroke(dp(context, width), stroke);
        return shape;
    }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
