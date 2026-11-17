package com.nanodesk.mobile;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Небольшие помощники для интерфейса, собираемого кодом (без XML и сторонних библиотек). */
public final class Ui {
    private Ui() {}

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static GradientDrawable box(int color, float radiusDp, Context c, int strokeColor, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), strokeColor);
        return g;
    }

    /** Фон с нажатием: без «ряби» и анимаций — просто другой цвет, когда палец на элементе. */
    public static Drawable pressable(Context c, int normal, int pressed, float radiusDp, int strokeColor, int strokeDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed }, box(pressed, radiusDp, c, strokeColor, strokeDp));
        s.addState(new int[] {}, box(normal, radiusDp, c, strokeColor, strokeDp));
        if (Theme.noAnim) { s.setEnterFadeDuration(0); s.setExitFadeDuration(0); }
        return s;
    }

    public static int mix(int a, int b, float t) {
        int r = (int) (Color.red(a) * (1 - t) + Color.red(b) * t);
        int g = (int) (Color.green(a) * (1 - t) + Color.green(b) * t);
        int bl = (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Кнопка с большой областью нажатия (не меньше 48dp — как требуют рекомендации Android). */
    public static TextView button(Context c, String s, boolean primary) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setMinHeight(dp(c, 48));
        t.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        t.setClickable(true);
        t.setFocusable(true);
        style(t, primary);
        return t;
    }

    public static void style(TextView t, boolean primary) {
        Context c = t.getContext();
        if (primary) {
            t.setTextColor(Theme.onAccent);
            t.setBackground(pressable(c, Theme.accent, Theme.simple ? mix(Theme.accent, Theme.bg, 0.35f) : mix(Theme.accent, Theme.bg, 0.25f), 14, Theme.line, Theme.simple ? 2 : 0));
        } else {
            t.setTextColor(Theme.text);
            t.setBackground(pressable(c, Theme.surface2, mix(Theme.surface2, Theme.text, 0.12f), 14, Theme.line, Theme.simple ? 2 : 0));
        }
    }

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setTextColor(Theme.text);
        e.setHintTextColor(Theme.sub);
        e.setMinHeight(dp(c, 52));
        e.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        e.setBackground(box(Theme.surface, 14, c, Theme.line, 1));
        return e;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    public static LinearLayout.LayoutParams lpm(Context c, int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    public static void window(Activity a) {
        Theme.load(a);
        Window w = a.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Theme.bg));
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(Theme.bg);
            w.setNavigationBarColor(Theme.bg);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            View d = w.getDecorView();
            int f = d.getSystemUiVisibility();
            if (Theme.light) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; else f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                if (Theme.light) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; else f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            d.setSystemUiVisibility(f);
        }
    }

    /** Переход между экранами: без анимации, если она отключена. */
    public static void noTransition(Activity a) {
        if (Theme.noAnim) a.overridePendingTransition(0, 0);
    }

    public static View space(Context c, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new ViewGroup.LayoutParams(1, dp(c, hDp)));
        return v;
    }
}
