package com.clipboardbridge.agent;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 平板端喇叭 agent（shell uid，app_process 啟動）：PC 的聲音在平板播出來。
 *
 * PC（Sync 的 audio_bridge）連 TCP :port，送 48 kHz / 雙聲道 / s16le 的裸 PCM，
 * 這裡直接寫進 AudioTrack。麥克風方向不在這裡：PC 另開一個 scrcpy-server
 * （audio_source=mic、audio_codec=raw）收音。
 *
 * 延遲控制：兩邊時脈不同步，PC 送得比平板播得快時，資料會堆在 socket 裡讓
 * 延遲越拖越長。所以每讀一次就看 socket 裡積了多少，超過 MAX_BACKLOG_MS 就丟到
 * 只剩 TARGET_BACKLOG_MS。
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
    private static final int CHUNK = 10 * BYTES_PER_MS;            // 每次讀寫 10 ms
    private static final int MAX_BACKLOG_MS = 120;
    private static final int TARGET_BACKLOG_MS = 30;
    // AudioTrack 實際配到的緩衝常常是 128 ms（6144 frames）；寫入是阻塞的，緩衝會被填滿，
    // 等於固定多 128 ms 延遲。建好後把「有效緩衝」縮到這個長度。
    private static final int TRACK_BUFFER_MS = 40;

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
        out("AUDRDY:port=" + port + " buffer=" + track.getBufferSizeInFrames() + "f"
                + " capacity=" + (Build.VERSION.SDK_INT >= 24 ? track.getBufferCapacityInFrames() : -1) + "f");

        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            while (true) {
                Socket s = ss.accept();
                out("AUDCONN:" + s.getRemoteSocketAddress());
                try {
                    s.setTcpNoDelay(true);
                    s.setReceiveBufferSize(64 * 1024);
                    play(s.getInputStream(), track);
                } catch (Throwable t) {
                    out("AUDERR:stream " + t);
                } finally {
                    try { s.close(); } catch (Throwable ignore) {}
                    track.pause();
                    track.flush();
                    out("AUDCONN:closed");
                }
            }
        } catch (Throwable t) {
            out("AUDERR:fatal " + t);
        } finally {
            track.release();
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
