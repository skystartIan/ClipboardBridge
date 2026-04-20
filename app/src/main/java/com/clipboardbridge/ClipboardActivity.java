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
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.io.OutputStream;

/**
 * 透明 Activity，專門用來設定剪貼簿
 * Android 10+ 只有前景 Activity 才能寫入剪貼簿
 * 執行完立刻 finish()
 */
public class ClipboardActivity extends Activity {

    static final String EXTRA_IMAGE_DATA = "image_data";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String b64 = getIntent().getStringExtra(EXTRA_IMAGE_DATA);
        if (b64 == null || b64.isEmpty()) {
            Log.e(ClipboardReceiver.TAG, "ClipboardActivity: no image_data");
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

            Uri uri = saveToMediaStore(bitmap);
            bitmap.recycle();

            if (uri == null) {
                toast("Error: MediaStore failed");
                finish();
                return;
            }

            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newUri(getContentResolver(), "image", uri));
                Log.d(ClipboardReceiver.TAG, "✓ Clipboard set via Activity: " + uri);
                toast("✓ 圖片已就緒，按 Ctrl+V 或長按貼上");
            }

        } catch (Exception e) {
            Log.e(ClipboardReceiver.TAG, "ClipboardActivity error: " + e.getMessage());
            toast("Error: " + e.getMessage());
        }

        finish();
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
