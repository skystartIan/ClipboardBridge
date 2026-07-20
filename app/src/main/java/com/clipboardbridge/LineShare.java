package com.clipboardbridge;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.Locale;

/**
 * PC 拖檔案過來後，代表 App 自己發 ACTION_SEND 把檔案分享給 LINE。
 *
 * 為什麼要由 App 發（而不是 PC 端 adb 直接 am start）：
 * adb 是 shell 身分，沒辦法把檔案的讀取權授給 LINE——實測 LINE 收到 intent 後
 * URI 授權被系統判定 denied，直接退回聊天清單。App 用自己的 FileProvider 產生
 * URI 再加 FLAG_GRANT_READ_URI_PERMISSION，授權天生成立（標準分享做法）。
 *
 * 直接指定 LINE 的分享入口 FullPickerLaunchActivity 可跳過系統分享選單
 * （少按兩下）；找不到該元件時退回不指定元件，由系統選單接手。
 */
class LineShare {

    private static final String TAG = ClipboardReceiver.TAG;
    private static final String AUTHORITY = "com.clipboardbridge.fileprovider";

    static final String LINE_PKG = "jp.naver.line.android";
    private static final String LINE_SHARE_ACT =
            "com.linecorp.line.share.common.view.FullPickerLaunchActivity";

    /**
     * @param path 平板本機絕對路徑（PC 端已 adb push 完成，通常在 /sdcard/Download）
     * @return true = 已把 LINE 的「選擇傳送對象」叫起來（或退回系統分享選單）
     */
    static boolean share(Context context, String path) {
        File f = new File(path);
        if (!f.isFile()) {
            Log.w(TAG, "LineShare: 檔案不存在 " + path);
            return false;
        }
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(context, AUTHORITY, f);
        } catch (IllegalArgumentException e) {
            // file_paths.xml 沒涵蓋到這個路徑
            Log.e(TAG, "LineShare: FileProvider 不接受此路徑 " + path + " - " + e);
            return false;
        }

        Intent base = new Intent(Intent.ACTION_SEND);
        base.setType(mimeOf(f.getName()));
        base.putExtra(Intent.EXTRA_STREAM, uri);
        base.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK);

        // 先試直接叫 LINE 的分享入口
        Intent direct = new Intent(base);
        direct.setClassName(LINE_PKG, LINE_SHARE_ACT);
        try {
            context.startActivity(direct);
            Log.d(TAG, "LineShare: → LINE 選擇傳送對象 " + f.getName());
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "LineShare: 直接叫 LINE 失敗（" + e + "），改用系統分享選單");
        }

        // 退路：不指定元件，讓系統分享選單出面（LINE 改名/改版也還能用）
        try {
            Intent chooser = Intent.createChooser(new Intent(base), null);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "LineShare: 系統分享選單也失敗 " + e);
            return false;
        }
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }
}
