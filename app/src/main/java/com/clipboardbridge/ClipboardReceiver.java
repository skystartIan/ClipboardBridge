package com.clipboardbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ClipboardReceiver extends BroadcastReceiver {

    static final String TAG = "ClipboardBridge";
    static final String ACTION_SET_IMAGE = "com.clipboardbridge.SET_IMAGE";
    static final String EXTRA_IMAGE_DATA = "image_data";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_SET_IMAGE.equals(intent.getAction())) return;

        String imageData = intent.getStringExtra(EXTRA_IMAGE_DATA);
        if (imageData == null || imageData.isEmpty()) {
            Log.e(TAG, "image_data is empty");
            return;
        }

        Log.d(TAG, "Received SET_IMAGE, data length=" + imageData.length());

        // 啟動透明 Activity 來設定剪貼簿（繞過背景限制）
        Intent actIntent = new Intent(context, ClipboardActivity.class);
        actIntent.putExtra(ClipboardActivity.EXTRA_IMAGE_DATA, imageData);
        actIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        actIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(actIntent);
    }
}
