package com.clipboardbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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

            if (isShizukuAvailable()) {
                success = setClipboardViaShizuku(uri);
                Log.d(ClipboardReceiver.TAG, "Shizuku result: " + success);
            }

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
            IBinder originalBinder = SystemServiceHelper.getSystemService("clipboard");
            if (originalBinder == null) {
                Log.e(ClipboardReceiver.TAG, "Cannot get clipboard binder");
                return false;
            }

            ShizukuBinderWrapper binderWrapper = new ShizukuBinderWrapper(originalBinder);
            Class<?> stubClass = Class.forName("android.content.IClipboard$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            Object iClipboard = asInterface.invoke(null, binderWrapper);

            // 列出所有方法，找到正確的 setPrimaryClip
            Log.d(ClipboardReceiver.TAG, "=== IClipboard methods ===");
            for (Method m : iClipboard.getClass().getMethods()) {
                if (m.getName().contains("setPrimary") || m.getName().contains("Clip")) {
                    StringBuilder sb = new StringBuilder(m.getName() + "(");
                    for (Class<?> p : m.getParameterTypes()) sb.append(p.getSimpleName()).append(",");
                    sb.append(")");
                    Log.d(ClipboardReceiver.TAG, "  method: " + sb);
                }
            }

            ClipData clip = ClipData.newUri(getContentResolver(), "image", imageUri);

            // 嘗試所有可能的簽章
            for (Method m : iClipboard.getClass().getMethods()) {
                if (!m.getName().equals("setPrimaryClip") && !m.getName().equals("setPrimaryClipAsPackage")) continue;
                
                Class<?>[] params = m.getParameterTypes();
                Log.d(ClipboardReceiver.TAG, "Trying: " + m.getName() + " params=" + params.length);
                
                try {
                    switch (params.length) {
                        case 1:
                            m.invoke(iClipboard, clip);
                            Log.d(ClipboardReceiver.TAG, "✓ " + m.getName() + "(1)");
                            return true;
                        case 2:
                            m.invoke(iClipboard, clip, "com.android.shell");
                            Log.d(ClipboardReceiver.TAG, "✓ " + m.getName() + "(2)");
                            return true;
                        case 3:
                            m.invoke(iClipboard, clip, "com.android.shell", 0);
                            Log.d(ClipboardReceiver.TAG, "✓ " + m.getName() + "(3)");
                            return true;
                        case 4:
                            m.invoke(iClipboard, clip, "com.android.shell", 0, "com.android.shell");
                            Log.d(ClipboardReceiver.TAG, "✓ " + m.getName() + "(4)");
                            return true;
                        case 5:
                            m.invoke(iClipboard, clip, "com.android.shell", "com.android.shell", 0, "com.android.shell");
                            Log.d(ClipboardReceiver.TAG, "✓ " + m.getName() + "(5)");
                            return true;
                    }
                } catch (Exception e) {
                    Log.w(ClipboardReceiver.TAG, m.getName() + "(" + params.length + ") failed: " + e.getMessage());
                }
            }

            return false;

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
