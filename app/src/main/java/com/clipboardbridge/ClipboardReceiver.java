package com.clipboardbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
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

        Intent serviceIntent = new Intent(context, ClipboardService.class);
        serviceIntent.putExtra(EXTRA_IMAGE_DATA, imageData);

        // Android 14+ 需要 RECEIVER_EXPORTED + 特殊處理
        // 用 goAsync() 讓 receiver 保持活躍，然後在背景執行
        final PendingResult result = goAsync();

        new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                Log.e(TAG, "startForegroundService failed: " + e.getMessage());
                // 備用：直接在這裡處理
                try {
                    ClipboardService.processInBackground(context, imageData);
                } catch (Exception e2) {
                    Log.e(TAG, "Background processing also failed: " + e2.getMessage());
                }
            } finally {
                result.finish();
            }
        }).start();
    }
}
