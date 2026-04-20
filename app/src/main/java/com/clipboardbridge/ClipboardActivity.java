package com.clipboardbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class ClipboardActivity extends Activity {

    static final String EXTRA_IMAGE_DATA = "image_data";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String b64 = getIntent().getStringExtra(EXTRA_IMAGE_DATA);
        if (b64 == null || b64.isEmpty()) {
            finish();
            return;
        }

        try {
            byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) { toast("Error: decode failed"); finish(); return; }

            Uri uri = saveToMediaStore(bitmap);
            bitmap.recycle();
            if (uri == null) { toast("Error: MediaStore failed"); finish(); return; }

            boolean success = false;

            // 嘗試 Shizuku
            if (isShizukuAvailable()) {
                success = setClipboardViaShizuku(uri);
            }

            // 備用：一般 ClipboardManager
            if (!success) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newUri(getContentResolver(), "image", uri));
                    success = true;
                    Log.d(ClipboardReceiver.TAG, "✓ Set via ClipboardManager: " + uri);
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
            // 用 Shizuku 執行 shell 指令寫入 Samsung semclipboard
            String[] cmd = new String[]{
                "sh", "-c",
                "content insert --uri content://com.sec.android.semclipboardprovider/images " +
                "--bind image_uri:s:" + imageUri.toString()
            };

            ShizukuRemoteProcess process = Shizuku.newProcess(cmd, null, null);
            int exit = process.waitFor();
            Log.d(ClipboardReceiver.TAG, "Shizuku semclipboard exit=" + exit);

            // 讀取 stderr 看有沒有錯誤
            InputStream err = process.getErrorStream();
            byte[] buf = new byte[512];
            int n = err.read(buf);
            if (n > 0) {
                Log.w(ClipboardReceiver.TAG, "Shizuku stderr: " + new String(buf, 0, n));
            }

            return exit == 0;

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
