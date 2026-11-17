package com.nanodesk.mobile.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Надёжная доставка поверх UDP: окно, SACK, пейсинг, повтор потерянного (порт RLink из ПК-версии).
 * Чистая логика без сокетов. Все методы вызываются под блокировкой {@link #sync}. Время — миллисекунды.
 */
public final class RLink {
    public static final int MSS = 1180;
    public static final int RCV_LIMIT = 8 * 1024 * 1024;
    public static final int SND_LIMIT = 4 * 1024 * 1024;
    private static final double MAX_CWND = 1024.0, MIN_CWND = 8.0, INIT_CWND = 16.0;
    private static final int DUP_THRESH = 3;
    private static final double MIN_RTO = 100.0, MAX_RTO = 2500.0;
    private static final double MAX_TOKENS = 64.0;
    private static final int RING = 8192;

    private static final class Seg {
        long seq;
        byte[] data;
        double sentAt;
        int tx;
        boolean acked;
        double retxAt = -1e9;
    }

    public interface DataSink { void sendData(long seq, byte[] payload); }

    public interface AckSink { void sendAck(long cum, long sack, int rwnd, long trigger); }

    public final Object sync = new Object();
    public DataSink sendData;
    public AckSink sendAck;

    // ---- отправка ----
    @SuppressWarnings("unchecked")
    private final ArrayDeque<byte[]>[] queues = new ArrayDeque[] { new ArrayDeque<byte[]>(), new ArrayDeque<byte[]>(), new ArrayDeque<byte[]>() };
    private int queuedBytes;
    private byte[] cur;
    private int curOff;
    private int framesSinceBulk;
    private long nextSeq, una;
    private final Seg[] ring = new Seg[RING];
    private double cwnd = INIT_CWND, ssthresh = 1e9;
    private int rwnd = RCV_LIMIT;
    private double srtt = -1, rttvar, rto = 300.0, minRtt = 1e9;
    private int rtoBackoff;
    private long recover = -1;
    private double tokens = 20.0, tokT = -1;
    private int sacked;
    private long highestSacked = -1;
    private double detectAt = -1;
    public int retx, timeouts, fastRetx;

    // ---- приём ----
    private long rcvNext;
    private final HashMap<Long, byte[]> ooo = new HashMap<Long, byte[]>();
    private int oooBytes;
    private final ArrayDeque<byte[]> rbuf = new ArrayDeque<byte[]>();
    private int rbufBytes;
    private byte[] rhead;
    private int rheadOff;
    private int ackPending;
    private double ackDue = -1;
    private long lastTrigger;
    private int consumedSinceAck;

    public double srtt() { return srtt < 0 ? 0 : srtt; }

    public double cwnd() { return cwnd; }

    public boolean canWrite() { return queuedBytes < SND_LIMIT; }

    public int available() { return rbufBytes; }

    public boolean idle() { return cur == null && queuedBytes == 0 && nextSeq == una; }

    // ---------------- отправка ----------------
    public void writeFrame(byte[] data, int prio) {
        if (data.length == 0) return;
        if (prio < 0) prio = 0;
        if (prio > 2) prio = 2;
        queues[prio].add(data);
        queuedBytes += data.length;
    }

    private byte[] pickFrame() {
        ArrayDeque<byte[]> q0 = queues[0], q1 = queues[1], q2 = queues[2];
        if (!q0.isEmpty()) return q0.poll();
        if (!q1.isEmpty() && !q2.isEmpty()) {
            if (framesSinceBulk >= 2) { framesSinceBulk = 0; return q2.poll(); }
            framesSinceBulk++;
            return q1.poll();
        }
        if (!q1.isEmpty()) return q1.poll();
        if (!q2.isEmpty()) return q2.poll();
        return null;
    }

    private byte[] nextChunk() {
        if (cur == null) {
            cur = pickFrame();
            if (cur == null) return null;
            curOff = 0;
        }
        int n = Math.min(MSS, cur.length - curOff);
        byte[] chunk = new byte[n];
        System.arraycopy(cur, curOff, chunk, 0, n);
        curOff += n;
        if (curOff >= cur.length) cur = null;
        queuedBytes -= n;
        return chunk;
    }

    private boolean hasPending() {
        return cur != null || !queues[0].isEmpty() || !queues[1].isEmpty() || !queues[2].isEmpty();
    }

    private int pipe() { return (int) (nextSeq - una) - sacked; }

    private double window() { return Math.min(cwnd, Math.max(1.0, (double) rwnd / MSS)); }

    private double rtoNow() { return rto * Math.pow(1.5, Math.min(rtoBackoff, 6)); }

    /** Отправляет повторы и новые данные. Возвращает время (мс), когда нужно вызвать снова (Double.MAX_VALUE — не нужно). */
    public double pump(double now) {
        if (tokT < 0) tokT = now;
        double s = srtt > 0 ? srtt : 50.0;
        double rate = Math.max(1.0, cwnd) * 1.5 / Math.max(1.0, s);   // пакетов в мс
        tokens = Math.min(MAX_TOKENS, tokens + (now - tokT) * rate);
        tokT = now;
        if (ackDue >= 0 && now >= ackDue) sendAckNow();
        if (detectAt >= 0 && now >= detectAt) detect(now);
        if (nextSeq > una) {
            Seg head = ring[(int) (una & (RING - 1))];
            if (head != null && head.tx > 0 && now - head.sentAt >= rtoNow()) onTimeout(now);
        }
        while (tokens >= 1.0 && pipe() < window()) {
            byte[] chunk = nextChunk();
            if (chunk == null) break;
            Seg sg = new Seg();
            sg.seq = nextSeq;
            sg.data = chunk;
            ring[(int) (nextSeq & (RING - 1))] = sg;
            nextSeq++;
            tx(sg, now);
            tokens -= 1.0;
        }
        double wake = Double.MAX_VALUE;
        if (nextSeq > una) {
            Seg h2 = ring[(int) (una & (RING - 1))];
            if (h2 != null) wake = h2.sentAt + rtoNow();
        }
        if (ackDue >= 0) wake = Math.min(wake, ackDue);
        if (detectAt >= 0) wake = Math.min(wake, detectAt);
        if (hasPending() && pipe() < window()) wake = Math.min(wake, now + 1.0);
        return wake;
    }

    private void tx(Seg sg, double now) {
        if (sg.tx > 0) retx++;
        sg.tx++;
        sg.sentAt = now;
        if (sendData != null) sendData.sendData(sg.seq, sg.data);
    }

    private void onTimeout(double now) {
        timeouts++;
        rtoBackoff++;
        ssthresh = Math.max(cwnd * 0.7, MIN_CWND);
        cwnd = MIN_CWND;
        recover = nextSeq;
        int n = 0;
        for (long q = una; q < nextSeq && n < 4; q++) {
            Seg sg = ring[(int) (q & (RING - 1))];
            if (sg != null && !sg.acked) { tx(sg, now); n++; }
        }
    }

    /** Пришло подтверждение: cum — всё до этого номера получено; sack — маска принятых после cum+1 (бит i = cum+1+i). */
    public void onAck(long cum, long sack, int window, long trigger, double now) {
        rwnd = window;
        int newly = 0;
        if (cum > nextSeq) cum = nextSeq;
        while (una < cum) {
            int ix = (int) (una & (RING - 1));
            Seg sg = ring[ix];
            ring[ix] = null;
            if (sg != null) {
                if (sg.acked) sacked--; else newly++;
                if (sg.tx == 1 && sg.seq == trigger) rtt(now - sg.sentAt);
            }
            una++;
        }
        if (sacked < 0) sacked = 0;
        if (sacked == 0) highestSacked = -1;
        if (sack != 0) {
            for (int i = 0; i < 64; i++) {
                if (((sack >>> i) & 1L) == 0) continue;
                long s = cum + 1 + i;
                if (s < una || s >= nextSeq) continue;
                Seg sg = ring[(int) (s & (RING - 1))];
                if (sg != null && !sg.acked) {
                    sg.acked = true;
                    sacked++;
                    newly++;
                    if (s > highestSacked) highestSacked = s;
                    if (sg.tx == 1 && s == trigger) rtt(now - sg.sentAt);
                }
            }
        }
        if (newly > 0) {
            rtoBackoff = 0;
            if (cwnd < ssthresh) cwnd = Math.min(MAX_CWND, cwnd + newly);
            else cwnd = Math.min(MAX_CWND, cwnd + 4.0 * newly / cwnd);
        }
        if (sacked > 0) detect(now);
    }

    /** Потеряно: сегмент не подтверждён, хотя выше подтверждены не меньше 3 и прошло больше ~1.1 RTT. */
    private void detect(double now) {
        double s = srtt > 0 ? srtt : 50.0;
        double need = s * 1.125;
        int sackedAbove = 0;
        List<Seg> lost = null;
        double next = -1;
        for (long q = highestSacked; q >= una; q--) {
            Seg sg = ring[(int) (q & (RING - 1))];
            if (sg == null) continue;
            if (sg.acked) { sackedAbove++; continue; }
            if (sackedAbove < DUP_THRESH) continue;
            double due = Math.max(sg.sentAt, sg.retxAt) + need;
            if (now >= due) {
                if (lost == null) lost = new ArrayList<Seg>();
                lost.add(sg);
            } else if (next < 0 || due < next) next = due;
        }
        detectAt = next;
        if (lost == null) return;
        if (lost.get(lost.size() - 1).seq >= recover) {   // самый старый из потерянных — новая «эпоха» потерь
            fastRetx++;
            ssthresh = Math.max(cwnd * 0.8, MIN_CWND);
            cwnd = ssthresh;
            recover = nextSeq;
        }
        for (int i = lost.size() - 1; i >= 0; i--) {
            Seg sg = lost.get(i);
            if (sg.tx == 1 || now - sg.retxAt >= need) {
                sg.retxAt = now;
                tx(sg, now);
            }
        }
    }

    private void rtt(double r) {
        if (r < 0.01) r = 0.01;
        if (r < minRtt) minRtt = r;
        // задержка выросла — выходим из медленного старта, пока очередь у роутера не переполнилась
        if (cwnd < ssthresh && cwnd >= 32 && r > minRtt + Math.max(4.0, minRtt / 8.0)) ssthresh = cwnd;
        if (srtt < 0) { srtt = r; rttvar = r / 2; }
        else {
            rttvar = 0.75 * rttvar + 0.25 * Math.abs(srtt - r);
            srtt = 0.875 * srtt + 0.125 * r;
        }
        rto = Math.min(MAX_RTO, Math.max(MIN_RTO, srtt * 1.5 + 4 * rttvar));
    }

    // ---------------- приём ----------------
    public void onData(long seq, byte[] payload, double now) {
        lastTrigger = seq;
        boolean immediate = false;
        if (seq < rcvNext) immediate = true;            // дубликат
        else if (seq == rcvNext) {
            deliver(payload);
            rcvNext++;
            byte[] p;
            while ((p = ooo.remove(rcvNext)) != null) {
                oooBytes -= p.length;
                deliver(p);
                rcvNext++;
            }
            if (!ooo.isEmpty()) immediate = true;
        } else {
            if (seq - rcvNext < 4096 && !ooo.containsKey(seq) && rbufBytes + oooBytes < RCV_LIMIT + 1024 * 1024) {
                ooo.put(seq, payload);
                oooBytes += payload.length;
            }
            immediate = true;
        }
        ackPending++;
        if (immediate || ackPending >= 2) sendAckNow();
        else if (ackDue < 0) ackDue = now + 5.0;
    }

    private void deliver(byte[] p) {
        rbuf.add(p);
        rbufBytes += p.length;
    }

    private void sendAckNow() {
        long mask = 0;
        if (!ooo.isEmpty()) {
            for (int i = 0; i < 64; i++)
                if (ooo.containsKey(rcvNext + 1 + i)) mask |= 1L << i;
        }
        int free = Math.max(0, RCV_LIMIT - rbufBytes - oooBytes);
        ackPending = 0;
        ackDue = -1;
        consumedSinceAck = 0;
        if (sendAck != null) sendAck.sendAck(rcvNext, mask, free, lastTrigger);
    }

    /** Читает до n байт из принятых данных (0 — пока ничего нет). */
    public int read(byte[] buf, int off, int n) {
        int got = 0;
        while (got < n) {
            if (rhead == null) {
                if (rbuf.isEmpty()) break;
                rhead = rbuf.poll();
                rheadOff = 0;
            }
            int take = Math.min(n - got, rhead.length - rheadOff);
            System.arraycopy(rhead, rheadOff, buf, off + got, take);
            rheadOff += take;
            got += take;
            rbufBytes -= take;
            if (rheadOff >= rhead.length) rhead = null;
        }
        consumedSinceAck += got;
        if (consumedSinceAck >= 65536) sendAckNow();     // окно приёма освободилось — сообщаем отправителю
        return got;
    }
}
