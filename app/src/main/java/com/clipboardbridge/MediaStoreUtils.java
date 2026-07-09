package com.clipboardbridge;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;

class MediaStoreUtils {

    static Uri saveBitmap(Context context, Bitmap bitmap) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "cb_" + System.currentTimeMillis() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ClipboardBridge");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            Log.d(ClipboardReceiver.TAG, "saveToMediaStore: inserting...");
            uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            Log.d(ClipboardReceiver.TAG, "saveToMediaStore: uri=" + uri);
            if (uri == null) {
                Log.e(ClipboardReceiver.TAG, "saveToMediaStore: insert returned null");
                return null;
            }
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    Log.e(ClipboardReceiver.TAG, "saveToMediaStore: openOutputStream null");
                    return null;
                }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                Log.d(ClipboardReceiver.TAG, "saveToMediaStore: compress done");
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            Log.d(ClipboardReceiver.TAG, "saveToMediaStore: success, uri=" + uri);
            return uri;
        } catch (Exception e) {
            Log.e(ClipboardReceiver.TAG, "saveToMediaStore failed: " + e.getMessage());
            if (uri != null) context.getContentResolver().delete(uri, null, null);
            return null;
        }
    }
}
