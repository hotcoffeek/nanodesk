package com.nanodesk.mobile;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.nanodesk.mobile.net.Msg;
import com.nanodesk.mobile.net.Session;

import java.util.List;

/** Экран сеанса: удалённый рабочий стол на весь экран, панель инструментов по нажатию на «ручку» сверху. */
public final class SessionActivity extends Activity implements Session.Listener, Link.StateListener, RemoteView.Events {
    private FrameLayout root;
    private RemoteView rv;
    private View handle;
    private HorizontalScrollView toolbar;
    private LinearLayout keysPanel;
    private TextView banner;
    private ToolBtn bKeyboard, bKeys, bMode, bPan, bMon;
    private final Handler h = new Handler();
    private boolean toolsOn, keysOn, kbOn;
    private final Runnable hideBanner = new Runnable() { public void run() { banner.setVisibility(View.GONE); } };
    private final Runnable autoHide = new Runnable() { public void run() { if (!kbOn && !keysOn) setTools(false); } };

    /** Кнопка панели инструментов: значок и подпись. */
    static final class ToolBtn extends View {
        final int icon;
        String label;
        boolean active;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ToolBtn(Context c, int icon, String label) {
            super(c);
            this.icon = icon; this.label = label;
            setClickable(true);
            setBackground(Ui.pressable(c, 0x00000000, 0x33FFFFFF, 14, 0, 0));
            setContentDescription(label);
        }
        protected void onMeasure(int w, int hh) { setMeasuredDimension(Ui.dp(getContext(), 72), Ui.dp(getContext(), 64)); }
        protected void onDraw(Canvas c) {
            int col = active ? Theme.accent : 0xFFFFFFFF;
            p.setColor(col);
            float u = Ui.dp(getContext(), 1);
            Icons.draw(c, icon, getWidth() / 2f, 22 * u, 26 * u, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(11.5f * u);
            c.drawText(label, getWidth() / 2f, 54 * u, p);
        }
        void set(String l, boolean a) { label = l; active = a; invalidate(); }
    }

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        if (Link.I.state == Link.IDLE) { finish(); return; }
        if (Store.keepOn(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        rv = new RemoteView(this);
        rv.events = this;
        root.addView(rv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadPrefs();

        banner = Ui.text(this, "", 14, 0xFFFFFFFF, true);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
        banner.setBackground(Ui.box(0xDD000000, 20, this, 0x55FFFFFF, 1));
        banner.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bp.topMargin = Ui.dp(this, 44);
        root.addView(banner, bp);

        // «ручка» сверху: нажать — показать панель
        handle = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            protected void onDraw(Canvas c) {
                float u = Ui.dp(getContext(), 1);
                p.setColor(0x99FFFFFF);
                c.drawRoundRect(getWidth() / 2f - 20 * u, 6 * u, getWidth() / 2f + 20 * u, 11 * u, 3 * u, 3 * u, p);
            }
        };
        handle.setContentDescription("Панель инструментов");
        handle.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setTools(!toolsOn); } });
        root.addView(handle, new FrameLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 28), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        buildKeys();
        buildToolbar();

        setContentView(root);
        applyImmersive();
        // клавиатура уменьшает область экрана, а не перекрывает его
        final View decor = getWindow().getDecorView();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                Rect r = new Rect();
                decor.getWindowVisibleDisplayFrame(r);
                int full = decor.getRootView().getHeight();
                int kb = full - r.bottom;
                boolean shown = kb > full * 0.15f;
                root.setPadding(0, 0, 0, shown ? kb : 0);
                if (shown != kbOn) { kbOn = shown; bKeyboard.set(kbOn ? "Скрыть" : "Клавиатура", kbOn); if (kbOn) setTools(false); }
            }
        });
    }

    private void loadPrefs() {
        rv.touchpad = Store.touchpad(this);
        rv.speed = Store.speed(this) / 10f;
        rv.invertScroll = Store.invertScroll(this);
        rv.haptics = Store.haptics(this);
        rv.pasteAll = Store.pasteAll(this);
    }

    // ---------- панели ----------

    private void buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        bKeyboard = tool(bar, Icons.KEYBOARD, "Клавиатура", new Runnable() { public void run() { toggleKeyboard(); } });
        bKeys = tool(bar, Icons.KEYS, "Клавиши", new Runnable() { public void run() { setKeys(!keysOn); } });
        bMode = tool(bar, rv.touchpad ? Icons.PAD : Icons.TOUCH, rv.touchpad ? "Тачпад" : "Касание", new Runnable() { public void run() { switchMode(); } });
        bPan = tool(bar, Icons.PAN, "Сдвиг", new Runnable() { public void run() { rv.panMode = !rv.panMode; bPan.set("Сдвиг", rv.panMode); toast(rv.panMode ? "Два пальца двигают картинку" : "Два пальца крутят колесо"); } });
        tool(bar, Icons.FIT, "Вписать", new Runnable() { public void run() { rv.fitToScreen(); } });
        bMon = tool(bar, Icons.MONITOR, "Экран", new Runnable() { public void run() { pickMonitor(); } });
        tool(bar, Icons.CLIP, "Вставить", new Runnable() { public void run() { pastePhone(); } });
        tool(bar, Icons.FOLDER, "Файлы", new Runnable() { public void run() { openFiles(); } });
        tool(bar, Icons.SLIDERS, "Качество", new Runnable() { public void run() { quick(); } });
        ToolBtn x = tool(bar, Icons.CLOSE, "Отключить", new Runnable() { public void run() { askDisconnect(); } });
        x.active = false;
        toolbar = new HorizontalScrollView(this);
        toolbar.setHorizontalScrollBarEnabled(false);
        toolbar.addView(bar);
        toolbar.setBackground(Ui.box(0xEE0B0D11, 0, this, 0, 0));
        toolbar.setVisibility(View.GONE);
        root.addView(toolbar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    private ToolBtn tool(LinearLayout bar, int icon, String label, final Runnable r) {
        ToolBtn b = new ToolBtn(this, icon, label);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { r.run(); scheduleHide(); } });
        bar.addView(b);
        return b;
    }

    private TextView chip(String t, final Runnable r) {
        TextView c = new TextView(this);
        c.setText(t);
        c.setTextColor(0xFFFFFFFF);
        c.setTextSize(14);
        c.setGravity(Gravity.CENTER);
        c.setMinWidth(Ui.dp(this, 52));
        c.setMinHeight(Ui.dp(this, 44));
        c.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        c.setClickable(true);
        c.setBackground(Ui.pressable(this, 0xFF2A2F3A, 0xFF4A5160, 10, 0, 0));
        c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { r.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 6);
        c.setLayoutParams(lp);
        return c;
    }

    private final java.util.HashMap<Integer, TextView> modChips = new java.util.HashMap<Integer, TextView>();

    private void addKey(LinearLayout row, String t, final int vk) {
        row.addView(chip(t, new Runnable() { public void run() { Keys.tap(Link.I.session, vk); Keys.releaseSticky(Link.I.session); } }));
    }

    private void addMod(LinearLayout row, String t, final int vk) {
        TextView c = chip(t, new Runnable() { public void run() { Keys.toggleSticky(Link.I.session, vk); } });
        modChips.put(vk, c);
        row.addView(c);
    }

    private void addCombo(LinearLayout row, String t, final int mod, final int vk) {
        row.addView(chip(t, new Runnable() { public void run() { Keys.combo(Link.I.session, mod, vk); } }));
    }

    private HorizontalScrollView rowScroll(LinearLayout row) {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        sv.addView(row);
        return sv;
    }

    private void buildKeys() {
        keysPanel = new LinearLayout(this);
        keysPanel.setOrientation(LinearLayout.VERTICAL);
        keysPanel.setBackground(Ui.box(0xEE0B0D11, 0, this, 0, 0));
        keysPanel.setVisibility(View.GONE);
        LinearLayout r1 = new LinearLayout(this);
        addKey(r1, "Esc", Keys.VK_ESC);
        addKey(r1, "Tab", Keys.VK_TAB);
        addMod(r1, "Ctrl", Keys.VK_CTRL);
        addMod(r1, "Alt", Keys.VK_ALT);
        addMod(r1, "Shift", Keys.VK_SHIFT);
        addMod(r1, "Win", Keys.VK_WIN);
        addKey(r1, "Del", Keys.VK_DELETE);
        addKey(r1, "Home", Keys.VK_HOME);
        addKey(r1, "End", Keys.VK_END);
        addKey(r1, "PgUp", Keys.VK_PGUP);
        addKey(r1, "PgDn", Keys.VK_PGDN);
        LinearLayout r2 = new LinearLayout(this);
        addKey(r2, "←", Keys.VK_LEFT);
        addKey(r2, "↑", Keys.VK_UP);
        addKey(r2, "↓", Keys.VK_DOWN);
        addKey(r2, "→", Keys.VK_RIGHT);
        addKey(r2, "Enter", Keys.VK_RETURN);
        addCombo(r2, "Копировать", Keys.VK_CTRL, Keys.VK_C);
        addCombo(r2, "Вставить", Keys.VK_CTRL, Keys.VK_V);
        addCombo(r2, "Всё", Keys.VK_CTRL, Keys.VK_A);
        addCombo(r2, "Отмена", Keys.VK_CTRL, Keys.VK_Z);
        addCombo(r2, "Alt+Tab", Keys.VK_ALT, Keys.VK_TAB);
        addCombo(r2, "Win+D", Keys.VK_WIN, 0x44);
        LinearLayout r3 = new LinearLayout(this);
        for (int i = 0; i < 12; i++) addKey(r3, "F" + (i + 1), Keys.VK_F1 + i);
        keysPanel.addView(rowScroll(r1));
        keysPanel.addView(rowScroll(r2));
        keysPanel.addView(rowScroll(r3));
        Keys.stickyChanged = new Runnable() {
            public void run() {
                for (java.util.Map.Entry<Integer, TextView> e : modChips.entrySet()) {
                    boolean on = Keys.isHeld(e.getKey());
                    e.getValue().setBackground(Ui.pressable(SessionActivity.this, on ? Theme.accent : 0xFF2A2F3A, 0xFF4A5160, 10, 0, 0));
                    e.getValue().setTextColor(on ? Theme.onAccent : 0xFFFFFFFF);
                }
            }
        };
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(keysPanel, lp);
    }

    private void setTools(boolean on) {
        toolsOn = on;
        h.removeCallbacks(autoHide);
        toolbar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) setKeys(false);
        updateKeysPosition();
        if (on) scheduleHide();
    }

    private void scheduleHide() {
        h.removeCallbacks(autoHide);
        if (toolsOn && !keysOn) h.postDelayed(autoHide, 6000);
    }

    private void setKeys(boolean on) {
        keysOn = on;
        if (on && !toolsOn) { toolsOn = true; toolbar.setVisibility(View.VISIBLE); }
        keysPanel.setVisibility(on ? View.VISIBLE : View.GONE);
        bKeys.set("Клавиши", on);
        updateKeysPosition();
        h.removeCallbacks(autoHide);
    }

    private void updateKeysPosition() {
        // панель клавиш — над панелью инструментов
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) keysPanel.getLayoutParams();
        lp.bottomMargin = toolsOn ? Ui.dp(this, 76) : 0;
        keysPanel.setLayoutParams(lp);
    }

    private void toggleKeyboard() {
        if (kbOn) rv.hideKeyboard(); else { setKeys(false); rv.showKeyboard(); }
    }

    private void switchMode() {
        rv.touchpad = !rv.touchpad;
        Store.setTouchpad(this, rv.touchpad);
        bMode.set(rv.touchpad ? "Тачпад" : "Касание", false);
        bMode.invalidate();
        toast(rv.touchpad ? "Тачпад: палец двигает курсор, касание — клик" : "Касание: палец — это курсор");
        rv.invalidate();
    }

    private void toast(String t) {
        banner.setText(t);
        banner.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideBanner);
        h.postDelayed(hideBanner, 2600);
    }

    // ---------- действия ----------

    private void pickMonitor() {
        final int n = Link.I.monCount;
        if (n <= 1) { toast("На компьютере один монитор"); return; }
        String[] items = new String[n];
        for (int i = 0; i < n; i++) items[i] = "Монитор " + (i + 1) + (i == Link.I.monIdx ? "  ✓" : "");
        Dlg.menu(this, "Экран компьютера", items, new Dlg.Pick() {
            public void pick(int i) { Session s = Link.I.session; if (s != null) s.selectMonitor(i); }
        });
    }

    private void pastePhone() {
        Session s = Link.I.session;
        if (s == null) return;
        if ((s.perms & Msg.PERM_CLIP) == 0) { toast("Владелец ПК не разрешил буфер обмена"); return; }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0 || d.getItemAt(0).coerceToText(this).length() == 0) { toast("Буфер телефона пуст"); return; }
            Keys.paste(s, d.getItemAt(0).coerceToText(this).toString());
            toast("Вставлено на компьютер");
        } catch (Exception e) {
            toast("Не удалось прочитать буфер");
        }
    }

    private void openFiles() {
        Session s = Link.I.session;
        if (s != null && (s.perms & Msg.PERM_FILES) == 0) { toast("Владелец ПК не разрешил передачу файлов"); return; }
        startActivity(new Intent(this, FilesActivity.class));
        Ui.noTransition(this);
    }

    private void askDisconnect() {
        Dlg.confirm(this, "Отключиться?", "Сеанс будет завершён.", "Отключить", new Runnable() {
            public void run() { Link.I.disconnect(); finish(); Ui.noTransition(SessionActivity.this); }
        });
    }

    private void quick() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        final TextView ql = Ui.text(this, "", 14, Theme.text, false);
        final SeekBar q = new SeekBar(this);
        q.setMax(95);
        q.setProgress(Store.quality(this) - 5);
        ql.setText("Качество картинки: " + Store.quality(this) + "%");
        q.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) { ql.setText("Качество картинки: " + (p + 5) + "%"); }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { Store.setQuality(SessionActivity.this, sb.getProgress() + 5); Link.I.applyQuality(); }
        });
        c.addView(ql);
        c.addView(q);
        final TextView fl = Ui.text(this, "", 14, Theme.text, false);
        fl.setPadding(0, Ui.dp(this, 12), 0, 0);
        final SeekBar f = new SeekBar(this);
        f.setMax(55);
        f.setProgress(Store.fps(this) - 5);
        fl.setText("Кадров в секунду: " + Store.fps(this));
        f.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) { fl.setText("Кадров в секунду: " + (p + 5)); }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { Store.setFps(SessionActivity.this, sb.getProgress() + 5); Link.I.applyQuality(); }
        });
        c.addView(fl);
        c.addView(f);
        c.addView(check("Подбирать автоматически по скорости сети", Store.autoQuality(this), new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) { Store.setAutoQuality(SessionActivity.this, v); Link.I.applyQuality(); }
        }));
        c.addView(check("Половинное разрешение (быстрее на слабой сети)", Store.halfRes(this), new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) { Store.setHalfRes(SessionActivity.this, v); Link.I.applyQuality(); }
        }));
        final TextView sl = Ui.text(this, "", 14, Theme.text, false);
        sl.setPadding(0, Ui.dp(this, 8), 0, 0);
        final SeekBar sp = new SeekBar(this);
        sp.setMax(25);
        sp.setProgress(Store.speed(this) - 5);
        sl.setText("Скорость курсора: ×" + (Store.speed(this) / 10f));
        sp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) { sl.setText("Скорость курсора: ×" + ((p + 5) / 10f)); }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { Store.setSpeed(SessionActivity.this, sb.getProgress() + 5); rv.speed = (sb.getProgress() + 5) / 10f; }
        });
        c.addView(sl);
        c.addView(sp);
        Dlg.custom(this, "Качество и управление", c, "Готово", null);
    }

    private CheckBox check(String t, boolean v, CompoundButton.OnCheckedChangeListener l) {
        CheckBox b = new CheckBox(this);
        b.setText(t);
        b.setTextColor(Theme.text);
        b.setChecked(v);
        b.setMinHeight(Ui.dp(this, 48));
        b.setOnCheckedChangeListener(l);
        return b;
    }

    // ---------- жизненный цикл ----------

    protected void onStart() {
        super.onStart();
        Link.I.attach(this);
        Link.I.addStateListener(this);
        Link.I.ui = this;
        rv.sync();
        rv.frameChanged();
        updateMon();
        if (Link.I.state == Link.RECONNECTING) showStatic("Восстанавливаю связь…");
        if ((Link.I.perms & Msg.PERM_CONTROL) == 0 && Link.I.state == Link.ACTIVE) toast("Режим просмотра: управление запрещено владельцем ПК");
    }

    protected void onStop() {
        super.onStop();
        Link.I.removeStateListener(this);
        Link.I.detach(this);
    }

    protected void onDestroy() {
        Keys.stickyChanged = null;
        super.onDestroy();
    }

    protected void onResume() {
        super.onResume();
        applyImmersive();
        if (Link.I.state == Link.IDLE) finish();
    }

    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) applyImmersive();
    }

    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyImmersive();
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    public void onBackPressed() {
        if (kbOn) { rv.hideKeyboard(); return; }
        if (keysOn) { setKeys(false); return; }
        if (toolsOn) { setTools(false); return; }
        Dlg.menu(this, "Сеанс", new String[] { "Свернуть — сеанс продолжится в фоне", "Отключиться" }, new Dlg.Pick() {
            public void pick(int i) {
                if (i == 0) moveTaskToBack(true);
                else { Link.I.disconnect(); finish(); Ui.noTransition(SessionActivity.this); }
            }
        });
    }

    public boolean dispatchKeyEvent(KeyEvent e) {
        // громкость — прокрутка колесом, удобно одной рукой
        if (e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP || e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && Store.volumeScroll(this)) {
                Session s = Link.I.session;
                if (s != null) s.wheel(e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ? 120 : -120, false, Link.I.curX, Link.I.curY);
                return true;
            }
            if (Store.volumeScroll(this)) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------- события ----------

    private void showStatic(String t) {
        banner.setText(t);
        banner.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideBanner);
    }

    private void updateMon() { bMon.set(Link.I.monCount > 1 ? "Экран " + (Link.I.monIdx + 1) : "Экран", false); }

    public void onState(int st, String msg, int err) {
        if (st == Link.IDLE) { finish(); Ui.noTransition(this); return; }
        if (st == Link.RECONNECTING) showStatic("Восстанавливаю связь…");
        else if (st == Link.ACTIVE) { banner.setVisibility(View.GONE); Link.I.applyQuality(); }
    }

    public void onUserTouch() {
        if (toolsOn && !keysOn && !kbOn) setTools(false);
    }

    public void onZoomChanged() { }

    public void onInfo(int w, int hh, int mi, int mc, String name, int sc) {
        runOnUiThread(new Runnable() { public void run() { rv.frameChanged(); updateMon(); } });
    }

    public void onFrame(List<Session.Rect> rects) { rv.postInvalidateOnAnimation(); }

    public void onCursor(boolean v, int x, int y) { rv.hostCursor(x, y); }

    public void onClip(final String t) {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("NanoDesk", t));
                    toast("Буфер компьютера скопирован на телефон");
                } catch (Exception ignore) { }
            }
        });
    }

    public void onPerms(final int p) {
        runOnUiThread(new Runnable() { public void run() { if ((p & Msg.PERM_CONTROL) == 0) toast("Режим просмотра: управление запрещено владельцем ПК"); } });
    }

    public void onNote(final String t) { runOnUiThread(new Runnable() { public void run() { toast(t); } }); }
    public void onWallpaper(byte[] j) { }
    public void onList(String s, List<Session.Item> it) { }
    public void onFileBegin(String n, long s) { }
    public void onFileData(byte[] d) { }
    public void onFileEnd() { }
    public void onClosed(String r, boolean b, boolean d) { }
}
