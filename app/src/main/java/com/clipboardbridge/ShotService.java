package com.clipboardbridge;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 框選截圖（M8）。
 *
 * 兩個入口，同一段流程：
 *   1. 硬體鍵盤按 F1 → onKeyEvent（電腦關機、藍牙鍵盤直連平板時唯一的路）
 *   2. PC 送 ImageServer 控制指令 6（滑鼠焦點在平板、Sync 攔下 F1 時）
 *
 * 流程：takeScreenshot() 抓當下畫面 → 蓋上全螢幕遮罩顯示那張凍結畫面 →
 * 使用者拖出矩形 → 裁切 → 寫進平板剪貼簿；PC 那邊若在等（走控制指令
 * 進來的），另外把 PNG 從原連線回傳讓它寫進 Windows 剪貼簿。
 *
 * 用 AccessibilityService 的 takeScreenshot 而非 MediaProjection：後者每次
 * 都要跳授權彈窗，前者只要服務開著就能直接截，適合當熱鍵用。
 *
 * F1 放行（PASSTHROUGH）：前景是遠端桌面時不接手，讓 F1 原封不動傳進去給
 * 遠端 PC 的截圖軟體（Snipaste 也是 F1）。無障礙服務攔鍵是全系統的，不放行
 * 的話遠端那邊永遠收不到。判斷放在這裡是因為只有平板知道自己前景是誰——
 * PC 端 Sync 猜不到，所以它現在也不攔 F1，一律轉發過來由這裡決定。
 *
 * 前景判斷用兩層（見 currentPkg）：按鍵當下直接查 getRootInActiveWindow，
 * 查不到才退回視窗切換事件記下的 topPkg。單靠事件會有空窗期，剛裝完 APK
 * 停在 RDP 裡按第一次 F1 就會誤判。
 */
@RequiresApi(api = Build.VERSION_CODES.R)   // takeScreenshot() 是 API 30 起才有
public class ShotService extends AccessibilityService {

    private static final String TAG = ClipboardReceiver.TAG;
    private static volatile ShotService instance;

    /** 前景是這些 App 時 F1 不接手，原樣放行（遠端桌面 → 讓遠端的 Snipaste 收到）。 */
    private static final Set<String> PASSTHROUGH = new HashSet<>(Arrays.asList(
            "com.microsoft.rdc.androidx"));      // Windows App（遠端桌面）

    /** 這些只是暫時蓋上來的視窗，不代表使用者換了 App，不能拿來更新 topPkg。 */
    private static final Set<String> TRANSIENT = new HashSet<>(Arrays.asList(
            "com.android.systemui",              // 通知欄／快捷面板
            "com.google.android.inputmethod.latin"));   // Gboard

    /** Ctrl+Space 在這兩個輸入法之間切換（見 switchIme）。 */
    private static final String IME_GBOARD =
            "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME";
    private static final String IME_SAMSUNG =
            "com.samsung.android.honeyboard/.service.HoneyBoardService";

    /** 三星鍵盤的 en_US 子類型 hash（`adb shell ime list -a` 查得）。 */
    private static final int SUBTYPE_EN_US = 65537;
    /** IME 的目前子類型（@hide 常數，只能寫字串）。寫它會真的讓 IME 切換語言。 */
    private static final String KEY_SUBTYPE = "selected_input_method_subtype";
    /** 切 IME 是非同步的，等它換完再寫子類型，否則會寫到舊 IME 上。 */
    private static final long IME_SETTLE_MS = 350;

    /**
     * 框選的保險逾時：遮罩若因故無法互動（例如停在系統設定的敏感頁面時，
     * Android 會強制隱藏所有非系統浮動視窗＝遮罩畫不出來也點不到），
     * finish()／fail() 永遠不會被呼叫，busy 就會永久卡住，之後在任何 App
     * 按 F1 都會被擋在 start() 第一行。給它一個上限自動收掉。
     */
    private static final long SELECT_TIMEOUT_MS = 45_000;

    // ── 點一下訊息 → 文字原地變成可選取 ────────────────
    /**
     * LINE 的訊息泡泡是唯讀 TextView（沒有 textIsSelectable），滑鼠拖不出選取範圍。
     *
     * 走過的死路（都有實機證據，別再試）：
     *  - 模擬長按叫出 LINE 的選單再點「選擇並複製」：PC 用 UHID 補送的按下會撞上
     *    雙擊判定窗、遮罩、touch slop，一路跟 Android 的手勢辨識器打架。
     *  - 對泡泡下 ACTION_SET_SELECTION：**回 true 但畫面完全沒有選取**（2026-07-23
     *    截圖確認）。那個 TextView 沒有 textIsSelectable 所以不渲染、沒有控制點，
     *    動作清單裡也沒有 ACTION_COPY，等於選了也拿不到。
     *
     * 現在的做法：使用者的真實點擊會發出 TYPE_VIEW_CLICKED，事件 source 就是被點的
     * 那個節點，文字與精確 bounds 都拿得到（座標推算完全不需要）。於是**在原地疊一層
     * 內容相同、真正可選取的 TextView**，讓使用者直接拖曳框選、按 Ctrl+C。
     * 沒有選單、不依賴 LINE 的版本或選單文字，任何 App 都適用。
     */
    private static final String PKG_LINE = "jp.naver.line.android";
    /** 選字層的保險逾時：不管使用者做了什麼，時間到就撤掉，不留殘留視窗。 */
    private static final long PICK_TIMEOUT_MS = 60_000;
    /** 等截圖取樣配色的上限；超過就先用上次的配色把選字層開出來，別讓使用者等。 */
    private static final long SAMPLE_WAIT_MS = 450;
    /** 選字層比原本的泡泡往外擴這麼多，讓斷行不同時也放得下。 */
    private static final int PICK_PAD_PX = 18;
    /** 裁切完成後的回呼（PC 走控制指令進來時用來取回 PNG）。 */
    interface ShotCallback {
        /**
         * png != null   → 裁切好的圖
         * png == null 且 cancelled  → 使用者自己取消（PC 不該再做別的事）
         * png == null 且 !cancelled → 截圖失敗（PC 應退回全螢幕 screencap）
         *
         * 這兩者一定要分開：以前都回 null，PC 一律當成「取消」而靜默放棄，
         * 截圖真的壞掉時使用者只會看到「按了沒反應」。
         */
        void onResult(byte[] png, boolean cancelled);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private WindowManager wm;
    private SelectView view;
    private Bitmap shot;
    private ShotCallback pending;
    /** 目前前景 App 的套件名（由 typeWindowStateChanged 事件維護）。 */
    private volatile String topPkg = "";
    /** 是否正處於遠端桌面（用來做進出 RDP 的一次性切換，不是每個事件都做）。 */
    private boolean inRdp = false;

    // 選字層狀態
    private View pickRoot;
    private TextView pickView;
    /** PC 跨屏進平板時 arm，帶 TTL；PC 崩潰或斷線時平板會自己解除。 */
    private volatile long armedUntil = 0;
    /** 上次取樣到的泡泡配色，截圖來不及時沿用（第一次才會用中性配色）。 */
    private int lastBg = 0xFF303030, lastFg = 0xFFFFFFFF;
    private final Runnable pickTimeout = this::hidePick;
    /** SELECT_TIMEOUT_MS 到了還沒收尾就當成取消，避免 busy 永久卡住。 */
    private final Runnable selectTimeout = () -> {
        if (busy.get()) {
            android.util.Log.w(TAG, "ShotService: 框選逾時，自動取消（遮罩可能被系統隱藏）");
            fail("框選已逾時取消");
        }
    };

    static ShotService get() {
        return instance;
    }

    /** 服務是否已被使用者啟用（PC 端可據此決定要不要退回自己截圖）。 */
    static boolean available() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        android.util.Log.d(TAG, "ShotService connected");
    }

    @Override
    public void onDestroy() {
        instance = null;
        // 服務被關掉（或使用者關開無障礙來重置）時把殘留的遮罩收乾淨，
        // 否則那個視窗會一直掛在 WindowManager 上。
        main.removeCallbacks(selectTimeout);
        if (busy.get()) fail(null);
        hidePick();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            onViewClicked(event);
            return;
        }
        // 以下只為了記住前景是誰（shot_service.xml 宣告的 typeWindowStateChanged）
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        // 輸入法／通知欄蓋上來不算換 App；自己的框選遮罩更不算
        if (TRANSIENT.contains(pkg) || getPackageName().equals(pkg)) return;
        topPkg = pkg;
        onForegroundChanged(pkg);
    }

    /**
     * 使用者點了某個節點（滑鼠或手指皆會觸發）。
     *
     * 這是取字功能的入口：事件的 source **就是被點的那個節點**，不必用 PC 推算的座標去猜
     * ——推算誤差 30~90px 而訊息泡泡才 46px 高，先前「選錯訊息」正是這麼來的。
     *
     * 目前這一版只驗證前提（事件送不送得出來、source 有沒有文字），確認後才接上選字覆蓋層。
     */
    private void onViewClicked(AccessibilityEvent event) {
        try {
            CharSequence p = event.getPackageName();
            String pkg = p == null ? "" : p.toString();
            if (!PKG_LINE.equals(pkg)) return;      // 先只觀察 LINE，免得洗版
            AccessibilityNodeInfo src = event.getSource();
            CharSequence t = src == null ? null : src.getText();
            String head = t == null ? "(無文字)"
                    : t.toString().substring(0, Math.min(12, t.length()));
            Rect b = new Rect();
            if (src != null) src.getBoundsInScreen(b);
            android.util.Log.d(TAG, "ShotService: 點擊事件 pkg=" + pkg
                    + " id=" + (src == null ? null : src.getViewIdResourceName())
                    + " 文字=「" + head + "」 len=" + (t == null ? 0 : t.length())
                    + " bounds=" + b);
            // 只對「可點擊**且**可長按」的文字節點動作。訊息泡泡兩者皆是（App 為它
            // 提供了長按選單），而一般按鈕通常只有 clickable——這樣點到按鈕、連結、
            // 聊天室清單時不會冒出選字層。用這個通用條件而不是比對 LINE 的
            // resource-id（chat_ui_message_text），免得 LINE 改版就失效。
            if (src == null || !src.isLongClickable()) return;
            // 輸入框（EditText）本來就能選字、還有游標與貼上選單，蓋一層上去只會擋路。
            // 實測：把文字貼進 LINE 的輸入框後再點它一下就會冒出選字層。
            CharSequence cls = event.getClassName();
            if (src.isEditable()
                    || (cls != null && cls.toString().contains("EditText"))) {
                android.util.Log.d(TAG, "ShotService: 可編輯欄位，不接手");
                return;
            }
            if (t == null || t.toString().trim().isEmpty()) return;
            if (!armed()) {
                android.util.Log.d(TAG, "ShotService: 未啟用（沒有滑鼠也沒被 PC arm），略過");
                return;
            }
            showPick(t.toString(), b);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ShotService: 點擊事件處理失敗 " + t);
        }
    }

    /**
     * 現在該不該接手點擊。
     *
     * 要擋的是「手指點訊息也跳出選字層」，要放行的是任何滑鼠操作——包含 PC 關機、
     * 平板直連藍牙滑鼠的情境。TYPE_VIEW_CLICKED 不帶輸入來源，所以取兩個訊號的聯集：
     *  1. PC 跨屏進來時送過 arm（控制指令 10，帶 TTL）。
     *  2. 平板上接著真的指向裝置：列舉 InputDevice 找 SOURCE_MOUSE。
     *     Sync 自己的 UHID 虛擬滑鼠叫 "SyncMouse"，用名字排除掉。
     */
    private boolean armed() {
        if (android.os.SystemClock.uptimeMillis() < armedUntil) return true;
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice dev = InputDevice.getDevice(id);
                if (dev == null || dev.isVirtual()) continue;
                if (!dev.supportsSource(InputDevice.SOURCE_MOUSE)) continue;
                String name = dev.getName() == null ? "" : dev.getName();
                if (name.startsWith("Sync")) continue;      // 我們自己的 UHID 滑鼠
                return true;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ShotService: 列舉輸入裝置失敗 " + t);
        }
        return false;
    }

    /**
     * 控制指令 11：對游標下（座標由 PC 給）的訊息開選字層。
     *
     * 給「點下去會被吃掉」的訊息用——純連結或純數字的訊息由 LinkMovementMethod
     * 接管觸控，`performClick()` 不會被呼叫，於是 TYPE_VIEW_CLICKED 根本不會發出，
     * 點擊觸發在原理上就沒有機會（實測：點泡泡最右內緣照樣開瀏覽器、零事件）。
     *
     * 這條路用的是 PC 推算的座標（誤差數十像素），但因為是使用者按側鍵的明確動作，
     * 抓錯了按 Esc 再來一次即可，不像自動觸發那樣會默默出錯。
     */
    static boolean pickAt(final int x, final int y) {
        final ShotService s = instance;
        if (s == null) return false;
        final boolean[] r = new boolean[1];
        final CountDownLatch latch = new CountDownLatch(1);
        s.main.post(() -> {
            try {
                AccessibilityNodeInfo n = s.findTextNodeAt(x, y);
                if (n == null) return;
                CharSequence t = n.getText();
                if (t == null || t.toString().trim().isEmpty()) return;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                s.showPick(t.toString(), b);
                r[0] = true;
            } catch (Throwable ex) {
                android.util.Log.w(TAG, "ShotService: pickAt 失敗 " + ex);
            } finally {
                latch.countDown();
            }
        });
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
        return r[0];
    }

    /** 控制指令 10：PC 跨屏進平板 arm、離開 disarm。 */
    static void setArm(boolean on, long ttlMs) {
        ShotService s = instance;
        if (s == null) return;
        s.armedUntil = on ? android.os.SystemClock.uptimeMillis() + ttlMs : 0;
        android.util.Log.d(TAG, "ShotService: arm=" + on);
        if (!on) s.main.post(s::hidePick);
    }

    // ── 選字層 ──────────────────────────────────────────
    /**
     * 在原本的泡泡位置疊一層內容相同、真正可選取的 TextView。
     *
     * 配色從截圖取樣（無障礙節點拿不到顏色字級），讓它看起來就像原本那則訊息變成
     * 可以選取的；截圖來不及就沿用上次的配色，不讓使用者等。
     */
    private void showPick(String text, Rect bounds) {
        main.post(() -> {
            final boolean[] done = new boolean[1];
            Runnable openWithLast = () -> {
                if (!done[0]) {
                    done[0] = true;
                    openPick(text, bounds, lastBg, lastFg);
                }
            };
            main.postDelayed(openWithLast, SAMPLE_WAIT_MS);
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(),
                        new TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(ScreenshotResult res) {
                                int[] c = null;
                                try (HardwareBuffer hb = res.getHardwareBuffer()) {
                                    Bitmap raw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                                    if (raw != null) {
                                        Bitmap soft = raw.copy(Bitmap.Config.ARGB_8888, false);
                                        raw.recycle();
                                        if (soft != null) {
                                            c = sampleColors(soft, bounds);
                                            soft.recycle();
                                        }
                                    }
                                } catch (Exception e) {
                                    android.util.Log.w(TAG, "ShotService: 取樣配色失敗 " + e);
                                }
                                final int[] cc = c;
                                main.post(() -> {
                                    if (cc != null) { lastBg = cc[0]; lastFg = cc[1]; }
                                    if (!done[0]) {
                                        done[0] = true;
                                        main.removeCallbacks(openWithLast);
                                        openPick(text, bounds, lastBg, lastFg);
                                    }
                                });
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                main.post(openWithLast);
                            }
                        });
            } catch (Throwable t) {
                main.post(openWithLast);
            }
        });
    }

    /**
     * 取樣泡泡的底色與文字色。
     *
     * 底色取靠近左右內緣、垂直中線的幾個點的中位數（那裡幾乎不會壓到字）；文字色
     * 取整塊裡亮度離底色最遠的那個像素——就是筆畫。
     */
    private static int[] sampleColors(Bitmap bmp, Rect b) {
        Rect r = new Rect(b);
        if (!r.intersect(0, 0, bmp.getWidth(), bmp.getHeight())) return null;
        int y = r.centerY();
        java.util.List<Integer> edge = new java.util.ArrayList<>();
        for (int dx = 2; dx <= 10; dx += 2) {
            edge.add(bmp.getPixel(Math.min(bmp.getWidth() - 1, r.left + dx), y));
            edge.add(bmp.getPixel(Math.max(0, r.right - dx), y));
        }
        java.util.Collections.sort(edge);
        int bg = edge.get(edge.size() / 2);
        int fg = 0, best = -1;
        int step = Math.max(1, r.width() / 60);
        for (int x = r.left; x < r.right; x += step) {
            for (int yy = r.top + 2; yy < r.bottom - 2; yy += Math.max(1, r.height() / 8)) {
                int p = bmp.getPixel(Math.min(bmp.getWidth() - 1, x),
                        Math.min(bmp.getHeight() - 1, yy));
                int d = Math.abs(lum(p) - lum(bg));
                if (d > best) { best = d; fg = p; }
            }
        }
        if (best < 40) fg = lum(bg) > 128 ? 0xFF000000 : 0xFFFFFFFF;
        return new int[]{bg | 0xFF000000, fg | 0xFF000000};
    }

    private static int lum(int c) {
        return (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100;
    }

    private void openPick(String text, Rect bounds, int bg, int fg) {
        hidePick();
        try {
            int pad = PICK_PAD_PX;
            int w = Math.max(160, bounds.width() + pad * 2);
            float size = fitTextSize(text, bounds.width(), bounds.height());

            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextIsSelectable(true);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            tv.setTextColor(fg);
            tv.setPadding(pad, pad, pad, pad);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(bg);
            shape.setCornerRadius(Math.min(48f, bounds.height() * 0.5f));
            tv.setBackground(shape);

            FrameLayout root = new FrameLayout(this) {
                @Override
                public boolean onTouchEvent(MotionEvent e) {
                    // 點到文字以外的地方＝收工（TextView 會自己吃掉它範圍內的觸控）
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) hidePick();
                    return true;
                }

                @Override
                public boolean dispatchKeyEvent(KeyEvent e) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN
                            && (e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                            || e.getKeyCode() == KeyEvent.KEYCODE_BACK)) {
                        hidePick();
                        return true;
                    }
                    return super.dispatchKeyEvent(e);
                }
            };
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    w, FrameLayout.LayoutParams.WRAP_CONTENT);
            flp.leftMargin = Math.max(0, bounds.left - pad);
            flp.topMargin = Math.max(0, bounds.top - pad);
            root.addView(tv, flp);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            wm.addView(root, lp);
            pickRoot = root;
            pickView = tv;
            main.removeCallbacks(pickTimeout);
            main.postDelayed(pickTimeout, PICK_TIMEOUT_MS);
            android.util.Log.d(TAG, "ShotService: 選字層開啟 " + text.length()
                    + " 字 size=" + (int) size + " bg=" + Integer.toHexString(bg));
        } catch (Throwable t) {
            android.util.Log.e(TAG, "ShotService: 選字層開啟失敗 " + t);
            pickRoot = null;
            pickView = null;
        }
    }

    /** 找一個讓整段文字大致填滿原本泡泡的字級。 */
    private static float fitTextSize(String text, int w, int h) {
        TextPaint p = new TextPaint();
        p.setAntiAlias(true);
        for (float s = Math.min(96, Math.max(24, h)); s >= 18; s -= 1f) {
            p.setTextSize(s);
            StaticLayout sl = StaticLayout.Builder
                    .obtain(text, 0, text.length(), p, Math.max(1, w)).build();
            if (sl.getHeight() <= h) return s;
        }
        return 18f;
    }

    private void hidePick() {
        main.removeCallbacks(pickTimeout);
        if (pickRoot != null) {
            try { wm.removeView(pickRoot); } catch (Exception ignore) { }
            pickRoot = null;
        }
        pickView = null;
    }

    /** Ctrl+C：把選字層裡選取的片段寫進剪貼簿（沒選就整段）。 */
    private boolean copyPickSelection() {
        TextView tv = pickView;
        if (tv == null) return false;
        try {
            CharSequence all = tv.getText();
            int a = tv.getSelectionStart(), b = tv.getSelectionEnd();
            if (a > b) { int tmp = a; a = b; b = tmp; }
            CharSequence out = (a >= 0 && b > a) ? all.subSequence(a, b) : all;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", out));
            android.util.Log.d(TAG, "ShotService: 選字複製 " + out.length() + " 字"
                    + (b > a ? "（選取範圍）" : "（未選取＝整段）"));
            Toast.makeText(this, "✓ 已複製 " + out.length() + " 字",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ShotService: 選字複製失敗 " + t);
        }
        hidePick();
        return true;
    }

    /**
     * 進出遠端桌面時自動切換平板輸入法。
     *
     * 在 RDP 裡打字時，平板的 IME 會先組字才把結果送進去，等於「兩層輸入法」
     * 打架——中文要由遠端的微軟注音處理才對。所以進 RDP 就把平板鍵盤壓成
     * 三星鍵盤的 en_US（不組字，按鍵原樣穿過去給遠端），離開再還原。
     *
     * 只在「進」「出」的瞬間做，不是每個視窗事件都做，免得使用者在 RDP 裡
     * 手動改了輸入法又被我們蓋回去。
     *
     * 離開時固定切回 Gboard、**不指定子類型**：Android 本來就有 per-IME 的
     * 子類型歷史（settings 的 input_methods_subtype_history），切回某個 IME
     * 時系統會自動套用它上次用的語言。早期版本改成「進去前暫存、出來寫回」，
     * 實測還原不了——沒有輸入框取得焦點時 selected_input_method_subtype 只是
     * 紀錄，IME 真實狀態會重新同步，暫存到的往往已經是被改過的值。交給系統
     * 的歷史機制反而準。
     */
    private void onForegroundChanged(String pkg) {
        boolean rdp = PASSTHROUGH.contains(pkg);
        if (rdp == inRdp) return;
        inRdp = rdp;
        if (rdp) {
            android.util.Log.d(TAG, "ShotService: 進入 RDP → 三星鍵盤 en_US");
            applyIme(IME_SAMSUNG, SUBTYPE_EN_US);
        } else {
            android.util.Log.d(TAG, "ShotService: 離開 RDP → Gboard（沿用上次語言）");
            applyIme(IME_GBOARD, 0);   // 0 ＝ 不寫子類型，讓系統從歷史還原
        }
    }

    /** 切到指定輸入法，並在它換完之後寫入子類型（順序不能反）。 */
    private void applyIme(String ime, int subtype) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            getSoftKeyboardController().switchToInputMethod(ime);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "ShotService: 切輸入法失敗: " + t);
        }
        if (subtype <= 0) return;
        main.postDelayed(() -> {
            try {
                // 需要 WRITE_SECURE_SETTINGS（用 adb pm grant 授予，簽章固定所以不會掉）
                Settings.Secure.putInt(getContentResolver(), KEY_SUBTYPE, subtype);
            } catch (Throwable t) {
                android.util.Log.e(TAG, "ShotService: 寫子類型失敗（缺 "
                        + "WRITE_SECURE_SETTINGS？）: " + t);
            }
        }, IME_SETTLE_MS);
    }

    @Override
    public void onInterrupt() { }

    /**
     * 按鍵當下的前景套件名。
     *
     * 優先直接問系統（getRootInActiveWindow），問不到才退回事件記錄的 topPkg。
     * 只靠事件不夠：服務剛啟用／剛開機時還沒收過任何視窗切換事件，topPkg 是空
     * 的，那時人若正停在 RDP 裡按 F1 就會被誤判成要截平板。
     */
    private String currentPkg() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root != null) {
                CharSequence p = root.getPackageName();
                if (p != null) {
                    String pkg = p.toString();
                    // 輸入法／通知欄疊在上面時別採信，退回上一個真正的 App
                    if (!TRANSIENT.contains(pkg) && !getPackageName().equals(pkg)) {
                        return pkg;
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ShotService: 查前景失敗，改用事件記錄: " + t);
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Throwable ignore) { }
            }
        }
        return topPkg;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // 選字層開著時，Ctrl+C 是要複製選取範圍的，不能讓它傳下去給 App。
        // 只在選字層存在時攔——其餘情況（實體鍵盤、Sync 轉發）Ctrl+C 照常送達平板。
        if (pickView != null && event.getKeyCode() == KeyEvent.KEYCODE_C
                && event.isCtrlPressed()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) copyPickSelection();
            return true;
        }
        // 只吃 F1 與 Ctrl+Space；其餘一律放行，別影響正常打字
        if (event.getKeyCode() == KeyEvent.KEYCODE_F1) {
            String pkg = currentPkg();
            if (PASSTHROUGH.contains(pkg)) {
                // 前景是遠端桌面 → 這顆 F1 是要給遠端 PC 的，別攔
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    android.util.Log.d(TAG, "ShotService: F1 放行給 " + pkg);
                }
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (busy.get()) {
                    // 已經有一場在進行 → 這次 F1 當「取消」。看不見遮罩時
                    // 這是使用者唯一的自救路徑（再按一次就能重新開始）。
                    android.util.Log.d(TAG, "ShotService: F1 取消進行中的框選");
                    fail(null);
                } else {
                    android.util.Log.d(TAG, "ShotService: F1 接手截圖（前景 " + pkg + "）");
                    start(null);
                }
            }
            return true;   // 連 UP 一起吃掉，免得落單的放開被下層收到
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed()) {
            String pkg = currentPkg();
            if (PASSTHROUGH.contains(pkg)) {
                // 遠端桌面：Ctrl+Space 是要給遠端 Windows 切它自己的輸入法的
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) switchIme();
            return true;
        }
        return false;
    }

    /**
     * Ctrl+Space 在 Gboard 與三星鍵盤之間切換。
     *
     * 為什麼做在這裡而不是靠系統：三星鍵盤自己會把 Ctrl+Space 當成內部的
     * 中／英切換而吃掉，所以一旦切到三星鍵盤就再也回不去 Gboard。無障礙的
     * 攔鍵層比 IME 更早拿到按鍵，在這裡攔就搶得贏。
     *
     * 也因為是攔實體按鍵，Sync 的 UHID 鍵盤（筆電鍵盤跨屏過來）和直連的藍牙
     * 鍵盤走的是同一條路，兩種情境都吃得到，不必依賴 PC 端。
     */
    private void switchIme() {
        String now = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        String target = (now != null && now.startsWith("com.google.android.inputmethod.latin"))
                ? IME_SAMSUNG : IME_GBOARD;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            android.util.Log.w(TAG, "ShotService: switchToInputMethod 需要 API 33");
            return;
        }
        boolean ok = false;
        try {
            ok = getSoftKeyboardController().switchToInputMethod(target);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "ShotService: 切換輸入法失敗: " + t);
        }
        android.util.Log.d(TAG, "ShotService: Ctrl+Space " + now + " → " + target
                + (ok ? " ok" : " 失敗"));
        if (ok) {
            Toast.makeText(this,
                    target.equals(IME_GBOARD) ? "Gboard" : "三星鍵盤",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 給 ImageServer 用的入口；cb 可為 null（純平板端操作，不必回傳）。 */
    static boolean trigger(ShotCallback cb) {
        ShotService s = instance;
        if (s == null) return false;
        s.main.post(() -> s.start(cb));
        return true;
    }

    // ── 游標下取字 ──────────────────────────────────────
    /**
     * 指令 8：直接把 (x,y) 那則訊息的整段文字寫進平板剪貼簿。
     *
     * 不碰 UI、不依賴選單文字，任何 App 都通；PC 端靠既有的 clipagent
     * CLIPTEXT 回拉，不必另接管道。要只取一段時用點擊 → 選字覆蓋層那條路。
     */
    static boolean copyText(final int x, final int y) {
        final ShotService s = instance;
        if (s == null) return false;
        final boolean[] r = new boolean[1];
        final CountDownLatch latch = new CountDownLatch(1);
        s.main.post(() -> {
            try {
                AccessibilityNodeInfo n = s.findTextNodeAt(x, y);
                if (n == null) return;
                CharSequence t = n.getText();
                if (t == null || t.toString().trim().isEmpty()) return;
                ClipboardManager cm = (ClipboardManager)
                        s.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return;
                cm.setPrimaryClip(ClipData.newPlainText("text", t));
                r[0] = true;
                android.util.Log.d(TAG, "ShotService: 取字 " + t.length() + " 字 → 剪貼簿");
                Toast.makeText(s, "✓ 已複製 " + t.length() + " 字", Toast.LENGTH_SHORT).show();
            } catch (Throwable ex) {
                android.util.Log.e(TAG, "ShotService copyText failed: " + ex);
            } finally {
                latch.countDown();
            }
        });
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
        return r[0];
    }


    /**
     * (x,y) 命中的最深、且**有 getText()** 的節點；精準命中不到就找附近最近的。
     *
     * 只認 getText()，絕不認 contentDescription：LINE 的圖片、貼圖、通話
     * 記錄都是靠 contentDescription 提供無障礙文字的，認了就會把它們誤判
     * 成文字訊息而去長按。
     *
     * 為什麼可以放寬到「附近」：座標是 PC 用航位推算估出來的游標位置，實測
     * 誤差數十像素，常常掉進訊息泡泡之間的縫隙而查無文字（2026-07-22 實測
     * 每次都卡在這）。但**真正叫出選單的是 PC 用 UHID 在真實游標位置送的
     * 長按**，這裡的節點查找只是一道「游標大概在不在訊息區」的閘門——抓到
     * 隔壁那則也無所謂，因為它不決定長按到哪裡。所以寧可寬鬆。
     * 反過來，點在圖片/貼圖上時附近沒有文字節點，閘門照樣擋得住。
     */
    private static final int NEAR_X_PX = 60;
    private static final int NEAR_Y_PX = 120;

    private AccessibilityNodeInfo findTextNodeAt(int x, int y) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo hit = deepestTextNode(root, x, y);
            if (hit != null) return hit;
            Best best = new Best();
            nearestTextNode(root, x, y, best);
            if (best.node != null) {
                android.util.Log.d(TAG, "ShotService: (" + x + "," + y
                        + ") 沒直接命中，取附近節點 dist=" + best.score);
            }
            return best.node;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ShotService: 節點查找失敗 " + t);
            return null;
        }
    }

    private static final class Best {
        AccessibilityNodeInfo node;
        int score = Integer.MAX_VALUE;
    }

    private void nearestTextNode(AccessibilityNodeInfo n, int x, int y, Best best) {
        if (n == null) return;
        CharSequence t = n.getText();
        if (t != null && !t.toString().trim().isEmpty()) {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            int dx = Math.max(0, Math.max(b.left - x, x - b.right));
            int dy = Math.max(0, Math.max(b.top - y, y - b.bottom));
            if (dx <= NEAR_X_PX && dy <= NEAR_Y_PX) {
                int score = dx * 2 + dy;      // 水平偏移比垂直更可疑
                if (score < best.score) {
                    best.score = score;
                    best.node = n;
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            nearestTextNode(n.getChild(i), x, y, best);
        }
    }

    private AccessibilityNodeInfo deepestTextNode(AccessibilityNodeInfo n, int x, int y) {
        if (n == null) return null;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (!b.contains(x, y)) return null;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = deepestTextNode(n.getChild(i), x, y);
            if (hit != null) return hit;
        }
        CharSequence t = n.getText();
        return (t != null && !t.toString().trim().isEmpty()) ? n : null;
    }

    // ── 截圖 → 遮罩 ─────────────────────────────────────
    private void start(ShotCallback cb) {
        if (!busy.compareAndSet(false, true)) {
            android.util.Log.d(TAG, "ShotService: 已有框選進行中");
            // 當成取消：另一場框選正在進行，PC 不該再插一張全螢幕進來
            if (cb != null) cb.onResult(null, true);
            return;
        }
        pending = cb;
        main.removeCallbacks(selectTimeout);
        main.postDelayed(selectTimeout, SELECT_TIMEOUT_MS);
        takeScreenshot(Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult r) {
                        Bitmap bmp = null;
                        try (HardwareBuffer hb = r.getHardwareBuffer()) {
                            Bitmap raw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                            if (raw != null) {
                                // 硬體 bitmap 不能直接裁切/壓縮 → 複製成軟體的
                                bmp = raw.copy(Bitmap.Config.ARGB_8888, false);
                                raw.recycle();
                            }
                        } catch (Exception e) {
                            android.util.Log.e(TAG, "ShotService wrap failed: " + e);
                        }
                        final Bitmap b = bmp;
                        main.post(() -> {
                            if (b == null) fail("截圖失敗");
                            else showOverlay(b);
                        });
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        android.util.Log.e(TAG, "takeScreenshot failed " + errorCode);
                        main.post(() -> fail("截圖失敗(" + errorCode + ")"));
                    }
                });
    }

    private void showOverlay(Bitmap bmp) {
        shot = bmp;
        try {
            view = new SelectView(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            wm.addView(view, lp);
            view.setFocusableInTouchMode(true);
            view.requestFocus();
        } catch (Exception e) {
            android.util.Log.e(TAG, "ShotService addView failed: " + e);
            fail("無法顯示框選遮罩");
        }
    }

    private void removeOverlay() {
        if (view != null) {
            try { wm.removeView(view); } catch (Exception ignore) { }
            view = null;
        }
    }

    /** msg == null ＝ 使用者主動取消（Esc/返回/右鍵）；有 msg ＝ 真的失敗。 */
    private void fail(String msg) {
        main.removeCallbacks(selectTimeout);
        removeOverlay();
        if (shot != null) { shot.recycle(); shot = null; }
        if (pending != null) { pending.onResult(null, msg == null); pending = null; }
        busy.set(false);
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ── 裁切 → 剪貼簿 ───────────────────────────────────
    private void finish(Rect r) {
        main.removeCallbacks(selectTimeout);
        Bitmap full = shot;
        ShotCallback cb = pending;
        removeOverlay();
        shot = null;
        pending = null;
        try {
            if (full == null || r == null || r.width() < 8 || r.height() < 8) {
                // 太小＝當成取消（誤點一下不該產生東西）。full==null 才是真失敗
                if (cb != null) cb.onResult(null, full != null);
                return;
            }
            r.intersect(0, 0, full.getWidth(), full.getHeight());
            Bitmap crop = Bitmap.createBitmap(full, r.left, r.top,
                    Math.max(1, r.width()), Math.max(1, r.height()));

            Uri uri = MediaStoreUtils.saveBitmap(this, crop);
            if (uri != null) {
                ClipboardManager cm = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newUri(getContentResolver(), "image", uri));
                }
                MediaStoreUtils.deleteOthers(this, uri);
            }
            byte[] png = null;
            if (cb != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                crop.compress(Bitmap.CompressFormat.PNG, 100, bos);
                png = bos.toByteArray();
            }
            android.util.Log.d(TAG, "ShotService: 裁切 " + crop.getWidth()
                    + "x" + crop.getHeight() + " → 剪貼簿");
            Toast.makeText(this, "✓ 已複製 " + crop.getWidth() + "×"
                    + crop.getHeight(), Toast.LENGTH_SHORT).show();
            crop.recycle();
            if (cb != null) cb.onResult(png, false);
        } catch (Exception e) {
            android.util.Log.e(TAG, "ShotService finish failed: " + e);
            if (cb != null) cb.onResult(null, false);   // 例外＝失敗，不是取消
        } finally {
            if (full != null) full.recycle();
            busy.set(false);
        }
    }

    // ── 框選視圖 ────────────────────────────────────────
    @SuppressWarnings("ViewConstructor")
    private class SelectView extends View {
        private final Paint dim = new Paint();
        private final Paint border = new Paint();
        private final Paint hint = new Paint();
        private float x0, y0, x1, y1;
        private boolean dragging;

        SelectView(Context c) {
            super(c);
            dim.setColor(Color.argb(120, 0, 0, 0));
            border.setColor(Color.argb(255, 60, 170, 255));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(3f);
            hint.setColor(Color.WHITE);
            hint.setTextSize(34f);
            hint.setAntiAlias(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (shot != null) canvas.drawBitmap(shot, 0, 0, null);
            if (!dragging) {
                canvas.drawRect(0, 0, getWidth(), getHeight(), dim);
                canvas.drawText("拖曳框選要複製的範圍（Esc 或右鍵取消）",
                        60, 90, hint);
                return;
            }
            Rect r = rect();
            // 四周變暗、選取區保持原樣
            canvas.drawRect(0, 0, getWidth(), r.top, dim);
            canvas.drawRect(0, r.bottom, getWidth(), getHeight(), dim);
            canvas.drawRect(0, r.top, r.left, r.bottom, dim);
            canvas.drawRect(r.right, r.top, getWidth(), r.bottom, dim);
            canvas.drawRect(r, border);
            canvas.drawText(r.width() + " × " + r.height(),
                    r.left, Math.max(40, r.top - 14), hint);
        }

        private Rect rect() {
            return new Rect((int) Math.min(x0, x1), (int) Math.min(y0, y1),
                    (int) Math.max(x0, x1), (int) Math.max(y0, y1));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 右鍵＝取消（滑鼠操作時很自然）
                    if ((e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                        fail(null);
                        return true;
                    }
                    x0 = x1 = e.getX();
                    y0 = y1 = e.getY();
                    dragging = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    x1 = e.getX();
                    y1 = e.getY();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    x1 = e.getX();
                    y1 = e.getY();
                    finish(rect());
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    fail(null);
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) {
                fail(null);
                return true;
            }
            return super.onKeyDown(keyCode, e);
        }
    }
}
