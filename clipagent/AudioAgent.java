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
    private static final int OPUS_MS = 20;                         // Opus 每包 20 ms
    private static final int SLOTS = 256;                          // 1.28 s 的格子
    // 喇叭抖動緩衝自動調整：每斷一次目標加 STEP，一分鐘沒斷就減 STEP/2，回到 PC 給的起始值為止。
    // 上限 = max(PC 給的上限, 目標×3)。11-07 熱點實測固定 30 ms 時每分鐘斷 9 次。
    private static final int ADAPT_STEP_PKTS = 4;                  // 20 ms
    private static final int ADAPT_CAP_PKTS = 80;                  // 400 ms

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
        final DatagramSocket spkUdp = udp;
        final AudioTrack spkTrack = track;

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
                    public void run() { handle(s, spkUdp, micUdp, spkTrack); }
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
                boolean opus = in.readUnsignedByte() == 1;
                out("AUDCONN:udp target=" + target + "ms max=" + max + "ms codec=" + (opus ? "opus" : "raw"));
                new UdpSession(udp, track, token, target, max, opus).run(in, s.getOutputStream());
            } else if (mode == 'M' && micUdp != null) {
                int token = in.readInt();
                int scid = in.readInt();
                boolean opus = in.readUnsignedByte() == 1;
                out("AUDCONN:mic scid=" + String.format("%08x", scid) + " codec=" + (opus ? "opus" : "raw"));
                new MicSession(micUdp, token, scid, opus).run(in, s.getOutputStream());
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
    /**
     * 喇叭 UDP。raw：每包 5 ms 的 PCM。opus（平板在計費網路時，PC 決定）：每包是一個
     * 20 ms 的 Opus 封包，這裡用 MediaCodec 解回 PCM 再播。抖動緩衝以「包」為單位，
     * 目標／上限用 ms 換算。
     */
    private static final class UdpSession {
        private final DatagramSocket udp;
        private final AudioTrack track;
        private final int token;
        private final boolean opus;
        private final int pktMs;
        private final int stepPkts;
        private final int capPkts;
        private final int baseTarget;
        private final int baseMax;
        private int targetPkts;          // 會自己調（lock 保護）
        private int maxPkts;
        private long lastUnderMs = System.currentTimeMillis();
        private long lastDecayMs = System.currentTimeMillis();

        private final Object lock = new Object();
        private final byte[][] slotData = new byte[SLOTS][];
        private final int[] slotLen = new int[SLOTS];
        private final long[] slotSeq = new long[SLOTS];
        private long next = -1;          // 下一個要播的 seq；-1 = 還沒收到任何包
        private long highest = -1;
        private volatile boolean running = true;

        // 統計（累計值，PC 自己算差）
        private long rx, lost, late, under, skipped, concealed, drained;
        private volatile long framesWritten;   // 寫進 AudioTrack 的 frame 數（量播放管線延遲用）
        private long overSince;                // 緩衝超過目標開始的時間（ms）；0 = 沒超過
        private static final int DRAIN_MARGIN_MS = 40;
        private static final int DRAIN_QUIET = 800;

        private static int peak(byte[] b, int n) {
            int m = 0;
            for (int i = 0; i + 1 < n; i += 2) {
                int v = Math.abs((short) ((b[i] & 0xFF) | (b[i + 1] << 8)));
                if (v > m) m = v;
            }
            return m;
        }

        /** AudioTrack 管線延遲（ms）：已寫入但還沒從喇叭播出的量，用 getTimestamp 算；拿不到回 -1。 */
        private long trackLatencyMs() {
            try {
                android.media.AudioTimestamp ts = new android.media.AudioTimestamp();
                if (!track.getTimestamp(ts)) return -1;
                double presented = ts.framePosition + (System.nanoTime() - ts.nanoTime) * (double) RATE / 1e9;
                return Math.round((framesWritten - presented) * 1000.0 / RATE);
            } catch (Throwable t) {
                return -1;
            }
        }

        UdpSession(DatagramSocket udp, AudioTrack track, int token, int targetMs, int maxMs, boolean opus) {
            this.udp = udp;
            this.track = track;
            this.token = token;
            this.opus = opus;
            this.pktMs = opus ? OPUS_MS : PKT_MS;
            this.stepPkts = Math.max(1, ADAPT_STEP_PKTS * PKT_MS / pktMs);
            this.capPkts = Math.max(2, ADAPT_CAP_PKTS * PKT_MS / pktMs);
            this.baseTarget = Math.max(opus ? 2 : 1, Math.min(capPkts, targetMs / pktMs));
            this.baseMax = Math.max(baseTarget + 2, Math.min(SLOTS - 8, maxMs / pktMs));
            this.targetPkts = baseTarget;
            this.maxPkts = baseMax;
            int cap = opus ? 2000 : PKT_PCM;
            for (int i = 0; i < SLOTS; i++) {
                slotSeq[i] = -1;
                slotData[i] = new byte[cap];
            }
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
                            Thread.sleep(250);              // 每 0.25 秒回報一次，PC 端才量得到緩衝的起伏
                            String line;
                            synchronized (lock) {
                                long depth = (next >= 0 && highest >= next) ? (highest - next + 1) * pktMs : 0;
                                line = "AUDST rx=" + rx + " lost=" + lost + " late=" + late
                                        + " under=" + under + " skip=" + skipped
                                        + " conceal=" + concealed + " depth=" + depth
                                        + " target=" + targetPkts * pktMs + " pktms=" + pktMs
                                        + " drain=" + drained;
                            }
                            line += " trk=" + trackLatencyMs() + "\n";
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
            OpusDec dec = null;
            try {
                if (opus) dec = new OpusDec();
                playLoop(dec);
            } finally {
                running = false;
                if (dec != null) dec.close();
                rxT.join(1000);
            }
        }

        private void receive() {
            byte[] buf = new byte[4096];
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
                int len = p.getLength() - PKT_HDR;
                if (opus ? (len <= 0 || len > 2000) : len != PKT_PCM) continue;
                int tok = readInt(buf, 0);
                if (tok != token) continue;                     // 舊連線殘留的包
                long seq = readInt(buf, 4) & 0xFFFFFFFFL;
                synchronized (lock) {
                    rx++;
                    if (next >= 0 && seq < next) { late++; continue; }
                    int i = (int) (seq % SLOTS);
                    System.arraycopy(buf, PKT_HDR, slotData[i], 0, len);
                    slotLen[i] = len;
                    slotSeq[i] = seq;
                    if (seq > highest) highest = seq;
                    if (next < 0) next = seq;
                    lock.notifyAll();
                }
            }
        }

        private void playLoop(OpusDec dec) throws Exception {
            int pcmLen = pktMs * BYTES_PER_MS;
            byte[] pktBuf = new byte[2000];
            byte[] outBuf = new byte[opus ? 16384 : PKT_PCM];
            byte[] last = new byte[pcmLen];
            boolean haveLast = false;
            boolean priming = true;
            boolean playing = false;
            while (running) {
                boolean got;
                int plen = 0;
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
                        targetPkts = Math.min(capPkts, targetPkts + stepPkts);
                        maxPkts = Math.min(SLOTS - 8, Math.max(baseMax, targetPkts * 3));
                        lastUnderMs = System.currentTimeMillis();
                        continue;
                    }
                    long nowMs = System.currentTimeMillis();
                    if (targetPkts > baseTarget && nowMs - lastUnderMs > 60000
                            && nowMs - lastDecayMs > 60000) {      // 穩了一分鐘：慢慢縮回去
                        targetPkts = Math.max(baseTarget, targetPkts - Math.max(1, stepPkts / 2));
                        maxPkts = Math.min(SLOTS - 8, Math.max(baseMax, targetPkts * 3));
                        lastDecayMs = nowMs;
                    }
                    if (depth > maxPkts) {                      // 積太多：跳到最新的 target
                        long nn = highest - targetPkts + 1;
                        skipped += (nn - next) * pktMs / PKT_MS;   // 以 5 ms 為單位回報，PC 端照舊換算
                        next = nn;
                    }
                    int i = (int) (next % SLOTS);
                    got = slotSeq[i] == next;
                    if (got) {
                        plen = slotLen[i];
                        System.arraycopy(slotData[i], 0, pktBuf, 0, plen);
                        slotSeq[i] = -1;
                    } else {
                        lost++;
                    }
                    next++;
                }
                int n;
                if (got && opus) {
                    n = dec.decode(pktBuf, plen, outBuf);
                    if (n > 0) {
                        System.arraycopy(outBuf, 0, last, 0, Math.min(n, pcmLen));
                        haveLast = true;
                    }
                } else if (got) {
                    System.arraycopy(pktBuf, 0, outBuf, 0, PKT_PCM);
                    System.arraycopy(outBuf, 0, last, 0, PKT_PCM);
                    haveLast = true;
                    n = PKT_PCM;
                } else if (haveLast) {
                    // 掉一包：用上一包減半補（比整段靜音不明顯）；連續掉就越來越小聲
                    halve(last);
                    System.arraycopy(last, 0, outBuf, 0, pcmLen);
                    n = pcmLen;
                    synchronized (lock) { concealed++; }
                } else {
                    java.util.Arrays.fill(outBuf, 0, pcmLen, (byte) 0);
                    n = pcmLen;
                }
                if (n <= 0) continue;
                // 降回目標：一批封包湧進來後緩衝會卡在目標之上，兩端時脈一樣快就永遠不會自己降。
                // 超過目標 DRAIN_MARGIN_MS 持續 1 秒，就把安靜的那一包丟掉不播；一直沒有安靜片段，
                // 超過 4 秒就直接丟（11-07 實測目標 80 時實際積了 200）。
                if (got) {
                    long excessPkts;
                    synchronized (lock) { excessPkts = (highest - next + 1) - targetPkts; }
                    long nowMs = System.currentTimeMillis();
                    if (excessPkts * pktMs > DRAIN_MARGIN_MS) {
                        if (overSince == 0) overSince = nowMs;
                        else if (nowMs - overSince > 1000
                                && (nowMs - overSince > 4000 || peak(outBuf, n) < DRAIN_QUIET)) {
                            synchronized (lock) { drained += n / BYTES_PER_MS; }
                            continue;
                        }
                    } else {
                        overSince = 0;
                    }
                }
                if (!playing) {
                    track.play();
                    playing = true;
                }
                track.write(outBuf, 0, n);                      // 阻塞：AudioTrack 決定播放速度
                framesWritten += n / FRAME_BYTES;
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

    /** MediaCodec 的 Opus 解碼器（48 kHz 雙聲道），同步模式，一包進、PCM 出。 */
    private static final class OpusDec {
        private final android.media.MediaCodec codec;
        private final android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        private long pts;

        OpusDec() throws Exception {
            android.media.MediaFormat f = android.media.MediaFormat.createAudioFormat("audio/opus", RATE, 2);
            // OpusHead：版本 1、2 聲道、pre-skip 312、48000 Hz、增益 0、mapping 0（皆 little-endian）
            byte[] head = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 2, (byte) 0x38, 0x01,
                    (byte) 0x80, (byte) 0xBB, 0, 0, 0, 0, 0};
            f.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(head));
            java.nio.ByteBuffer d = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            d.putLong(312L * 1000000000L / RATE);
            d.flip();
            f.setByteBuffer("csd-1", d);
            java.nio.ByteBuffer pr = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            pr.putLong(80000000L);
            pr.flip();
            f.setByteBuffer("csd-2", pr);
            codec = android.media.MediaCodec.createDecoderByType("audio/opus");
            codec.configure(f, null, null, 0);
            codec.start();
            out("AUDCONN:opus decoder " + codec.getName());
        }

        int decode(byte[] pkt, int len, byte[] out) {
            int ii = codec.dequeueInputBuffer(20000);
            if (ii >= 0) {
                java.nio.ByteBuffer b = codec.getInputBuffer(ii);
                b.clear();
                b.put(pkt, 0, len);
                codec.queueInputBuffer(ii, 0, len, pts, 0);
                pts += OPUS_MS * 1000L;
            }
            int n = 0;
            while (true) {
                int oi = codec.dequeueOutputBuffer(info, n == 0 ? 20000 : 0);
                if (oi >= 0) {
                    java.nio.ByteBuffer ob = codec.getOutputBuffer(oi);
                    int sz = Math.min(info.size, out.length - n);
                    ob.position(info.offset);
                    ob.get(out, n, sz);
                    n += sz;
                    codec.releaseOutputBuffer(oi, false);
                    if (n >= out.length / 2) break;
                } else if (oi == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                        || oi == android.media.MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    continue;
                } else {
                    break;
                }
            }
            return n;
        }

        void close() {
            try { codec.stop(); } catch (Throwable ignore) {}
            try { codec.release(); } catch (Throwable ignore) {}
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

        private final boolean opus;

        MicSession(DatagramSocket udp, int token, int scid, boolean opus) {
            this.opus = opus;
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
                                    + " peer=" + (p == null ? "-" : p.toString()) + "\n").getBytes("UTF-8"));
                            ctrlOut.flush();
                        }
                    } catch (Throwable t) {
                        stop();
                    }
                }
            }, "MicStat");
            hel.setDaemon(true); st.setDaemon(true);
            hel.start(); st.start();

            DataInputStream din = new DataInputStream(pcm);
            if (opus) {
                // server 用 audio_codec=opus、send_codec_meta、send_frame_meta：先 4 bytes codec id
                int codec = din.readInt();
                if (codec == 0 || codec == 1) throw new Exception("server 停用了麥克風串流 codec=" + codec);
            }
            byte[] pkt = new byte[PKT_HDR + (opus ? 4000 : PKT_PCM)];
            byte[] fh = new byte[12];
            putInt(pkt, 0, token);
            long seq = 0;
            try {
                while (running) {
                    int len;
                    if (opus) {
                        // 每個 Opus 封包前有 12 bytes：pts＋旗標(8)、長度(4)；config 封包（OpusHead）不用送
                        din.readFully(fh);
                        len = ((fh[8] & 0xFF) << 24) | ((fh[9] & 0xFF) << 16) | ((fh[10] & 0xFF) << 8) | (fh[11] & 0xFF);
                        if (len < 0 || len > 4000) throw new Exception("Opus 封包長度怪怪的：" + len);
                        din.readFully(pkt, PKT_HDR, len);
                        if ((fh[0] & 0x80) != 0) continue;          // PACKET_FLAG_CONFIG
                    } else {
                        len = PKT_PCM;
                        din.readFully(pkt, PKT_HDR, len);
                    }
                    SocketAddress p = peer;
                    if (p != null) {
                        putInt(pkt, 4, (int) seq);
                        try {
                            udp.send(new DatagramPacket(pkt, PKT_HDR + len, p));
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
