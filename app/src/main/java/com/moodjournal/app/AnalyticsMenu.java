package com.moodjournal.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.PopupWindow;
import android.widget.TextView;

/** Anchored, keyboard-accessible choices using the same palette as the app's dialogs. */
final class AnalyticsMenu extends BaseAdapter {
    private final Context context;
    private final int selected;
    private static final int[] TITLES = {R.string.mood_graph, R.string.mood_analysis};
    private static final int[] DESCRIPTIONS = {R.string.analytics_graph_description, R.string.analytics_analysis_description};
    private static final int[] IDS = {R.id.analytics_graph, R.id.analytics_analysis};
    private static final int[] ICONS = {R.drawable.ic_mood_graph, R.drawable.ic_mood_analysis};

    private AnalyticsMenu(Context context, int selected) { this.context = context; this.selected = selected; }

    static ListPopupWindow create(Context context, View anchor, int selected, int availableWidth) {
        AnalyticsMenu choices = new AnalyticsMenu(context, selected);
        ListPopupWindow popup = new ListPopupWindow(context);
        popup.setAnchorView(anchor); popup.setDropDownGravity(Gravity.END); popup.setModal(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setWidth(Math.min(choices.dp(340), availableWidth - choices.dp(32)));
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setBackgroundDrawable(context.getDrawable(R.drawable.popup_menu_background));
        TextView heading = choices.text(R.string.analytics_menu_heading, 11, true);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); heading.setLetterSpacing(.1f);
        heading.setPadding(choices.dp(16), choices.dp(12), choices.dp(16), choices.dp(12));
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        popup.setPromptPosition(ListPopupWindow.POSITION_PROMPT_ABOVE); popup.setPromptView(heading);
        popup.setAdapter(choices);
        return popup;
    }

    static void decorate(ListPopupWindow popup) {
        // The list is created by show(). Leave native list focus and keyboard selection intact.
        Context context = popup.getListView().getContext();
        int gap = Math.round(8 * context.getResources().getDisplayMetrics().density);
        popup.getListView().setPadding(gap, 0, gap, gap);
        popup.getListView().setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.getListView().setDividerHeight(gap);
        AnalyticsMenu choices = (AnalyticsMenu) popup.getListView().getAdapter();
        int highlight = (Appearance.color(context, 0xFF705180) & 0x00FFFFFF) | 0x22000000;
        StateListDrawable focus = new StateListDrawable();
        for (int state : new int[]{android.R.attr.state_focused, android.R.attr.state_pressed, android.R.attr.state_hovered})
            focus.addState(new int[]{state}, choices.shape(highlight));
        focus.addState(new int[]{}, choices.shape(Color.TRANSPARENT));
        popup.getListView().setSelector(focus); popup.getListView().setDrawSelectorOnTop(true);
        popup.show(); // Recalculate wrap-content height after adding list padding and spacing.
    }

    @Override public int getCount() { return TITLES.length; }
    @Override public Object getItem(int position) { return context.getString(TITLES[position]); }
    @Override public long getItemId(int position) { return IDS[position]; }
    @Override public boolean hasStableIds() { return true; }
    @Override public View getView(int position, View convertView, ViewGroup parent) {
        boolean current = selected == position;
        LinearLayout row = new LinearLayout(context); row.setId(IDS[position]); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14)); row.setMinimumHeight(dp(84));
        int accent = Appearance.color(context, 0xFF705180);
        int surface = Appearance.color(context, current ? 0xFFEDE6F1 : 0xFFF8F4FA);
        int ripple = (accent & 0x00FFFFFF) | 0x22000000;
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape(surface), shape(Color.WHITE)));
        row.setActivated(current);
        row.setContentDescription(context.getString(TITLES[position]) + ". " + context.getString(DESCRIPTIONS[position])
                + (current ? ". " + context.getString(R.string.analytics_current_view) : ""));
        ImageView icon = icon(ICONS[position]); row.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout words = new LinearLayout(context); words.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(TITLES[position], 19, false); title.setTypeface(Typeface.create("serif", Typeface.NORMAL)); words.addView(title);
        TextView description = text(DESCRIPTIONS[position], 13, false);
        description.setTextColor(Appearance.color(context, 0xFF6D6470)); description.setLineSpacing(dp(2), 1);
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.topMargin = dp(5); words.addView(description, spacing);
        LinearLayout.LayoutParams width = new LinearLayout.LayoutParams(0, -2, 1); width.setMarginStart(dp(12)); row.addView(words, width);
        if (current) {
            ImageView check = icon(R.drawable.ic_analytics_current);
            LinearLayout.LayoutParams mark = new LinearLayout.LayoutParams(dp(20), dp(20)); mark.setMarginStart(dp(8)); row.addView(check, mark);
        }
        // Announce each choice as a single item, rather than reading decorative children separately.
        words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return row;
    }
    private ImageView icon(int resource) {
        ImageView icon = new ImageView(context); icon.setImageResource(resource);
        icon.setImageTintList(ColorStateList.valueOf(Appearance.color(context, 0xFF705180)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return icon;
    }
    private TextView text(int resource, int size, boolean accent) {
        TextView text = new TextView(context); text.setText(resource); text.setTextSize(size);
        text.setTextColor(Appearance.color(context, accent ? 0xFF705180 : 0xFF312B35)); return text;
    }
    private GradientDrawable shape(int color) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(14)); return shape;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
