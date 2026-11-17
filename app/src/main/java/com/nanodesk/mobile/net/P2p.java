package com.nanodesk.mobile.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Подключение к ПК: по ID (через интернет, с пробивкой NAT) или по адресу (в одной сети). */
public final class P2p {
    public static final String[] STUN = new String[] {
            "stun.cloudflare.com:3478", "global.stun.twilio.com:3478", "stun.l.google.com:19302",
            "stun1.l.google.com:19302", "stun.nextcloud.com:3478", "stun.stunprotocol.org:3478",
            "stun.sipgate.net:3478", "stun.services.mozilla.com:3478"
    };
    public static final int DEFAULT_PORT = 21740, DISCOVERY_PORT = 7772;

    public static final class Found {
        public String id, name, ip;
        public int port;
    }

    public static final class ConnectException extends IOException {
        public final boolean offline;
        public ConnectException(String m, boolean offline) { super(m); this.offline = offline; }
    }

    public interface Cancel { boolean cancelled(); }

    private static final class Pending {
        final CountDownLatch acpt = new CountDownLatch(1);
        volatile List<InetSocketAddress> hostCands;
        volatile boolean refused;
    }

    public final String myId;
    public UdpHub hub;
    /** Если задан — поиск в сети и подключение по локальному IP идут через Wi‑Fi в обход VPN. */
    public volatile UdpHub.Binder wifiBinder;
    private UdpHub lanHub;
    public final Signal sig = new Signal();
    private final Map<String, Pending> pending = new HashMap<String, Pending>();
    private volatile List<InetSocketAddress> mapped = new ArrayList<InetSocketAddress>();
    public volatile String nat = "?";
    private static final SecureRandom RNG = new SecureRandom();

    public P2p() {
        StringBuilder sb = new StringBuilder();
        sb.append(1 + RNG.nextInt(9));
        for (int i = 0; i < 8; i++) sb.append(RNG.nextInt(10));
        myId = sb.toString();
    }

    /** Запуск: общий UDP-сокет, соединение с брокерами, первый опрос STUN. */
    public void start() throws IOException {
        hub = new UdpHub(0);
        hub.relaySend = new UdpHub.RelaySender() {
            public void send(String peerId, byte[] data, int len) { sig.sendRaw(peerId, data, len); }
        };
        sig.rawHandler = new Signal.RawHandler() {
            public void onRaw(byte[] data, int len) { hub.injectRelay(data, len); }
        };
        sig.handler = new Signal.Handler() {
            public void onSignal(Signal.Msg m) { onSignalMsg(m); }
        };
        sig.start(myId);
        Thread t = new Thread(new Runnable() { public void run() { refreshStun(); } }, "stun0");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        try { sig.stop(); } catch (Exception ignore) { }
        try { if (hub != null) hub.dispose(); } catch (Exception ignore) { }
        try { if (lanHub != null) lanHub.dispose(); } catch (Exception ignore) { }
    }

    /** Сеть сменилась (Wi‑Fi ↔ мобильный): адреса устарели, брокеры переподключаем. */
    public void networkChanged() {
        sig.resetAll();
        Thread t = new Thread(new Runnable() { public void run() { refreshStun(); } }, "stun1");
        t.setDaemon(true);
        t.start();
    }

    public void refreshStun() {
        try {
            InetSocketAddress[] a = hub.stunQuery(STUN, 2500);
            List<InetSocketAddress> l = new ArrayList<InetSocketAddress>();
            for (InetSocketAddress e : a) {
                if (e == null) continue;
                boolean dup = false;
                for (InetSocketAddress x : l) if (x.equals(e)) dup = true;
                if (!dup) l.add(e);
            }
            mapped = l;
            nat = UdpHub.natKind(a);
        } catch (Exception ignore) { }
    }

    public static List<InetAddress> localIps() {
        List<InetAddress> res = new ArrayList<InetAddress>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) res.add(a);
                }
            }
        } catch (Exception ignore) { }
        return res;
    }

    public List<InetSocketAddress> candidates() {
        List<InetSocketAddress> l = new ArrayList<InetSocketAddress>();
        for (InetSocketAddress e : mapped) l.add(e);
        for (InetAddress a : localIps()) l.add(new InetSocketAddress(a, hub.port));
        return l;
    }

    private void onSignalMsg(Signal.Msg m) {
        Pending p;
        synchronized (pending) { p = pending.get(m.from); }
        if (p == null) return;
        if (m.type.equals("REFU")) { p.refused = true; p.acpt.countDown(); return; }
        if (m.type.equals("ACPT")) {
            List<InetSocketAddress> l = new ArrayList<InetSocketAddress>();
            for (String c : m.cands) {
                try {
                    int k = c.lastIndexOf(':');
                    l.add(new InetSocketAddress(InetAddress.getByName(c.substring(0, k)), Integer.parseInt(c.substring(k + 1))));
                } catch (Exception ignore) { }
            }
            p.hostCands = l;
            p.acpt.countDown();
        }
    }

    /** Подключение по ID. Возвращает открытое соединение (пароль ещё не проверен). */
    public UdpConn connectId(String id, String myName, Cancel cancel) throws IOException {
        if (cancel == null) cancel = new Cancel() { public boolean cancelled() { return false; } };
        if (sig.connectedCount() == 0) {
            long end = System.currentTimeMillis() + 6000;
            while (sig.connectedCount() == 0 && System.currentTimeMillis() < end && !cancel.cancelled()) sleep(100);
            if (sig.connectedCount() == 0) throw new ConnectException("Нет связи с серверами сигналов — проверьте интернет", false);
        }
        if (mapped.isEmpty()) refreshStun();
        Pending p = new Pending();
        synchronized (pending) { pending.put(id, p); }
        long cid = UdpHub.newCid();
        UdpConn conn = hub.create(cid);
        conn.tag = id;
        conn.relayOk = true;
        conn.relayInit = true;
        try {
            long end = System.currentTimeMillis() + 14000;
            boolean got = false;
            while (System.currentTimeMillis() < end) {
                if (cancel.cancelled()) throw new ConnectException("Отменено", false);
                sig.send(id, "CONN", Long.toString(cid), myName, candidates());
                try { if (p.acpt.await(1000, TimeUnit.MILLISECONDS)) { got = true; break; } } catch (InterruptedException e) { throw new ConnectException("Отменено", false); }
            }
            if (!got) throw new ConnectException("Компьютер не отвечает: выключен, не запущен NanoDesk или ID неверный", true);
            if (p.refused) throw new ConnectException("Владелец ПК отклонил подключение", false);
            List<InetSocketAddress> hc = p.hostCands;
            conn.startPunch(hc == null ? new ArrayList<InetSocketAddress>() : hc);
            long until = System.currentTimeMillis() + 50000;
            while (System.currentTimeMillis() < until) {
                if (cancel.cancelled()) throw new ConnectException("Отменено", false);
                if (conn.waitReady(300)) return conn;
                if (conn.isClosed()) break;
            }
            throw new ConnectException("Не удалось установить связь (сеть блокирует соединение)", false);
        } catch (IOException e) {
            conn.kill("ошибка", false);
            throw e;
        } finally {
            synchronized (pending) { pending.remove(id); }
        }
    }

    /** Прямое подключение по IP (в одной сети или с проброшенным портом). */
    public UdpConn connectDirect(String host, int port, Cancel cancel) throws IOException {
        InetAddress ip = InetAddress.getByName(host);
        long cid = UdpHub.newCid();
        UdpHub use = hub;
        // адрес из локальной сети при включённом VPN: трафик в обход VPN — отдельным сокетом, привязанным к Wi‑Fi
        if (wifiBinder != null && (ip.isSiteLocalAddress() || ip.isLinkLocalAddress())) {
            synchronized (this) {
                if (lanHub == null) { try { lanHub = new UdpHub(0, wifiBinder); } catch (IOException ignore) { } }
                if (lanHub != null) use = lanHub;
            }
        }
        UdpConn conn = use.create(cid);
        conn.startDirect(new InetSocketAddress(ip, port));
        long until = System.currentTimeMillis() + 9000;
        while (System.currentTimeMillis() < until) {
            if (cancel != null && cancel.cancelled()) break;
            if (conn.waitReady(300)) return conn;
            if (conn.isClosed()) break;
        }
        conn.kill("нет ответа", false);
        throw new ConnectException("Нет ответа по адресу " + host + ":" + port, true);
    }

    /** Пароль → защищённый канал. */
    public static Channel handshake(UdpConn conn, String password) throws IOException {
        conn.readTimeoutMs = 15000;
        try {
            Channel ch = Channel.viewerHandshake(conn.in(), conn.out(), conn, password);
            conn.trusted = true;
            conn.readTimeoutMs = 0;
            return ch;
        } catch (IOException e) {
            conn.kill(e.getMessage() == null ? "ошибка" : e.getMessage(), true);
            throw e;
        }
    }

    /** Поиск компьютеров с NanoDesk в этой же сети. */
    public List<Found> scanLan(int ms) {
        final List<Found> res = new ArrayList<Found>();
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            UdpHub.Binder wb = wifiBinder;
            if (wb != null) { try { wb.bind(s); } catch (Exception ignore) { } }
            s.bind(new InetSocketAddress(0));
            s.setBroadcast(true);
            byte[] q = "NDQ1?".getBytes(Bytes.UTF8);
            List<InetAddress> targets = new ArrayList<InetAddress>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            try {
                Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                while (en != null && en.hasMoreElements()) {
                    NetworkInterface ni = en.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
                    }
                }
            } catch (Exception ignore) { }
            long end = System.currentTimeMillis() + ms;
            long nextSend = 0;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (InetAddress t : targets) {
                        try { s.send(new DatagramPacket(q, q.length, t, DISCOVERY_PORT)); } catch (Exception ignore) { }
                    }
                    nextSend = System.currentTimeMillis() + 600;
                }
                s.setSoTimeout(150);
                try {
                    DatagramPacket pk = new DatagramPacket(buf, buf.length);
                    s.receive(pk);
                    String t = new String(pk.getData(), 0, pk.getLength(), Bytes.UTF8);
                    String[] a = t.split("\\|", -1);
                    if (a.length >= 4 && a[0].equals("NDA1")) {
                        Found f = new Found();
                        f.id = a[1]; f.name = a[2];
                        f.ip = pk.getAddress().getHostAddress();
                        try { f.port = Integer.parseInt(a[3]); } catch (Exception e) { f.port = DEFAULT_PORT; }
                        boolean dup = false;
                        for (Found x : res) if (x.id.equals(f.id)) dup = true;
                        if (!dup && !f.id.equals(myId)) res.add(f);
                    }
                } catch (java.net.SocketTimeoutException ignore) { }
            }
        } catch (Exception ignore) {
        } finally {
            if (s != null) s.close();
        }
        return res;
    }

    private static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignore) { } }
}
