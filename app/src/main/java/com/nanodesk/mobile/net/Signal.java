package com.nanodesk.mobile.net;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Сигнальный канал: сообщения «хочу подключиться / вот мои адреса» идут через несколько публичных MQTT-брокеров сразу.
 * Здесь нет паролей и ключей — только адреса. Безопасность даёт SRP и шифрование самого сеанса.
 */
public final class Signal {
    public static final String[] DEFAULT_BROKERS = new String[] {
            "ssl://broker.emqx.io:8883", "tcp://broker.emqx.io:1883",
            "ssl://broker.hivemq.com:8883", "tcp://broker.hivemq.com:1883", "tcp://broker.mqtt-dashboard.com:1883",
            "tcp://test.mosquitto.org:1883",
            "ssl://mqtt.eclipseprojects.io:8883", "tcp://mqtt.eclipseprojects.io:1883",
            "tcp://public.mqtthq.com:1883"
    };
    static final String ROOT = "ldsk-p2p-5c2f/";
    static final String DATA_ROOT = ROOT + "d/";

    public static final class Msg {
        public String type, from, cid, msgId, name;
        public final List<String> cands = new ArrayList<String>();
    }

    public interface Handler { void onSignal(Msg m); }

    public interface RawHandler { void onRaw(byte[] data, int len); }

    private final List<MqttClient> clients = new ArrayList<MqttClient>();
    private final Map<String, Long> seen = new HashMap<String, Long>();
    private final Map<String, CountDownLatch> probes = new HashMap<String, CountDownLatch>();
    private final Map<String, MqttClient> relayFor = new HashMap<String, MqttClient>();
    private final Map<String, Long> relayAt = new HashMap<String, Long>();
    private final Map<String, Map<MqttClient, Long>> via = new HashMap<String, Map<MqttClient, Long>>();
    public String myId = "";
    public Handler handler;
    public RawHandler rawHandler;
    private static final SecureRandom RNG = new SecureRandom();

    public void start(String id) {
        myId = id;
        for (String spec : DEFAULT_BROKERS) {
            boolean tls = spec.startsWith("ssl://");
            String u = spec.substring(spec.indexOf("://") + 3);
            int k = u.lastIndexOf(':');
            String host = u.substring(0, k);
            int port = Integer.parseInt(u.substring(k + 1));
            final MqttClient c = new MqttClient(host, port, tls);
            c.listener = new MqttClient.Listener() {
                public void onMessage(MqttClient from, String topic, byte[] payload) { onMsg(from, topic, payload); }
            };
            c.subscribe(ROOT + id);
            c.subscribe(DATA_ROOT + id);
            clients.add(c);
            c.start();
        }
    }

    public void stop() { for (MqttClient c : clients) c.stop(); }

    public void resetAll() { for (MqttClient c : clients) { try { c.reconnect(); } catch (Exception ignore) { } } }

    public int connectedCount() {
        int n = 0;
        for (MqttClient c : clients) if (c.connected) n++;
        return n;
    }

    static String clean(String s) { return (s == null ? "" : s).replace("|", "/").replace("\n", " "); }

    private static byte[][] sigKeys(String id) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] k = sha.digest(("ldsk-signal-v1|" + id).getBytes(Bytes.UTF8));
        Mac h = Mac.getInstance("HmacSHA256");
        h.init(new SecretKeySpec(k, "HmacSHA256"));
        byte[] e = h.doFinal("e".getBytes("US-ASCII"));
        byte[] m = h.doFinal("m".getBytes("US-ASCII"));
        return new byte[][] { e, m };
    }

    /** Сообщения шифруются ключом, выведенным из ID получателя: так адреса не видны тем, кто подписался на все темы брокера. */
    static byte[] seal(String toId, String text) throws Exception {
        byte[][] ks = sigKeys(toId);
        byte[] iv = new byte[16];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ks[0], "AES"), new IvParameterSpec(iv));
        byte[] ct = c.doFinal(text.getBytes(Bytes.UTF8));
        byte[] outb = new byte[16 + ct.length + 32];
        System.arraycopy(iv, 0, outb, 0, 16);
        System.arraycopy(ct, 0, outb, 16, ct.length);
        Mac h = Mac.getInstance("HmacSHA256");
        h.init(new SecretKeySpec(ks[1], "HmacSHA256"));
        h.update(outb, 0, 16 + ct.length);
        byte[] tag = h.doFinal();
        System.arraycopy(tag, 0, outb, 16 + ct.length, 32);
        return outb;
    }

    static String open(String myId, byte[] data) {
        try {
            if (data == null || data.length < 16 + 16 + 32) return null;
            byte[][] ks = sigKeys(myId);
            int ctLen = data.length - 16 - 32;
            Mac h = Mac.getInstance("HmacSHA256");
            h.init(new SecretKeySpec(ks[1], "HmacSHA256"));
            h.update(data, 0, 16 + ctLen);
            byte[] tag = h.doFinal();
            byte[] got = new byte[32];
            System.arraycopy(data, 16 + ctLen, got, 0, 32);
            if (!Srp.same(tag, got)) return null;
            byte[] iv = new byte[16];
            System.arraycopy(data, 0, iv, 0, 16);
            byte[] ct = new byte[ctLen];
            System.arraycopy(data, 16, ct, 0, ctLen);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(ks[0], "AES"), new IvParameterSpec(iv));
            return new String(c.doFinal(ct), Bytes.UTF8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex6() {
        byte[] r = new byte[6];
        RNG.nextBytes(r);
        StringBuilder sb = new StringBuilder();
        for (byte x : r) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }

    public void send(String toId, String type, String cid, String name, List<InetSocketAddress> cands) {
        StringBuilder sb = new StringBuilder();
        sb.append("ND1|").append(type).append('|').append(myId).append('|').append(cid).append('|');
        sb.append(hex6()).append('|').append(clean(name)).append('|');
        if (cands != null) {
            for (int i = 0; i < cands.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(cands.get(i).getAddress().getHostAddress()).append(':').append(cands.get(i).getPort());
            }
        }
        byte[] payload;
        try { payload = seal(toId, sb.toString()); } catch (Exception e) { return; }
        String topic = ROOT + toId;
        for (MqttClient c : clients) c.publish(topic, payload);
    }

    // Оценка брокера для резервного канала (меньше — лучше).
    private int relayScore(MqttClient x, Map<MqttClient, Long> sv, long now) {
        int sc = x.rttMs >= 0 ? x.rttMs : 500;
        Long t = sv == null ? null : sv.get(x);
        if (!(t != null && now - t < 180000)) sc += 300;
        if (x.rx.get() == 0) sc += 150;
        return sc;
    }

    private MqttClient pickRelay(String toId) {
        long now = System.currentTimeMillis();
        synchronized (via) {
            MqttClient cur = relayFor.get(toId);
            Long at = relayAt.get(toId);
            boolean curOk = cur != null && cur.connected;
            if (curOk && at != null && now - at < 10000) return cur;
            Map<MqttClient, Long> sv = via.get(toId);
            MqttClient best = null;
            int bestScore = Integer.MAX_VALUE;
            for (MqttClient x : clients) {
                if (!x.connected) continue;
                int sc = relayScore(x, sv, now);
                if (sc < bestScore) { bestScore = sc; best = x; }
            }
            if (best == null) return null;
            if (curOk && best != cur && bestScore > relayScore(cur, sv, now) * 7 / 10 - 10) best = cur;
            if (relayFor.size() > 200) { relayFor.clear(); relayAt.clear(); }
            relayFor.put(toId, best);
            relayAt.put(toId, now);
            return best;
        }
    }

    private void noteVia(String peer, MqttClient c) {
        synchronized (via) {
            Map<MqttClient, Long> d = via.get(peer);
            if (d == null) {
                if (via.size() > 200) via.clear();
                d = new HashMap<MqttClient, Long>();
                via.put(peer, d);
            }
            d.put(c, System.currentTimeMillis());
        }
    }

    /** Резервный канал: один пакет соединения (как в UDP) уходит через один из брокеров. */
    public boolean sendRaw(String toId, byte[] data, int len) {
        byte[] payload = new byte[len + 1];
        payload[0] = (byte) 0xD1;
        System.arraycopy(data, 0, payload, 1, len);
        String topic = DATA_ROOT + toId;
        for (int attempt = 0; attempt < 3; attempt++) {
            MqttClient c = pickRelay(toId);
            if (c == null) return false;
            if (c.publish(topic, payload)) return true;
        }
        return false;
    }

    private void onMsg(MqttClient src, String topic, byte[] payload) {
        if (topic.startsWith(DATA_ROOT)) {
            if (payload.length > 5 && (payload[0] & 0xFF) == 0xD1) {
                RawHandler rh = rawHandler;
                if (rh != null) {
                    byte[] d = new byte[payload.length - 1];
                    System.arraycopy(payload, 1, d, 0, d.length);
                    try { rh.onRaw(d, d.length); } catch (Exception ignore) { }
                }
            }
            return;
        }
        try { onMsg2(src, topic, payload); } catch (Exception ignore) { }
    }

    private void onMsg2(MqttClient src, String topic, byte[] payload) {
        if (!topic.startsWith(ROOT)) return;
        String text = open(myId, payload);
        if (text == null) return;
        String[] p = text.split("\\|", -1);
        if (p.length < 6 || !p[0].equals("ND1")) return;
        Msg m = new Msg();
        m.type = p[1]; m.from = p[2]; m.cid = p[3]; m.msgId = p[4]; m.name = p[5];
        if (m.from.length() == 0 || m.from.length() > 20 || m.from.equals(myId)) return;
        noteVia(m.from, src);
        if (p.length > 6 && p[6].length() > 0) {
            for (String c : p[6].split(",")) {
                if (m.cands.size() >= 8) break;
                m.cands.add(c);
            }
        }
        long now = System.currentTimeMillis();
        synchronized (seen) {
            if (seen.containsKey(m.msgId)) return;         // то же сообщение уже пришло через другой брокер
            seen.put(m.msgId, now);
            if (seen.size() > 400) {
                List<String> old = new ArrayList<String>();
                for (Map.Entry<String, Long> kv : seen.entrySet()) if (now - kv.getValue() > 60000) old.add(kv.getKey());
                for (String k : old) seen.remove(k);
            }
        }
        if (m.type.equals("PING")) { send(m.from, "PONG", "0", "", null); return; }
        if (m.type.equals("PONG")) {
            CountDownLatch ev;
            synchronized (probes) { ev = probes.get(m.from); }
            if (ev != null) ev.countDown();
            return;
        }
        Handler h = handler;
        if (h != null) h.onSignal(m);
    }

    /** Проверка «компьютер с таким ID сейчас в сети?» — ждём ответ PONG. */
    public boolean probe(String id, int timeoutMs) {
        CountDownLatch ev = new CountDownLatch(1);
        synchronized (probes) { probes.put(id, ev); }
        try {
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                send(id, "PING", "0", "", null);
                try {
                    if (ev.await(1200, TimeUnit.MILLISECONDS)) return true;
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return false;
        } finally {
            synchronized (probes) { probes.remove(id); }
        }
    }

    /** Самопроверка шифрования служебных сообщений. */
    public static boolean selfTest() {
        try {
            byte[] b = seal("123456789", "ND1|PING|1|0|x||");
            return "ND1|PING|1|0|x||".equals(open("123456789", b)) && open("987654321", b) == null;
        } catch (Exception e) {
            return false;
        }
    }
}
