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
    private String pcHost = null;

    @Override
    public void onCreate() {
        super.onCreate();
        new Thread(this::detectPcIp).start();
    }

    private void detectPcIp() {
        // 從 /proc/net/tcp6 取得 ADB 連線的 PC IP
        // ADB port 5555 = 0x15B3
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/net/tcp6"));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.contains("15B3")) continue;
                // 格式: sl local_addr:port rem_addr:port state
                // 找 state=01 (ESTABLISHED) 且 local 含 15B3
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) continue;
                String local = parts[1];  // local_addr:port
                String remote = parts[2]; // rem_addr:port
                String state = parts[3];  // state

                // state 01 = ESTABLISHED
                if (!"01".equals(state)) continue;
                // local port 是 15B3 (5555)
                if (!local.endsWith(":15B3")) continue;

                // remote addr 是 PC 的 IP（hex，little-endian）
                String remHex = remote.split(":")[0];
                // 取最後 8 個字元（IPv4 mapped in IPv6）
                if (remHex.length() >= 8) {
                    remHex = remHex.substring(remHex.length() - 8);
                }
                // 轉換 little-endian hex 到 IP
                int b1 = Integer.parseInt(remHex.substring(6, 8), 16);
                int b2 = Integer.parseInt(remHex.substring(4, 6), 16);
                int b3 = Integer.parseInt(remHex.substring(2, 4), 16);
                int b4 = Integer.parseInt(remHex.substring(0, 2), 16);
                String ip = b1 + "." + b2 + "." + b3 + "." + b4;

                if (!ip.equals("0.0.0.0") && !ip.startsWith("0.")) {
                    pcHost = ip;
                    Log.d(TAG, "Detected PC IP: " + pcHost);
                    br.close();
                    return;
                }
            }
            br.close();
            Log.w(TAG, "Could not detect PC IP from tcp6");
        } catch (Exception e) {
            Log.e(TAG, "detectPcIp error: " + e.getMessage());
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (pcHost == null) {
            new Thread(this::detectPcIp).start();
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
        new Thread(() -> {
            try {
                Socket socket = new Socket(pcHost, PC_PORT);
                socket.setSoTimeout(3000);
                OutputStream out = socket.getOutputStream();
                out.write((data + "\n").getBytes("UTF-8"));
                out.flush();
                socket.close();
            } catch (Exception e) {
                Log.w(TAG, "sendToPC(" + pcHost + ") failed: " + e.getMessage());
                pcHost = null;
                new Thread(this::detectPcIp).start();
            }
        }).start();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) { }
}
