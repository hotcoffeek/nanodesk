package com.nanodesk.mobile;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Настройки: внешний вид (в том числе простая чёрно-белая тема без анимаций), управление, сеанс. */
public final class SettingsActivity extends Activity {
    private interface Bool { void on(boolean v); }

    private LinearLayout body;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Theme.bg);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        MainActivity.IconBtn back = new MainActivity.IconBtn(this, Icons.BACK);
        back.setContentDescription("Назад");
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); Ui.noTransition(SettingsActivity.this); } });
        head.addView(back);
        head.addView(Ui.text(this, "Настройки", 20, Theme.text, true));
        col.addView(head);
        ScrollView sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 32));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(col);
        fill();
    }

    private void section(String t) {
        TextView s = Ui.text(this, t, 13.5f, Theme.sub, true);
        s.setPadding(Ui.dp(this, 6), Ui.dp(this, 22), 0, Ui.dp(this, 8));
        body.addView(s);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.box(Theme.surface, 18, this, Theme.line, Theme.simple ? 2 : 1));
        body.addView(c);
        return c;
    }

    private View line(LinearLayout card, String title, String sub, View right, final Runnable click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 60));
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(Ui.text(this, title, 16, Theme.text, false));
        if (sub != null) { TextView s = Ui.text(this, sub, 12.5f, Theme.sub, false); tx.addView(s); }
        r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (right != null) r.addView(right);
        if (click != null) {
            r.setClickable(true);
            r.setBackground(Ui.pressable(this, 0x00000000, Ui.mix(Theme.surface, Theme.text, 0.1f), 18, 0, 0));
            r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { click.run(); } });
        }
        card.addView(r);
        return r;
    }

    private void toggle(LinearLayout card, String title, String sub, boolean val, final Bool on) {
        final Switch sw = new Switch(this);
        sw.setChecked(val);
        sw.setClickable(false);
        line(card, title, sub, sw, new Runnable() { public void run() { sw.toggle(); } });
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean v) { on.on(v); }
        });
    }

    private static String themeName(String t) {
        if (Theme.OLED.equals(t)) return "Тёмная OLED (чисто чёрная)";
        if (Theme.LIGHT.equals(t)) return "Светлая";
        if (Theme.SIMPLE.equals(t)) return "Простая: чёрное на белом";
        if (Theme.SIMPLE_DARK.equals(t)) return "Простая: белое на чёрном";
        return "Тёмная";
    }

    private void fill() {
        body.removeAllViews();
        final Activity me = this;

        section("ВНЕШНИЙ ВИД");
        LinearLayout c1 = card();
        line(c1, "Тема", themeName(Store.theme(this)), null, new Runnable() {
            public void run() {
                final String[] keys = { Theme.DARK, Theme.OLED, Theme.LIGHT, Theme.SIMPLE, Theme.SIMPLE_DARK };
                String[] names = new String[keys.length];
                for (int i = 0; i < keys.length; i++) names[i] = themeName(keys[i]);
                Dlg.menu(me, "Тема", names, new Dlg.Pick() {
                    public void pick(int i) {
                        Store.setTheme(me, keys[i]);
                        restart();
                    }
                });
            }
        });

        section("УПРАВЛЕНИЕ");
        LinearLayout c2 = card();
        toggle(c2, "Режим «тачпад»", "Палец двигает курсор, как на ноутбуке. Выключено — «касание»: палец сам указывает место", Store.touchpad(this),
                new Bool() { public void on(boolean v) { Store.setTouchpad(me, v); } });
        line(c2, "Размер курсора", new String[] { "Маленький", "Средний", "Большой" }[Store.pointer(this)], null, new Runnable() {
            public void run() {
                Dlg.menu(me, "Размер курсора", new String[] { "Маленький", "Средний", "Большой" }, new Dlg.Pick() {
                    public void pick(int i) { Store.setPointer(me, i); fill(); }
                });
            }
        });
        toggle(c2, "Вибрация при жестах", "Отклик на долгое нажатие, правый клик и перетаскивание", Store.haptics(this),
                new Bool() { public void on(boolean v) { Store.setHaptics(me, v); } });
        toggle(c2, "Обратная прокрутка", "Меняет направление прокрутки двумя пальцами", Store.invertScroll(this),
                new Bool() { public void on(boolean v) { Store.setInvertScroll(me, v); } });
        toggle(c2, "Кнопки громкости — прокрутка", "Удобно одной рукой: «+» вверх, «−» вниз", Store.volumeScroll(this),
                new Bool() { public void on(boolean v) { Store.setVolumeScroll(me, v); } });
        toggle(c2, "Весь текст вставкой", "Текст вводится через буфер обмена и не зависит от раскладки на компьютере. Не работает в играх и некоторых полях пароля", Store.pasteAll(this),
                new Bool() { public void on(boolean v) { Store.setPasteAll(me, v); } });

        section("СЕАНС");
        LinearLayout c3 = card();
        toggle(c3, "Не гасить экран", "Пока открыт удалённый рабочий стол", Store.keepOn(this),
                new Bool() { public void on(boolean v) { Store.setKeepOn(me, v); } });
        toggle(c3, "Автоподбор качества", "Качество и частота кадров подстраиваются под скорость сети", Store.autoQuality(this),
                new Bool() { public void on(boolean v) { Store.setAutoQuality(me, v); } });
        toggle(c3, "Половинное разрешение", "Быстрее на слабой сети и мобильном интернете", Store.halfRes(this),
                new Bool() { public void on(boolean v) { Store.setHalfRes(me, v); } });

        section("О ПРИЛОЖЕНИИ");
        LinearLayout c4 = card();
        line(c4, "NanoDesk для Android", "Версия 1.0 · подключение к компьютерам с NanoDesk по ID или IP. Пароли хранятся в защищённом хранилище телефона", null, null);
    }

    private void restart() {
        Theme.load(this);
        Intent i = new Intent(this, SettingsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        finish();
        startActivity(i);
        overridePendingTransition(0, 0);
    }
}
