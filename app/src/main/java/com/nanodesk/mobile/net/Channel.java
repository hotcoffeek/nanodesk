package com.nanodesk.mobile.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.HashSet;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Зашифрованный канал: кадр = [длина 4][номер 8][IV 16][шифртекст][HMAC 32]; AES-256-CBC и HMAC-SHA256.
 * В HMAC входят направление и номер кадра. Ключи выводятся из общего секрета SRP.
 */
public final class Channel {
    private final InputStream in;
    private final OutputStream out;
    private final UdpConn conn;       // может быть null (тесты)
    private final SecretKeySpec encKey;
    private final Mac sendMac, recvMac;
    private final byte dirSend, dirRecv;
    private long sendSeq, recvNext;
    private final HashSet<Long> recvAhead = new HashSet<Long>();
    private final Object sendLock = new Object();
    private final SecureRandom rng = new SecureRandom();
    public volatile long bytesSent, bytesRecv;

    public Channel(InputStream in, OutputStream out, UdpConn conn, byte[] sessionKey, boolean isHost) {
        this.in = in;
        this.out = out;
        this.conn = conn;
        try {
            encKey = new SecretKeySpec(derive(sessionKey, "E"), "AES");
            byte[] mk = derive(sessionKey, "M");
            sendMac = Mac.getInstance("HmacSHA256");
            sendMac.init(new SecretKeySpec(mk, "HmacSHA256"));
            recvMac = Mac.getInstance("HmacSHA256");
            recvMac.init(new SecretKeySpec(mk, "HmacSHA256"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        dirSend = (byte) (isHost ? 1 : 2);
        dirRecv = (byte) (isHost ? 2 : 1);
    }

    static byte[] derive(byte[] key, String label) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(label.getBytes("US-ASCII"));
    }

    private static byte[] tag(Mac h, byte dir, long seq, byte[] data, int off, int len) {
        byte[] buf = new byte[9 + len];
        buf[0] = dir;
        for (int i = 0; i < 8; i++) buf[1 + i] = (byte) (seq >> (56 - 8 * i));
        System.arraycopy(data, off, buf, 9, len);
        return h.doFinal(buf);
    }

    public void send(int type, byte[] body) throws IOException { send(type, body, 0, body == null ? 0 : body.length); }

    public void send(int type, byte[] body, int off, int len) throws IOException {
        byte[] plain = new byte[1 + len];
        plain[0] = (byte) type;
        if (len > 0) System.arraycopy(body, off, plain, 1, len);
        synchronized (sendLock) {
            if (conn != null) conn.writePriority = Msg.priority(type);
            byte[] iv = new byte[16];
            rng.nextBytes(iv);
            byte[] ct;
            try {
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.ENCRYPT_MODE, encKey, new IvParameterSpec(iv));
                ct = c.doFinal(plain);
            } catch (Exception e) {
                throw new IOException("encrypt: " + e.getMessage());
            }
            long seq = sendSeq++;
            int total = 8 + 16 + ct.length + 32;
            byte[] frame = new byte[4 + total];
            frame[0] = (byte) (total >> 24);
            frame[1] = (byte) (total >> 16);
            frame[2] = (byte) (total >> 8);
            frame[3] = (byte) total;
            for (int i = 0; i < 8; i++) frame[4 + i] = (byte) (seq >> (56 - 8 * i));
            System.arraycopy(iv, 0, frame, 12, 16);
            System.arraycopy(ct, 0, frame, 28, ct.length);
            byte[] tg = tag(sendMac, dirSend, seq, frame, 12, 16 + ct.length);
            System.arraycopy(tg, 0, frame, 28 + ct.length, 32);
            out.write(frame, 0, frame.length);
            out.flush();
            bytesSent += frame.length;
        }
    }

    /** Результат приёма: тип и тело сообщения. */
    public static final class Packet {
        public int type;
        public byte[] body;
    }

    /** null — соединение закрыто; исключение — повреждённые данные. */
    public Packet recv() throws IOException {
        byte[] hdr = new byte[4];
        if (!readExact(hdr, 4)) return null;
        int total = ((hdr[0] & 0xFF) << 24) | ((hdr[1] & 0xFF) << 16) | ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
        if (total < 8 + 16 + 16 + 32 || total > 64 * 1024 * 1024) throw new IOException("bad frame");
        byte[] data = new byte[total];
        if (!readExact(data, total)) return null;
        bytesRecv += total + 4;
        long seq = 0;
        for (int i = 0; i < 8; i++) seq = (seq << 8) | (data[i] & 0xFF);
        int ctLen = total - 8 - 16 - 32;
        byte[] tg = tag(recvMac, dirRecv, seq, data, 8, 16 + ctLen);
        byte[] got = new byte[32];
        System.arraycopy(data, 8 + 16 + ctLen, got, 0, 32);
        if (!Srp.same(tg, got)) throw new IOException("bad mac");
        // кадры разных приоритетов могут обгонять друг друга, поэтому номер приходит в кадре; повтор номера запрещён
        if (seq < recvNext || recvAhead.contains(seq)) throw new IOException("replay");
        if (seq == recvNext) {
            recvNext++;
            while (recvAhead.remove(recvNext)) recvNext++;
        } else {
            recvAhead.add(seq);
        }
        byte[] iv = new byte[16];
        System.arraycopy(data, 8, iv, 0, 16);
        byte[] ct = new byte[ctLen];
        System.arraycopy(data, 24, ct, 0, ctLen);
        byte[] plain;
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, encKey, new IvParameterSpec(iv));
            plain = c.doFinal(ct);
        } catch (Exception e) {
            throw new IOException("decrypt: " + e.getMessage());
        }
        if (plain.length < 1) throw new IOException("empty");
        Packet p = new Packet();
        p.type = plain[0] & 0xFF;
        p.body = new byte[plain.length - 1];
        System.arraycopy(plain, 1, p.body, 0, p.body.length);
        return p;
    }

    private boolean readExact(byte[] buf, int n) throws IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r <= 0) return false;
            got += r;
        }
        return true;
    }

    public UdpConn conn() { return conn; }

    public void close() {
        try { if (conn != null) conn.kill("закрыто", true); } catch (Exception ignore) { }
    }

    // ---------- рукопожатие ----------

    /** Зритель: бросает AuthException при неверном пароле. */
    public static Channel viewerHandshake(InputStream in, OutputStream out, UdpConn conn, String password) throws IOException {
        Srp.Client cl = new Srp.Client();
        out.write(cl.aPub, 0, cl.aPub.length);
        out.flush();
        for (int round = 0; round < 3; round++) {
            byte[] salt = new byte[16];
            byte[] bpub = new byte[Srp.LEN];
            if (!readFully(in, salt, 16) || !readFully(in, bpub, Srp.LEN)) throw new IOException("closed");
            byte[] m1 = cl.respond(salt, bpub, password);
            out.write(m1, 0, m1.length);
            out.flush();
            int st = in.read();
            if (st < 0) throw new IOException("closed");
            if (st == 0) throw new AuthException("Неверный пароль");
            if (st == 1) {
                byte[] m2 = new byte[32];
                if (!readFully(in, m2, 32)) throw new IOException("closed");
                if (!Srp.same(m2, cl.expectedM2)) throw new AuthException("Хост не прошёл проверку");
                return new Channel(in, out, conn, cl.key, false);
            }
        }
        throw new AuthException("Неверный пароль");
    }

    static boolean readFully(InputStream in, byte[] b, int n) throws IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r <= 0) return false;
            got += r;
        }
        return true;
    }
}
