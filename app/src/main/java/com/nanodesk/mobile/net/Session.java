package com.nanodesk.mobile.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/** Сеанс удалённого управления: приём экрана и отправка ввода поверх защищённого канала. */
public final class Session {
    public static final class Rect {
        public int x, y, w, h;
        public byte[] jpeg;
    }

    public static final class Item {
        public String name, path;
        public boolean dir;
        public long size;
    }

    public interface Listener {
        void onInfo(int w, int h, int monIdx, int monCount, String hostName, int scale);
        /** Вызывается в потоке приёма; пока метод не вернулся, следующий кадр не запрашивается. */
        void onFrame(List<Rect> rects);
        void onCursor(boolean visible, int x, int y);
        void onClip(String text);
        void onPerms(int perms);
        void onNote(String text);
        void onWallpaper(byte[] jpeg);
        void onList(String shown, List<Item> items);
        void onFileBegin(String name, long size);
        void onFileData(byte[] data);
        void onFileEnd();
        void onClosed(String reason, boolean byHost, boolean denied);
    }

    public final Channel ch;
    public final UdpConn conn;
    public Listener listener;
    public volatile String resumeToken = "";
    public volatile int perms = 7;
    public volatile int remoteW, remoteH, monIdx, monCount = 1, scale = 1;
    public volatile String hostName = "";
    public volatile long rxBytes;
    public volatile int frames;
    private volatile boolean closed, byeSeen, denied;
    private final Object oq = new Object();
    private final LinkedList<Object[]>[] q;
    private byte[] pendingMove;
    private boolean pendingQuality;
    private byte[] qualityBody;
    private volatile boolean paused;

    @SuppressWarnings("unchecked")
    public Session(Channel ch) {
        this.ch = ch;
        this.conn = ch.conn();
        q = new LinkedList[3];
        for (int i = 0; i < 3; i++) q[i] = new LinkedList<Object[]>();
    }

    public void start() {
        Thread r = new Thread(new Runnable() { public void run() { readLoop(); } }, "sess-rx");
        r.setDaemon(true);
        r.start();
        Thread w = new Thread(new Runnable() { public void run() { writeLoop(); } }, "sess-tx");
        w.setDaemon(true);
        w.start();
    }

    public boolean isClosed() { return closed; }

    // ---------- отправка ----------

    public void post(int type, byte[] body) {
        if (closed) return;
        synchronized (oq) {
            q[Msg.priority(type)].add(new Object[] { type, body });
            oq.notifyAll();
        }
    }

    private void writeLoop() {
        try {
            while (!closed) {
                Object[] it = null;
                byte[] mv = null, ql = null;
                int pr = 0;
                synchronized (oq) {
                    while (!closed) {
                        if (pendingQuality) { ql = qualityBody; pendingQuality = false; break; }
                        if (pendingMove != null) { mv = pendingMove; pendingMove = null; break; }
                        for (int i = 0; i < 3 && it == null; i++) if (!q[i].isEmpty()) { it = q[i].removeFirst(); pr = i; }
                        if (it != null) break;
                        try { oq.wait(); } catch (InterruptedException e) { return; }
                    }
                }
                if (closed) return;
                if (ql != null) { conn.writePriority = 0; ch.send(Msg.QUALITY, ql); continue; }
                if (mv != null) { conn.writePriority = 0; ch.send(Msg.MOUSE, mv); continue; }
                int ty = (Integer) it[0];
                if (ty == -1) {
                    byte[] pb = (byte[]) it[1];
                    Thread.sleep(((pb[0] & 0xFF) << 8) | (pb[1] & 0xFF));
                    continue;
                }
                conn.writePriority = pr;
                ch.send(ty, (byte[]) it[1]);
            }
        } catch (Exception e) {
            finish("Соединение потеряно", false, false);
        }
    }

    /** Движение мыши «склеивается»: на медленной сети уходит только последнее положение. */
    public void mouseMove(int x, int y) {
        byte[] b = new Bytes.W().u8(0).u8(0).i32(x).i32(y).i32(0).toBytes();
        synchronized (oq) { pendingMove = b; oq.notifyAll(); }
    }

    public void mouseButton(boolean down, int button, int x, int y) {
        post(Msg.MOUSE, new Bytes.W().u8(down ? 1 : 2).u8(button).i32(x).i32(y).i32(0).toBytes());
    }

    public void wheel(int delta, boolean horizontal, int x, int y) {
        post(Msg.MOUSE, new Bytes.W().u8(horizontal ? 4 : 3).u8(0).i32(x).i32(y).i32(delta).toBytes());
    }

    public void key(int vk, boolean up) {
        post(Msg.KEY, new byte[] { (byte) vk, (byte) (up ? 1 : 0) });
    }

    /** Пауза в очереди отправки: чтобы хост успел обработать буфер обмена перед Ctrl+V. */
    public void pause(int ms) { post(-1, new byte[] { (byte) (ms >> 8), (byte) ms }); }

    public void tapKey(int vk) { key(vk, false); key(vk, true); }

    public void clip(String text) { post(Msg.CLIP, text.getBytes(Bytes.UTF8)); }

    /** q — качество 1..100, fps, auto — подбирать автоматически, scale 1 или 2. Только JPEG. */
    public void quality(int q1, int fps, boolean auto, int scaleDiv, int mbps) {
        byte[] b = new byte[] { (byte) q1, (byte) fps, (byte) (auto ? 1 : 0), (byte) scaleDiv, 1, (byte) mbps };
        synchronized (oq) { qualityBody = b; pendingQuality = true; oq.notifyAll(); }
    }

    public void selectMonitor(int idx) { post(Msg.MONITOR, new Bytes.W().i32(idx).toBytes()); }

    public void power(int action) { post(Msg.POWER, new byte[] { (byte) action }); }

    public void listDir(String path) { post(Msg.LIST_REQ, new Bytes.W().str(path == null ? "" : path).toBytes()); }

    public void getFile(String path) { post(Msg.GET_FILE, new Bytes.W().str(path).toBytes()); }

    public void bye() {
        try { ch.send(Msg.BYE, new byte[0]); } catch (Exception ignore) { }
    }

    /** Отправка файла на ПК (в Загрузки / Рабочий стол / Документы). */
    public void upload(final InputStream in, final String name, final long size, final int dest, final Runnable done, final String[] err) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    post(Msg.DROP_AT, new Bytes.W().i32(dest).i32(0).toBytes());
                    post(Msg.FILE_BEGIN, new Bytes.W().str(name).i64(size).toBytes());
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0 && !closed) {
                        byte[] c = new byte[n];
                        System.arraycopy(buf, 0, c, 0, n);
                        post(Msg.FILE_DATA, c);
                        // не копим в памяти больше пары мегабайт
                        while (!closed) {
                            int len;
                            synchronized (oq) { len = q[2].size(); }
                            if (len < 64) break;
                            Thread.sleep(15);
                        }
                    }
                    post(Msg.FILE_END, new byte[0]);
                } catch (Exception e) {
                    if (err != null) err[0] = String.valueOf(e.getMessage());
                } finally {
                    try { in.close(); } catch (Exception ignore) { }
                    if (done != null) done.run();
                }
            }
        }, "upload");
        t.setDaemon(true);
        t.start();
    }

    // ---------- приём ----------

    private void readLoop() {
        String why = "Соединение закрыто";
        try {
            while (!closed) {
                Channel.Packet p = ch.recv();
                if (p == null) break;
                if (p.type == Msg.SCREEN) { onScreen(p.body); continue; }
                handle(p.type, p.body);
            }
        } catch (Exception e) {
            if (conn != null && conn.closeReason.length() > 0) why = conn.closeReason;
            else if (e.getMessage() != null) why = e.getMessage();
        }
        finish(why, byeSeen, denied);
    }

    private void onScreen(byte[] body) {
        List<Rect> l = new ArrayList<Rect>();
        try {
            Bytes.R r = new Bytes.R(body);
            r.u8();
            int n = r.i32();
            for (int i = 0; i < n && i < 4096; i++) {
                Rect x = new Rect();
                x.x = r.i32(); x.y = r.i32(); x.w = r.i32(); x.h = r.i32();
                int len = r.i32();
                x.jpeg = r.bytes(len);
                l.add(x);
            }
        } catch (Exception e) {
            l.clear();
        }
        frames++;
        Listener ls = listener;
        try { if (ls != null && !l.isEmpty()) ls.onFrame(l); } catch (Exception ignore) { }
        post(Msg.ACK, new byte[0]);
    }

    private void handle(int type, byte[] body) {
        Listener ls = listener;
        try {
            switch (type) {
                case Msg.INFO: {
                    Bytes.R r = new Bytes.R(body);
                    int w = r.i32(), h = r.i32();
                    monIdx = r.i32();
                    monCount = r.i32();
                    hostName = r.str();
                    int sc = 1;
                    if (r.left() >= 4) sc = r.i32();
                    if (sc < 1 || sc > 2) sc = 1;
                    remoteW = w; remoteH = h; scale = sc;
                    if (ls != null) ls.onInfo(w, h, monIdx, monCount, hostName, sc);
                    break;
                }
                case Msg.CURSOR:
                    if (body.length >= 9 && ls != null) ls.onCursor((body[0] & 1) != 0, Bytes.r32le(body, 1), Bytes.r32le(body, 5));
                    break;
                case Msg.CLIP:
                    if (ls != null) ls.onClip(new String(body, Bytes.UTF8));
                    break;
                case Msg.PERMS:
                    if (body.length >= 1) { perms = body[0] & 0xFF; if (ls != null) ls.onPerms(perms); }
                    break;
                case Msg.NOTE:
                    if (ls != null) ls.onNote(new String(body, Bytes.UTF8));
                    break;
                case Msg.RESUME:
                    resumeToken = new String(body, "US-ASCII");
                    break;
                case Msg.WALLPAPER:
                    if (ls != null) ls.onWallpaper(body);
                    break;
                case Msg.BYE:
                    byeSeen = true;
                    break;
                case Msg.DENIED:
                    denied = true;
                    break;
                case Msg.LIST_RESP: {
                    Bytes.R r = new Bytes.R(body);
                    String shown = r.str();
                    int n = r.i32();
                    List<Item> items = new ArrayList<Item>();
                    for (int i = 0; i < n && i < 6000; i++) {
                        Item it = new Item();
                        it.name = r.str(); it.path = r.str(); it.dir = r.bool(); it.size = r.i64();
                        items.add(it);
                    }
                    if (ls != null) ls.onList(shown, items);
                    break;
                }
                case Msg.FILE_BEGIN: {
                    Bytes.R r = new Bytes.R(body);
                    String name = r.str();
                    long size = r.i64();
                    if (ls != null) ls.onFileBegin(name, size);
                    break;
                }
                case Msg.FILE_DATA:
                    if (ls != null) ls.onFileData(body);
                    break;
                case Msg.FILE_END:
                    if (ls != null) ls.onFileEnd();
                    break;
                default:
                    break;
            }
        } catch (Exception ignore) { }
    }

    private void finish(String reason, boolean byHost, boolean den) {
        synchronized (oq) {
            if (closed) return;
            closed = true;
            oq.notifyAll();
        }
        Listener ls = listener;
        try { ch.close(); } catch (Exception ignore) { }
        if (ls != null) { try { ls.onClosed(reason, byHost, den || denied); } catch (Exception ignore) { } }
    }

    public void close() {
        bye();
        finish("Отключено", false, false);
    }
}
