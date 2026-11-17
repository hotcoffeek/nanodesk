package com.nanodesk.mobile;

import android.content.Context;

/** Цветовые темы. «Простая» — чисто чёрно-белая, без цветных акцентов; вместе с ней отключаются все анимации. */
public final class Theme {
    private Theme() {}

    public static final String OLED = "oled", DARK = "dark", LIGHT = "light", SIMPLE = "simple", SIMPLE_DARK = "simple_dark";

    public static int bg, surface, surface2, text, sub, accent, onAccent, line, danger, ok;
    public static boolean light, noAnim, simple;
    public static String name = DARK;

    public static void load(Context c) {
        String n = Store.theme(c);
        apply(n);
        noAnim = simple;   // анимации есть у всех тем, кроме «простых»
    }

    public static void apply(String n) {
        name = n;
        simple = false;
        if (LIGHT.equals(n)) {
            light = true;
            bg = 0xFFF3F5F8; surface = 0xFFFFFFFF; surface2 = 0xFFE7EBF1; text = 0xFF14181F; sub = 0xFF5D6675;
            accent = 0xFF1F6FEB; onAccent = 0xFFFFFFFF; line = 0xFFD5DAE3; danger = 0xFFD93A3A; ok = 0xFF1E9E5A;
        } else if (OLED.equals(n)) {
            light = false;
            bg = 0xFF000000; surface = 0xFF0E1013; surface2 = 0xFF1A1D22; text = 0xFFF1F3F6; sub = 0xFF8D95A3; line = 0xFF22262D;
            accent = 0xFF4C9AFF; onAccent = 0xFF000000; danger = 0xFFFF5A5A; ok = 0xFF3DDC84;
        } else if (SIMPLE.equals(n)) {
            // белый фон, чёрный текст, только чёрные рамки
            light = true; simple = true;
            bg = 0xFFFFFFFF; surface = 0xFFFFFFFF; surface2 = 0xFFEDEDED; text = 0xFF000000; sub = 0xFF444444; line = 0xFF000000;
            accent = 0xFF000000; onAccent = 0xFFFFFFFF; danger = 0xFF000000; ok = 0xFF000000;
        } else if (SIMPLE_DARK.equals(n)) {
            light = false; simple = true;
            bg = 0xFF000000; surface = 0xFF000000; surface2 = 0xFF1C1C1C; text = 0xFFFFFFFF; sub = 0xFFBBBBBB; line = 0xFFFFFFFF;
            accent = 0xFFFFFFFF; onAccent = 0xFF000000; danger = 0xFFFFFFFF; ok = 0xFFFFFFFF;
        } else {
            light = false;
            bg = 0xFF11141A; surface = 0xFF1B1F27; surface2 = 0xFF262B35; text = 0xFFEEF1F6; sub = 0xFF98A1B1; line = 0xFF2F3541;
            accent = 0xFF5AA2FF; onAccent = 0xFF06101F; danger = 0xFFFF6B6B; ok = 0xFF4BD68A;
        }
    }
}
