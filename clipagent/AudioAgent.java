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
    // 11-07：dumpsys media.audio_flinger 確認這條軌已經是 FAST（F1），可以用更小的緩衝；40 → 20 ms
    private static final int TRACK_BUFFER_MS = 20;

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
                int pcPort = in.readUnsignedShort();
                out("AUDCONN:mic scid=" + String.format("%08x", scid) + " codec=" + (opus ? "opus" : "raw"));
                MicSession ms = new MicSession(micUdp, token, scid, opus);
                ms.setInitialPeer(new InetSocketAddress(s.getInetAddress(), pcPort));
                ms.run(in, s.getOutputStream());
            } else if (mode == 'L') {
                int usage = in.readUnsignedByte();
                int src = in.readUnsignedByte();
                loopTest(usage, src, s.getOutputStream());
            } else if (mode == 'A' && micUdp != null) {
                int token = in.readInt();
                boolean opus = in.readUnsignedByte() == 1;
                int src = in.readUnsignedByte();
                int bitrate = in.readInt();
                int pcPort = in.readUnsignedShort();
                out("AUDCONN:agent mic codec=" + (opus ? "opus" : "raw") + " src=" + src);
                AgentMicSession am = new AgentMicSession(micUdp, token, opus, src, bitrate);
                am.setInitialPeer(new InetSocketAddress(s.getInetAddress(), pcPort));
                am.run(in, s.getOutputStream());
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
        private long winStart = System.currentTimeMillis();   // 這一秒的觀察窗
        private long winMin = Long.MAX_VALUE;  // 觀察窗內最低深度（包）
        private long drainBudget;              // 還要丟幾包才回到目標
        private long budgetSince;              // 預算從什麼時候開始（ms）
        private static final int DRAIN_MARGIN_MS = 20;
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
                // 每秒看一次「這一秒裡最低的深度」，連最低點都比目標高 DRAIN_MARGIN_MS 以上，
                // 多出來的就是白白多積的延遲：記成預算，之後遇到安靜的包就丟（不播）。
                // 連續 3 秒都沒遇到安靜的包就直接丟。11-07 實測：目標 40 時深度一直在 80～100，
                // 第一版用「持續超過 1 秒」判斷，深度一跳到 80 就重置，一分鐘只去掉 20 ms。
                long nowMs = System.currentTimeMillis();
                long depthNow;
                synchronized (lock) { depthNow = highest - next + 1; }
                if (depthNow < winMin) winMin = depthNow;
                if (nowMs - winStart >= 1000) {
                    long excess = winMin - targetPkts;
                    if (excess * pktMs > DRAIN_MARGIN_MS) {
                        drainBudget = excess;
                        if (budgetSince == 0) budgetSince = nowMs;
                    } else {
                        drainBudget = 0;
                        budgetSince = 0;
                    }
                    winMin = Long.MAX_VALUE;
                    winStart = nowMs;
                }
                if (got && drainBudget > 0
                        && (peak(outBuf, n) < DRAIN_QUIET || nowMs - budgetSince > 3000)) {
                    drainBudget--;
                    if (drainBudget == 0) budgetSince = 0;
                    synchronized (lock) { drained += n / BYTES_PER_MS; }
                    continue;
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

    // ── 平板本機迴路測試（'L'）────────────────────────────
    /**
     * 只在平板上量「喇叭 → 空氣 → 麥克風」的來回：用一條新的 AudioTrack 嗶 5 次，同時用 AudioRecord 錄，
     * 看每次嗶聲從「寫進 AudioTrack」到「錄到」隔多久。不經網路、不經 X1。
     *   usage：0 = MEDIA（喇叭轉送現在用的），1 = VOICE_COMMUNICATION（通話路徑，常繞過音效）
     *   src  ：AgentMicSession 的音源編號
     * 結果用一行 "LOOP usage=.. src=.. ms=a,b,c,d,e" 回給 PC。
     */
    private static void loopTest(int usage, int src, OutputStream outS) throws Exception {
        android.media.AudioAttributes.Builder ab = new android.media.AudioAttributes.Builder();
        if (usage == 1) {
            ab.setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
              .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH);
        } else {
            ab.setUsage(android.media.AudioAttributes.USAGE_MEDIA)
              .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH);
        }
        android.media.AudioFormat ofmt = new android.media.AudioFormat.Builder().setSampleRate(RATE)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build();
        android.media.AudioTrack.Builder tb = new android.media.AudioTrack.Builder()
                .setAudioAttributes(ab.build()).setAudioFormat(ofmt)
                .setBufferSizeInBytes(Math.max(android.media.AudioTrack.getMinBufferSize(RATE,
                        android.media.AudioFormat.CHANNEL_OUT_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT),
                        4 * PKT_PCM))
                .setTransferMode(android.media.AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= 26) tb.setPerformanceMode(android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        final android.media.AudioTrack t = tb.build();
        if (Build.VERSION.SDK_INT >= 24) t.setBufferSizeInFrames(TRACK_BUFFER_MS * RATE / 1000);

        android.media.AudioFormat ifmt = new android.media.AudioFormat.Builder().setSampleRate(RATE)
                .setChannelMask(android.media.AudioFormat.CHANNEL_IN_STEREO)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build();
        android.media.AudioRecord.Builder rb = new android.media.AudioRecord.Builder()
                .setAudioSource(AgentMicSession.sourceOf(src)).setAudioFormat(ifmt)
                .setBufferSizeInBytes(Math.max(android.media.AudioRecord.getMinBufferSize(RATE,
                        android.media.AudioFormat.CHANNEL_IN_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT),
                        8 * PKT_PCM));
        if (Build.VERSION.SDK_INT >= 31) rb.setContext(new ShellContext());
        final android.media.AudioRecord r = rb.build();

        final int secs = 8;
        final short[] recBuf = new short[RATE * secs];         // 只存左聲道
        final long[] recStartNs = new long[1];
        r.startRecording();
        Thread rt = new Thread(new Runnable() {
            public void run() {
                byte[] b = new byte[PKT_PCM];
                int pos = 0;
                recStartNs[0] = System.nanoTime();
                while (pos < recBuf.length) {
                    int n = r.read(b, 0, b.length);
                    if (n <= 0) break;
                    for (int i = 0; i + 3 < n && pos < recBuf.length; i += 4) {
                        recBuf[pos++] = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
                    }
                }
            }
        }, "LoopRec");
        rt.start();

        // 嗶：1500 Hz、100 ms；每 1.2 秒一次，前 1 秒安靜（量底噪）
        byte[] silence = new byte[PKT_PCM];
        byte[] beep = new byte[RATE / 10 * FRAME_BYTES];
        for (int i = 0; i < RATE / 10; i++) {
            double env = Math.min(1.0, Math.min(i / 240.0, (RATE / 10 - i) / 240.0));
            short v = (short) (16000 * env * Math.sin(2 * Math.PI * 1500 * i / RATE));
            beep[4 * i] = (byte) v; beep[4 * i + 1] = (byte) (v >> 8);
            beep[4 * i + 2] = (byte) v; beep[4 * i + 3] = (byte) (v >> 8);
        }
        t.play();
        // 時間軸一律用硬體時間戳對齊（第一版用「開始讀的時刻」，AudioRecord 第一次 read 會先吐
        // 之前錄好的緩衝，時間整段錯位，量出 0 ms）：
        //   嗶聲從喇叭出去的時刻 = AudioTrack.getTimestamp 換算 beepFrame
        //   聲音進麥克風的時刻   = AudioRecord.getTimestamp 換算錄到的 frame 位置
        // 兩者相減 = 時間戳之後的喇叭硬體＋空氣＋時間戳之前的麥克風硬體（看不見的那一段）。
        long[] sentNs = new long[5];
        long[] beepFrame = new long[5];
        android.media.AudioTimestamp tts = new android.media.AudioTimestamp();
        boolean haveTts = false;
        long start = System.nanoTime();
        int k = 0;
        long written = 0;
        while (System.nanoTime() - start < (long) (secs - 1) * 1000000000L) {
            long el = (System.nanoTime() - start) / 1000000L;
            if (k < 5 && el >= 1000 + k * 1200) {
                sentNs[k] = System.nanoTime();
                beepFrame[k] = written;
                t.write(beep, 0, beep.length);
                written += beep.length / FRAME_BYTES;
                k++;
            } else {
                t.write(silence, 0, silence.length);
                written += silence.length / FRAME_BYTES;
            }
            android.media.AudioTimestamp x = new android.media.AudioTimestamp();
            if (t.getTimestamp(x)) { tts = x; haveTts = true; }
        }
        rt.join(3000);
        android.media.AudioTimestamp rts = new android.media.AudioTimestamp();
        boolean haveRts = r.getTimestamp(rts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == 0;
        t.stop(); t.release();
        r.stop(); r.release();
        if (!haveTts || !haveRts) {
            String line = "LOOP usage=" + usage + " src=" + src + " 沒有時間戳 track=" + haveTts + " rec=" + haveRts + "\n";
            outS.write(line.getBytes("UTF-8"));
            outS.flush();
            return;
        }

        // 底噪：第 0.2～0.9 秒
        int floor = 0;
        for (int i = (int) (0.2 * RATE); i < (int) (0.9 * RATE); i++) floor = Math.max(floor, Math.abs(recBuf[i]));
        int thr = Math.max(floor * 4, 800);
        StringBuilder hidden = new StringBuilder();
        StringBuilder fromWrite = new StringBuilder();
        for (int j = 0; j < k; j++) {
            double presentNs = tts.nanoTime + (beepFrame[j] - tts.framePosition) * 1e9 / RATE;
            long from = rts.framePosition + Math.round((presentNs - rts.nanoTime) * RATE / 1e9) - RATE / 20;
            int hit = -1;
            for (long i = Math.max(0, from); i < Math.min(recBuf.length, from + RATE); i++) {
                if (Math.abs(recBuf[(int) i]) > thr) { hit = (int) i; break; }
            }
            if (j > 0) { hidden.append(','); fromWrite.append(','); }
            if (hit < 0) {
                hidden.append('-');
                fromWrite.append('-');
            } else {
                double captureNs = rts.nanoTime + (hit - rts.framePosition) * 1e9 / RATE;
                hidden.append(Math.round((captureNs - presentNs) / 1e6));
                fromWrite.append(Math.round((captureNs - sentNs[j]) / 1e6));
            }
        }
        String line = "LOOP usage=" + usage + " src=" + src + " floor=" + floor
                + " hidden_ms=" + hidden + " write_to_mic_ms=" + fromWrite + "\n";
        out("AUD" + line.trim());
        outS.write(line.getBytes("UTF-8"));
        outS.flush();
    }

    // ── 麥克風：agent 自己錄（'A'）────────────────────────────
    /**
     * 不經 scrcpy-server，AudioAgent 自己用 AudioRecord 錄平板麥克風（11-07 為了量／降低錄音延遲）。
     * 封包格式和送法跟 MicSession 一樣（token＋seq＋資料，跟著 PC 的 hello 回送），PC 端不用分。
     *   音源 src：0 MIC、1 VOICE_COMMUNICATION、2 VOICE_RECOGNITION、3 UNPROCESSED、4 CAMCORDER
     *   opus：用 MediaCodec 的 Opus 編碼器，每包 20 ms；否則每包 5 ms 的 PCM
     * MICST 多回報 rec=：用 AudioRecord.getTimestamp 算「聲音進麥克風」到「程式讀到」的延遲（ms）。
     * 建不起來（權限、音源不支援）就回一行 MICERR 後關掉，PC 會改用 scrcpy-server。
     */
    private static final class AgentMicSession {
        private final DatagramSocket udp;
        private final int token;
        private final boolean opus;
        private final int src;
        private final int bitrate;
        private volatile boolean running = true;
        private volatile SocketAddress peer;
        private volatile long tx, hello, framesRead;
        private volatile android.media.AudioRecord rec;

        /** 先用「TCP 控制連線的對方 IP＋PC 告知的 UDP 埠」當回送位址，不用等 hello。
         *  11-07 實測 hello 要約 3 秒才到，Teams 開麥克風的頭 3 秒都沒聲音；之後 hello 到了再改用它的來源。 */
        void setInitialPeer(SocketAddress a) {
            peer = a;
            out("AUDCONN:agent mic initial peer " + a);
        }

        AgentMicSession(DatagramSocket udp, int token, boolean opus, int src, int bitrate) {
            this.udp = udp;
            this.token = token;
            this.opus = opus;
            this.src = src;
            this.bitrate = bitrate;
        }

        private static int sourceOf(int s) {
            switch (s) {
                case 1: return android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION;
                case 2: return android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION;
                case 3: return android.media.MediaRecorder.AudioSource.UNPROCESSED;
                case 4: return android.media.MediaRecorder.AudioSource.CAMCORDER;
                default: return android.media.MediaRecorder.AudioSource.MIC;
            }
        }

        private long recLatencyMs() {
            try {
                android.media.AudioRecord r = rec;
                if (r == null) return -1;
                android.media.AudioTimestamp ts = new android.media.AudioTimestamp();
                if (r.getTimestamp(ts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) != 0) return -1;
                // 最後讀到的那個 frame 是什麼時候進麥克風的
                double capturedNs = ts.nanoTime + (framesRead - ts.framePosition) * 1e9 / RATE;
                return Math.round((System.nanoTime() - capturedNs) / 1e6);
            } catch (Throwable t) {
                return -1;
            }
        }

        void run(final InputStream ctrlIn, final OutputStream ctrlOut) throws Exception {
            Thread ctl = new Thread(new Runnable() {
                public void run() {
                    try { while (ctrlIn.read() >= 0) { } } catch (Throwable ignore) {}
                    running = false;
                }
            }, "AMicCtl");
            ctl.setDaemon(true);
            ctl.start();

            android.media.AudioRecord r;
            OpusEnc enc = null;
            try {
                android.media.AudioFormat fmt = new android.media.AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_IN_STEREO)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .build();
                int min = android.media.AudioRecord.getMinBufferSize(RATE,
                        android.media.AudioFormat.CHANNEL_IN_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT);
                android.media.AudioRecord.Builder b = new android.media.AudioRecord.Builder()
                        .setAudioSource(sourceOf(src))
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(Math.max(min, 4 * OPUS_MS * BYTES_PER_MS));
                if (Build.VERSION.SDK_INT >= 31) b.setContext(new ShellContext());
                r = b.build();
                if (r.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                    r.release();
                    throw new Exception("AudioRecord 沒有初始化");
                }
                r.startRecording();
                if (opus) enc = new OpusEnc(bitrate);
                out("AUDCONN:agent mic src=" + src + " min=" + min + " opus=" + opus);
            } catch (Throwable t) {
                String msg = "MICERR " + t + "\n";
                try { ctrlOut.write(msg.getBytes("UTF-8")); ctrlOut.flush(); } catch (Throwable ignore) {}
                out("AUDERR:agent mic " + t);
                return;
            }
            rec = r;

            Thread hel = new Thread(new Runnable() { public void run() { helloLoop(); } }, "AMicHello");
            Thread st = new Thread(new Runnable() {
                public void run() {
                    try {
                        while (running) {
                            Thread.sleep(250);
                            SocketAddress p = peer;
                            ctrlOut.write(("MICST tx=" + tx + " hello=" + hello + " rec=" + recLatencyMs()
                                    + " src=" + src + " peer=" + (p == null ? "-" : p.toString())
                                    + "\n").getBytes("UTF-8"));
                            ctrlOut.flush();
                        }
                    } catch (Throwable t) {
                        running = false;
                    }
                }
            }, "AMicStat");
            hel.setDaemon(true); st.setDaemon(true);
            hel.start(); st.start();

            int chunk = opus ? OPUS_MS * BYTES_PER_MS : PKT_PCM;
            byte[] pcm = new byte[chunk];
            byte[] pkt = new byte[PKT_HDR + 4000];
            putInt(pkt, 0, token);
            long seq = 0;
            java.util.ArrayList<byte[]> outPkts = new java.util.ArrayList<byte[]>();
            try {
                while (running) {
                    int n = 0;
                    while (n < chunk && running) {
                        int k = r.read(pcm, n, chunk - n);
                        if (k < 0) throw new Exception("AudioRecord.read " + k);
                        n += k;
                    }
                    framesRead += chunk / FRAME_BYTES;
                    outPkts.clear();
                    if (opus) {
                        enc.encode(pcm, outPkts);
                    } else {
                        outPkts.add(pcm);
                    }
                    for (byte[] data : outPkts) {
                        SocketAddress p = peer;
                        if (p != null && data.length <= 4000) {
                            putInt(pkt, 4, (int) seq);
                            System.arraycopy(data, 0, pkt, PKT_HDR, data.length);
                            try {
                                udp.send(new DatagramPacket(pkt, PKT_HDR + data.length, p));
                                tx++;
                            } catch (Throwable t) {
                                // 暫時送不出去：丟掉
                            }
                        }
                        seq++;
                    }
                }
            } finally {
                running = false;
                try { r.stop(); } catch (Throwable ignore) {}
                r.release();
                rec = null;
                if (enc != null) enc.close();
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
                    if (running) out("AUDERR:agent mic hello " + t);
                    return;
                }
                if (p.getLength() == 4 && UdpSession.readInt(buf, 0) == token) {
                    SocketAddress a = p.getSocketAddress();
                    if (peer == null || !a.equals(peer)) out("AUDCONN:agent mic peer " + a);
                    peer = a;
                    hello++;
                }
            }
        }

        private static void putInt(byte[] b, int o, int v) {
            b[o] = (byte) (v >>> 24);
            b[o + 1] = (byte) (v >>> 16);
            b[o + 2] = (byte) (v >>> 8);
            b[o + 3] = (byte) v;
        }
    }

    /** MediaCodec 的 Opus 編碼器（48 kHz 雙聲道），每次送 20 ms PCM，取出 0～n 個封包。 */
    private static final class OpusEnc {
        private final android.media.MediaCodec codec;
        private final android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        private long pts;

        OpusEnc(int bitrate) throws Exception {
            android.media.MediaFormat f = android.media.MediaFormat.createAudioFormat("audio/opus", RATE, 2);
            f.setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrate);
            codec = android.media.MediaCodec.createEncoderByType("audio/opus");
            codec.configure(f, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            out("AUDCONN:opus encoder " + codec.getName());
        }

        void encode(byte[] pcm, java.util.List<byte[]> out) {
            int ii = codec.dequeueInputBuffer(20000);
            if (ii >= 0) {
                java.nio.ByteBuffer b = codec.getInputBuffer(ii);
                b.clear();
                b.put(pcm, 0, pcm.length);
                codec.queueInputBuffer(ii, 0, pcm.length, pts, 0);
                pts += OPUS_MS * 1000L;
            }
            while (true) {
                int oi = codec.dequeueOutputBuffer(info, 0);
                if (oi >= 0) {
                    if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        java.nio.ByteBuffer ob = codec.getOutputBuffer(oi);
                        byte[] d = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(d);
                        out.add(d);
                    }
                    codec.releaseOutputBuffer(oi, false);
                } else if (oi == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                        || oi == android.media.MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    continue;
                } else {
                    break;
                }
            }
        }

        void close() {
            try { codec.stop(); } catch (Throwable ignore) {}
            try { codec.release(); } catch (Throwable ignore) {}
        }
    }

    /** 讓 AudioRecord 以 shell（com.android.shell）身分錄音的假 Context，做法同 scrcpy 的 FakeContext。 */
    private static final class ShellContext extends android.content.ContextWrapper {
        ShellContext() { super(null); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public android.content.AttributionSource getAttributionSource() {
            return new android.content.AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
        }
        @Override public int getDeviceId() { return 0; }
        @Override public android.content.Context getApplicationContext() { return this; }
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

        void setInitialPeer(SocketAddress a) {
            peer = a;
            out("AUDCONN:mic initial peer " + a);
        }

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
