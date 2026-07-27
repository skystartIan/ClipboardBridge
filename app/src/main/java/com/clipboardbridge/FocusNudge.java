package com.clipboardbridge;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 焦點輕推：短暫加一個可取得焦點的覆蓋視窗再移除，逼前景 Activity
 * 重跑一次 onWindowFocusChanged。
 *
 * 為什麼需要：Winlator 把 Android 剪貼簿推進 Wine 的動作，全專案只有
 * XServerDisplayActivity.onWindowFocusChanged(hasFocus) 這一個觸發點 ——
 * 沒有 OnPrimaryClipChangedListener，也沒有輪詢。而滑鼠跨屏不會產生任何
 * 焦點變化（Winlator 一直是前景、一直有焦點），所以 PC 複製的新內容
 * 送進 Android 剪貼簿之後，Wine 那邊完全不知道。
 *
 * 症狀是「時不時失靈」：只有在複製之後、貼上之前碰巧發生過焦點循環
 * （螢幕關了又開、切過別的 App、剛啟動容器）才會成功 —— 成功才是巧合。
 *
 * 用覆蓋視窗而不是透明 Activity：沒有轉場動畫、不進 Recents、不會有閃爍。
 */
final class FocusNudge {

    /** 夠久到系統確實搬動輸入焦點，短到不會吃掉使用者的按鍵。 */
    private static final int HOLD_MS = 80;

    private FocusNudge() {}

    static void nudge(Context context) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            final WindowManager wm =
                    (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;

            final View v = new View(app);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            // 關鍵：**不加** FLAG_NOT_FOCUSABLE，這個視窗才會把輸入焦點搶過來。
            // FLAG_NOT_TOUCHABLE 讓觸控照樣穿透到底下。
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1, type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;

            try {
                wm.addView(v, lp);
            } catch (Exception e) {
                Log.w(ClipboardReceiver.TAG, "FocusNudge addView 失敗: " + e);
                return;
            }
            main.postDelayed(() -> {
                try { wm.removeView(v); }
                catch (Exception ignore) {}
            }, HOLD_MS);
        });
    }
}
