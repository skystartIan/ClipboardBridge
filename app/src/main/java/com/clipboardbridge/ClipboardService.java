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
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class ClipboardService extends Service {

    private static final String CHANNEL_ID = "cb_bridge";
    private static final int NOTIF_ID = 1001;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Processing..."));

        // 啟用 Hidden API bypass
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("");
        }

        if (intent != null) {
            String b64 = intent.getStringExtra(ClipboardReceiver.EXTRA_IMAGE_DATA);
            if (b64 != null && !b64.isEmpty()) {
                handleBase64(b64);
            }
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    private void handleBase64(String b64) {
        Log.d(ClipboardReceiver.TAG, "handleBase64: length=" + b64.length());

        try {
            byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) { toast("Error: decode failed"); return; }

            Log.d(ClipboardReceiver.TAG, "Bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());

            Uri uri = saveToMediaStore(bitmap);
            bitmap.recycle();
            if (uri == null) { toast("Error: MediaStore failed"); return; }

            boolean success = false;

            // 嘗試 Shizuku
            if (isShizukuAvailable()) {
                success = setClipboardViaShizuku(uri);
                Log.d(ClipboardReceiver.TAG, "Shizuku result: " + success);
            }

            // 備用：一般 ClipboardManager（在前景 Service 中有效）
            if (!success) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newUri(getContentResolver(), "image", uri));
                    success = true;
                    Log.d(ClipboardReceiver.TAG, "✓ Set via ClipboardManager");
                }
            }

            if (success) toast("✓ 圖片已就緒，按 Ctrl+V 或長按貼上");

        } catch (Exception e) {
            Log.e(ClipboardReceiver.TAG, "handleBase64 error: " + e.getMessage());
            toast("Error: " + e.getMessage());
        }
    }

    private boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder() &&
                   Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean setClipboardViaShizuku(Uri imageUri) {
        try {
            android.os.IBinder originalBinder = SystemServiceHelper.getSystemService("clipboard");
            if (originalBinder == null) return false;

            ShizukuBinderWrapper binderWrapper = new ShizukuBinderWrapper(originalBinder);
            Class<?> stubClass = Class.forName("android.content.IClipboard$Stub");
            Method asInterface = stubClass.getMethod("asInterface", android.os.IBinder.class);
            Object iClipboard = asInterface.invoke(null, binderWrapper);

            // 用 HiddenApiBypass 取得所有方法
            List<Method> methods = (List<Method>) HiddenApiBypass.getDeclaredMethods(iClipboard.getClass());
            Log.d(ClipboardReceiver.TAG, "Shizuku methods: " + methods.size());

            ClipData clip = ClipData.newUri(getContentResolver(), "image", imageUri);

            for (Method m : methods) {
                String name = m.getName().toLowerCase();
                if (!name.contains("set")) continue;
                if (!name.contains("clip") && !name.contains("primary")) continue;

                m.setAccessible(true);
                Class<?>[] params = m.getParameterTypes();
                Log.d(ClipboardReceiver.TAG, "Trying: " + m.getName() + "(" + params.length + ")");

                try {
                    switch (params.length) {
                        case 1: m.invoke(iClipboard, clip); break;
                        case 2: m.invoke(iClipboard, clip, "com.android.shell"); break;
                        case 3: m.invoke(iClipboard, clip, "com.android.shell", 0); break;
                        case 4: m.invoke(iClipboard, clip, "com.android.shell", 0, "com.android.shell"); break;
                        case 5: m.invoke(iClipboard, clip, "com.android.shell", "com.android.shell", 0, "com.android.shell"); break;
                        default: continue;
                    }
                    Log.d(ClipboardReceiver.TAG, "✓ Shizuku " + m.getName() + "(" + params.length + ")");
                    return true;
                } catch (Exception e) {
                    Log.w(ClipboardReceiver.TAG, m.getName() + " failed: " + e.getMessage());
                }
            }
            return false;

        } catch (Exception e) {
            Log.e(ClipboardReceiver.TAG, "Shizuku error: " + e.getMessage());
            return false;
        }
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
