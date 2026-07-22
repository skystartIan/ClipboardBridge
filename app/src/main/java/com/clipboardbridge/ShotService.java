package com.clipboardbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
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

    // ── 游標下取字（LINE 訊息複製）──────────────────────
    /**
     * LINE 的訊息泡泡是唯讀 TextView（沒有 textIsSelectable），滑鼠拖不出
     * 選取範圍，只能長按叫出選單再點「選取文字」。指令 7 就是把這兩步自動
     * 化，讓使用者一點訊息就直接落在選取畫面裡，接著自己拉範圍按 Ctrl+C。
     *
     * 只對 LINE 做：別的 App 要嘛本來就能拖曳選取，要嘛沒有這個選單。
     */
    private static final String PKG_LINE = "jp.naver.line.android";
    /** 長按選單裡那一項的文字。LINE 改版換字時 PC 端可用控制指令帶新值進來。 */
    private static final String MENU_SELECT_TEXT = "選取文字";
    private static final long MENU_POLL_MS = 120;      // 每隔多久找一次選單
    private static final long MENU_WAIT_MS = 2000;     // 找不到就放棄
    private static final long GESTURE_FALLBACK_MS = 600;   // 改用真實觸控長按
    private static final long GESTURE_HOLD_MS = 500;
    private static final long MASK_LINGER_MS = 400;    // 點完選單再多蓋一下
    /**
     * 遮罩的硬逾時。這條路上每一步都可能卡住（節點沒反應、選單不出來、
     * 遮罩被系統強制隱藏），所以不用任何持久旗標判斷收尾——時間到就無條件
     * 撤掉。ShotService 的 busy 曾經因為「遮罩畫不出來→兩條清除路徑都走不到」
     * 而永久卡死整個框選功能，這裡不重蹈覆轍。
     */
    private static final long MASK_TIMEOUT_MS = 3000;

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
    /** 取字流程進行中（與框選截圖的 busy 各自獨立，互不影響）。 */
    private boolean picking = false;
    private View mask;
    private Bitmap maskShot;
    /** 遮罩無條件收掉，不管流程走到哪一步。 */
    private final Runnable maskTimeout = () -> {
        if (picking) {
            android.util.Log.w(TAG, "ShotService: 取字逾時，撤遮罩");
            endPick();
        }
    };
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
        main.removeCallbacks(maskTimeout);
        if (picking) endPick();
        super.onDestroy();
    }

    /** 只為了記住前景是誰（shot_service.xml 已宣告 typeWindowStateChanged）。 */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
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
     * 指令 7：把 (x,y) 那則訊息帶進 LINE 的「選取文字」畫面。
     *
     * PC 端在平板模式收到左鍵點擊就送這個（座標＝Sync 推算的平板游標位置）。
     * 回傳「有沒有開始」——沒有文字節點、前景不是 LINE 都直接回 false 且
     * 什麼都不做，所以點圖片、貼圖、空白處完全沒有副作用。
     */
    static boolean selectText(final int x, final int y, final boolean useMask) {
        final ShotService s = instance;
        if (s == null) return false;
        final boolean[] r = new boolean[1];
        final CountDownLatch latch = new CountDownLatch(1);
        s.main.post(() -> {
            try { r[0] = s.startPick(x, y, useMask); }
            finally { latch.countDown(); }
        });
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
        return r[0];
    }

    /**
     * 指令 8：直接把 (x,y) 那則訊息的整段文字寫進平板剪貼簿。
     *
     * 不碰 UI、不依賴選單文字，任何 App 都通；PC 端靠既有的 clipagent
     * CLIPTEXT 回拉，不必另接管道。要只取一段時才用指令 7。
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

    /** 主執行緒；回傳是否真的開始了一場取字。 */
    private boolean startPick(int x, int y, boolean useMask) {
        if (picking) return false;
        String pkg = currentPkg();
        if (!PKG_LINE.equals(pkg)) return false;
        // 先看游標下有沒有文字，有才動作 —— 圖片/貼圖只有 contentDescription
        // 沒有 getText()，於是連長按都不會發生，也就不會冒出選單閃一下。
        final AccessibilityNodeInfo node = findTextNodeAt(x, y);
        if (node == null) return false;
        picking = true;
        main.removeCallbacks(maskTimeout);
        main.postDelayed(maskTimeout, MASK_TIMEOUT_MS);
        if (useMask && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 蓋上凍結畫面，把長按漣漪和選單整個藏起來，使用者只看到
            // 「畫面靜止一下 → 直接進入選取模式」
            takeScreenshot(Display.DEFAULT_DISPLAY,
                    Executors.newSingleThreadExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult res) {
                            Bitmap bmp = null;
                            try (HardwareBuffer hb = res.getHardwareBuffer()) {
                                Bitmap raw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                                if (raw != null) {
                                    bmp = raw.copy(Bitmap.Config.ARGB_8888, false);
                                    raw.recycle();
                                }
                            } catch (Exception e) {
                                android.util.Log.w(TAG, "ShotService: 遮罩截圖失敗 " + e);
                            }
                            final Bitmap b = bmp;
                            main.post(() -> {
                                if (picking) {
                                    if (b != null) showMask(b);
                                    longPress(node, x, y);
                                }
                            });
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            // 截不到就不遮，功能照做（頂多看到選單閃一下）
                            android.util.Log.w(TAG, "ShotService: 遮罩截圖失敗 " + errorCode);
                            main.post(() -> { if (picking) longPress(node, x, y); });
                        }
                    });
        } else {
            longPress(node, x, y);
        }
        return true;
    }

    /**
     * 長按目標節點叫出選單。
     *
     * ACTION_LONG_CLICK 要下在真正 long-clickable 的那一層（LINE 的文字
     * TextView 常常只是容器的小孩），所以往上找第一個可長按的祖先。
     */
    private void longPress(AccessibilityNodeInfo node, int x, int y) {
        AccessibilityNodeInfo target = node;
        try {
            AccessibilityNodeInfo p = node;
            while (p != null && !p.isLongClickable()) p = p.getParent();
            if (p != null) target = p;
        } catch (Throwable ignore) { }
        boolean ok = false;
        try {
            ok = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ShotService: 長按失敗 " + t);
        }
        android.util.Log.d(TAG, "ShotService: 取字長按 (" + x + "," + y + ") ok=" + ok);
        final int base = windowCount();
        main.postDelayed(() -> waitMenu(1, x, y, false, base), MENU_POLL_MS);
    }

    /**
     * 等長按選單出現 → 點「選取文字」。
     *
     * 兩層保險：ACTION_LONG_CLICK 沒效果（有些 view 只認真實觸控）時，
     * 600ms 後改用 dispatchGesture 送一次真的長按；再等不到就放棄。
     *
     * 放棄時只有在「確實多了一個視窗」才送 BACK 把選單收掉——沒看到選單
     * 就送 BACK 會直接退出聊天室，那個副作用比留著選單嚴重得多。
     */
    private void waitMenu(int tries, int x, int y, boolean gestured, int base) {
        if (!picking) return;
        AccessibilityNodeInfo item = findMenuItem();
        if (item != null) {
            AccessibilityNodeInfo c = item;
            try {
                while (c != null && !c.isClickable()) c = c.getParent();
            } catch (Throwable ignore) { c = null; }
            boolean ok = (c != null ? c : item)
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK);
            android.util.Log.d(TAG, "ShotService: 點「" + MENU_SELECT_TEXT + "」ok=" + ok);
            main.postDelayed(this::endPick, MASK_LINGER_MS);
            return;
        }
        long elapsed = tries * MENU_POLL_MS;
        if (!gestured && elapsed >= GESTURE_FALLBACK_MS) {
            gestureLongPress(x, y);
            main.postDelayed(() -> waitMenu(tries + 1, x, y, true, base), MENU_POLL_MS);
            return;
        }
        if (elapsed >= MENU_WAIT_MS) {
            if (windowCount() > base) {
                android.util.Log.d(TAG, "ShotService: 選單裡沒有「"
                        + MENU_SELECT_TEXT + "」，收掉");
                performGlobalAction(GLOBAL_ACTION_BACK);
            } else {
                android.util.Log.d(TAG, "ShotService: 長按沒有叫出任何選單，放棄");
            }
            main.postDelayed(this::endPick, MASK_LINGER_MS);
            return;
        }
        main.postDelayed(() -> waitMenu(tries + 1, x, y, gestured, base), MENU_POLL_MS);
    }

    /** 真實觸控長按（ACTION_LONG_CLICK 無效時的後備）。 */
    private void gestureLongPress(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(
                            p, 0, GESTURE_HOLD_MS))
                    .build();
            dispatchGesture(g, null, null);
            android.util.Log.d(TAG, "ShotService: 改用觸控長按 (" + x + "," + y + ")");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ShotService: 觸控長按失敗 " + t);
        }
    }

    private AccessibilityNodeInfo findMenuItem() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            List<AccessibilityNodeInfo> found =
                    root.findAccessibilityNodeInfosByText(MENU_SELECT_TEXT);
            if (found == null) return null;
            for (AccessibilityNodeInfo n : found) {
                if (n != null && n.isVisibleToUser()) return n;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ShotService: 找選單失敗 " + t);
        }
        return null;
    }

    /** 目前有幾個視窗——用來判斷長按到底有沒有叫出彈出選單。 */
    private int windowCount() {
        try {
            return getWindows() == null ? 0 : getWindows().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * (x,y) 命中的最深、且**有 getText()** 的節點。
     *
     * 只認 getText()，絕不認 contentDescription：LINE 的圖片、貼圖、通話
     * 記錄都是靠 contentDescription 提供無障礙文字的，認了就會把它們誤判
     * 成文字訊息而去長按。
     */
    private AccessibilityNodeInfo findTextNodeAt(int x, int y) {
        try {
            return deepestTextNode(getRootInActiveWindow(), x, y);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ShotService: 節點查找失敗 " + t);
            return null;
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

    // ── 取字用的凍結畫面遮罩 ────────────────────────────
    private void showMask(Bitmap bmp) {
        removeMask();
        maskShot = bmp;
        try {
            View v = new View(this) {
                @Override
                protected void onDraw(Canvas canvas) {
                    if (maskShot != null) canvas.drawBitmap(maskShot, 0, 0, null);
                }

                @Override
                public boolean onTouchEvent(MotionEvent e) {
                    return true;   // 遮罩期間的點擊全部吃掉，免得打到底下看不見的選單
                }
            };
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            wm.addView(v, lp);
            mask = v;
        } catch (Exception e) {
            android.util.Log.e(TAG, "ShotService: 遮罩顯示失敗 " + e);
            maskShot = null;
        }
    }

    private void removeMask() {
        if (mask != null) {
            try { wm.removeView(mask); } catch (Exception ignore) { }
            mask = null;
        }
        if (maskShot != null) {
            maskShot.recycle();
            maskShot = null;
        }
    }

    private void endPick() {
        main.removeCallbacks(maskTimeout);
        removeMask();
        picking = false;
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
