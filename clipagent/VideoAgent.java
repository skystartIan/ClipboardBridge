package com.clipboardbridge.agent;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

/**
 * 平板端影像 agent（shell uid，app_process 啟動）：把 scrcpy-server 的影像改走 UDP。
 *
 * scrcpy client（X1）照常用 adb 啟動 scrcpy-server（tunnel_forward），但 client 的
 * --tunnel-host 指到 X1 本機的轉接程式（Sync\video_udp_proxy.py），不經 adb forward。
 * 轉接程式啟動這個 agent，agent 在平板內部連上 server 的 localabstract socket：
 *
 *   TCP :port 'S'+token  session：轉 dummy byte、device meta(64)＋codec meta(12)，
 *                        之後每秒回一行 "VST ..." 統計。PC 關掉它 = 結束。
 *   TCP :port 'C'+token  control：跟 server 的 control socket 雙向對接（可靠）。
 *   UDP :port            影像：每一格（scrcpy 的 12-byte frame header＋資料）切成
 *                        CHUNK 大小的片段，每 GROUP 片加一片 XOR 備援（FEC）。
 *                        PC 送 hello 決定回送位址（跟麥克風一樣沿原路回去），
 *                        也送 NACK 要求重送。
 *
 * UDP 封包（agent → PC）：
 *   token u32, frame u32, total u32, idx u16, nchunks u16, kind u8(0=資料,1=備援)
 *   後面是片段資料（備援片：XOR，長度一律 CHUNK）
 * UDP 封包（PC → agent）：
 *   hello：token u32
 *   NACK ：token u32, 0xFF, frame u32, n u16, idx u16 × n（n=0 = 整格重送）
 *
 * 聲音模式（2026-11-10，第 4 個參數 "audio"）：launcher 那條獨立的聲音串流（scrcpy_audio.exe，
 * --no-video --no-control）只有一條 audio socket，資料格式跟影像一樣（12-byte header＋資料），
 * 只是 codec meta 4 bytes、沒有 control。每個封包通常只有一片，不加備援片。
 *
 * 啟動：CLASSPATH=... app_process / com.clipboardbridge.agent.VideoAgent <scid hex> <port> <token> [audio]
 * stdout：VIDRDY / VIDCONN / VIDERR
 */
public final class VideoAgent {

    private static final int CHUNK = 1150;                 // tailnet MTU 1280 以內
    private static final int GROUP = 8;                    // 每 8 片資料 1 片備援
    private static final int HDR = 17;
    private static final int KEEP = 256;                   // 保留最近幾格供重送

    private final String socketName;
    private final int port;
    private final int token;
    private final boolean audio;

    private DatagramSocket udp;
    private volatile SocketAddress peer;
    private volatile boolean running = true;
    private LocalSocket videoLs, controlLs;

    private final byte[][] keepData = new byte[KEEP][];
    private final long[] keepSeq = new long[KEEP];

    // 統計
    private volatile long frames, chunksSent, resent, nacks, hellos, bytes;

    private VideoAgent(String scidHex, int port, int token, boolean audio) {
        this.socketName = "scrcpy_" + scidHex;
        this.port = port;
        this.token = token;
        this.audio = audio;
        for (int i = 0; i < KEEP; i++) keepSeq[i] = -1;
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            out("VIDERR:usage <scid hex> <port> <token>");
            return;
        }
        try {
            boolean audio = args.length > 3 && "audio".equals(args[3]);
            new VideoAgent(args[0], Integer.parseInt(args[1]), (int) Long.parseLong(args[2]), audio).run();
        } catch (Throwable t) {
            out("VIDERR:fatal " + t);
        }
        out("VIDCONN:exit");
        System.exit(0);
    }

    private void run() throws Exception {
        udp = new DatagramSocket(null);
        udp.setReuseAddress(true);
        udp.bind(new InetSocketAddress(port));
        udp.setSendBufferSize(1024 * 1024);
        udp.setSoTimeout(200);
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        ss.setSoTimeout(60000);
        out("VIDRDY:port=" + port);

        Socket session = acceptMode(ss, 'S');
        final OutputStream sessOut = session.getOutputStream();
        final InputStream sessIn = session.getInputStream();
        new Thread(new Runnable() {
            public void run() { udpRx(); }
        }, "VidUdpRx").start();

        // 1. 連 server 的影像 socket，拿 dummy byte 轉給 PC
        videoLs = connectLs();
        InputStream vin = videoLs.getInputStream();
        int dummy = vin.read();
        if (dummy < 0) throw new Exception("server 沒送 dummy byte");
        sessOut.write(0);
        sessOut.flush();
        out(audio ? "VIDCONN:audio socket ok" : "VIDCONN:video socket ok");

        // 2. PC 接著連 control（scrcpy client 收到 dummy 後才會連 control）；聲音模式沒有 control
        if (!audio) {
            Socket ctrl = acceptMode(ss, 'C');
            controlLs = connectLs();
            pipe(ctrl.getInputStream(), controlLs.getOutputStream(), "VidCtlUp");
            pipe(controlLs.getInputStream(), ctrl.getOutputStream(), "VidCtlDown");
            out("VIDCONN:control socket ok");
        }
        ss.close();

        // 3. device meta(64)＋codec meta（影像 12、聲音 4）走 session（可靠）
        DataInputStream din = new DataInputStream(vin);
        byte[] meta = new byte[audio ? 68 : 76];
        din.readFully(meta);
        sessOut.write(meta);
        sessOut.flush();

        // PC 關 session = 結束
        new Thread(new Runnable() {
            public void run() {
                try { while (sessIn.read() >= 0) { } } catch (Throwable ignore) {}
                stop();
            }
        }, "VidSess").start();
        new Thread(new Runnable() {
            public void run() {
                try {
                    while (running) {
                        Thread.sleep(1000);
                        SocketAddress p = peer;
                        String line = "VST frames=" + frames + " chunks=" + chunksSent + " resent=" + resent
                                + " nacks=" + nacks + " hello=" + hellos + " bytes=" + bytes
                                + " peer=" + (p == null ? "-" : p.toString()) + "\n";
                        synchronized (sessOut) {
                            sessOut.write(line.getBytes("UTF-8"));
                            sessOut.flush();
                        }
                    }
                } catch (Throwable t) {
                    stop();
                }
            }
        }, "VidStat").start();

        // 4. 影像：一格一格讀，切片送 UDP
        byte[] head = new byte[12];
        long seq = 0;
        try {
            while (running) {
                din.readFully(head);
                int len = ((head[8] & 0xFF) << 24) | ((head[9] & 0xFF) << 16)
                        | ((head[10] & 0xFF) << 8) | (head[11] & 0xFF);
                if (len < 0 || len > 64 * 1024 * 1024) throw new Exception("frame 長度怪怪的：" + len);
                byte[] f = new byte[12 + len];
                System.arraycopy(head, 0, f, 0, 12);
                din.readFully(f, 12, len);
                int slot = (int) (seq % KEEP);
                synchronized (keepData) {
                    keepData[slot] = f;
                    keepSeq[slot] = seq;
                }
                sendFrame(seq, f, null);
                frames++;
                bytes += f.length;
                seq++;
            }
        } finally {
            stop();
        }
    }

    private Socket acceptMode(ServerSocket ss, char want) throws Exception {
        while (true) {
            Socket s = ss.accept();
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(s.getInputStream());
            int mode = in.read();
            int tok = in.readInt();
            if (mode == want && tok == token) return s;
            out("VIDERR:bad conn mode=" + mode);
            s.close();
        }
    }

    private LocalSocket connectLs() throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (true) {
            try {
                LocalSocket l = new LocalSocket();
                l.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
                return l;
            } catch (Throwable t) {
                if (System.currentTimeMillis() > deadline) throw new Exception("連不上 " + socketName + "：" + t);
                Thread.sleep(100);
            }
        }
    }

    private void pipe(final InputStream in, final OutputStream os, String name) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                byte[] b = new byte[16384];
                try {
                    int n;
                    while ((n = in.read(b)) >= 0) {
                        os.write(b, 0, n);
                        os.flush();
                    }
                } catch (Throwable ignore) {
                }
                stop();
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    /** 送一格；only != null 時只送這些片（重送用，idx 是資料片編號，>= nchunks 代表備援片）。 */
    private void sendFrame(long seq, byte[] f, int[] only) {
        SocketAddress p = peer;
        if (p == null) return;
        int total = f.length;
        int n = (total + CHUNK - 1) / CHUNK;
        int groups = (n + GROUP - 1) / GROUP;
        byte[] pkt = new byte[HDR + CHUNK];
        putInt(pkt, 0, token);
        putInt(pkt, 4, (int) seq);
        putInt(pkt, 8, total);
        putShort(pkt, 14, n);
        try {
            if (only == null) {
                byte[] parity = new byte[CHUNK];
                for (int i = 0; i < n; i++) {
                    int off = i * CHUNK;
                    int len = Math.min(CHUNK, total - off);
                    putShort(pkt, 12, i);
                    pkt[16] = 0;
                    System.arraycopy(f, off, pkt, HDR, len);
                    udp.send(new DatagramPacket(pkt, HDR + len, p));
                    chunksSent++;
                    for (int k = 0; k < len; k++) parity[k] ^= f[off + k];
                    boolean groupEnd = (i % GROUP == GROUP - 1) || i == n - 1;
                    if (groupEnd) {
                        if (n > 1) {                         // 只有一片的格子不需要備援
                            putShort(pkt, 12, n + i / GROUP);
                            pkt[16] = 1;
                            System.arraycopy(parity, 0, pkt, HDR, CHUNK);
                            udp.send(new DatagramPacket(pkt, HDR + CHUNK, p));
                            chunksSent++;
                        }
                        java.util.Arrays.fill(parity, (byte) 0);
                    }
                    // 大格（關鍵幀）稍微分散送，避免一口氣塞爆行動網路的佇列
                    if (i % 32 == 31) Thread.sleep(1);
                }
            } else {
                for (int idx : only) {
                    if (idx < n) {
                        int off = idx * CHUNK;
                        int len = Math.min(CHUNK, total - off);
                        putShort(pkt, 12, idx);
                        pkt[16] = 0;
                        System.arraycopy(f, off, pkt, HDR, len);
                        udp.send(new DatagramPacket(pkt, HDR + len, p));
                    } else if (idx < n + groups) {
                        int g = idx - n;
                        byte[] parity = new byte[CHUNK];
                        for (int i = g * GROUP; i < Math.min(n, (g + 1) * GROUP); i++) {
                            int off = i * CHUNK;
                            int len = Math.min(CHUNK, total - off);
                            for (int k = 0; k < len; k++) parity[k] ^= f[off + k];
                        }
                        putShort(pkt, 12, idx);
                        pkt[16] = 1;
                        System.arraycopy(parity, 0, pkt, HDR, CHUNK);
                        udp.send(new DatagramPacket(pkt, HDR + CHUNK, p));
                    }
                    resent++;
                }
            }
        } catch (Throwable t) {
            // 暫時送不出去：丟掉，PC 會 NACK
        }
    }

    private void udpRx() {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                p.setLength(buf.length);
                udp.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (Throwable t) {
                if (running) out("VIDERR:udp rx " + t);
                return;
            }
            int len = p.getLength();
            if (len < 4 || getInt(buf, 0) != token) continue;
            if (len == 4) {                                  // hello
                SocketAddress a = p.getSocketAddress();
                if (peer == null || !a.equals(peer)) out("VIDCONN:peer " + a);
                peer = a;
                hellos++;
                continue;
            }
            if (len >= 11 && (buf[4] & 0xFF) == 0xFF) {      // NACK
                nacks++;
                long seq = getInt(buf, 5) & 0xFFFFFFFFL;
                int cnt = ((buf[9] & 0xFF) << 8) | (buf[10] & 0xFF);
                byte[] f = null;
                int slot = (int) (seq % KEEP);
                synchronized (keepData) {
                    if (keepSeq[slot] == seq) f = keepData[slot];
                }
                if (f == null) continue;                     // 太舊了，PC 自己會放棄
                if (cnt == 0) {
                    int n = (f.length + CHUNK - 1) / CHUNK;
                    int groups = (n + GROUP - 1) / GROUP;
                    int[] all = new int[n + (n > 1 ? groups : 0)];
                    for (int i = 0; i < all.length; i++) all[i] = i;
                    sendFrame(seq, f, all);
                } else {
                    int[] idx = new int[Math.min(cnt, (len - 11) / 2)];
                    for (int i = 0; i < idx.length; i++) {
                        idx[i] = ((buf[11 + 2 * i] & 0xFF) << 8) | (buf[12 + 2 * i] & 0xFF);
                    }
                    sendFrame(seq, f, idx);
                }
            }
        }
    }

    private void stop() {
        if (!running) return;
        running = false;
        for (LocalSocket l : new LocalSocket[]{videoLs, controlLs}) {
            if (l != null) {
                try { l.shutdownInput(); } catch (Throwable ignore) {}
                try { l.close(); } catch (Throwable ignore) {}
            }
        }
        if (udp != null) udp.close();
        out("VIDCONN:stopped frames=" + frames + " resent=" + resent + " nacks=" + nacks);
        System.exit(0);
    }

    private static int getInt(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    private static void putShort(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 8);
        b[o + 1] = (byte) v;
    }

    private static void out(String s) {
        System.out.println(s);
        System.out.flush();
    }
}
