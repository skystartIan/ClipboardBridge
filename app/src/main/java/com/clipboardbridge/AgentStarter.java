package com.clipboardbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * 用 Shizuku（shell uid）啟動平板剪貼簿 agent，取代 MacroDroid。
 *
 * - 完全不碰 UI / Activity（MacroDroid 會狂彈 DummyActivity 的病灶就在那）。
 * - 冪等：內嵌看門狗 shell（pidfile + /proc/PID/cmdline 硬去重，跟 start_clipagent.sh 同一套已驗證邏輯），
 *   呼幾次都只會有一個看門狗、一個 agent。
 * - 由 NotificationService（常駐、系統會可靠重啟）定期呼叫；BootReceiver / MainActivity 也各補一次。
 *
 * 前提：/data/local/tmp/clipagent.dex 存在（公司電腦 Sync 推過一次即持久保留；
 * /data/local/tmp 重開機不會清）。缺 dex 會回報 NODEX。
 */
final class AgentStarter {

    static final String TAG = "CBAgentStart";
    private static final String DEX = "/data/local/tmp/clipagent.dex";
    private static final String LOCK = "/data/local/tmp/clipagent_watch.pid";
    private static final String AGENT_CLASS = "com.clipboardbridge.agent.ClipAgent";
    static final String DEFAULT_REMOTE = "100.64.0.8";
    static final int DEFAULT_PORT = 27210;
    private static final int WATCH_INTERVAL = 20; // 秒

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private AgentStarter() {}

    static boolean shizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 冪等啟動；非阻塞（自開背景執行緒）。Shizuku 未就緒就安靜跳過，下次再試。 */
    static void ensureAgent(Context ctx) {
        if (!BUSY.compareAndSet(false, true)) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                if (!shizukuReady()) {
                    Log.w(TAG, "Shizuku 未就緒/未授權，略過（稍後重試）");
                    return;
                }
                String remote = DEFAULT_REMOTE;
                int port = DEFAULT_PORT;
                try {
                    SharedPreferences sp =
                            app.getSharedPreferences("clipagent", Context.MODE_PRIVATE);
                    remote = sp.getString("remote_ip", DEFAULT_REMOTE);
                    port = sp.getInt("port", DEFAULT_PORT);
                } catch (Throwable ignore) {}

                String result = runViaShizuku(buildCommand(remote, port));
                Log.d(TAG, "ensureAgent -> " + result + " (peer " + remote + ":" + port + ")");
            } catch (Throwable t) {
                Log.e(TAG, "ensureAgent failed: " + t);
            } finally {
                BUSY.set(false);
            }
        }, "clipagent-starter").start();
    }

    /**
     * 內嵌看門狗指令。外層 sh 做 pidfile 去重；setsid 出一個常駐 sh 迴圈，
     * agent 死了每 20s 重拉，去重靠 ps grep 單行 app_process（可靠）。
     */
    private static String buildCommand(String remote, int port) {
        // 內層迴圈（會被 setsid 成獨立 session，argv 尾帶 clipagent_watch 供 /proc 硬偵測）
        String inner =
                "while true; do "
              + "if ! ps -A -o ARGS 2>/dev/null | grep -q \"[C]lipAgent\"; then "
              + "CLASSPATH=" + DEX + " app_process / " + AGENT_CLASS + " " + remote + " " + port
              + " >/dev/null 2>&1 & "
              + "fi; sleep " + WATCH_INTERVAL + "; done";

        return
                "DEX=" + DEX + "; LOCK=" + LOCK + "; "
              + "if [ ! -f \"$DEX\" ]; then echo NODEX; exit 1; fi; "
              + "if [ -f \"$LOCK\" ]; then OP=$(cat \"$LOCK\" 2>/dev/null); "
              +   "if [ -n \"$OP\" ] && kill -0 \"$OP\" 2>/dev/null "
              +   "&& grep -qa clipagent_watch /proc/$OP/cmdline 2>/dev/null; "
              +   "then echo RUNNING; exit 0; fi; fi; "
              + "DETACH=setsid; command -v setsid >/dev/null 2>&1 || DETACH=nohup; "
              + "$DETACH sh -c '" + inner + "' clipagent_watch >/dev/null 2>&1 </dev/null & "
              + "echo $! > \"$LOCK\"; echo STARTED";
    }

    /** 用 Shizuku.newProcess 跑 sh -c，讀回第一行（STARTED/RUNNING/NODEX）。 */
    private static String runViaShizuku(String cmd) {
        Process p = null;
        try {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            // 明確包成 Object[]，避免 varargs 把 String[] 誤解成參數陣列本身
            Object proc = m.invoke(null, new Object[]{
                    new String[]{"sh", "-c", cmd}, null, null});
            if (!(proc instanceof Process)) return "ERR:not-a-process";
            p = (Process) proc;

            String firstLine = "";
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                if (line != null) firstLine = line.trim();
            }
            try { p.waitFor(); } catch (InterruptedException ignore) {}
            return firstLine.isEmpty() ? "OK" : firstLine;
        } catch (Throwable t) {
            return "ERR:" + t;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignore) {}
        }
    }
}
