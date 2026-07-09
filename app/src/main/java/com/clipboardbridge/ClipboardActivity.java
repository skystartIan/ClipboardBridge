package com.clipboardbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
import java.nio.file.Files;

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
                        StringBuilder sb = new StringBuilder();
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.FileReader(file))) {
                            String line;
                            while ((line = reader.readLine()) != null) sb.append(line);
                        }
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
            Log.d(ClipboardReceiver.TAG, "Calling saveToMediaStore...");
            Uri uri = saveToMediaStore(bitmap);
            bitmap.recycle();
            if (uri == null) { toast("Error: MediaStore failed"); finish(); return; }

            boolean success = false;

            // 直接用 ClipboardManager（Shizuku 會卡住）
            Log.d(ClipboardReceiver.TAG, "Trying ClipboardManager...");
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            Log.d(ClipboardReceiver.TAG, "ClipboardManager: " + (cm != null ? "OK" : "NULL"));
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newUri(getContentResolver(), "image", uri));
                success = true;
                Log.d(ClipboardReceiver.TAG, "✓ Set via ClipboardManager: " + uri);
            }

            if (success) Log.d(ClipboardReceiver.TAG, "✓ Clipboard set successfully");

        } catch (Exception e) {
            Log.e(ClipboardReceiver.TAG, "Error: " + e.getMessage());
            toast("Error: " + e.getMessage());
        }

        finish();
    }

    private Uri saveToMediaStore(Bitmap bitmap) {
        return MediaStoreUtils.saveBitmap(this, bitmap);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
