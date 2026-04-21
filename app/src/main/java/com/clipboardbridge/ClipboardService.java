package com.clipboardbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.OutputStream;

public class ClipboardService extends Service {

    private static final String CHANNEL_ID = "cb_bridge";
    private static final int NOTIF_ID = 1001;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Processing..."));

        if (intent != null) {
            String b64 = intent.getStringExtra(ClipboardReceiver.EXTRA_IMAGE_DATA);
            if (b64 != null && !b64.isEmpty()) {
                handleBase64(this, b64);
            }
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    // 靜態方法，讓 BroadcastReceiver 在 Service 啟動失敗時也能呼叫
    public static void processInBackground(Context context, String b64) {
        new Thread(() -> handleBase64(context, b64)).start();
    }

    private static void handleBase64(Context context, String b64) {
        Log.d(ClipboardReceiver.TAG, "handleBase64: length=" + b64.length());

        try {
            byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                showToast(context, "Error: decode failed");
                return;
            }

            Log.d(ClipboardReceiver.TAG, "Bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());

            Uri uri = saveToMediaStore(context, bitmap);
            bitmap.recycle();
            if (uri == null) {
                showToast(context, "Error: MediaStore failed");
                return;
            }

            // 設定剪貼簿（需要在主執行緒）
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newUri(context.getContentResolver(), "image", uri));
                        Log.d(ClipboardReceiver.TAG, "✓ Set via ClipboardManager: " + uri);
                        showToast(context, "✓ 圖片已就緒，按 Ctrl+V 或長按貼上");
                    }
                } catch (Exception e) {
                    Log.e(ClipboardReceiver.TAG, "setPrimaryClip failed: " + e.getMessage());
                }
            });

        } catch (Exception e) {
            Log.e(ClipboardReceiver.TAG, "handleBase64 error: " + e.getMessage());
        }
    }

    private static Uri saveToMediaStore(Context context, Bitmap bitmap) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "cb_" + System.currentTimeMillis() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ClipboardBridge");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) return null;
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            Log.e(ClipboardReceiver.TAG, "saveToMediaStore failed: " + e.getMessage());
            if (uri != null) context.getContentResolver().delete(uri, null, null);
            return null;
        }
    }

    private static void showToast(Context context, String msg) {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context.getApplicationContext(), msg, Toast.LENGTH_SHORT).show()
        );
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Clipboard Bridge", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clipboard Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }
}
