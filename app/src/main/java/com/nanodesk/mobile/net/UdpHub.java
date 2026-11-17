package com.nanodesk.mobile.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Один общий UDP-сокет приложения: через него идут STUN, пробивка NAT и все соединения. */
public final class UdpHub {
    public static final int T_HELLO = 0x41, T_HELLO_ACK = 0x42, T_DIRECT = 0x43, T_DATA = 0x50, T_ACK = 0x51, T_PING = 0x52, T_CLOSE = 0x5F;

    public interface RelaySender { void send(String peerId, byte[] data, int len); }

    private final DatagramSocket sock;
    private final HashMap<Long, UdpConn> conns = new HashMap<Long, UdpConn>();
    private volatile UdpConn[] snapshot = new UdpConn[0];
    private volatile boolean stop;
    public final int port;
    public volatile RelaySender relaySend;
    private static final long T0 = System.nanoTime();
    private static final SecureRandom RNG = new SecureRandom();

    public static double now() { return (System.nanoTime() - T0) / 1e6 + 1.0; }

    /** Привязка сокета к конкретной сети (например, к Wi‑Fi в обход VPN). */
    public interface Binder { void bind(DatagramSocket s) throws IOException; }

    public UdpHub(int bindPort) throws IOException { this(bindPort, null); }

    public UdpHub(int bindPort, Binder binder) throws IOException {
        DatagramSocket s;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            if (binder != null) { try { binder.bind(s); } catch (Exception ignore) { } }
            try { s.setReceiveBufferSize(4 * 1024 * 1024); } catch (Exception ignore) { }
            try { s.setSendBufferSize(2 * 1024 * 1024); } catch (Exception ignore) { }
            s.bind(new InetSocketAddress(bindPort));
        } catch (IOException e) {
            if (bindPort == 0) throw e;
            s = new DatagramSocket(null);
            if (binder != null) { try { binder.bind(s); } catch (Exception ignore) { } }
            s.bind(new InetSocketAddress(0));
        }
        sock = s;
        port = sock.getLocalPort();
        Thread rx = new Thread(new Runnable() { public void run() { rxLoop(); } }, "udp-rx");
        rx.setDaemon(true);
        rx.start();
        Thread tk = new Thread(new Runnable() { public void run() { tickLoop(); } }, "udp-tick");
        tk.setDaemon(true);
        tk.start();
    }

    public void dispose() {
        stop = true;
        for (UdpConn c : snapshot) { try { c.kill("закрыто", true); } catch (Exception ignore) { } }
        try { sock.close(); } catch (Exception ignore) { }
    }

    // Условный адрес «через посредника»: 240.x.y.z:1 (зарезервированный диапазон, в интернете не встречается).
    public static InetSocketAddress relayEp(long cid) {
        try {
            return new InetSocketAddress(InetAddress.getByAddress(new byte[] { (byte) 240, (byte) (cid >> 16), (byte) (cid >> 8), (byte) cid }), 1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isRelayEp(InetSocketAddress ep) {
        if (ep == null || ep.getPort() != 1) return false;
        InetAddress a = ep.getAddress();
        if (a == null) return false;
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 240;
    }

    /** Пакет из резервного канала — обрабатывается как обычный, «от» условного адреса. */
    public void injectRelay(byte[] data, int n) {
        if (n < 5 || stop) return;
        long cid = Bytes.r32be(data, 1);
        try { dispatch(data, n, relayEp(cid)); } catch (Exception ignore) { }
    }

    void sendTo(byte[] b, int len, InetSocketAddress ep) {
        if (isRelayEp(ep)) {
            RelaySender rs = relaySend;
            if (rs == null || len < 5) return;
            UdpConn rc = find(Bytes.r32be(b, 1));
            if (rc == null || rc.tag.length() == 0) return;
            rs.send(rc.tag, b, len);
            return;
        }
        try {
            sock.send(new DatagramPacket(b, len, ep));
        } catch (Exception ignore) { }
    }

    public UdpConn find(long cid) {
        synchronized (conns) { return conns.get(cid); }
    }

    public UdpConn create(long cid) {
        UdpConn c = new UdpConn(this, cid);
        synchronized (conns) {
            conns.put(cid, c);
            rebuild();
        }
        return c;
    }

    public static long newCid() {
        while (true) {
            long v = RNG.nextInt() & 0xFFFFFFFFL;
            if (v != 0) return v;
        }
    }

    void unregister(UdpConn c) {
        synchronized (conns) {
            UdpConn x = conns.get(c.cid);
            if (x == c) conns.remove(c.cid);
            rebuild();
        }
    }

    private void rebuild() {
        snapshot = conns.values().toArray(new UdpConn[0]);
    }

    private void rxLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket pk = new DatagramPacket(buf, buf.length);
        while (!stop) {
            try {
                pk.setLength(buf.length);
                sock.receive(pk);
            } catch (IOException e) {
                if (stop || sock.isClosed()) break;
                try { Thread.sleep(2); } catch (InterruptedException ie) { break; }
                continue;
            }
            int n = pk.getLength();
            if (n < 1) continue;
            try {
                dispatch(buf, n, new InetSocketAddress(pk.getAddress(), pk.getPort()));
            } catch (Exception ignore) { }
        }
    }

    private void tickLoop() {
        while (!stop) {
            UdpConn[] a = snapshot;
            double t = now();
            for (UdpConn c : a) {
                try { c.tick(t); } catch (Exception ignore) { }
            }
            try { Thread.sleep(a.length > 0 ? 2 : 40); } catch (InterruptedException e) { break; }
        }
    }

    private void dispatch(byte[] buf, int n, InetSocketAddress from) {
        int t = buf[0] & 0xFF;
        if (t < 0x40) { onStun(buf, n); return; }
        if (n < 5) return;
        long cid = Bytes.r32be(buf, 1);
        if (t == T_DIRECT) return;      // телефон не принимает входящих прямых подключений
        UdpConn c;
        synchronized (conns) { c = conns.get(cid); }
        if (c != null) c.onPacket(t, buf, n, from);
    }

    // ---------------- STUN ----------------
    private static final class StunReq {
        byte[] tx = new byte[12];
        InetSocketAddress mapped;
        final CountDownLatch done = new CountDownLatch(1);
    }

    private final List<StunReq> stunPending = new ArrayList<StunReq>();

    private static byte[] buildStun(byte[] tx) {
        byte[] b = new byte[20];
        b[0] = 0; b[1] = 1;                                   // Binding Request
        b[4] = 0x21; b[5] = 0x12; b[6] = (byte) 0xA4; b[7] = 0x42;   // magic cookie
        System.arraycopy(tx, 0, b, 8, 12);
        return b;
    }

    private void onStun(byte[] buf, int n) {
        if (n < 20 || buf[4] != 0x21 || buf[5] != 0x12 || (buf[6] & 0xFF) != 0xA4 || buf[7] != 0x42) return;
        if (buf[0] != 0x01 || buf[1] != 0x01) return;     // только Binding Success Response
        StunReq req = null;
        synchronized (stunPending) {
            for (StunReq r : stunPending) {
                boolean same = true;
                for (int i = 0; i < 12; i++) if (r.tx[i] != buf[8 + i]) { same = false; break; }
                if (same) { req = r; break; }
            }
        }
        if (req == null) return;
        int len = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        int end = Math.min(n, 20 + len);
        int p = 20;
        InetSocketAddress mapped = null;
        while (p + 4 <= end) {
            int at = ((buf[p] & 0xFF) << 8) | (buf[p + 1] & 0xFF);
            int al = ((buf[p + 2] & 0xFF) << 8) | (buf[p + 3] & 0xFF);
            int v = p + 4;
            if (v + al > end) break;
            if (at == 0x0020 && al >= 8 && buf[v + 1] == 1) {
                int prt = (((buf[v + 2] & 0xFF) << 8) | (buf[v + 3] & 0xFF)) ^ 0x2112;
                byte[] ip = new byte[] { (byte) (buf[v + 4] ^ 0x21), (byte) (buf[v + 5] ^ 0x12), (byte) (buf[v + 6] ^ 0xA4), (byte) (buf[v + 7] ^ 0x42) };
                try { mapped = new InetSocketAddress(InetAddress.getByAddress(ip), prt); } catch (Exception ignore) { }
                break;
            }
            if (at == 0x0001 && al >= 8 && buf[v + 1] == 1 && mapped == null) {
                int prt2 = ((buf[v + 2] & 0xFF) << 8) | (buf[v + 3] & 0xFF);
                byte[] ip2 = new byte[] { buf[v + 4], buf[v + 5], buf[v + 6], buf[v + 7] };
                try { mapped = new InetSocketAddress(InetAddress.getByAddress(ip2), prt2); } catch (Exception ignore) { }
            }
            p = v + ((al + 3) & ~3);
        }
        if (mapped != null) {
            req.mapped = mapped;
            req.done.countDown();
        }
    }

    /**
     * Спрашивает «какой у меня внешний адрес» у нескольких STUN-серверов с этого же сокета.
     * Возвращает по одному адресу на сервер (null — не ответил).
     */
    public InetSocketAddress[] stunQuery(final String[] servers, int timeoutMs) {
        final StunReq[] rq = new StunReq[servers.length];
        synchronized (stunPending) {
            for (int i = 0; i < rq.length; i++) {
                rq[i] = new StunReq();
                RNG.nextBytes(rq[i].tx);
                stunPending.add(rq[i]);
            }
        }
        for (int i = 0; i < rq.length; i++) {
            final StunReq r = rq[i];
            final String spec = servers[i];
            Thread th = new Thread(new Runnable() {
                public void run() {
                    try {
                        String host = spec;
                        int prt = 3478;
                        int k = spec.lastIndexOf(':');
                        if (k > 0) { host = spec.substring(0, k); prt = Integer.parseInt(spec.substring(k + 1)); }
                        InetAddress ip = null;
                        for (InetAddress a : InetAddress.getAllByName(host)) {
                            if (a.getAddress().length == 4) { ip = a; break; }
                        }
                        if (ip == null) return;
                        InetSocketAddress server = new InetSocketAddress(ip, prt);
                        byte[] req = buildStun(r.tx);
                        for (int n = 0; n < 4; n++) {
                            if (r.done.getCount() == 0) break;
                            sendTo(req, req.length, server);
                            if (r.done.await(350, TimeUnit.MILLISECONDS)) break;
                        }
                    } catch (Exception ignore) { }
                }
            }, "stun-" + i);
            th.setDaemon(true);
            th.start();
        }
        long startAt = System.currentTimeMillis();
        long endAt = startAt + timeoutMs;
        while (System.currentTimeMillis() < endAt) {
            int answered = 0;
            for (StunReq r : rq) if (r.done.getCount() == 0) answered++;
            long el = System.currentTimeMillis() - startAt;
            // живые серверы отвечают за доли секунды; трёх ответов за полсекунды хватает и для адреса, и для типа NAT
            if (answered >= rq.length || (answered >= 3 && el >= 500) || (answered >= 2 && el >= timeoutMs / 2)) break;
            try { Thread.sleep(15); } catch (InterruptedException e) { break; }
        }
        InetSocketAddress[] res = new InetSocketAddress[rq.length];
        synchronized (stunPending) {
            for (int i = 0; i < rq.length; i++) {
                res[i] = rq[i].mapped;
                stunPending.remove(rq[i]);
            }
        }
        return res;
    }

    /** Тип NAT по ответам разных серверов: одинаковый внешний порт — «конусный» (пробивка работает), разный — «симметричный». */
    public static String natKind(InetSocketAddress[] answers) {
        InetSocketAddress first = null;
        int n = 0;
        for (InetSocketAddress e : answers) {
            if (e == null) continue;
            n++;
            if (first == null) first = e;
            else if (e.getPort() != first.getPort() || !e.getAddress().equals(first.getAddress())) return "symmetric";
        }
        if (n == 0) return "none";
        if (n == 1) return "unknown";
        return "cone";
    }
}
