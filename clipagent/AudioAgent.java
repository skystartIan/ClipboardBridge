package com.clipboardbridge.agent;

import android.media.AudioAttributes;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * 平板端喇叭 agent（shell uid，app_process 啟動）：PC 的聲音在平板播出來。
 *
 * PC（Sync 的 audio_bridge）連 TCP :port，第一個 byte 決定傳法：
 *   'T'：後面直接是 48 kHz / 雙聲道 / s16le 的裸 PCM（舊做法）。
 *   'U'：後面接 token(u32) target_ms(u16) max_ms(u16)，PCM 改走 UDP :port，
 *        每包 = token(u32) + seq(u32) + 5 ms PCM（960 bytes）。TCP 留著當控制通道：
 *        PC 關掉它就結束；這裡每秒回一行 "AUDST ..." 統計，PC 用它判斷 UDP 有沒有通，
 *        不通就改回 'T'。
 *   'M'：麥克風。後面接 token(u32) scid(u32)，見 MicSession：在平板內部接 scrcpy-server
 *        的麥克風串流，UDP 從 port+1 送回 PC。可以跟喇叭同時連（每條連線一個執行緒）。
 *
 * 為什麼要 UDP：TCP 掉一包，後面全部要等重傳，聲音會「停一下再一口氣衝出來」。
 * UDP 掉了就是少 5 ms，用前一包補上，聽不太出來。
 *
 * 延遲控制：
 *   TCP：每讀一次就看 socket 積了多少，超過 MAX_BACKLOG_MS 就丟到剩 TARGET_BACKLOG_MS。
 *   UDP：自己的抖動緩衝。攢到 target_ms 才開始播；積超過 max_ms 就跳到最新的 target_ms；
 *        沒資料了就重新攢。
 *
 * AudioAttributes 用 USAGE_MEDIA（音量鍵調得到）加 ALLOW_CAPTURE_BY_NONE：
 * launcher 的 scrcpy 聲音串流（audio_source=playback）抓不到這裡放的聲音，
 * 不然 PC 的聲音會被抓回 PC 形成迴圈。
 *
 * 啟動：
 *   CLASSPATH=/data/local/tmp/clipagent.dex app_process / \
 *     com.clipboardbridge.agent.AudioAgent [port]
 * stdout：AUDRDY / AUDCONN / AUDDROP / AUDERR，給 PC 端寫 log。
 */
public final class AudioAgent {

    private static final int RATE = 48000;
    private static final int FRAME_BYTES = 4;                      // 雙聲道 s16
    private static final int BYTES_PER_MS = RATE * FRAME_BYTES / 1000;
    private static final int CHUNK = 10 * BYTES_PER_MS;            // TCP：每次讀寫 10 ms
    private static final int MAX_BACKLOG_MS = 120;
    private static final int TARGET_BACKLOG_MS = 30;
    // AudioTrack 實際配到的緩衝常常是 128 ms（6144 frames）；寫入是阻塞的，緩衝會被填滿，
    // 等於固定多 128 ms 延遲。建好後把「有效緩衝」縮到這個長度。
    private static final int TRACK_BUFFER_MS = 40;

    // UDP
    private static final int PKT_MS = 5;
    private static final int PKT_PCM = PKT_MS * BYTES_PER_MS;      // 960
    private static final int PKT_HDR = 8;
    private static final int SLOTS = 128;                          // 640 ms 的格子

    public static void main(String[] args) {
        int port = 27230;
        if (args.length >= 1) {
            try { port = Integer.parseInt(args[0]); } catch (Exception ignore) {}
        }
        AudioTrack track;
        try {
            track = newTrack();
        } catch (Throwable first) {
            // 沒有 app context 時，部分版本建 AudioTrack 會失敗：補一個 ActivityThread 再試
            out("AUDERR:plain AudioTrack failed: " + first + " ; retry with ActivityThread");
            try {
                setupActivityThread();
                track = newTrack();
            } catch (Throwable t) {
                out("AUDERR:fatal " + t);
                return;
            }
        }

        DatagramSocket udp = null;
        try {
            udp = new DatagramSocket(null);
            udp.setReuseAddress(true);
            udp.bind(new InetSocketAddress(port));
            udp.setReceiveBufferSize(256 * 1024);
            udp.setSoTimeout(200);
        } catch (Throwable t) {
            out("AUDERR:udp bind " + t);
            udp = null;
        }

        DatagramSocket mu = null;
        try {
            mu = new DatagramSocket(null);
            mu.setReuseAddress(true);
            mu.bind(new InetSocketAddress(port + 1));
            mu.setSendBufferSize(256 * 1024);
            mu.setSoTimeout(200);
        } catch (Throwable t) {
            out("AUDERR:mic udp bind " + t);
            mu = null;
        }
        final DatagramSocket micUdp = mu;

        out("AUDRDY:port=" + port + " buffer=" + track.getBufferSizeInFrames() + "f"
                + " capacity=" + (Build.VERSION.SDK_INT >= 24 ? track.getBufferCapacityInFrames() : -1) + "f"
                + " udp=" + (udp != null) + " micudp=" + (micUdp != null));

        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            while (true) {
                final Socket s = ss.accept();
                // 一次可能同時有兩條：喇叭（'U'／'T'）和麥克風（'M'），各開一個執行緒
                new Thread(new Runnable() {
                    public void run() { handle(s, udp, micUdp, track); }
                }, "AudConn").start();
            }
        } catch (Throwable t) {
            out("AUDERR:fatal " + t);
        } finally {
            track.release();
            if (udp != null) udp.close();
            if (micUdp != null) micUdp.close();
        }
    }

    private static void handle(Socket s, DatagramSocket udp, DatagramSocket micUdp, AudioTrack track) {
        out("AUDCONN:" + s.getRemoteSocketAddress());
        boolean speaker = false;
        try {
            s.setTcpNoDelay(true);
            s.setReceiveBufferSize(64 * 1024);
            DataInputStream in = new DataInputStream(s.getInputStream());
            int mode = in.read();
            if (mode == 'U' && udp != null) {
                speaker = true;
                int token = in.readInt();
                int target = in.readUnsignedShort();
                int max = in.readUnsignedShort();
                out("AUDCONN:udp target=" + target + "ms max=" + max + "ms");
                new UdpSession(udp, track, token, target, max).run(in, s.getOutputStream());
            } else if (mode == 'M' && micUdp != null) {
                int token = in.readInt();
                int scid = in.readInt();
                out("AUDCONN:mic scid=" + String.format("%08x", scid));
                new MicSession(micUdp, token, scid).run(in, s.getOutputStream());
            } else if (mode == 'T') {
                speaker = true;
                play(in, track);
            } else {
                out("AUDERR:bad mode " + mode);
            }
        } catch (Throwable t) {
            out("AUDERR:stream " + t);
        } finally {
            try { s.close(); } catch (Throwable ignore) {}
            if (speaker) {
                synchronized (track) {
                    track.pause();
                    track.flush();
                }
            }
            out("AUDCONN:closed");
        }
    }

    private static AudioTrack newTrack() {
        AudioAttributes.Builder ab = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
        if (Build.VERSION.SDK_INT >= 29) {
            ab.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE);
        }
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack.Builder tb = new AudioTrack.Builder()
                .setAudioAttributes(ab.build())
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(Math.max(min, 2 * CHUNK))
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= 26) {
            tb.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        AudioTrack t = tb.build();
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            throw new IllegalStateException("AudioTrack not initialized");
        }
        if (Build.VERSION.SDK_INT >= 24) {
            t.setBufferSizeInFrames(TRACK_BUFFER_MS * RATE / 1000);
        }
        return t;
    }

    // ── TCP（'T'）─────────────────────────────────────────
    private static void play(InputStream in, AudioTrack track) throws Exception {
        byte[] buf = new byte[CHUNK];
        byte[] junk = new byte[CHUNK];
        long dropped = 0;
        long lastReport = System.currentTimeMillis();
        boolean started = false;
        while (true) {
            // 讀滿一個 chunk（樣本要對齊，不能寫半個 frame）
            int n = 0;
            while (n < CHUNK) {
                int r = in.read(buf, n, CHUNK - n);
                if (r < 0) return;
                n += r;
            }
            if (!started) {
                track.play();
                started = true;
            }
            track.write(buf, 0, CHUNK);

            int backlog = in.available();
            if (backlog > MAX_BACKLOG_MS * BYTES_PER_MS) {
                int skip = backlog - TARGET_BACKLOG_MS * BYTES_PER_MS;
                skip -= skip % FRAME_BYTES;
                int left = skip;
                while (left > 0) {
                    int r = in.read(junk, 0, Math.min(junk.length, left));
                    if (r < 0) return;
                    left -= r;
                }
                dropped += skip;
            }
            long now = System.currentTimeMillis();
            if (dropped > 0 && now - lastReport > 10000) {
                out("AUDDROP:" + (dropped / BYTES_PER_MS) + "ms");
                dropped = 0;
                lastReport = now;
            }
        }
    }

    // ── UDP（'U'）─────────────────────────────────────────
    private static final class UdpSession {
        private final DatagramSocket udp;
        private final AudioTrack track;
        private final int token;
        private final int targetPkts;
        private final int maxPkts;

        private final Object lock = new Object();
        private final byte[][] slotData = new byte[SLOTS][PKT_PCM];
        private final long[] slotSeq = new long[SLOTS];
        private long next = -1;          // 下一個要播的 seq；-1 = 還沒收到任何包
        private long highest = -1;
        private volatile boolean running = true;

        // 統計（累計值，PC 自己算差）
        private long rx, lost, late, under, skipped, concealed;

        UdpSession(DatagramSocket udp, AudioTrack track, int token, int targetMs, int maxMs) {
            this.udp = udp;
            this.track = track;
            this.token = token;
            this.targetPkts = Math.max(1, Math.min(SLOTS / 2, targetMs / PKT_MS));
            this.maxPkts = Math.max(targetPkts + 2, Math.min(SLOTS - 4, maxMs / PKT_MS));
            for (int i = 0; i < SLOTS; i++) slotSeq[i] = -1;
        }

        void run(final InputStream ctrlIn, final OutputStream ctrlOut) throws Exception {
            Thread rxT = new Thread(new Runnable() { public void run() { receive(); } }, "AudUdpRx");
            Thread ctl = new Thread(new Runnable() {
                public void run() {
                    // PC 關掉 TCP = 結束
                    try { while (ctrlIn.read() >= 0) { } } catch (Throwable ignore) {}
                    running = false;
                    synchronized (lock) { lock.notifyAll(); }
                }
            }, "AudUdpCtl");
            Thread st = new Thread(new Runnable() {
                public void run() {
                    try {
                        while (running) {
                            Thread.sleep(1000);
                            String line;
                            synchronized (lock) {
                                long depth = (next >= 0 && highest >= next) ? (highest - next + 1) * PKT_MS : 0;
                                line = "AUDST rx=" + rx + " lost=" + lost + " late=" + late
                                        + " under=" + under + " skip=" + skipped
                                        + " conceal=" + concealed + " depth=" + depth + "\n";
                            }
                            ctrlOut.write(line.getBytes("UTF-8"));
                            ctrlOut.flush();
                        }
                    } catch (Throwable t) {
                        running = false;
                        synchronized (lock) { lock.notifyAll(); }
                    }
                }
            }, "AudUdpStat");
            rxT.setDaemon(true); ctl.setDaemon(true); st.setDaemon(true);
            rxT.start(); ctl.start(); st.start();
            try {
                playLoop();
            } finally {
                running = false;
                rxT.join(1000);
            }
        }

        private void receive() {
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (running) {
                try {
                    p.setLength(buf.length);
                    udp.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (Throwable t) {
                    if (running) out("AUDERR:udp rx " + t);
                    return;
                }
                if (p.getLength() != PKT_HDR + PKT_PCM) continue;
                int tok = readInt(buf, 0);
                if (tok != token) continue;                     // 舊連線殘留的包
                long seq = readInt(buf, 4) & 0xFFFFFFFFL;
                synchronized (lock) {
                    rx++;
                    if (next >= 0 && seq < next) { late++; continue; }
                    int i = (int) (seq % SLOTS);
                    System.arraycopy(buf, PKT_HDR, slotData[i], 0, PKT_PCM);
                    slotSeq[i] = seq;
                    if (seq > highest) highest = seq;
                    if (next < 0) next = seq;
                    lock.notifyAll();
                }
            }
        }

        private void playLoop() throws InterruptedException {
            byte[] outBuf = new byte[PKT_PCM];
            byte[] last = new byte[PKT_PCM];
            boolean haveLast = false;
            boolean priming = true;
            boolean playing = false;
            while (running) {
                boolean got;
                synchronized (lock) {
                    if (next < 0 || (priming && highest - next + 1 < targetPkts)) {
                        lock.wait(20);
                        continue;
                    }
                    priming = false;
                    long depth = highest - next + 1;
                    if (depth <= 0) {                           // 沒資料了：重新攢
                        priming = true;
                        under++;
                        continue;
                    }
                    if (depth > maxPkts) {                      // 積太多：跳到最新的 target
                        long nn = highest - targetPkts + 1;
                        skipped += nn - next;
                        next = nn;
                    }
                    int i = (int) (next % SLOTS);
                    got = slotSeq[i] == next;
                    if (got) {
                        System.arraycopy(slotData[i], 0, outBuf, 0, PKT_PCM);
                        slotSeq[i] = -1;
                    } else {
                        lost++;
                    }
                    next++;
                }
                if (got) {
                    System.arraycopy(outBuf, 0, last, 0, PKT_PCM);
                    haveLast = true;
                } else if (haveLast) {
                    // 掉一包：用上一包減半補（比整段靜音不明顯）；連續掉就越來越小聲
                    halve(last);
                    System.arraycopy(last, 0, outBuf, 0, PKT_PCM);
                    synchronized (lock) { concealed++; }
                } else {
                    java.util.Arrays.fill(outBuf, (byte) 0);
                }
                if (!playing) {
                    track.play();
                    playing = true;
                }
                track.write(outBuf, 0, PKT_PCM);                // 阻塞：AudioTrack 決定播放速度
            }
        }

        private static void halve(byte[] b) {
            for (int i = 0; i + 1 < b.length; i += 2) {
                short v = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
                v = (short) (v / 2);
                b[i] = (byte) v;
                b[i + 1] = (byte) (v >> 8);
            }
        }

        private static int readInt(byte[] b, int o) {
            return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                    | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
        }
    }

    // ── 麥克風 UDP（'M'）──────────────────────────────────
    /**
     * PC 已經用 adb 在平板上開好一個 scrcpy-server（audio_source=mic、audio_codec=raw、
     * tunnel_forward=true），它在平板內部聽 localabstract:scrcpy_<scid>。這裡直接在平板上
     * 連過去（不經 adb forward），讀到的 PCM 每 5 ms 一包，用 UDP 送回 PC。
     *
     * 送到哪：PC 每秒從自己的 UDP socket 送一個 hello（4 bytes token）到這裡的 port+1，
     * 這裡就回給 hello 的來源位址。平板在外面時，PC 的封包是經 MT5000 進 tailnet 來的，
     * 來源是 MT5000，回給它就會沿原路回到 PC；平板主動往 192.168.50.x 送反而會被平板自己的
     * Tailscale 收走，所以一律「跟著 hello 回」。
     *
     * PC 關掉 TCP 就結束，同時關掉 LocalSocket，scrcpy-server 讀到斷線會自己退出
     * （不要 pkill 活的 scrcpy-server，平板會軟重啟）。
     */
    private static final class MicSession {
        private final DatagramSocket udp;
        private final int token;
        private final int scid;
        private volatile boolean running = true;
        private volatile SocketAddress peer;
        private volatile LocalSocket ls;
        private volatile long tx, hello;

        MicSession(DatagramSocket udp, int token, int scid) {
            this.udp = udp;
            this.token = token;
            this.scid = scid;
        }

        void run(final InputStream ctrlIn, final OutputStream ctrlOut) throws Exception {
            Thread ctl = new Thread(new Runnable() {
                public void run() {
                    try { while (ctrlIn.read() >= 0) { } } catch (Throwable ignore) {}
                    stop();
                }
            }, "MicCtl");
            ctl.setDaemon(true);
            ctl.start();

            String name = String.format("scrcpy_%08x", scid);
            long deadline = System.currentTimeMillis() + 8000;
            while (running) {
                try {
                    LocalSocket l = new LocalSocket();
                    l.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
                    ls = l;
                    break;
                } catch (Throwable t) {
                    if (System.currentTimeMillis() > deadline) throw new Exception("連不上 " + name + "：" + t);
                    Thread.sleep(200);
                }
            }
            if (!running) { closeLs(); return; }
            InputStream pcm = ls.getInputStream();
            if (pcm.read() < 0) throw new Exception("scrcpy-server 沒送 dummy byte");
            out("AUDCONN:mic server connected");

            Thread hel = new Thread(new Runnable() { public void run() { helloLoop(); } }, "MicHello");
            Thread st = new Thread(new Runnable() {
                public void run() {
                    try {
                        while (running) {
                            Thread.sleep(1000);
                            SocketAddress p = peer;
                            ctrlOut.write(("MICST tx=" + tx + " hello=" + hello
                                    + " peer=" + (p == null ? "-" : p.toString()) + "
").getBytes("UTF-8"));
                            ctrlOut.flush();
                        }
                    } catch (Throwable t) {
                        stop();
                    }
                }
            }, "MicStat");
            hel.setDaemon(true); st.setDaemon(true);
            hel.start(); st.start();

            byte[] pkt = new byte[PKT_HDR + PKT_PCM];
            putInt(pkt, 0, token);
            long seq = 0;
            try {
                while (running) {
                    int n = 0;
                    while (n < PKT_PCM) {
                        int r = pcm.read(pkt, PKT_HDR + n, PKT_PCM - n);
                        if (r < 0) return;
                        n += r;
                    }
                    SocketAddress p = peer;
                    if (p != null) {
                        putInt(pkt, 4, (int) seq);
                        try {
                            udp.send(new DatagramPacket(pkt, pkt.length, p));
                            tx++;
                        } catch (Throwable t) {
                            // 暫時送不出去（換網路中）：丟掉這包，繼續
                        }
                    }
                    seq++;                                   // 沒 peer 時也前進，PC 端看得出空檔
                }
            } finally {
                stop();
                hel.join(1000);
            }
        }

        private void helloLoop() {
            byte[] buf = new byte[64];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (running) {
                try {
                    p.setLength(buf.length);
                    udp.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                } catch (Throwable t) {
                    if (running) out("AUDERR:mic hello " + t);
                    return;
                }
                if (p.getLength() == 4 && UdpSession.readInt(buf, 0) == token) {
                    SocketAddress a = p.getSocketAddress();
                    if (peer == null || !a.equals(peer)) out("AUDCONN:mic peer " + a);
                    peer = a;
                    hello++;
                }
            }
        }

        private void stop() {
            running = false;
            closeLs();
        }

        private void closeLs() {
            LocalSocket l = ls;
            if (l != null) {
                try { l.shutdownInput(); } catch (Throwable ignore) {}
                try { l.close(); } catch (Throwable ignore) {}
            }
        }

        private static void putInt(byte[] b, int o, int v) {
            b[o] = (byte) (v >>> 24);
            b[o + 1] = (byte) (v >>> 16);
            b[o + 2] = (byte) (v >>> 8);
            b[o + 3] = (byte) v;
        }
    }

    private static void setupActivityThread() throws Exception {
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Constructor<?> c = atClass.getDeclaredConstructor();
        c.setAccessible(true);
        Object at = c.newInstance();
        Field cur = atClass.getDeclaredField("sCurrentActivityThread");
        cur.setAccessible(true);
        cur.set(null, at);
        Field sys = atClass.getDeclaredField("mSystemThread");
        sys.setAccessible(true);
        sys.setBoolean(at, true);
    }

    private static void out(String s) {
        System.out.println(s);
        System.out.flush();
    }
}
