package com.nanodesk.mobile;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.nanodesk.mobile.net.AuthException;
import com.nanodesk.mobile.net.Channel;
import com.nanodesk.mobile.net.P2p;
import com.nanodesk.mobile.net.Session;
import com.nanodesk.mobile.net.UdpConn;

import java.util.List;

/**
 * Единственное подключение приложения. Живёт отдельно от экранов: поворот, сворачивание и возврат
 * не разрывают сеанс, а при потере связи подключение восстанавливается само (по токену, без пароля).
 */
public final class Link implements Session.Listener {
    public static final Link I = new Link();

    public static final int IDLE = 0, CONNECTING = 1, ACTIVE = 2, RECONNECTING = 3;
    public static final int E_NONE = 0, E_AUTH = 1, E_OFFLINE = 2, E_OTHER = 3, E_DENIED = 4;

    public interface StateListener { void onState(int state, String message, int error); }

    public Context app;
    public P2p p2p;
    public volatile int state = IDLE;
    public volatile String message = "";
    public volatile Session session;
    public volatile Store.Device dev;
    public volatile Session.Listener ui;
    private final java.util.concurrent.CopyOnWriteArrayList<StateListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<StateListener>();

    public void addStateListener(StateListener l) { if (l != null) listeners.addIfAbsent(l); }
    public void removeStateListener(StateListener l) { listeners.remove(l); }

    // состояние экрана удалённого ПК — переживает пересоздание окна
    public volatile Bitmap frame;
    private Canvas fc;
    public volatile int remoteW, remoteH, scale = 1, monIdx, monCount = 1, perms = 7;
    public volatile int curX, curY;
    public volatile boolean curVis;
    public volatile String hostName = "";
    public volatile String note = "";
    public volatile long lastFrameAt;
    public volatile boolean background;

    private volatile boolean cancel, userClosed;
    private String password = "";
    private volatile String token = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started;
    private long startedAt;

    private Link() {}

    public synchronized void ensureStarted(Context c) {
        if (started) return;
        app = c.getApplicationContext();
        try {
            p2p = new P2p();
            p2p.start();
        } catch (Exception e) {
            p2p = null;
            return;
        }
        try {
            final ConnectivityManager cm0 = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            p2p.wifiBinder = new com.nanodesk.mobile.net.UdpHub.Binder() {
                @SuppressWarnings("deprecation")
                public void bind(java.net.DatagramSocket s) throws java.io.IOException {
                    for (Network n : cm0.getAllNetworks()) {
                        android.net.NetworkCapabilities nc = cm0.getNetworkCapabilities(n);
                        if (nc == null || nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
                        if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            n.bindSocket(s);
                            return;
                        }
                    }
                }
            };
        } catch (Exception ignore) { }
        started = true;
        startedAt = System.currentTimeMillis();
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                    public void onAvailable(Network n) { networkChanged(); }
                    public void onLost(Network n) { }
                });
            }
        } catch (Exception ignore) { }
    }

    private void networkChanged() {
        if (System.currentTimeMillis() - startedAt < 4000) return;
        final P2p p = p2p;
        if (p != null) p.networkChanged();
        Session s = session;
        // сеть сменилась — старый путь мёртв; не ждём таймаута, а сразу восстанавливаем соединение
        if (state == ACTIVE && s != null) { try { s.conn.kill("сеть сменилась", false); } catch (Exception ignore) { } }
    }

    private void setState(final int st, final String msg, final int err) {
        state = st;
        message = msg;
        main.post(new Runnable() { public void run() { for (StateListener l : listeners) l.onState(st, msg, err); } });
    }

    public boolean busy() { return state != IDLE; }

    // ---------- подключение ----------

    public void connect(final Store.Device d, final String pw, final boolean remember) {
        if (state != IDLE || p2p == null) return;
        cancel = false;
        userClosed = false;
        dev = d;
        password = pw;
        token = "";
        setState(CONNECTING, "Подключаюсь…", E_NONE);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Session s = dial(d, pw);
                    if (cancel) { s.close(); setState(IDLE, "", E_NONE); return; }
                    d.last = System.currentTimeMillis();
                    if (remember) d.pw = Store.encrypt(pw); else d.pw = "";
                    if (d.name.length() == 0) d.name = d.id;
                    Store.save(app, d);
                    begin(s);
                } catch (AuthException e) {
                    setState(IDLE, e.getMessage(), E_AUTH);
                } catch (P2p.ConnectException e) {
                    setState(IDLE, e.getMessage(), cancel ? E_NONE : (e.offline ? E_OFFLINE : E_OTHER));
                } catch (Exception e) {
                    setState(IDLE, cancel ? "" : (e.getMessage() == null ? "Не удалось подключиться" : e.getMessage()), cancel ? E_NONE : E_OTHER);
                }
            }
        }, "link-connect");
        t.setDaemon(true);
        t.start();
    }

    private Session dial(Store.Device d, String pw) throws Exception {
        P2p.Cancel cn = new P2p.Cancel() { public boolean cancelled() { return cancel; } };
        UdpConn conn = d.ip ? p2p.connectDirect(d.id, d.port, cn) : p2p.connectId(d.id, Store.phoneName(app), cn);
        Channel ch = P2p.handshake(conn, pw);
        Session s = new Session(ch);
        s.listener = this;
        return s;
    }

    private void begin(Session s) {
        session = s;
        s.start();
        applyQuality();
        setState(ACTIVE, "", E_NONE);
        try {
            Intent i = new Intent(app, SessionService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
        } catch (Exception ignore) { }
    }

    /** Отмена подключения или отключение по желанию пользователя. */
    public void disconnect() {
        cancel = true;
        userClosed = true;
        Session s = session;
        if (s != null) s.close();
        if (state == CONNECTING || state == RECONNECTING) setState(IDLE, "", E_NONE);
    }

    public void applyQuality() {
        Session s = session;
        if (s == null) return;
        if (background) s.quality(Math.min(40, Store.quality(app)), 3, false, 2, 0);
        else s.quality(Store.quality(app), Store.fps(app), Store.autoQuality(app), Store.halfRes(app) ? 2 : 1, 0);
    }

    /** Когда приложение свёрнуто — картинка почти не нужна, держим только связь (экономит батарею и трафик). */
    public void setBackground(boolean bg) {
        if (background == bg) return;
        background = bg;
        applyQuality();
    }

    // ---------- восстановление связи ----------

    private void reconnect() {
        final Store.Device d = dev;
        setState(RECONNECTING, "Связь потеряна — восстанавливаю…", E_NONE);
        Thread t = new Thread(new Runnable() {
            public void run() {
                int attempt = 0;
                while (!userClosed && attempt < 40) {
                    attempt++;
                    try { Thread.sleep(Math.min(5000, 1200 * attempt)); } catch (InterruptedException e) { return; }
                    if (userClosed) break;
                    try {
                        String pw = token.length() > 0 ? token : password;
                        Session s = dial(d, pw);
                        if (userClosed) { s.close(); break; }
                        begin(s);
                        return;
                    } catch (AuthException e) {
                        // токен больше не действует (например, хост перезапущен)
                        setState(IDLE, "Сеанс завершён. Подключитесь заново.", E_OTHER);
                        return;
                    } catch (Exception e) {
                        if (attempt == 6) { try { p2p.networkChanged(); } catch (Exception ignore) { } }
                    }
                }
                if (state == RECONNECTING) setState(IDLE, userClosed ? "" : "Не удалось восстановить соединение", userClosed ? E_NONE : E_OTHER);
            }
        }, "link-reconnect");
        t.setDaemon(true);
        t.start();
    }

    // ---------- события сеанса ----------

    private void ensureFrame(int w, int h) {
        Bitmap f = frame;
        if (f == null || f.getWidth() != w || f.getHeight() != h) {
            try {
                Bitmap nb = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
                nb.eraseColor(0xFF000000);
                Canvas nc = new Canvas(nb);
                if (f != null) nc.drawBitmap(f, 0, 0, null);
                synchronized (this) { frame = nb; fc = nc; }
            } catch (OutOfMemoryError e) { }
        }
    }

    public void onInfo(int w, int h, int mi, int mc, String name, int sc) {
        remoteW = w; remoteH = h; monIdx = mi; monCount = mc; hostName = name; scale = sc;
        ensureFrame((w + sc - 1) / sc, (h + sc - 1) / sc);
        Store.Device d = dev;
        if (d != null && name != null && name.length() > 0 && !name.equals(d.name)) {
            d.name = name;
            Store.Device saved = Store.find(app, d.id);
            if (saved != null) { saved.name = name; Store.save(app, saved); }
        }
        Session.Listener l = ui;
        if (l != null) l.onInfo(w, h, mi, mc, name, sc);
    }

    public void onFrame(List<Session.Rect> rects) {
        for (Session.Rect r : rects) {
            Bitmap b = null;
            try { b = BitmapFactory.decodeByteArray(r.jpeg, 0, r.jpeg.length); } catch (OutOfMemoryError e) { }
            if (b == null) continue;
            if (frame == null) ensureFrame(r.x + b.getWidth(), r.y + b.getHeight());
            Canvas c;
            synchronized (this) { c = fc; }
            if (c != null) c.drawBitmap(b, r.x, r.y, null);
            b.recycle();
        }
        lastFrameAt = System.currentTimeMillis();
        Session.Listener l = ui;
        if (l != null) l.onFrame(rects);
    }

    public void onCursor(boolean v, int x, int y) {
        curVis = v; curX = x; curY = y;
        Session.Listener l = ui;
        if (l != null) l.onCursor(v, x, y);
    }

    public void onClip(String t) { Session.Listener l = ui; if (l != null) l.onClip(t); }
    public void onPerms(int p) { perms = p; Session.Listener l = ui; if (l != null) l.onPerms(p); }
    public void onNote(String t) { note = t; Session.Listener l = ui; if (l != null) l.onNote(t); }
    public void onWallpaper(byte[] j) { Session.Listener l = ui; if (l != null) l.onWallpaper(j); }
    public void onList(String s, List<Session.Item> it) { Session.Listener l = ui; if (l != null) l.onList(s, it); }
    public void onFileBegin(String n, long s) { Session.Listener l = ui; if (l != null) l.onFileBegin(n, s); }
    public void onFileData(byte[] d) { Session.Listener l = ui; if (l != null) l.onFileData(d); }
    public void onFileEnd() { Session.Listener l = ui; if (l != null) l.onFileEnd(); }

    public void onClosed(String reason, boolean byHost, boolean denied) {
        Session s = session;
        if (s != null && s.resumeToken.length() > 0) token = s.resumeToken;
        session = null;
        if (userClosed || byHost || denied) {
            userClosed = true;
            setState(IDLE, denied ? "Владелец ПК отклонил подключение" : (byHost ? "Владелец ПК завершил сеанс" : ""), denied ? E_DENIED : E_NONE);
            return;
        }
        reconnect();
    }

    // Экраны подключаются и отключаются парами; когда не осталось ни одного видимого — переходим на экономный режим.
    private int visibleCount;

    public synchronized void attach(Session.Listener l) {
        ui = l;
        visibleCount++;
        setBackground(false);
    }

    public synchronized void detach(Session.Listener l) {
        if (ui == l) ui = null;
        if (visibleCount > 0) visibleCount--;
        if (visibleCount == 0 && state == ACTIVE) setBackground(true);
    }

    public void resetScreen() {
        synchronized (this) { frame = null; fc = null; }
    }
}
