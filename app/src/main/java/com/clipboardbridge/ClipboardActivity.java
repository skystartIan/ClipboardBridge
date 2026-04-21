package com.clipboardbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.io.OutputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class ClipboardActivity extends Activity {

    static final String EXTRA_IMAGE_DATA = "image_data";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String b64 = getIntent().getStringExtra(EXTRA_IMAGE_DATA);
        if (b64 == null || b64.isEmpty()) { finish(); return; }

        try {
            byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) { toast("Error: decode failed"); finish(); return; }

            Uri uri = saveToMediaStore(bitmap);
            bitmap.recycle();
            if (uri == null) { toast("Error: MediaStore failed"); finish(); return; }

            boolean success = false;

            // 嘗試 Shizuku + ShizukuBinderWrapper
            if (isShizukuAvailable()) {
                success = setClipboardViaShizuku(uri);
                Log.d(ClipboardReceiver.TAG, "Shizuku result: " + success);
            }

            // 備用：一般 ClipboardManager
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
            Log.e(ClipboardReceiver.TAG, "Error: " + e.getMessage());
            toast("Error: " + e.getMessage());
        }

        finish();
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
            // 用 ShizukuBinderWrapper 以 shell 身份取得 clipboard binder
            IBinder originalBinder = SystemServiceHelper.getSystemService("clipboard");
            if (originalBinder == null) {
                Log.e(ClipboardReceiver.TAG, "Cannot get clipboard binder");
                return false;
            }

            ShizukuBinderWrapper binderWrapper = new ShizukuBinderWrapper(originalBinder);

            // 用反射取得 IClipboard.Stub.asInterface
            Class<?> stubClass = Class.forName("android.content.IClipboard$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            Object iClipboard = asInterface.invoke(null, binderWrapper);

            // 建立 ClipData
            ClipData clip = ClipData.newUri(getContentResolver(), "image", imageUri);

            // 呼叫 setPrimaryClipAsPackage 或 setPrimaryClip
            // Android 版本不同，方法簽章不同，逐一嘗試
            boolean called = false;

            // Android 12+ 
            try {
                Method m = iClipboard.getClass().getMethod(
                    "setPrimaryClipAsPackage", ClipData.class, String.class, int.class, String.class);
                m.invoke(iClipboard, clip, "com.android.shell", 0, "com.android.shell");
                called = true;
                Log.d(ClipboardReceiver.TAG, "✓ setPrimaryClipAsPackage");
            } catch (Exception e) {
                Log.w(ClipboardReceiver.TAG, "setPrimaryClipAsPackage failed: " + e.getMessage());
            }

            // Android 10-11
            if (!called) {
                try {
                    Method m = iClipboard.getClass().getMethod(
                        "setPrimaryClip", ClipData.class, String.class, int.class);
                    m.invoke(iClipboard, clip, "com.android.shell", 0);
                    called = true;
                    Log.d(ClipboardReceiver.TAG, "✓ setPrimaryClip(3 args)");
                } catch (Exception e) {
                    Log.w(ClipboardReceiver.TAG, "setPrimaryClip(3) failed: " + e.getMessage());
                }
            }

            // fallback
            if (!called) {
                try {
                    Method m = iClipboard.getClass().getMethod(
                        "setPrimaryClip", ClipData.class, String.class);
                    m.invoke(iClipboard, clip, "com.android.shell");
                    called = true;
                    Log.d(ClipboardReceiver.TAG, "✓ setPrimaryClip(2 args)");
                } catch (Exception e) {
                    Log.w(ClipboardReceiver.TAG, "setPrimaryClip(2) failed: " + e.getMessage());
                }
            }

            return called;

        } catch (Exception e) {
            Log.e(ClipboardReceiver.TAG, "Shizuku failed: " + e.getMessage());
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
