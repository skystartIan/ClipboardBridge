package com.clipboardbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class ClipboardReceiver extends BroadcastReceiver {

    static final String TAG = "ClipboardBridge";
    static final String ACTION_SET_IMAGE = "com.clipboardbridge.SET_IMAGE";
    static final String EXTRA_IMAGE_PATH = "image_path";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_SET_IMAGE.equals(intent.getAction())) return;

        String imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH);
        if (imagePath == null || imagePath.isEmpty()) {
            Log.e(TAG, "image_path is empty");
            return;
        }

        Log.d(TAG, "Received SET_IMAGE: " + imagePath);

        Intent serviceIntent = new Intent(context, ClipboardService.class);
        serviceIntent.putExtra(EXTRA_IMAGE_PATH, imagePath);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
