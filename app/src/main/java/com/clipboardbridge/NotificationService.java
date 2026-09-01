package com.clipboardbridge;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.Socket;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "CBNotification";
    private static final int PC_PORT = 9999;
    private static final String ACTION_SET_PC_IP = "com.clipboardbridge.SET_PC_IP";
    private static final String PREFS = "notif";
    private static final String KEY_PC_IP = "pc_ip";
    private static final String IP_FILE = "/sdcard/cb_pc_ip.txt";
    private String pcHost = null;
    private final java.util.Map<String, Long> recentNotifs = new java.util.HashMap<>();
    private static final long DEDUP_MS = 2000; // 2秒內相同通知只傳一次

    private final BroadcastReceiver ipReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_SET_PC_IP.equals(intent.getAction())) {
                String ip = intent.getStringExtra("pc_ip");
                if (ip != null && !ip.isEmpty()) {
                    pcHost = ip.trim();
                    savePcIp(pcHost);
                    Log.d(TAG, "PC IP received: " + pcHost);
                }
            }
        }
    };

    private ImageServer imageServer;
    private CommandServer commandServer;

    private void savePcIp(String ip) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_PC_IP, ip).apply();
        } catch (Throwable t) {
            Log.w(TAG, "save pc_ip failed: " + t);
        }
    }

    /**
     * 服務被重建時（adb install -r、force-stop、系統回收後重啟 listener）記憶體裡的
     * pcHost 會歸零，如果只等 Sync 下次連線廣播，中間所有通知都會被靜默丟掉——PC 端
     * 完全沒動靜，看起來就像整個壞了。所以起來先把上次的 IP 撈回來。
     *
     * 以 SharedPreferences 為主（不需要任何權限）；沒有才退回 Sync 推上來的
     * /sdcard/cb_pc_ip.txt——那是從舊版升上來、prefs 還是空的時候唯一的來源。
     * IP 換了也不用擔心：Sync 每次連線都會重送廣播覆蓋掉。
     */
    private String loadPcIp() {
        try {
            String ip = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_PC_IP, null);
            if (ip != null && !ip.trim().isEmpty()) return ip.trim();
        } catch (Throwable ignore) { }
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader(IP_FILE));
            String line = r.readLine();
            if (line != null && !line.trim().isEmpty()) {
                String ip = line.trim();
                // 順手存進 prefs：之後就不必再依賴這個檔案存在／儲存權限還在
                savePcIp(ip);
                return ip;
            }
        } catch (Throwable ignore) {
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable ignore) { }
            }
        }
        return null;
    }

    // 平板獨立自啟：定期用 Shizuku 確保 clip agent 活著（取代 MacroDroid）
    private static final long AGENT_CHECK_MS = 60_000;
    private final android.os.Handler agentHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable agentTick = new Runnable() {
        @Override
        public void run() {
            try { AgentStarter.ensureAgent(NotificationService.this); } catch (Throwable ignore) {}
            agentHandler.postDelayed(this, AGENT_CHECK_MS);
        }
    };

    // Tailscale 心跳：掉線時自己拉回來，遠端維護的最後一道保險（見 TailscaleWatch）
    private final Runnable tailscaleTick = new Runnable() {
        @Override
        public void run() {
            try { TailscaleWatch.tick(NotificationService.this); } catch (Throwable ignore) {}
            agentHandler.postDelayed(this, TailscaleWatch.CHECK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(ACTION_SET_PC_IP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ipReceiver, filter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(ipReceiver, filter);
        }
        // 圖片剪貼簿 TCP 直送伺服器（跟著這個常駐 listener 的生命週期）
        imageServer = new ImageServer(this);
        imageServer.start();
        // 檔案拖放投放區（overlay；由 PC 透過 ImageServer 控制訊框開關）
        try { DropZone.init(this); } catch (Throwable t) {
            Log.w(TAG, "DropZone init failed: " + t);
        }
        // VPS → 平板的遠端指令通道（tailnet 來源檢查 + secret，見 CommandServer）
        try {
            commandServer = new CommandServer(this);
            commandServer.start();
        } catch (Throwable t) {
            Log.w(TAG, "CommandServer init failed: " + t);
        }
        // 開機後 Shizuku 可能還沒起來 → 先試一次，再每 60s 重試（冪等，去重靠看門狗）
        agentHandler.post(agentTick);
        agentHandler.post(tailscaleTick);
        pcHost = loadPcIp();
        Log.d(TAG, pcHost != null
                ? "NotificationService started, PC IP restored: " + pcHost
                : "NotificationService started, waiting for PC IP...");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(ipReceiver); } catch (Exception e) { }
        try { if (imageServer != null) imageServer.stop(); } catch (Exception e) { }
        try { if (commandServer != null) commandServer.stop(); } catch (Exception e) { }
        try { agentHandler.removeCallbacks(agentTick); } catch (Exception e) { }
        try { agentHandler.removeCallbacks(tailscaleTick); } catch (Exception e) { }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (pcHost == null) {
            Log.w(TAG, "PC IP not set, dropping notification");
            return;
        }

        try {
            String pkg = sbn.getPackageName();
            if (pkg.startsWith("android") || pkg.equals("com.android.systemui")) return;

            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;

            // 一律用 getCharSequence：setContentTitle() 收的是 CharSequence，
            // 有些 App（Spotify 的媒體通知）塞的是 SpannableString 而不是 String，
            // 這時 getString() 會回傳預設值 → 標題整個變空的（歌名就是這樣掉的）。
            CharSequence titleSeq = extras.getCharSequence(Notification.EXTRA_TITLE);
            String title = titleSeq != null ? titleSeq.toString() : "";
            CharSequence textSeq = extras.getCharSequence(Notification.EXTRA_TEXT);
            String text = textSeq != null ? textSeq.toString() : "";

            if (title.isEmpty() && text.isEmpty()) return;

            // 過濾靜音通知（只傳重要度 >= DEFAULT 的通知）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
                    android.app.NotificationChannel ch = nm.getNotificationChannel(notification.getChannelId());
                    if (ch != null && ch.getImportance() <= android.app.NotificationManager.IMPORTANCE_LOW) {
                        return;
                    }
                } catch (Exception e) { }
            }

            String appName = pkg;
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                appName = pm.getApplicationLabel(ai).toString();
            } catch (Exception e) { }

            JSONObject json = new JSONObject();
            json.put("pkg", pkg);
            json.put("app", appName);
            json.put("title", title);
            json.put("text", text);
            json.put("time", System.currentTimeMillis());

            // 去重複：同一則通知 2 秒內只傳一次
            String dedupKey = pkg + "|" + title + "|" + text;
            long now = System.currentTimeMillis();
            synchronized (recentNotifs) {
                Long lastTime = recentNotifs.get(dedupKey);
                if (lastTime != null && now - lastTime < DEDUP_MS) return;
                recentNotifs.put(dedupKey, now);
                // 清理過期的記錄
                recentNotifs.entrySet().removeIf(e -> now - e.getValue() > 10000);
            }

            Log.d(TAG, "Notification: " + appName + " - " + title + " -> " + pcHost);
            sendToPC(json.toString());

        } catch (Exception e) {
            Log.e(TAG, "onNotificationPosted error: " + e.getMessage());
        }
    }

    private void sendToPC(final String data) {
        final String host = pcHost;
        new Thread(() -> {
            try {
                Socket socket = new Socket(host, PC_PORT);
                socket.setSoTimeout(3000);
                OutputStream out = socket.getOutputStream();
                out.write((data + "\n").getBytes("UTF-8"));
                out.flush();
                socket.close();
            } catch (Exception e) {
                Log.w(TAG, "sendToPC(" + host + ") failed: " + e.getMessage());
            }
        }).start();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) { }
}
