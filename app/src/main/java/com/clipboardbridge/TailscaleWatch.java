package com.clipboardbridge;

import android.content.Context;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tailscale 心跳：週期檢查平板還在不在 tailnet 上，掉了就試著把它拉回來。
 *
 * 這支存在的理由是「平板失聯時你還剩什麼」。VPS 打不進來的時候，能自救的
 * 只有平板自己，所以偵測必須**純本地**——不查 DNS、不連控制伺服器、不需要
 * 網路通。做法是掃網路介面看有沒有 100.64.0.0/10 的位址：tailscaled 建好
 * 隧道就會有，沒有就是沒連上。這個檢查也不需要任何權限。
 *
 * 拉回來則需要 Shizuku（要 shell 才能叫得動別的 App）。所以偵測永遠有效、
 * 回復不一定有效——重開機後沒人點過 Shizuku 時只會記 log。這是刻意的分層：
 * 至少讓 status 說得出「我還活著，只是 Tailscale 斷了」。
 */
final class TailscaleWatch {

    private static final String TAG = ClipboardReceiver.TAG;

    static final long CHECK_MS = 60_000;
    /**
     * 連續幾次沒看到 tailnet 位址才動手。設 3（約 3 分鐘）是因為漫遊、休眠喚醒、
     * 切換 WiFi/行動網路時介面本來就會短暫消失，一次就重拉會變成一直在騷擾使用者。
     */
    private static final int MISS_THRESHOLD = 3;
    /** 回復動作的冷卻時間。拉不起來時每 60 秒彈一次 Tailscale 是災難。 */
    private static final long RECOVER_COOLDOWN_MS = 10 * 60_000L;
    /** 拉起後等多久再確認結果。實測 Tailscale 約 10 秒把隧道建回來。 */
    private static final long VERIFY_DELAY_MS = 15_000L;

    private static final String TS_PKG = "com.tailscale.ipn";

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static int misses = 0;
    private static long lastRecover = 0;

    private TailscaleWatch() {}

    /**
     * 目前的 tailnet IPv4 位址，沒連上回 null。
     *
     * 直接認 100.64.0.0/10 而不是找名為 tailscale0 的介面：Android 上
     * tailscaled 走的是 VpnService 建的 tun，介面名稱不保證（常見 tun0，
     * 但和其他 VPN 共存時會變）。位址網段才是穩定的識別。
     */
    static String tailnetIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return null;
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    byte[] b = a.getAddress();
                    if (b == null || b.length != 4) continue;
                    int o1 = b[0] & 0xFF, o2 = b[1] & 0xFF;
                    if (o1 == 100 && o2 >= 64 && o2 <= 127) return a.getHostAddress();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "TailscaleWatch: 介面列舉失敗 " + t);
        }
        return null;
    }

    /**
     * 由 NotificationService 定期呼叫。非阻塞（自開背景執行緒）——介面列舉和
     * Shizuku 都不該壓在 main looper 上。
     */
    static void tick(Context ctx) {
        if (!BUSY.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                String ip = tailnetIp();
                if (ip != null) {
                    if (misses > 0) Log.i(TAG, "TailscaleWatch: 已恢復 " + ip);
                    misses = 0;
                    return;
                }
                misses++;
                Log.w(TAG, "TailscaleWatch: 沒有 tailnet 位址（連續 " + misses + " 次）");
                if (misses < MISS_THRESHOLD) return;

                long now = System.currentTimeMillis();
                if (now - lastRecover < RECOVER_COOLDOWN_MS) return;
                if (!AgentStarter.shizukuReady()) {
                    // 重開機後沒人點過 Shizuku 就是這條路。偵測仍然有效，
                    // status 問得出「活著但 Tailscale 斷了」，由 bot 去請人點一下。
                    Log.w(TAG, "TailscaleWatch: 需要回復但 Shizuku 未就緒");
                    return;
                }
                lastRecover = now;
                // monkey 帶 LAUNCHER category 是最不挑版本的叫醒方式：
                // 不必知道 Tailscale 的 Activity 元件名（改版會變）。
                AgentStarter.runShell(
                        "monkey -p " + TS_PKG + " -c android.intent.category.LAUNCHER 1",
                        4096, 15);
                // **不要記 monkey 的輸出**：三星改過的 monkey 會把參數回顯到 stderr
                // （"bash arg: -p" 之類），看起來像失敗，實際上指令是成功的。
                // 2026-09-02 實測就因此誤判過一次。改成等一下再看實際結果——
                // 這行 log 的用途是「在你看不到的失聯期間交代發生了什麼」，
                // 訊息誤導就完全失去價值。
                try { Thread.sleep(VERIFY_DELAY_MS); } catch (InterruptedException ignore) {}
                String after = tailnetIp();
                Log.i(TAG, "TailscaleWatch: 已拉起 Tailscale，"
                        + (VERIFY_DELAY_MS / 1000) + " 秒後 tailnet 位址＝"
                        + (after != null ? after : "仍無（下次冷卻後再試）"));
            } catch (Throwable t) {
                Log.e(TAG, "TailscaleWatch tick failed: " + t);
            } finally {
                BUSY.set(false);
            }
        }, "tailscale-watch").start();
    }
}
