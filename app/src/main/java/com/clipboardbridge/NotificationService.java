package com.clipboardbridge;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "CBNotification";
    private static final int PC_PORT = 9999;
    private static final String ACTION_SET_PC_IP = "com.clipboardbridge.SET_PC_IP";
    private String pcHost = null;

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

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(ACTION_SET_PC_IP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ipReceiver, filter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(ipReceiver, filter);
        }
        Log.d(TAG, "NotificationService started, waiting for PC IP...");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(ipReceiver); } catch (Exception e) { }
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

            String iconB64 = "";
            try {
                PackageManager pm = getPackageManager();
                Drawable icon = pm.getApplicationIcon(pkg);
                Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                icon.setBounds(0, 0, 96, 96);
                icon.draw(canvas);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 80, baos);
                iconB64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                bmp.recycle();
            } catch (Exception e) { }

            JSONObject json = new JSONObject();
            json.put("pkg", pkg);
            json.put("app", appName);
            json.put("title", title);
            json.put("text", text);
            json.put("icon", iconB64);
            json.put("time", System.currentTimeMillis());

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
                pcHost = null;
            }
        }).start();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) { }
}
