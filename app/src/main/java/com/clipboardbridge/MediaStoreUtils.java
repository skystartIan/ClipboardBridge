package com.clipboardbridge;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;

class MediaStoreUtils {

    /**
     * 刪掉 Pictures/ClipboardBridge 底下除了 keepUri 以外的所有圖片，
     * 避免剪貼簿橋接的暫存圖不斷累積在相簿。keepUri 是目前剪貼簿指向的那張，保留。
     */
    static void deleteOthers(Context context, Uri keepUri) {
        long keepId = -1;
        try { keepId = ContentUris.parseId(keepUri); } catch (Exception ignore) {}
        String sel = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {"Pictures/ClipboardBridge%"};
        int deleted = 0;
        try (Cursor c = context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, sel, args, null)) {
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                while (c.moveToNext()) {
                    long id = c.getLong(idCol);
                    if (id == keepId) continue;
                    Uri u = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    try { deleted += context.getContentResolver().delete(u, null, null); }
                    catch (Exception ignore) {}
                }
            }
        } catch (Exception e) {
            Log.w(ClipboardReceiver.TAG, "deleteOthers failed: " + e.getMessage());
        }
        if (deleted > 0) Log.d(ClipboardReceiver.TAG, "cleaned " + deleted + " old clip images");
    }

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
