package com.clipboardbridge;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.OutputStream;
import java.net.Socket;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "CBNotification";
    private static final int PC_PORT = 9999;
    private static final String IP_FILE = "/sdcard/cb_pc_ip.txt";
    private String pcHost = null;

    @Override
    public void onCreate() {
        super.onCreate();
        detectPcIp();
    }

    private void detectPcIp() {
        try {
            BufferedReader br = new BufferedReader(new FileReader(IP_FILE));
            String ip = br.readLine();
            br.close();
            if (ip != null && !ip.trim().isEmpty()) {
                pcHost = ip.trim();
                Log.d(TAG, "PC IP loaded: " + pcHost);
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot read PC IP file: " + e.getMessage());
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 每次通知都重新讀 IP（確保最新）
        if (pcHost == null) detectPcIp();
        if (pcHost == null) return;

        try {
            String pkg = sbn.getPackageName();
            if (pkg.startsWith("android") || pkg.equals("com.android.systemui")) return;

            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;

            String title = extras.getString(Notification.EXTRA_TITLE, "");
            CharSequence textSeq = extras.getCharSequence(Notification.EXTRA_TEXT);
            String text = textSeq != null ? textSeq.toString() : "";

            if (title.isEmpty() && text.isEmpty()) return;

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
