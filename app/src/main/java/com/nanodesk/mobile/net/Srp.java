package com.nanodesk.mobile.net;

import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * SRP-6a (группа 2048 бит из RFC 3526/5054, хеш SHA-256) — тот же обмен, что и в ПК-версии.
 * Пароль по сети не передаётся; одна догадка = одно подключение.
 */
public final class Srp {
    private Srp() {}

    public static final int LEN = 256;
    private static final String IDENTITY = "ld";
    private static final SecureRandom RNG = new SecureRandom();

    private static final BigInteger N = new BigInteger(1, hex(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
            + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
            + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
            + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
            + "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
            + "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
            + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
            + "3995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF"));
    private static final BigInteger G = BigInteger.valueOf(2);
    private static final byte[] NHASH;
    private static final BigInteger KMUL;

    static {
        byte[] hn = h(pad(N));
        byte[] hg = h(pad(G));
        NHASH = new byte[32];
        for (int i = 0; i < 32; i++) NHASH[i] = (byte) (hn[i] ^ hg[i]);
        KMUL = fromBytes(h(pad(N), pad(G)));
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    static byte[] h(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Число из байтов «старший первым» (всегда неотрицательное). */
    private static BigInteger fromBytes(byte[] be) { return new BigInteger(1, be); }

    /** Число в 256 байт «старший первым». */
    static byte[] pad(BigInteger x) {
        byte[] t = x.toByteArray();
        byte[] r = new byte[LEN];
        int n = Math.min(t.length, LEN);
        System.arraycopy(t, t.length - n, r, LEN - n, n);
        return r;
    }

    private static BigInteger x(byte[] salt, String password) {
        byte[] inner = h((IDENTITY + ":" + password).getBytes(Bytes.UTF8));
        return fromBytes(h(salt, inner));
    }

    private static BigInteger randomSecret() {
        while (true) {
            byte[] b = new byte[32];
            RNG.nextBytes(b);
            BigInteger r = fromBytes(b);
            if (r.signum() != 0) return r;
        }
    }

    private static byte[] proofM1(byte[] salt, byte[] aPad, byte[] bPad, byte[] key) {
        return h(NHASH, h(IDENTITY.getBytes(Bytes.UTF8)), salt, aPad, bPad, key);
    }

    private static boolean bad(BigInteger v) { return v.mod(N).signum() == 0; }

    public static boolean same(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int d = 0;
        for (int i = 0; i < a.length; i++) d |= a[i] ^ b[i];
        return d == 0;
    }

    /** Сторона зрителя (телефон). Один и тот же A годится для нескольких попыток. */
    public static final class Client {
        public final byte[] aPub;
        private final BigInteger a;
        public byte[] expectedM2;
        public byte[] key;

        public Client() {
            a = randomSecret();
            aPub = pad(G.modPow(a, N));
        }

        public byte[] respond(byte[] salt, byte[] bPad, String password) throws IOException {
            BigInteger b = fromBytes(bPad);
            if (bad(b)) throw new AuthException("Некорректный ответ хоста");
            BigInteger u = fromBytes(h(aPub, bPad));
            if (u.signum() == 0) throw new AuthException("Некорректный ответ хоста");
            BigInteger xv = x(salt, password);
            BigInteger gx = G.modPow(xv, N);
            BigInteger t = b.subtract(KMUL.multiply(gx).mod(N)).mod(N);
            BigInteger s = t.modPow(a.add(u.multiply(xv)), N);
            byte[] k = h(pad(s));
            byte[] m1 = proofM1(salt, aPub, bPad, k);
            key = k;
            expectedM2 = h(aPub, m1, k);
            return m1;
        }
    }

    /** Сторона хоста — нужна только для проверки обмена в тестах. */
    public static final class Server {
        public final byte[] salt;
        public final byte[] bPub;
        private final BigInteger v, b, bigB;

        public Server(String password) {
            salt = new byte[16];
            RNG.nextBytes(salt);
            v = G.modPow(x(salt, password), N);
            b = randomSecret();
            bigB = KMUL.multiply(v).add(G.modPow(b, N)).mod(N);
            bPub = pad(bigB);
        }

        /** Возвращает {m2, key} или null, если пароль неверный. */
        public byte[][] verify(byte[] aPad, byte[] m1) {
            BigInteger a = fromBytes(aPad);
            if (bad(a) || bigB.signum() == 0) return null;
            BigInteger u = fromBytes(h(aPad, bPub));
            if (u.signum() == 0) return null;
            BigInteger s = a.multiply(v.modPow(u, N)).mod(N).modPow(b, N);
            byte[] k = h(pad(s));
            byte[] expect = proofM1(salt, aPad, bPub, k);
            if (!same(expect, m1)) return null;
            return new byte[][] { h(aPad, m1, k), k };
        }
    }
}
