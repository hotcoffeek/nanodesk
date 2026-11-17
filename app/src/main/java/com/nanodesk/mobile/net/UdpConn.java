package com.nanodesk.mobile.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Соединение: надёжный двусторонний поток между двумя устройствами по UDP (порт UdpConn из ПК-версии). */
public final class UdpConn {
    public static final int PUNCHING = 0, OPEN = 1, CLOSED = 2;

    private final UdpHub hub;
    public final long cid;
    private final RLink link = new RLink();
    private volatile InetSocketAddress remote;
    private volatile InetSocketAddress alt;       // второй известный путь к собеседнику (посредник, пока основной — прямой, и наоборот)
    private double nextProbe;
    private List<InetSocketAddress> cands = new ArrayList<InetSocketAddress>();
    private volatile int state;
    private final double createdAt;
    private double lastRecv, lastSend, nextHello, punchStart, nextRelayHello;
    public double punchTimeoutMs = 14000;
    private final CountDownLatch ready = new CountDownLatch(1);
    public volatile boolean trusted;              // прошёл проверку пароля; пока false — соединение живёт не дольше 65 с
    public volatile String closeReason = "";
    public long bytesTx, bytesRx;
    public volatile int writePriority;
    public volatile int readTimeoutMs;            // 0 — ждать бесконечно
    public volatile Runnable onClosed;
    public String tag = "";                       // ID собеседника (для резервного канала)
    public boolean relayOk;                       // принимаем пакеты резервного канала
    public boolean relayInit;                     // сами подключаем посредника, если напрямую не вышло
    public int relayAfter = 14000;
    private boolean direct;

    UdpConn(UdpHub hub, long cid) {
        this.hub = hub;
        this.cid = cid;
        createdAt = UdpHub.now();
        lastRecv = createdAt;
        lastSend = createdAt;
        link.sendData = new RLink.DataSink() {
            public void sendData(long seq, byte[] payload) { sendDataPkt(seq, payload); }
        };
        link.sendAck = new RLink.AckSink() {
            public void sendAck(long cum, long sack, int rwnd, long trigger) { sendAckPkt(cum, sack, rwnd, trigger); }
        };
    }

    public boolean isOpen() { return state == OPEN; }

    public boolean isClosed() { return state == CLOSED; }

    public InetSocketAddress remote() { return remote; }

    public boolean relayed() { InetSocketAddress r = remote; return r != null && UdpHub.isRelayEp(r); }

    public double rttMs() { synchronized (link.sync) { return link.srtt(); } }

    public String stats() {
        synchronized (link.sync) {
            return "rtt " + (int) link.srtt() + " мс, окно " + (int) link.cwnd() + ", повторов " + link.retx + ", таймаутов " + link.timeouts;
        }
    }

    private boolean fromPeer(InetSocketAddress from) {
        InetSocketAddress r = remote, a = alt;
        return (r != null && r.equals(from)) || (a != null && a.equals(from));
    }

    /** Начать пробивку NAT: HELLO уходит по всем адресам-кандидатам, пока кто-то не ответит. */
    public void startPunch(List<InetSocketAddress> candidates) {
        List<InetSocketAddress> l = new ArrayList<InetSocketAddress>();
        for (InetSocketAddress e : candidates) if (e != null) l.add(e);
        cands = l;
        punchStart = UdpHub.now();
        nextHello = 0;
        if (relayOk && punchTimeoutMs < 42000) punchTimeoutMs = 42000;
    }

    /** Прямое подключение по адресу: зовём хост пакетом DIRECT, он создаст соединение с тем же cid. */
    public void startDirect(InetSocketAddress host) {
        List<InetSocketAddress> l = new ArrayList<InetSocketAddress>();
        l.add(host);
        cands = l;
        direct = true;
        punchStart = UdpHub.now();
        nextHello = 0;
    }

    public boolean waitReady(int ms) {
        try {
            return ready.await(ms, TimeUnit.MILLISECONDS) && state == OPEN;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void setOpen() {
        boolean was;
        synchronized (this) {
            was = state == PUNCHING;
            if (was) state = OPEN;
        }
        if (was) {
            lastRecv = UdpHub.now();
            ready.countDown();
        }
    }

    public void kill(String why, boolean notify) {
        synchronized (this) {
            if (state == CLOSED) return;
            state = CLOSED;
        }
        closeReason = why;
        InetSocketAddress r = remote;
        if (notify && r != null) {
            byte[] b = new byte[5];
            b[0] = (byte) UdpHub.T_CLOSE;
            Bytes.w32be(b, 1, cid);
            for (int i = 0; i < 3; i++) hub.sendTo(b, 5, r);
        }
        ready.countDown();
        synchronized (link.sync) { link.sync.notifyAll(); }
        hub.unregister(this);
        Runnable h = onClosed;
        if (h != null) { try { h.run(); } catch (Exception ignore) { } }
    }

    // ---------- пакеты наружу ----------
    private void sendDataPkt(long seq, byte[] payload) {
        InetSocketAddress r = remote;
        if (r == null) return;
        byte[] b = new byte[9 + payload.length];
        b[0] = (byte) UdpHub.T_DATA;
        Bytes.w32be(b, 1, cid);
        Bytes.w32be(b, 5, seq);
        System.arraycopy(payload, 0, b, 9, payload.length);
        hub.sendTo(b, b.length, r);
        bytesTx += b.length;
        lastSend = UdpHub.now();
    }

    private void sendAckPkt(long cum, long sack, int rwnd, long trigger) {
        InetSocketAddress r = remote;
        if (r == null) return;
        byte[] b = new byte[25];
        b[0] = (byte) UdpHub.T_ACK;
        Bytes.w32be(b, 1, cid);
        Bytes.w32be(b, 5, cum);
        Bytes.w64be(b, 9, sack);
        Bytes.w32be(b, 17, rwnd & 0xFFFFFFFFL);
        Bytes.w32be(b, 21, trigger);
        hub.sendTo(b, 25, r);
        lastSend = UdpHub.now();
    }

    private void sendSimple(int type, InetSocketAddress to) {
        byte[] b = new byte[5];
        b[0] = (byte) type;
        Bytes.w32be(b, 1, cid);
        hub.sendTo(b, 5, to);
    }

    // ---------- пакеты снаружи (поток приёма хаба) ----------
    void onPacket(int t, byte[] buf, int n, InetSocketAddress from) {
        if (state == CLOSED) return;
        if (UdpHub.isRelayEp(from)) {
            if (!relayOk || t == UdpHub.T_DIRECT) return;
            // хост уже открылся по прямому пути, а мы его не слышим и зовём через посредника — переходим на посредника
            if (t == UdpHub.T_HELLO && state == OPEN && remote != null && !remote.equals(from) && UdpHub.now() - lastRecv > 2500) remote = from;
        }
        switch (t) {
            case UdpHub.T_HELLO:
                // соединение держится через посредника, а собеседник стучится напрямую — запоминаем путь и отвечаем
                if (state == OPEN && remote != null && UdpHub.isRelayEp(remote) && !UdpHub.isRelayEp(from) && cands != null && cands.size() > 0) {
                    alt = from;
                    sendSimple(UdpHub.T_HELLO_ACK, from);
                    break;
                }
                if (state == PUNCHING && remote == null) remote = from;
                if (remote != null && remote.equals(from)) {
                    sendSimple(UdpHub.T_HELLO_ACK, from);
                    setOpen();
                } else if (state == OPEN) sendSimple(UdpHub.T_HELLO_ACK, from);
                break;
            case UdpHub.T_HELLO_ACK:
                // прямой путь заработал в обе стороны — уходим с посредника на него (посредник остаётся запасным)
                if (state == OPEN && remote != null && UdpHub.isRelayEp(remote) && !UdpHub.isRelayEp(from) && cands != null && cands.size() > 0) {
                    alt = remote;
                    remote = from;
                    lastRecv = UdpHub.now();
                    break;
                }
                if (state == PUNCHING && remote == null) remote = from;
                if (remote != null && remote.equals(from)) setOpen();
                break;
            case UdpHub.T_PING:
                if (fromPeer(from)) lastRecv = UdpHub.now();
                break;
            case UdpHub.T_CLOSE:
                if (fromPeer(from)) kill("собеседник закрыл соединение", false);
                break;
            case UdpHub.T_DATA:
                if (n < 10 || !fromPeer(from)) return;
                if (state == PUNCHING) setOpen();
                {
                    byte[] payload = new byte[n - 9];
                    System.arraycopy(buf, 9, payload, 0, payload.length);
                    double now = UdpHub.now();
                    lastRecv = now;
                    bytesRx += n;
                    synchronized (link.sync) {
                        link.onData(Bytes.r32be(buf, 5), payload, now);
                        link.pump(now);
                        link.sync.notifyAll();
                    }
                }
                break;
            case UdpHub.T_ACK:
                if (n < 25 || !fromPeer(from)) return;
                if (state == PUNCHING) setOpen();
                {
                    double now = UdpHub.now();
                    lastRecv = now;
                    synchronized (link.sync) {
                        link.onAck(Bytes.r32be(buf, 5), Bytes.r64be(buf, 9), (int) Bytes.r32be(buf, 17), Bytes.r32be(buf, 21), now);
                        link.pump(now);
                        link.sync.notifyAll();
                    }
                }
                break;
            default:
                break;
        }
    }

    /** Вызывается хабом раз в пару миллисекунд. */
    void tick(double now) {
        int st = state;
        if (st == CLOSED) return;
        if (!trusted && now - createdAt > 65000) { kill("время на подключение вышло", true); return; }
        if (st == PUNCHING) {
            if (cands.size() > 0 && now >= nextHello) {
                for (InetSocketAddress c : cands) {
                    if (direct) {
                        byte[] b = new byte[5];
                        b[0] = (byte) UdpHub.T_DIRECT;
                        Bytes.w32be(b, 1, cid);
                        hub.sendTo(b, 5, c);
                    } else sendSimple(UdpHub.T_HELLO, c);
                }
                nextHello = now + 80;
            }
            if (relayInit && tag.length() > 0 && punchStart > 0 && now - punchStart > relayAfter && now >= nextRelayHello) {
                sendSimple(UdpHub.T_HELLO, UdpHub.relayEp(cid));
                nextRelayHello = now + 350;
            }
            if (punchStart > 0 && now - punchStart > punchTimeoutMs) kill("не удалось пробить соединение", false);
            return;
        }
        synchronized (link.sync) {
            link.pump(now);
            link.sync.notifyAll();
        }
        InetSocketAddress r = remote;
        // идём через посредника — раз в 1,5 с пробуем прямой путь: если пробьётся, соединение само перейдёт на него
        if (r != null && !direct && cands != null && cands.size() > 0 && now >= nextProbe && UdpHub.isRelayEp(r)) {
            nextProbe = now + 1500;
            for (InetSocketAddress pc : cands) sendSimple(UdpHub.T_HELLO, pc);
        }
        if (r != null && now - lastSend >= 2000) { sendSimple(UdpHub.T_PING, r); lastSend = now; }
        // связь налажена — обрыв замечаем быстро (пинги идут каждые 2 с)
        double deadAfter = !trusted ? 20000 : (r != null && UdpHub.isRelayEp(r) ? 14000 : 8000);
        if (now - lastRecv > deadAfter) kill("нет ответа от компьютера", true);
    }

    // ---------- потоки ----------
    private final InputStream in = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int r = read(one, 0, 1);
            return r <= 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            if (count <= 0) return 0;
            long end = readTimeoutMs > 0 ? System.currentTimeMillis() + readTimeoutMs : Long.MAX_VALUE;
            synchronized (link.sync) {
                while (true) {
                    int got = link.read(buffer, offset, count);
                    if (got > 0) { link.sync.notifyAll(); return got; }
                    if (state == CLOSED) return -1;
                    long wait = 200;
                    if (readTimeoutMs > 0) {
                        long left = end - System.currentTimeMillis();
                        if (left <= 0) throw new SocketTimeoutException("timeout");
                        if (left < wait) wait = left + 1;
                    }
                    try {
                        link.sync.wait(wait);
                    } catch (InterruptedException e) {
                        throw new IOException("interrupted");
                    }
                }
            }
        }
    };

    private final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] { (byte) b }, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int count) throws IOException {
            if (count <= 0) return;
            byte[] copy = new byte[count];
            System.arraycopy(buffer, offset, copy, 0, count);
            int pr = writePriority;
            synchronized (link.sync) {
                while (!link.canWrite()) {
                    if (state == CLOSED) throw new IOException("closed");
                    try {
                        link.sync.wait(50);
                    } catch (InterruptedException e) {
                        throw new IOException("interrupted");
                    }
                }
                if (state == CLOSED) throw new IOException("closed");
                link.writeFrame(copy, pr);
                link.pump(UdpHub.now());
            }
        }
    };

    public InputStream in() { return in; }

    public OutputStream out() { return out; }

    /** Для тестов: хост принимает прямое подключение. */
    void acceptFrom(InetSocketAddress from) {
        remote = from;
        setOpen();
    }
}
