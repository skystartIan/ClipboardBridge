package com.clipboardbridge;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentResolver;
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

    /** 清理策略：保留最近 KEEP_RECENT 張、且只刪 OLD_AGE_S 秒前的舊圖。 */
    private static final int KEEP_RECENT = 5;
    private static final long OLD_AGE_S = 10 * 60;   // 10 分鐘

    /**
     * 圖片 clip：item 0 一定要帶一個非 null 的 text，不能用 ClipData.newUri。
     *
     * Winlator 11.1 的 XServerDisplayActivity.onWindowFocusChanged 每次視窗重新
     * 取得焦點都會把 Android 剪貼簿推進 Wine，而它只檢查了 clip 本身非 null：
     *     winHandler.setClipboardData(primaryClip.getItemAt(0).getText().toString());
     * 剪貼簿是純圖片時 getText() 回 null → NPE → Winlator 整個閃退，容器裡的
     * wineserver 一起被帶走（2026-07-27 追到）。ClipData.newUri 造出來的正是
     * 這種只有 URI 的 item，所以這裡自己組：description 仍然只宣告圖片 mime，
     * 依 mime 判斷的貼上行為完全不變，只是 item 多掛一個空字串當保險。
     * 自家 ClipAgent 是先看 getUri()、getText() 只是 fallback，不受影響。
     */
    static ClipData imageClip(ContentResolver cr, CharSequence label, Uri uri) {
        String type = cr.getType(uri);
        ClipDescription desc = new ClipDescription(
                label, new String[]{ type != null ? type : "image/*" });
        return new ClipData(desc, new ClipData.Item("", null, uri));
    }

    /**
     * 清理 Pictures/ClipboardBridge 底下累積的橋接暫存圖，避免塞爆相簿。
     *
     * 保守策略（保留最近 KEEP_RECENT 張、只刪 OLD_AGE_S 秒前的）：不能一推新圖
     * 就把前一張立刻刪掉。LINE 是延遲上傳——貼上當下只拿到指向該檔的 URI，真正
     * 上傳排在後面；若此時連續截圖，來源檔被立刻刪 → 上傳失敗 → 聊天室出現
     * 「檔案毀損，無法貼上」（2026-07-24 追到，PC 端 img_clipboard 早已同樣處理）。
     * keepUri 是目前剪貼簿指向的那張，永遠保留。
     */
    static void deleteOthers(Context context, Uri keepUri) {
        long keepId = -1;
        try { keepId = ContentUris.parseId(keepUri); } catch (Exception ignore) {}
        String sel = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {"Pictures/ClipboardBridge%"};
        // 依加入時間新→舊排序，前 KEEP_RECENT 張一律保留
        String order = MediaStore.Images.Media.DATE_ADDED + " DESC";
        long nowS = System.currentTimeMillis() / 1000L;
        int deleted = 0;
        try (Cursor c = context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID,
                             MediaStore.Images.Media.DATE_ADDED}, sel, args, order)) {
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
                int rank = 0;
                while (c.moveToNext()) {
                    long id = c.getLong(idCol);
                    if (id == keepId) continue;
                    rank++;
                    if (rank <= KEEP_RECENT) continue;          // 最近 N 張保留
                    long dateAddedS = c.getLong(dateCol);       // 秒
                    if (nowS - dateAddedS < OLD_AGE_S) continue; // 還太新，LINE 可能仍在上傳
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
