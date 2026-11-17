package com.nanodesk.mobile.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;

/** Чтение и запись данных в том же виде, что и .NET BinaryWriter/BinaryReader (порядок байтов — младший первым). */
public final class Bytes {
    public static final Charset UTF8 = Charset.forName("UTF-8");

    private Bytes() {}

    public static int r32le(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    public static long r64le(byte[] b, int o) {
        return (r32le(b, o) & 0xFFFFFFFFL) | ((long) r32le(b, o + 4) << 32);
    }

    public static void w32le(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    // Порядок «старший первым» — так устроены пакеты UDP-потока.
    public static void w32be(byte[] b, int o, long v) {
        b[o] = (byte) (v >> 24);
        b[o + 1] = (byte) (v >> 16);
        b[o + 2] = (byte) (v >> 8);
        b[o + 3] = (byte) v;
    }

    public static void w64be(byte[] b, int o, long v) {
        w32be(b, o, v >>> 32);
        w32be(b, o + 4, v);
    }

    public static long r32be(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    public static long r64be(byte[] b, int o) {
        return (r32be(b, o) << 32) | r32be(b, o + 4);
    }

    /** Запись в стиле BinaryWriter. */
    public static final class W {
        private final ByteArrayOutputStream bo = new ByteArrayOutputStream();

        public W u8(int v) { bo.write(v & 0xFF); return this; }

        public W i32(int v) {
            bo.write(v); bo.write(v >> 8); bo.write(v >> 16); bo.write(v >> 24);
            return this;
        }

        public W i64(long v) {
            i32((int) v);
            i32((int) (v >> 32));
            return this;
        }

        public W raw(byte[] b) {
            bo.write(b, 0, b.length);
            return this;
        }

        /** Строка: длина в байтах (7 бит на байт), затем UTF-8. */
        public W str(String s) {
            byte[] d = s.getBytes(UTF8);
            int n = d.length;
            while (n >= 0x80) { bo.write((n & 0x7F) | 0x80); n >>>= 7; }
            bo.write(n);
            bo.write(d, 0, d.length);
            return this;
        }

        public byte[] toBytes() { return bo.toByteArray(); }
    }

    /** Чтение в стиле BinaryReader. */
    public static final class R {
        private final byte[] b;
        private int p;
        private final int end;

        public R(byte[] b) { this(b, 0, b.length); }

        public R(byte[] b, int off, int len) { this.b = b; this.p = off; this.end = off + len; }

        public int pos() { return p; }

        public int left() { return end - p; }

        private void need(int n) throws IOException {
            if (n < 0 || p + n > end) throw new IOException("short data");
        }

        public int u8() throws IOException { need(1); return b[p++] & 0xFF; }

        public int i32() throws IOException { need(4); int v = r32le(b, p); p += 4; return v; }

        public long i64() throws IOException { need(8); long v = r64le(b, p); p += 8; return v; }

        public boolean bool() throws IOException { return u8() != 0; }

        public byte[] bytes(int n) throws IOException {
            need(n);
            byte[] r = new byte[n];
            System.arraycopy(b, p, r, 0, n);
            p += n;
            return r;
        }

        public String str() throws IOException {
            int n = 0, shift = 0;
            while (true) {
                int c = u8();
                n |= (c & 0x7F) << shift;
                if ((c & 0x80) == 0) break;
                shift += 7;
                if (shift > 28) throw new IOException("bad string length");
            }
            need(n);
            String s = new String(b, p, n, UTF8);
            p += n;
            return s;
        }
    }
}
