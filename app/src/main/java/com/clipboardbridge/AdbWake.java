package com.clipboardbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * wake_adb：不靠 Shizuku、也不靠 adb，讓平板自己把 AutoADB 救起來。
 *
 * 為什麼需要它：重開機後 AutoADB 偶爾沒起來 → 5555 沒開 → Shizuku 也起不來
 * （它要靠 adb 啟動）→ CommandServer 的 shell 指令全失效，三者互相卡住，以前
 * 只能有人到平板上手動點 AutoADB。ClipboardBridge 的無障礙服務在 Android
 * 「背景啟動 Activity」的豁免清單上，可以替人點這一下。
 *
 * 流程（AutoADB 原始碼在 D:\Trial\SMX230\adb-auto-enable-main）：
 *   1. 拉起 AutoADB 的 MainActivity。它的 onCreate 只會起前景服務＋:9093 網頁，
 *      傳的是 boot_config=false，**不會**動 adb——光開 App 不夠。
 *   2. 等 :9093 開了，打 GET /api/test。那支 API 會照開機流程整套重跑一次：
 *      等 WiFi → 等 30 秒 → 打開無線偵錯 → mDNS／掃埠找到配對埠 → 切到 5555。
 *      約 50～60 秒。/api/switch 不行：它不會先打開無線偵錯。
 *   3. 等本機 5555 打開才算成功。
 *
 * 螢幕關著時：Activity 可能被鎖屏擋住不建立，那就點亮螢幕再試一次（鎖屏照舊，
 * 不會解鎖）。
 */
final class AdbWake {

    private static final String TAG = ClipboardReceiver.TAG;
    static final String PKG = "com.tpn.adbautoenable";
    private static final String ACTIVITY = PKG + ".MainActivity";
    private static final int WEB_PORT = 9093;
    private static final int ADB_PORT = 5555;

    /** 拉起 Activity 後等網頁出現的時間。 */
    private static final int WEB_WAIT_S = 12;
    /** /api/test 之後等 5555 的時間（AutoADB 自己要跑 50～60 秒）。 */
    static final int ADB_WAIT_S = 110;
    /** 呼叫端 socket 要給的總時間。 */
    static final int TOTAL_TIMEOUT_S = WEB_WAIT_S * 2 + ADB_WAIT_S + 20;

    private static final AtomicBoolean busy = new AtomicBoolean(false);

    private AdbWake() { }

    static JSONObject run(Context ctx) {
        JSONObject j = new JSONObject();
        JSONArray steps = new JSONArray();
        long t0 = SystemClock.elapsedRealtime();
        if (!busy.compareAndSet(false, true)) {
            return put(j, "ok", false, "error", "已有一個 wake_adb 在跑");
        }
        PowerManager.WakeLock screenLock = null;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            boolean screenOn = pm != null && pm.isInteractive();
            j.put("screen_on_before", screenOn);
            j.put("a11y", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && ShotService.available());

            if (portOpen(ADB_PORT)) {
                steps.put("5555 本來就開著，什麼都沒做");
                j.put("ok", true).put("already", true);
                return j;
            }

            boolean webUp = portOpen(WEB_PORT);
            steps.put(webUp ? "9093 已經開著，跳過開 App" : "9093 沒開，去拉起 AutoADB");
            if (!webUp) {
                steps.put("launch#1: " + launch(ctx));
                webUp = waitPort(WEB_PORT, WEB_WAIT_S);
                steps.put("等 9093 " + WEB_WAIT_S + "s: " + webUp);
            }
            if (!webUp && pm != null && !pm.isInteractive()) {
                // 鎖屏可能擋下了 Activity → 點亮螢幕重來。不解鎖，只是亮。
                screenLock = wakeScreen(pm);
                j.put("woke_screen", true);
                steps.put("點亮螢幕後 launch#2: " + launch(ctx));
                webUp = waitPort(WEB_PORT, WEB_WAIT_S);
                steps.put("等 9093 " + WEB_WAIT_S + "s: " + webUp);
            }
            if (!webUp) {
                j.put("ok", false).put("error", "AutoADB 的 9093 沒有起來（Activity 可能被擋了）");
                return j;
            }

            // 把拉起來的畫面收掉，別擋著正在用平板的人。前景服務會留著。
            SystemClock.sleep(1500);
            if (ShotService.backIfTop(PKG)) steps.put("已按返回收掉 AutoADB 畫面");

            int code = httpGet("http://127.0.0.1:" + WEB_PORT + "/api/test");
            steps.put("GET /api/test → " + code);
            if (code != 200) {
                j.put("ok", false).put("error", "/api/test 回 " + code);
                return j;
            }

            boolean adb = waitPort(ADB_PORT, ADB_WAIT_S);
            steps.put("等 5555 " + ADB_WAIT_S + "s: " + adb);
            j.put("ok", adb);
            if (!adb) j.put("error", "AutoADB 跑完了但 5555 沒開，看 :9093/api/logs");
            return j;
        } catch (Throwable t) {
            Log.e(TAG, "AdbWake failed", t);
            put(j, "ok", false, "error", t.toString());
            return j;
        } finally {
            if (screenLock != null) {
                try { screenLock.release(); } catch (Throwable ignore) { }
            }
            busy.set(false);
            put(j, "steps", steps, "elapsed_s", (SystemClock.elapsedRealtime() - t0) / 1000);
            Log.i(TAG, "AdbWake: " + j);
        }
    }

    /** 回傳一行描述這次怎麼啟動、有沒有丟例外（被擋時多半不會丟，只能看 9093 有沒有起來）。 */
    private static String launch(Context ctx) {
        Intent it = ctx.getPackageManager().getLaunchIntentForPackage(PKG);
        String how = "launchIntent";
        if (it == null) {
            // 套件可見性被過濾或 launcher 項目消失時，直接指名元件
            it = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(PKG, ACTIVITY));
            how = "explicit";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ShotService.available()) {
            String err = ShotService.launch(it);
            return how + " via a11y " + (err == null ? "ok" : err);
        }
        try {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return how + " via app ctx ok（沒有 a11y，多半會被背景限制擋掉）";
        } catch (Throwable t) {
            return how + " via app ctx " + t;
        }
    }

    @SuppressWarnings("deprecation")
    private static PowerManager.WakeLock wakeScreen(PowerManager pm) {
        // SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP 雖然標成 deprecated，仍是一般 App
        // 唯一能把螢幕點亮的方法。帶 timeout，就算 release 漏掉也會自己放掉。
        PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "clipboardbridge:wake_adb");
        wl.acquire(30_000);
        SystemClock.sleep(800);
        return wl;
    }

    private static boolean waitPort(int port, int seconds) {
        long end = SystemClock.elapsedRealtime() + seconds * 1000L;
        while (SystemClock.elapsedRealtime() < end) {
            if (portOpen(port)) return true;
            SystemClock.sleep(1000);
        }
        return portOpen(port);
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            int code = c.getResponseCode();
            try { c.getInputStream().close(); } catch (Exception ignore) { }
            return code;
        } catch (Exception e) {
            Log.w(TAG, "AdbWake GET " + url + ": " + e);
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static JSONObject put(JSONObject j, Object... kv) {
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) j.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignore) { }
        return j;
    }
}
