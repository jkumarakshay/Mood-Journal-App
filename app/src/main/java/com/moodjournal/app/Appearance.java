package com.moodjournal.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import androidx.core.graphics.ColorUtils;

/** Explicit app appearance, independent of the device theme. Stored Heap colors stay unchanged. */
final class Appearance {
    static boolean isDark(Context context) {
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getBoolean("dark", false);
    }

    static void setDark(Context context, boolean dark) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("dark", dark).apply();
    }

    static void apply(Activity activity) {
        activity.setTheme(isDark(activity) ? R.style.AppTheme_Dark : R.style.AppTheme);
    }

    static int onAccent(Context context) { return isDark(context) ? 0xFF2C1737 : 0xFFFFFFFF; }

    static boolean isPresetHeapColor(int rgb) {
        for (int i = 1; i < JournalHeap.COLORS.length; i++) if (JournalHeap.COLORS[i] == rgb) return true;
        return false;
    }

    static int heapSurface(Context context, int rgb) {
        // Existing pastel profiles keep their dark variants. Custom RGB stays exact.
        return isPresetHeapColor(rgb) ? color(context, rgb) : rgb;
    }

    static int heapInk(Context context, int rgb) {
        int background = heapSurface(context, rgb), normal = color(context, 0xFF312B35);
        if (ColorUtils.calculateContrast(normal, background) >= 4.5) return normal;
        int alternate = isDark(context) ? 0xFF312B35 : 0xFFF4EEF5;
        if (ColorUtils.calculateContrast(alternate, background) >= 4.5) return alternate;
        return ColorUtils.calculateContrast(Color.BLACK, background) > ColorUtils.calculateContrast(Color.WHITE, background) ? Color.BLACK : Color.WHITE;
    }

    static int color(Context context, int light) {
        if (!isDark(context)) return light;
        return switch (light) {
            case 0xFF312B35 -> 0xFFF4EEF5; // Text
            case 0xFF6D6470 -> 0xFFC0B5C3; // Secondary text
            case 0xFF705180, 0xFF775B83 -> 0xFFD1B2E0; // Accent
            case 0xFFFAF7F2 -> 0xFF1C1920; // Page
            case 0xFFFFFFFF -> 0xFF29252F; // Surface
            case 0xFFEDE6F1 -> 0xFF3A2E44; // Lavender
            case 0xFFE8EDE1 -> 0xFF2D3B30; // Sage
            case 0xFFFAE6D5 -> 0xFF453226; // Peach
            case 0xFFE1EDF5 -> 0xFF293B48; // Sky
            case 0xFFF5E2E8 -> 0xFF452F38; // Rose
            case 0xFFF5F0F7 -> 0xFF332C3A; // Secondary button
            case 0xFFF8F4FA -> 0xFF25212B; // Mood choice
            case 0xFFE8E1EA -> 0xFF514657; // Chart grid
            default -> light;
        };
    }
}
