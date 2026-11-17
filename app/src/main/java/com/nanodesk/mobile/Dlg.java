package com.nanodesk.mobile;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Диалоги в стиле приложения (общая тема, большие кнопки, без анимации в простом режиме). */
public final class Dlg {
    private Dlg() {}

    public interface Text { void ok(String value, boolean checked); }
    public interface Pick { void pick(int index); }

    private static Dialog base(Activity a, LinearLayout body) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        body.setBackground(Ui.box(Theme.surface, 20, a, Theme.line, Theme.simple ? 2 : 1));
        d.setContentView(body);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        if (Theme.noAnim) w.setWindowAnimations(0);
        int width = Math.min(a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 32), Ui.dp(a, 420));
        w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        return d;
    }

    private static LinearLayout column(Activity a, String title, String msg) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 16));
        TextView t = Ui.text(a, title, 19, Theme.text, true);
        l.addView(t);
        if (msg != null && msg.length() > 0) {
            TextView m = Ui.text(a, msg, 14.5f, Theme.sub, false);
            m.setPadding(0, Ui.dp(a, 8), 0, 0);
            l.addView(m);
        }
        return l;
    }

    public static void input(final Activity a, String title, String msg, String hint, String initial, boolean password,
                             String checkLabel, boolean checked, String okLabel, final Text cb) {
        LinearLayout l = column(a, title, msg);
        final EditText e = Ui.input(a, hint);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (initial != null) e.setText(initial);
        l.addView(e, Ui.lpm(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        final CheckBox cb2 = new CheckBox(a);
        if (checkLabel != null) {
            cb2.setText(checkLabel);
            cb2.setTextColor(Theme.text);
            cb2.setChecked(checked);
            cb2.setMinHeight(Ui.dp(a, 48));
            l.addView(cb2, Ui.lpm(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        }
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.END);
        TextView no = Ui.button(a, "Отмена", false);
        TextView ok = Ui.button(a, okLabel, true);
        row.addView(no);
        row.addView(ok, Ui.lpm(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0));
        l.addView(row, Ui.lpm(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));
        final Dialog d = base(a, l);
        no.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); } });
        final Runnable go = new Runnable() { public void run() { d.dismiss(); cb.ok(e.getText().toString(), cb2.isChecked()); } };
        ok.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { go.run(); } });
        e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, android.view.KeyEvent ev) { go.run(); return true; }
        });
        d.show();
        e.requestFocus();
        e.setSelection(e.getText().length());
    }

    public static void confirm(Activity a, String title, String msg, String okLabel, final Runnable yes) {
        LinearLayout l = column(a, title, msg);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.END);
        TextView no = Ui.button(a, "Отмена", false);
        TextView ok = Ui.button(a, okLabel, true);
        row.addView(no);
        row.addView(ok, Ui.lpm(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0));
        l.addView(row, Ui.lpm(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 18, 0, 0));
        final Dialog d = base(a, l);
        no.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); } });
        ok.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); yes.run(); } });
        d.show();
    }

    public static void menu(Activity a, String title, String[] items, final Pick cb) {
        LinearLayout l = column(a, title, null);
        l.setPadding(Ui.dp(a, 12), Ui.dp(a, 18), Ui.dp(a, 12), Ui.dp(a, 10));
        ((TextView) l.getChildAt(0)).setPadding(Ui.dp(a, 10), 0, 0, Ui.dp(a, 8));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] dd = new Dialog[1];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView t = Ui.text(a, items[i], 16, Theme.text, false);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setMinHeight(Ui.dp(a, 52));
            t.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
            t.setBackground(Ui.pressable(a, Theme.surface, Ui.mix(Theme.surface, Theme.text, 0.1f), 12, 0, 0));
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dd[0].dismiss(); cb.pick(idx); } });
            list.addView(t);
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(list);
        l.addView(sv);
        dd[0] = base(a, l);
        dd[0].show();
    }

    public static void info(Activity a, String title, String msg) {
        LinearLayout l = column(a, title, msg);
        TextView ok = Ui.button(a, "Понятно", true);
        l.addView(ok, Ui.lpm(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 18, 0, 0));
        final Dialog d = base(a, l);
        ok.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); } });
        d.show();
    }

    /** Окно с произвольным содержимым и одной кнопкой «Готово». */
    public static Dialog custom(Activity a, String title, View content, String okLabel, final Runnable onClose) {
        LinearLayout l = column(a, title, null);
        l.addView(content, Ui.lpm(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        TextView ok = Ui.button(a, okLabel, true);
        l.addView(ok, Ui.lpm(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));
        final Dialog d = base(a, l);
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        ok.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); } });
        if (onClose != null) d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { public void onDismiss(android.content.DialogInterface x) { onClose.run(); } });
        d.show();
        return d;
    }
}
