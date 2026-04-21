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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "CBNotification";
    private static final String PC_HOST = "localhost";
    private static final int PC_PORT = 9999;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();

            // 過濾系統通知
            if (pkg.startsWith("android") || pkg.equals("com.android.systemui")) return;

            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;

            String title = extras.getString(Notification.EXTRA_TITLE, "");
            CharSequence textSeq = extras.getCharSequence(Notification.EXTRA_TEXT);
            String text = textSeq != null ? textSeq.toString() : "";

            if (title.isEmpty() && text.isEmpty()) return;

            // 取得 App 名稱
            String appName = pkg;
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                appName = pm.getApplicationLabel(ai).toString();
            } catch (Exception e) { /* 用 package name */ }

            // 取得 App 圖示（轉 base64）
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
            } catch (Exception e) { /* 沒有圖示 */ }

            // 組成 JSON
            JSONObject json = new JSONObject();
            json.put("pkg", pkg);
            json.put("app", appName);
            json.put("title", title);
            json.put("text", text);
            json.put("icon", iconB64);
            json.put("time", System.currentTimeMillis());

            Log.d(TAG, "Notification: " + appName + " - " + title);

            // 傳送到 PC（透過 adb reverse 的 localhost:9999）
            sendToPC(json.toString());

        } catch (Exception e) {
            Log.e(TAG, "onNotificationPosted error: " + e.getMessage());
        }
    }

    private void sendToPC(final String data) {
        new Thread(() -> {
            try {
                Socket socket = new Socket(PC_HOST, PC_PORT);
                OutputStream out = socket.getOutputStream();
                out.write((data + "\n").getBytes("UTF-8"));
                out.flush();
                socket.close();
            } catch (Exception e) {
                Log.w(TAG, "sendToPC failed: " + e.getMessage());
            }
        }).start();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 不需要處理
    }
}
