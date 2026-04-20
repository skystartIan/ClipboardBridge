package com.clipboardbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class ClipboardService extends Service {

    private static final String CHANNEL_ID = "cb_bridge";
    private static final int NOTIF_ID = 1001;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Processing..."));

        if (intent != null) {
            String path = intent.getStringExtra(ClipboardReceiver.EXTRA_IMAGE_PATH);
            if (path != null) handleImage(path);
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    private void handleImage(String imagePath) {
        Log.d(ClipboardReceiver.TAG, "handleImage: " + imagePath);

        File file = new File(imagePath);
        if (!file.exists()) {
            Log.e(ClipboardReceiver.TAG, "File not found: " + imagePath);
            toast("Error: file not found: " + imagePath);
            return;
        }

        Bitmap bitmap = null;
        try {
            // 用 FileInputStream 直接讀，避免路徑權限問題
            InputStream is = new FileInputStream(file);
            bitmap = BitmapFactory.decodeStream(is);
            is.close();
        } catch (Exception e) {
            Log.e(ClipboardReceiver.TAG, "decode failed", e);
            toast("Error: decode failed: " + e.getMessage());
            return;
        }

        if (bitmap == null) {
            toast("Error: bitmap is null");
            return;
        }

        Uri uri = saveToMediaStore(bitmap);
        bitmap.recycle();

        if (uri == null) {
            toast("Error: MediaStore save failed");
            return;
        }

        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;

        cm.setPrimaryClip(ClipData.newUri(getContentResolver(), "image", uri));
        Log.d(ClipboardReceiver.TAG, "Image set to clipboard: " + uri);
        toast("Image ready to paste ✓");
    }

    private Uri saveToMediaStore(Bitmap bitmap) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "cb_" + System.currentTimeMillis() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ClipboardBridge");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;

            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) return null;
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }

            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;

        } catch (Exception e) {
            Log.e(ClipboardReceiver.TAG, "saveToMediaStore failed", e);
            if (uri != null) getContentResolver().delete(uri, null, null);
            return null;
        }
    }

    private void toast(final String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show()
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