package com.clipboardbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.io.OutputStream;

import rikka.shizuku.Shizuku;

/**
 * 透明 Activity
 * 1. 先嘗試用 Shizuku 寫入 semclipboard（Samsung）
 * 2. 備用：用一般 ClipboardManager 寫入
 */
public class ClipboardActivity extends Activity {

    static final String EXTRA_IMAGE_DATA = "image_data";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String b64 = getIntent().getStringExtra(EXTRA_IMAGE_DATA);
        if (b64 == null || b64.isEmpty()) {
            Log.e(ClipboardReceiver.TAG, "No image_data");
            finish();
            return;
        }

        try {
            byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                toast("Error: cannot decode bitmap");
                finish();
                return;
            }

            // 存到 MediaStore 拿 URI
            Uri uri = saveToMediaStore(bitmap);
            bitmap.recycle();

            if (uri == null) {
                toast("Error: MediaStore failed");
                finish();
                return;
            }

            boolean success = false;

            // 嘗試用 Shizuku 執行 ADB 指令寫入剪貼簿
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

            if (success) {
                toast("✓ 圖片已就緒，按 Ctrl+V 或長按貼上");
            }

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
            Log.w(ClipboardReceiver.TAG, "Shizuku not available: " + e.getMessage());
            return false;
        }
    }

    private boolean setClipboardViaShizuku(Uri imageUri) {
        try {
            // 用 Shizuku 執行 content 指令寫入剪貼簿
            // 先取得 content:// 的實際路徑
            String uriStr = imageUri.toString();

            // 用 Shizuku 執行 am broadcast 給自己，但以 shell 身份
            // Shell 身份可以繞過 semclipboard 的權限限制
            String[] cmd = new String[]{
                "content", "insert",
                "--uri", "content://com.sec.android.semclipboardprovider/images",
                "--bind", "image_uri:s:" + uriStr
            };

            Shizuku.newProcess(cmd, null, null).waitFor();
            Log.d(ClipboardReceiver.TAG, "✓ Set via Shizuku semclipboard");
            return true;

        } catch (Exception e) {
            Log.e(ClipboardReceiver.TAG, "Shizuku semclipboard failed: " + e.getMessage());
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
            Log.e(ClipboardReceiver.TAG, "MediaStore failed: " + e.getMessage());
            if (uri != null) getContentResolver().delete(uri, null, null);
            return null;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
