import com.nanodesk.mobile.net.*;
import java.net.*;
import java.util.*;

public class Loop {
    public static void main(String[] a) throws Exception {
        System.out.println("signal selftest: " + Signal.selfTest());
        UdpHub hostHub = new UdpHub(0), cliHub = new UdpHub(0);
        long cid = UdpHub.newCid();
        UdpConn hc = hostHub.create(cid), cc = cliHub.create(cid);
        InetSocketAddress ha = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), hostHub.port);
        InetSocketAddress ca = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), cliHub.port);
        hc.startPunch(Arrays.asList(ca));
        cc.startPunch(Arrays.asList(ha));
        System.out.println("open: " + cc.waitReady(5000) + " " + hc.waitReady(5000));
        final String pw = "1234";
        final Srp.Server srv = new Srp.Server(pw);
        final Channel[] hch = new Channel[1];
        final Exception[] herr = new Exception[1];
        Thread ht = new Thread(new Runnable() { public void run() {
            try {
                java.io.InputStream in = hc.in(); java.io.OutputStream out = hc.out();
                byte[] A = new byte[Srp.LEN];
                int g = 0; while (g < A.length) g += in.read(A, g, A.length - g);
                out.write(srv.salt); out.write(srv.bPub); out.flush();
                byte[] m1 = new byte[32]; g = 0; while (g < 32) g += in.read(m1, g, 32 - g);
                byte[][] r = srv.verify(A, m1);
                if (r == null) { out.write(0); out.flush(); throw new Exception("bad pw"); }
                out.write(1); out.write(r[0]); out.flush();
                hch[0] = new Channel(in, out, hc, r[1], true);
                // echo-хост: получает мышь, отвечает Info и большим «экраном»
                Channel.Packet p;
                while ((p = hch[0].recv()) != null) {
                    if (p.type == Msg.MOUSE) {
                        byte[] big = new byte[400000];
                        new Random(1).nextBytes(big);
                        hch[0].send(Msg.SCREEN, big);
                    }
                    if (p.type == Msg.BYE) break;
                }
            } catch (Exception e) { herr[0] = e; e.printStackTrace(); }
        }});
        ht.setDaemon(true); ht.start();
        Channel ch = P2p.handshake(cc, pw);
        System.out.println("handshake ok");
        ch.send(Msg.MOUSE, new byte[14]);
        Channel.Packet p = ch.recv();
        System.out.println("got type " + p.type + " len " + p.body.length);
        byte[] big = new byte[400000]; new Random(1).nextBytes(big);
        System.out.println("data equal: " + Arrays.equals(big, p.body));
        // неверный пароль
        long cid2 = UdpHub.newCid();
        UdpConn h2 = hostHub.create(cid2), c2 = cliHub.create(cid2);
        h2.startPunch(Arrays.asList(ca)); c2.startPunch(Arrays.asList(ha));
        c2.waitReady(5000);
        final Srp.Server s2 = new Srp.Server("9999");
        Thread t2 = new Thread(new Runnable() { public void run() { try {
            java.io.InputStream in = h2.in(); java.io.OutputStream out = h2.out();
            byte[] A = new byte[Srp.LEN]; int g = 0; while (g < A.length) g += in.read(A, g, A.length - g);
            out.write(s2.salt); out.write(s2.bPub); out.flush();
            byte[] m1 = new byte[32]; g = 0; while (g < 32) g += in.read(m1, g, 32 - g);
            byte[][] r = s2.verify(A, m1);
            out.write(r == null ? 0 : 1); out.flush();
        } catch (Exception e) {} }});
        t2.setDaemon(true); t2.start();
        try { P2p.handshake(c2, "1234"); System.out.println("WRONG: accepted"); }
        catch (AuthException e) { System.out.println("wrong password rejected: " + e.getMessage()); }
        System.exit(0);
    }
}
