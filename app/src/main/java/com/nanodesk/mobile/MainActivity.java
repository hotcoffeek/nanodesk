package com.nanodesk.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.nanodesk.mobile.net.P2p;

import java.util.List;

/** Главный экран: ввод ID, список «моих компьютеров», поиск в сети. */
public final class MainActivity extends Activity implements Link.StateListener {
    private LinearLayout list;
    private EditText idBox;
    private FrameLayout overlay;
    private TextView ovTitle, ovText;
    private final Handler h = new Handler();
    private boolean scanning;

    /** Значок-кнопка, нарисованный кодом. */
    static final class IconBtn extends View {
        final int kind;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        IconBtn(android.content.Context c, int kind) {
            super(c);
            this.kind = kind;
            setClickable(true);
            setBackground(Ui.pressable(c, 0x00000000, Ui.mix(Theme.bg, Theme.text, 0.14f), 24, 0, 0));
            setMinimumWidth(Ui.dp(c, 48));
            setMinimumHeight(Ui.dp(c, 48));
        }
        protected void onMeasure(int w, int hh) { int s = Ui.dp(getContext(), 48); setMeasuredDimension(s, s); }
        protected void onDraw(Canvas c) {
            p.setColor(Theme.text);
            Icons.draw(c, kind, getWidth() / 2f, getHeight() / 2f, Ui.dp(getContext(), 24), p);
        }
    }

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        Link.I.ensureStarted(this);
        build();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // шапка
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 4));
        TextView title = Ui.text(this, "NanoDesk", 24, Theme.text, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        IconBtn simple = new IconBtn(this, Icons.MOON);
        simple.setContentDescription("Простой режим");
        simple.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggleSimple(); } });
        head.addView(simple);
        IconBtn set = new IconBtn(this, Icons.SLIDERS);
        set.setContentDescription("Настройки");
        set.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); Ui.noTransition(MainActivity.this); }
        });
        head.addView(set);
        col.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // карточка подключения
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        card.setBackground(Ui.box(Theme.surface, 20, this, Theme.line, Theme.simple ? 2 : 1));
        card.addView(Ui.text(this, "Подключиться к компьютеру", 16, Theme.text, true));
        TextView hint = Ui.text(this, "Введите ID из окна NanoDesk на компьютере или его IP-адрес в этой же сети", 13.5f, Theme.sub, false);
        hint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        card.addView(hint);
        idBox = Ui.input(this, "ID или IP-адрес");
        idBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        idBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        idBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, android.view.KeyEvent ev) { goTyped(); return true; }
        });
        card.addView(idBox);
        TextView go = Ui.button(this, "Подключиться", true);
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { goTyped(); } });
        card.addView(go, Ui.lpm(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        TextView scan = Ui.button(this, "Найти компьютеры в сети", false);
        scan.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { scan(); } });
        card.addView(scan, Ui.lpm(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        body.addView(card);

        TextView sec = Ui.text(this, "Мои компьютеры", 14, Theme.sub, true);
        sec.setPadding(Ui.dp(this, 6), Ui.dp(this, 22), 0, Ui.dp(this, 8));
        body.addView(sec);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list);

        root.addView(col);

        // экран «Подключаюсь…»
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Theme.bg);
        overlay.setClickable(true);
        overlay.setVisibility(View.GONE);
        LinearLayout oc = new LinearLayout(this);
        oc.setOrientation(LinearLayout.VERTICAL);
        oc.setGravity(Gravity.CENTER_HORIZONTAL);
        oc.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        ovTitle = Ui.text(this, "Подключаюсь…", 22, Theme.text, true);
        ovTitle.setGravity(Gravity.CENTER);
        ovText = Ui.text(this, "", 14.5f, Theme.sub, false);
        ovText.setGravity(Gravity.CENTER);
        ovText.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 28));
        TextView cancel = Ui.button(this, "Отмена", false);
        cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { Link.I.disconnect(); } });
        oc.addView(ovTitle);
        oc.addView(ovText);
        oc.addView(cancel, new LinearLayout.LayoutParams(Ui.dp(this, 200), ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(oc, fp);
        root.addView(overlay);

        setContentView(root);
    }

    protected void onResume() {
        super.onResume();
        Link.I.addStateListener(this);
        refreshList();
        onState(Link.I.state, Link.I.message, Link.E_NONE);
        if (Link.I.state == Link.ACTIVE) openSession();
    }

    protected void onPause() {
        super.onPause();
        Link.I.removeStateListener(this);
    }

    private void toggleSimple() {
        if (Theme.simple) {
            Store.setTheme(this, Store.prevTheme(this));
        } else {
            Store.setPrevTheme(this, Store.theme(this));
            Store.setTheme(this, Theme.light ? Theme.SIMPLE : Theme.SIMPLE_DARK);
        }
        recreateNoAnim();
    }

    private void recreateNoAnim() {
        Theme.load(this);
        finish();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
        overridePendingTransition(0, 0);
    }

    private void refreshList() {
        list.removeAllViews();
        List<Store.Device> l = Store.devices(this);
        if (l.isEmpty()) {
            TextView e = Ui.text(this, "Пока пусто. Подключитесь к компьютеру один раз — он появится здесь, и в следующий раз хватит одного нажатия.", 14, Theme.sub, false);
            e.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), 0);
            list.addView(e);
            return;
        }
        for (final Store.Device d : l) list.addView(row(d));
    }

    private View row(final Store.Device d) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));
        r.setMinimumHeight(Ui.dp(this, 68));
        r.setBackground(Ui.pressable(this, Theme.surface, Ui.mix(Theme.surface, Theme.text, 0.1f), 18, Theme.line, Theme.simple ? 2 : 1));
        r.setClickable(true);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = Ui.dp(this, 8);
        r.setLayoutParams(rp);

        View ic = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            protected void onDraw(Canvas c) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Theme.surface2);
                c.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f, p);
                p.setColor(Theme.accent);
                Icons.draw(c, Icons.PC, getWidth() / 2f, getHeight() / 2f, getWidth() * 0.5f, p);
            }
        };
        r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 6), 0);
        TextView n = Ui.text(this, d.name.length() > 0 ? d.name : d.id, 16.5f, Theme.text, true);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String sub = (d.ip ? d.id + ":" + d.port : "ID " + fmtId(d.id));
        if (d.last > 0) sub += " · " + DateUtils.getRelativeTimeSpanString(d.last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        TextView s = Ui.text(this, sub, 13, Theme.sub, false);
        s.setSingleLine(true);
        tx.addView(n);
        tx.addView(s);
        r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        IconBtn more = new IconBtn(this, Icons.MORE);
        more.setContentDescription("Действия");
        more.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { actions(d); } });
        r.addView(more);

        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { open(d); } });
        r.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { actions(d); return true; } });
        return r;
    }

    static String fmtId(String id) {
        if (id.length() == 9) return id.substring(0, 3) + " " + id.substring(3, 6) + " " + id.substring(6);
        return id;
    }

    private void actions(final Store.Device d) {
        final boolean hasPw = d.pw.length() > 0;
        String[] items = hasPw ? new String[] { "Переименовать", "Забыть пароль", "Удалить из списка" } : new String[] { "Переименовать", "Удалить из списка" };
        Dlg.menu(this, d.name.length() > 0 ? d.name : d.id, items, new Dlg.Pick() {
            public void pick(int i) {
                if (i == 0) {
                    Dlg.input(MainActivity.this, "Название", null, "Название", d.name, false, null, false, "Сохранить", new Dlg.Text() {
                        public void ok(String v, boolean c) { d.name = v.trim(); Store.save(MainActivity.this, d); refreshList(); }
                    });
                } else if (hasPw && i == 1) {
                    d.pw = ""; Store.save(MainActivity.this, d); refreshList();
                } else {
                    Store.remove(MainActivity.this, d.id); refreshList();
                }
            }
        });
    }

    private static boolean looksLikeIp(String s) { return s.matches("\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?"); }

    private void goTyped() {
        String t = idBox.getText().toString().trim().replace(" ", "");
        if (t.length() == 0) { idBox.requestFocus(); return; }
        Store.Device d = new Store.Device();
        if (looksLikeIp(t)) {
            d.ip = true;
            int k = t.indexOf(':');
            d.id = k > 0 ? t.substring(0, k) : t;
            d.port = k > 0 ? Integer.parseInt(t.substring(k + 1)) : P2p.DEFAULT_PORT;
        } else if (t.matches("\\d{6,12}")) {
            d.id = t;
        } else {
            Dlg.info(this, "Неверный ID", "ID состоит из цифр (например 123 456 789). Он показан в окне NanoDesk на компьютере. Для подключения по адресу введите IP, например 192.168.1.20.");
            return;
        }
        Store.Device old = Store.find(this, d.id);
        open(old != null ? old : d);
    }

    private void open(final Store.Device d) {
        if (Link.I.p2p == null) { Dlg.info(this, "Сеть недоступна", "Не удалось запустить сетевой модуль. Перезапустите приложение."); return; }
        if (Link.I.state != Link.IDLE) return;
        String saved = Store.decrypt(d.pw);
        if (saved.length() > 0) { Link.I.connect(d, saved, true); return; }
        String name = d.name.length() > 0 ? d.name : (d.ip ? d.id : fmtId(d.id));
        Dlg.input(this, "Пароль", name, "Пароль доступа", "", true,
                Store.canRemember() ? "Запомнить пароль на этом телефоне" : null, true, "Подключить", new Dlg.Text() {
            public void ok(String v, boolean c) {
                if (v.length() == 0) return;
                Link.I.connect(d, v, c && Store.canRemember());
            }
        });
    }

    private void scan() {
        if (scanning) return;
        scanning = true;
        ovTitle.setText("Ищу компьютеры…");
        ovText.setText("Проверяю вашу Wi‑Fi сеть");
        overlay.setVisibility(View.VISIBLE);
        Thread t = new Thread(new Runnable() {
            public void run() {
                final List<P2p.Found> res = Link.I.p2p == null ? new java.util.ArrayList<P2p.Found>() : Link.I.p2p.scanLan(2200);
                h.post(new Runnable() {
                    public void run() {
                        scanning = false;
                        overlay.setVisibility(Link.I.state == Link.IDLE ? View.GONE : View.VISIBLE);
                        showFound(res);
                    }
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void showFound(final List<P2p.Found> res) {
        if (res.isEmpty()) {
            Dlg.info(this, "Никого не нашлось", "Компьютеры с NanoDesk должны быть включены и находиться в той же Wi‑Fi сети. Либо введите ID вручную — так можно подключаться и через интернет.");
            return;
        }
        String[] items = new String[res.size()];
        for (int i = 0; i < items.length; i++) items[i] = res.get(i).name + "  ·  " + res.get(i).ip;
        Dlg.menu(this, "Найдено в сети", items, new Dlg.Pick() {
            public void pick(int i) {
                P2p.Found f = res.get(i);
                Store.Device d = Store.find(MainActivity.this, f.ip);
                if (d == null) { d = new Store.Device(); d.id = f.ip; d.ip = true; d.port = f.port; d.name = f.name; }
                open(d);
            }
        });
    }

    public void onState(int st, String msg, int err) {
        if (scanning) return;
        if (st == Link.CONNECTING || st == Link.RECONNECTING) {
            ovTitle.setText(st == Link.CONNECTING ? "Подключаюсь…" : "Восстанавливаю связь…");
            ovText.setText(Link.I.dev != null ? (Link.I.dev.name.length() > 0 ? Link.I.dev.name : Link.I.dev.id) : "");
            overlay.setVisibility(View.VISIBLE);
        } else {
            overlay.setVisibility(View.GONE);
        }
        if (st == Link.ACTIVE) { openSession(); return; }
        if (st == Link.IDLE && msg != null && msg.length() > 0 && err != Link.E_NONE) {
            if (err == Link.E_AUTH) {
                final Store.Device d = Link.I.dev;
                if (d != null) { d.pw = ""; Store.save(this, d); }
                Dlg.info(this, "Неверный пароль", "Проверьте пароль. Одноразовый пароль меняется после каждого подключения — возьмите новый из окна NanoDesk.");
            } else if (err == Link.E_OFFLINE) {
                Dlg.info(this, "Компьютер не отвечает", msg);
            } else {
                Dlg.info(this, "Не удалось подключиться", msg);
            }
            Link.I.message = "";
        } else if (st == Link.IDLE && msg != null && msg.length() > 0) {
            Dlg.info(this, "Сеанс завершён", msg);
            Link.I.message = "";
        }
    }

    private boolean opened;

    private void openSession() {
        if (opened) return;
        opened = true;
        Intent i = new Intent(this, SessionActivity.class);
        startActivity(i);
        Ui.noTransition(this);
        h.postDelayed(new Runnable() { public void run() { opened = false; } }, 800);
    }

    public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE && Link.I.state != Link.IDLE) { Link.I.disconnect(); return; }
        super.onBackPressed();
    }
}
