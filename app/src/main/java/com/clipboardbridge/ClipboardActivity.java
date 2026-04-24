package com.clipboardbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class ClipboardActivity extends Activity {

    static final String EXTRA_IMAGE_DATA = "image_data";
    static final String CB_FILE_PATH = "/sdcard/cb_b64.txt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("");
        }

        // 優先從 Intent 讀，沒有就從檔案讀（繞過 Binder 1MB 限制）
        String b64 = getIntent().getStringExtra(EXTRA_IMAGE_DATA);

        if (b64 == null || b64.isEmpty()) {
            Log.d(ClipboardReceiver.TAG, "image_data empty in Intent, trying file: " + CB_FILE_PATH);
            try {
                File file = new File(CB_FILE_PATH);
                if (file.exists()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        byte[] bytes = Files.readAllBytes(file.toPath());
                        b64 = new String(bytes, "UTF-8").trim();
                    } else {
                        // API < 26 fallback
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.FileReader(file));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        reader.close();
                        b64 = sb.toString().trim();
                    }
                    file.delete();
                    Log.d(ClipboardReceiver.TAG, "Read from file, length=" + b64.length());
                } else {
                    Log.e(ClipboardReceiver.TAG, "File not found: " + CB_FILE_PATH);
                }
            } catch (Exception e) {
                Log.e(ClipboardReceiver.TAG, "File read error: " + e.getMessage());
            }
        }

        if (b64 == null || b64.isEmpty()) {
            Log.e(ClipboardReceiver.TAG, "No image data from Intent or file");
            finish();
            return;
        }

        try {
            // 清理換行符，確保 Base64 解碼正確
            b64 = b64.replaceAll("[\\r\\n\\s]", "");
            Log.d(ClipboardReceiver.TAG, "Decoding b64, length=" + b64.length());
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            Log.d(ClipboardReceiver.TAG, "Decoded bytes=" + bytes.length);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                Log.e(ClipboardReceiver.TAG, "Bitmap decode failed");
                finish(); return;
            }
            Log.d(ClipboardReceiver.TAG, "Bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());

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
                    Log.d(ClipboardReceiver.TAG, "✓ Set via ClipboardManager: " + uri);
                }
            }

            if (success) Log.d(ClipboardReceiver.TAG, "✓ Clipboard set successfully");

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

            List<Method> methods = (List<Method>) HiddenApiBypass.getDeclaredMethods(iClipboard.getClass());
            ClipData clip = ClipData.newUri(getContentResolver(), "image", imageUri);

            for (Method m : methods) {
                String name = m.getName().toLowerCase();
                if (!name.contains("set")) continue;
                if (!name.contains("clip") && !name.contains("primary")) continue;

                m.setAccessible(true);
                Class<?>[] params = m.getParameterTypes();

                try {
                    switch (params.length) {
                        case 1: m.invoke(iClipboard, clip); break;
                        case 2: m.invoke(iClipboard, clip, "com.android.shell"); break;
                        case 3: m.invoke(iClipboard, clip, "com.android.shell", 0); break;
                        case 4: m.invoke(iClipboard, clip, "com.android.shell", 0, "com.android.shell"); break;
                        case 5: m.invoke(iClipboard, clip, "com.android.shell", "com.android.shell", 0, "com.android.shell"); break;
                        default: continue;
                    }
                    Log.d(ClipboardReceiver.TAG, "✓ " + m.getName() + "(" + params.length + ")");
                    return true;
                } catch (Exception e) {
                    Log.w(ClipboardReceiver.TAG, m.getName() + " failed: " + e.getMessage());
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
