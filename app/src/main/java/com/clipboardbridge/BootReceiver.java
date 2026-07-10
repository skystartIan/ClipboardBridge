package com.clipboardbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(ClipboardReceiver.TAG, "Boot completed - receiver ready");
        // 平板獨立自啟：開機用 Shizuku 起 clip agent（NotificationService 也會定期補）
        try { AgentStarter.ensureAgent(context); } catch (Throwable ignore) {}
    }
}
