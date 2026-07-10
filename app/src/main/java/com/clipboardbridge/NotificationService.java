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
                    Log.d(TAG, "PC IP received: " + pcHost);
                }
            }
        }
    };

    private ImageServer imageServer;

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
        // 開機後 Shizuku 可能還沒起來 → 先試一次，再每 60s 重試（冪等，去重靠看門狗）
        agentHandler.post(agentTick);
        Log.d(TAG, "NotificationService started, waiting for PC IP...");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(ipReceiver); } catch (Exception e) { }
        try { if (imageServer != null) imageServer.stop(); } catch (Exception e) { }
        try { agentHandler.removeCallbacks(agentTick); } catch (Exception e) { }
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

            String title = extras.getString(Notification.EXTRA_TITLE, "");
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
