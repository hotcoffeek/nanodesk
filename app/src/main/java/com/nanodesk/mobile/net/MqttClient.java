package com.nanodesk.mobile.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Минимальный клиент MQTT 3.1.1 — только то, что нужно для обмена адресами между устройствами. */
public final class MqttClient {
    public interface Listener { void onMessage(MqttClient from, String topic, byte[] payload); }

    public final String host;
    public final int port;
    public final boolean tls;
    public volatile boolean connected;
    public volatile String lastError = "";
    public final AtomicInteger rx = new AtomicInteger(), tx = new AtomicInteger();
    public volatile int rttMs = -1;
    public Listener listener;

    private final List<String> subs = new ArrayList<String>();
    private final Object wlock = new Object();
    private final String clientId;
    private volatile OutputStream out;
    private volatile Socket sock;
    private volatile boolean stop;
    private int packetId = 1;
    private volatile long lastRx;
    private int backoff = 1000;
    private volatile long pingAt;
    private static final SecureRandom RNG = new SecureRandom();

    public MqttClient(String host, int port, boolean tls) {
        this.host = host;
        this.port = port;
        this.tls = tls;
        byte[] r = new byte[6];
        RNG.nextBytes(r);
        StringBuilder sb = new StringBuilder("nm");
        for (byte x : r) sb.append(String.format("%02x", x & 0xFF));
        clientId = sb.toString();
    }

    public String name() { return host + ":" + port; }

    public void start() {
        Thread th = new Thread(new Runnable() { public void run() { runLoop(); } }, "mqtt-" + host);
        th.setDaemon(true);
        th.start();
    }

    public void stop() {
        stop = true;
        drop();
    }

    /** Сеть сменилась: старое соединение «зависло» — переподключаемся сразу. */
    public void reconnect() {
        backoff = 300;
        drop();
    }

    private void drop() {
        connected = false;
        try { Socket s = sock; if (s != null) s.close(); } catch (Exception ignore) { }
    }

    public void subscribe(String topic) {
        synchronized (subs) { if (!subs.contains(topic)) subs.add(topic); }
        if (connected) {
            try { sendSubscribe(topic); } catch (Exception e) { drop(); }
        }
    }

    public boolean publish(String topic, byte[] payload) {
        if (!connected) return false;
        try {
            byte[] t = topic.getBytes(Bytes.UTF8);
            byte[] body = new byte[2 + t.length + payload.length];
            body[0] = (byte) (t.length >> 8);
            body[1] = (byte) t.length;
            System.arraycopy(t, 0, body, 2, t.length);
            System.arraycopy(payload, 0, body, 2 + t.length, payload.length);
            sendPacket(0x30, body);
            tx.incrementAndGet();
            return true;
        } catch (Exception e) {
            drop();
            return false;
        }
    }

    private void sendSubscribe(String topic) throws IOException {
        byte[] t = topic.getBytes(Bytes.UTF8);
        byte[] body = new byte[2 + 2 + t.length + 1];
        int id;
        synchronized (wlock) { packetId = (packetId + 1) & 0xFFFF; if (packetId == 0) packetId = 1; id = packetId; }
        body[0] = (byte) (id >> 8);
        body[1] = (byte) id;
        body[2] = (byte) (t.length >> 8);
        body[3] = (byte) t.length;
        System.arraycopy(t, 0, body, 4, t.length);
        body[4 + t.length] = 0;     // QoS 0
        sendPacket(0x82, body);
    }

    private void sendPacket(int first, byte[] body) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(first);
        int len = body.length;
        do {
            int d = len % 128;
            len /= 128;
            if (len > 0) d |= 0x80;
            bo.write(d);
        } while (len > 0);
        bo.write(body, 0, body.length);
        byte[] all = bo.toByteArray();
        synchronized (wlock) {
            OutputStream o = out;
            if (o == null) throw new IOException("no stream");
            o.write(all);
            o.flush();
        }
    }

    private void runLoop() {
        while (!stop) {
            try { serve(); } catch (Exception e) { lastError = String.valueOf(e.getMessage()); }
            drop();
            if (stop) break;
            for (int i = 0; i < backoff / 100 && !stop; i++) {
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
            }
            backoff = Math.min(backoff * 2, 20000);
        }
    }

    private static boolean readFull(InputStream s, byte[] b, int n) throws IOException {
        int got = 0;
        while (got < n) {
            int r = s.read(b, got, n - got);
            if (r <= 0) return false;
            got += r;
        }
        return true;
    }

    private static final class Pk {
        int first;
        byte[] body;
    }

    private Pk readPacket(InputStream s) throws IOException {
        int b = s.read();
        if (b < 0) return null;
        Pk p = new Pk();
        p.first = b;
        int len = 0, mul = 1;
        for (int i = 0; i < 4; i++) {
            int d = s.read();
            if (d < 0) return null;
            len += (d & 127) * mul;
            mul *= 128;
            if ((d & 128) == 0) break;
        }
        if (len > 256 * 1024) throw new IOException("packet too big");
        p.body = new byte[len];
        if (len > 0 && !readFull(s, p.body, len)) return null;
        return p;
    }

    private static void sleepQuiet(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignore) { } }

    private void serve() throws IOException {
        Socket s = new Socket();
        sock = s;
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(new InetSocketAddress(host, port), 7000);
        s.setSoTimeout(10000);
        InputStream in;
        OutputStream o;
        if (tls) {
            SSLSocket ss = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, host, port, true);
            List<String> pr = new ArrayList<String>();
            for (String x : ss.getSupportedProtocols()) if (x.equals("TLSv1.2") || x.equals("TLSv1.3")) pr.add(x);
            if (!pr.isEmpty()) ss.setEnabledProtocols(pr.toArray(new String[0]));
            ss.startHandshake();
            sock = ss;
            in = ss.getInputStream();
            o = ss.getOutputStream();
        } else {
            in = s.getInputStream();
            o = s.getOutputStream();
        }
        synchronized (wlock) { out = o; }

        // CONNECT: MQTT 3.1.1, чистая сессия, проверка связи каждые 40 с
        byte[] cid = clientId.getBytes(Bytes.UTF8);
        byte[] body = new byte[10 + 2 + cid.length];
        body[0] = 0; body[1] = 4; body[2] = 'M'; body[3] = 'Q'; body[4] = 'T'; body[5] = 'T';
        body[6] = 4; body[7] = 0x02; body[8] = 0; body[9] = 40;
        body[10] = (byte) (cid.length >> 8);
        body[11] = (byte) cid.length;
        System.arraycopy(cid, 0, body, 12, cid.length);
        rttMs = -1;
        pingAt = 0;
        long hs = System.nanoTime();
        sendPacket(0x10, body);

        Pk pk = readPacket(in);
        if (pk == null) throw new IOException("закрыто");
        if ((pk.first >> 4) != 2 || pk.body.length < 2 || pk.body[1] != 0) throw new IOException("брокер отклонил подключение");
        rttMs = (int) Math.max(1, (System.nanoTime() - hs) / 1000000L);
        sock.setSoTimeout(0);
        lastRx = System.currentTimeMillis();
        backoff = 1000;
        connected = true;
        synchronized (subs) { for (String t : subs) sendSubscribe(t); }

        Thread ping = new Thread(new Runnable() {
            public void run() {
                int n = 0, total = 0;
                while (!stop && connected) {
                    sleepQuiet(500);
                    n++;
                    total++;
                    boolean early = total == 2 || total == 6 || total == 12;
                    if (n < 30 && !early) continue;
                    if (n >= 30) n = 0;
                    pingAt = System.nanoTime();
                    try { sendPacket(0xC0, new byte[0]); } catch (Exception e) { drop(); return; }
                    if (System.currentTimeMillis() - lastRx > 50000) { drop(); return; }
                }
            }
        }, "mqtt-ping");
        ping.setDaemon(true);
        ping.start();

        while (!stop) {
            pk = readPacket(in);
            if (pk == null) { lastError = "брокер закрыл соединение"; break; }
            lastRx = System.currentTimeMillis();
            int type = pk.first >> 4;
            if (type == 13) {       // PINGRESP — заодно замер задержки
                long pt = pingAt;
                pingAt = 0;
                if (pt != 0) {
                    int ms = (int) Math.max(1, (System.nanoTime() - pt) / 1000000L);
                    rttMs = rttMs < 0 ? ms : Math.min(rttMs, ms);
                }
            }
            if (type == 3) {        // PUBLISH
                byte[] b = pk.body;
                if (b.length < 2) continue;
                int tl = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
                int p = 2 + tl;
                if (p > b.length) continue;
                int qos = (pk.first >> 1) & 3;
                if (qos > 0) {
                    if (p + 2 > b.length) continue;
                    if (qos == 1) { try { sendPacket(0x40, new byte[] { b[p], b[p + 1] }); } catch (Exception ignore) { } }
                    p += 2;
                }
                rx.incrementAndGet();
                String topic = new String(b, 2, tl, Bytes.UTF8);
                byte[] payload = new byte[b.length - p];
                System.arraycopy(b, p, payload, 0, payload.length);
                Listener l = listener;
                if (l != null) { try { l.onMessage(this, topic, payload); } catch (Exception ignore) { } }
            }
        }
        connected = false;
    }
}
