package com.moodjournal.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

/** Inline picker stays inside the existing secure Heap dialog and commits only with Create Heap. */
final class HeapColorPicker extends LinearLayout {
    private final float[] hsv = {0, 0, 1};
    private Integer color;
    private boolean updating, valid = true, swatchesReady;
    private final LinearLayout panel;
    private final ColorWheelView wheel;
    private final SeekBar brightness;
    private final EditText hex;
    private final TextView summary, rgb, error, brightnessLabel;
    private final View swatch;
    private final Button toggle;
    private final Button[] recommended = new Button[5];

    HeapColorPicker(Context context) {
        super(context); setOrientation(VERTICAL); setSaveEnabled(false);
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        swatch = new View(context); row.addView(swatch, new LayoutParams(dp(36), dp(36)));
        summary = label("", 15); summary.setId(R.id.heap_color_preview);
        LayoutParams summarySpace = new LayoutParams(0, -2, 1); summarySpace.setMarginStart(dp(12)); row.addView(summary, summarySpace);
        toggle = button(R.string.heap_choose_color); toggle.setId(R.id.heap_color_toggle); row.addView(toggle);
        addView(row);
        panel = new LinearLayout(context); panel.setOrientation(VERTICAL); panel.setVisibility(GONE);
        addView(panel);
        TextView instructions = label(context.getString(R.string.heap_color_help), 14); add(panel, instructions, 12);
        wheel = new ColorWheelView(context); wheel.setId(R.id.heap_color_wheel);
        LayoutParams wheelSpace = new LayoutParams(-1, dp(250)); wheelSpace.gravity = Gravity.CENTER_HORIZONTAL; panel.addView(wheel, wheelSpace);
        brightnessLabel = label("", 14); add(panel, brightnessLabel, 4);
        brightness = new SeekBar(context); brightness.setId(R.id.heap_color_brightness); brightness.setMax(100); brightness.setProgress(100);
        brightness.setSaveEnabled(false); brightness.setMinimumHeight(dp(48)); brightness.setContentDescription(context.getString(R.string.heap_color_brightness));
        brightness.setProgressTintList(ColorStateList.valueOf(Appearance.color(context, 0xFF705180)));
        brightness.setThumbTintList(ColorStateList.valueOf(Appearance.color(context, 0xFF705180))); add(panel, brightness, 0);
        TextView codeLabel = label(context.getString(R.string.heap_color_code), 14); codeLabel.setLabelFor(R.id.heap_color_hex); add(panel, codeLabel, 10);
        hex = new EditText(context); hex.setId(R.id.heap_color_hex); hex.setHint(R.string.heap_color_code_hint); hex.setSingleLine(true);
        hex.setTypeface(Typeface.MONOSPACE); hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)}); hex.setMinHeight(dp(52)); hex.setSaveEnabled(false);
        hex.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING); hex.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); add(panel, hex, 4);
        error = label(context.getString(R.string.heap_color_invalid), 14); error.setId(R.id.heap_color_error);
        error.setVisibility(GONE); error.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE); add(panel, error, 4);
        rgb = label("", 14); add(panel, rgb, 8);
        add(panel, label(context.getString(R.string.heap_color_recommended), 14), 16);
        LinearLayout choices = new LinearLayout(context);
        for (int i = 0; i < recommended.length; i++) {
            final int index = i + 1; Button choice = new Button(context); recommended[i] = choice;
            choice.setId(new int[]{R.id.heap_color_lavender, R.id.heap_color_sage, R.id.heap_color_peach, R.id.heap_color_sky, R.id.heap_color_rose}[i]);
            choice.setMinWidth(0); choice.setMinimumWidth(0); choice.setPadding(0, 0, 0, 0); choice.setTextSize(18); choice.setAllCaps(false);
            choice.setBackgroundTintList(null); choice.setStateListAnimator(null); choice.setSaveEnabled(false);
            choice.setContentDescription(context.getString(R.string.heap_color_swatch, JournalHeap.COLOR_NAMES[index], hex(JournalHeap.COLORS[index])));
            choice.setTooltipText(JournalHeap.COLOR_NAMES[index]);
            choice.setOnClickListener(v -> setColor(JournalHeap.COLORS[index]));
            LayoutParams space = new LayoutParams(0, dp(52), 1); space.setMarginEnd(dp(6)); choices.addView(choice, space);
        }
        add(panel, choices, 6);
        Button reset = button(R.string.heap_color_default); reset.setId(R.id.heap_color_default); reset.setOnClickListener(v -> setColor(null)); add(panel, reset, 12);
        wheel.setListener((hue, saturation) -> { hsv[0] = hue; hsv[1] = saturation; fromHsv(); });
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { if (!updating) { hsv[2] = progress / 100f; fromHsv(); } }
        });
        hex.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (updating) return;
                String value = s.toString().trim();
                if (value.isEmpty()) { color = null; hsv[0] = hsv[1] = 0; hsv[2] = 1; valid = true; render(false); }
                else if (value.matches("#?[0-9a-fA-F]{6}")) { color = Color.parseColor(value.startsWith("#") ? value : "#" + value); Color.colorToHSV(color, hsv); valid = true; render(false); }
                else { valid = false; error.setVisibility(VISIBLE); }
            }
            public void afterTextChanged(Editable s) {}
        });
        toggle.setOnClickListener(v -> {
            boolean open = panel.getVisibility() != VISIBLE; panel.setVisibility(open ? VISIBLE : GONE);
            toggle.setText(open ? R.string.heap_hide_color : R.string.heap_choose_color);
            if (open) {
                ((InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindowToken(), 0);
                wheel.requestFocus(); wheel.post(() -> wheel.requestRectangleOnScreen(new Rect(0, 0, wheel.getWidth(), wheel.getHeight()), false));
            }
        });
        render(true);
    }

    Integer getColor() { if (!valid) throw new IllegalArgumentException(getContext().getString(R.string.heap_color_invalid)); return color; }
    boolean isValid() { return valid; }
    void focusCode() {
        panel.setVisibility(VISIBLE); toggle.setText(R.string.heap_hide_color); hex.requestFocus();
        hex.post(() -> hex.requestRectangleOnScreen(new Rect(0, 0, hex.getWidth(), hex.getHeight()), false));
    }
    void setColor(Integer value) {
        if (value != null && Color.alpha(value) != 255) throw new IllegalArgumentException("Heap colors must be opaque RGB");
        color = value; valid = true; Color.colorToHSV(value == null ? Color.WHITE : value, hsv); render(true);
    }
    private void fromHsv() { color = Color.HSVToColor(hsv); valid = true; render(true); }
    private void render(boolean updateCode) {
        updating = true;
        try {
            int preview = color == null ? Appearance.color(getContext(), Color.WHITE) : color;
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(preview);
            dot.setStroke(dp(1), Appearance.color(getContext(), 0xFFE8E1EA)); swatch.setBackground(dot);
            summary.setText(color == null ? getContext().getString(R.string.heap_color_default_name) : hex(color));
            wheel.setHsv(hsv); brightness.setProgress(Math.round(hsv[2] * 100));
            brightnessLabel.setText(getContext().getString(R.string.heap_color_brightness_value, Math.round(hsv[2] * 100)));
            rgb.setText(color == null ? getContext().getString(R.string.heap_color_optional) : getContext().getString(R.string.heap_color_rgb, Color.red(color), Color.green(color), Color.blue(color)));
            if (updateCode) hex.setText(color == null ? "" : hex(color));
            error.setVisibility(GONE);
            for (int i = 0; i < recommended.length; i++) {
                int value = JournalHeap.COLORS[i + 1]; boolean selected = color != null && color == value;
                Button choice = recommended[i]; if (swatchesReady && choice.isSelected() == selected) continue;
                choice.setSelected(selected); choice.setText(selected ? "✓" : ""); choice.setTextColor(0xFF312B35);
                GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(value);
                circle.setStroke(dp(selected ? 3 : 1), Appearance.color(getContext(), selected ? 0xFF705180 : 0xFFE8E1EA));
                choice.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33705180), circle, null));
            }
            swatchesReady = true;
        } finally { updating = false; }
    }
    private static String hex(int color) { return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF); }
    private TextView label(String value, int size) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(Appearance.color(getContext(), 0xFF312B35)); view.setLineSpacing(dp(3), 1); return view;
    }
    private Button button(int text) {
        Button button = new Button(getContext()); button.setText(text); button.setAllCaps(false); button.setTextSize(14); button.setMinHeight(dp(48));
        button.setTextColor(Appearance.color(getContext(), 0xFF705180)); button.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(Appearance.color(getContext(), 0xFFEDE6F1)); shape.setCornerRadius(dp(14));
        button.setBackgroundTintList(null); button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33705180), shape, null)); return button;
    }
    private void add(LinearLayout parent, View view, int top) { LayoutParams params = new LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(view, params); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
