package com.clipboardbridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PC → 平板圖片剪貼簿的 TCP 直送伺服器（掛在常駐的 NotificationService 內）。
 *
 * 協議：client 送 [4-byte big-endian length][image bytes(PNG/JPEG)]，
 * server 存入 MediaStore → setPrimaryClip → 回 1 byte（1=成功 0=失敗）。
 *
 * 相比舊路徑（adb push base64 + am start ClipboardActivity）：
 * 不經 base64、不搶焦點（RDP/全螢幕 app 不會被打斷）、快一個數量級。
 */
class ImageServer {

    private static final String TAG = ClipboardReceiver.TAG;
    static final int PORT = 9998;
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    /** 控制指令 0-4 是 DropZone 的（見 DropZone.onControl）；5 = 分享檔案給 LINE */
    private static final int CTRL_SHARE_FILE = 5;
    /** 6 = 框選截圖：叫出 ShotService 的遮罩，裁好後把 PNG 從本連線回傳 */
    private static final int CTRL_REGION_SHOT = 6;
    /**
     * 7 = 把游標下那則 LINE 訊息帶進「選取文字」畫面（之後使用者自己拉範圍）
     * 8 = 直接把游標下的整段文字寫進平板剪貼簿
     * 兩者都額外帶 [4B x][4B y]（平板實體像素，Sync 推算的游標位置），
     * 7 再多一個 [1B 是否上遮罩]。回 1 byte：1=有做、0=沒做。
     */
    private static final int CTRL_SELECT_TEXT = 7;
    private static final int CTRL_COPY_TEXT = 8;
    private static final int SHOT_TIMEOUT_S = 120;   // 使用者慢慢框，別急著斷線
    /** 框選截圖的失敗回傳碼（負數，與正常的 PNG 長度不會混淆）。 */
    private static final int SHOT_NO_SVC = -1;       // 無障礙服務沒開
    private static final int SHOT_FAILED = -2;       // 截圖／裁切失敗、逾時

    /** regionShot 的結果：png 有圖就是成功；沒圖時 cancelled 區分「取消」和「失敗」。 */
    private static final class ShotResult {
        final byte[] png;
        final boolean cancelled;
        ShotResult(byte[] png, boolean cancelled) {
            this.png = png;
            this.cancelled = cancelled;
        }
    }

    private final Context context;
    private volatile ServerSocket server;
    private volatile boolean running;
    private Thread thread;

    ImageServer(Context context) {
        this.context = context.getApplicationContext();
    }

    void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(() -> {
            // 外層迴圈：ServerSocket 或 accept 掛掉時重綁，不會一次例外就永久死掉
            while (running) {
                ServerSocket srv = null;
                try {
                    srv = new ServerSocket(PORT);
                    server = srv;
                    Log.d(TAG, "ImageServer listening :" + PORT);
                    while (running) {
                        final Socket s = srv.accept();
                        new Thread(() -> {
                            try { handle(s); }
                            catch (Throwable t) { Log.w(TAG, "handle err: " + t); }
                        }).start();
                    }
                } catch (Exception e) {
                    if (running) Log.w(TAG, "ImageServer loop err, rebinding: " + e);
                } finally {
                    try { if (srv != null) srv.close(); } catch (Exception ignore) {}
                }
                if (running) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignore) {}
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) { }
        server = null;
        thread = null;
    }

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(15000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            int len = in.readInt();
            if (len == 0) {
                // 控制訊框：len==0 + 1 byte 指令 → DropZone（檔案拖放投放區）
                int cmd = in.read();
                String ip = s.getInetAddress().getHostAddress();
                if (cmd == CTRL_SHARE_FILE) {
                    // 分享指令額外帶參數：[4B pathLen][path utf-8]
                    int plen = in.readInt();
                    if (plen <= 0 || plen > 4096) {
                        Log.e(TAG, "ImageServer: bad share path length " + plen);
                        out.write(0);
                        out.flush();
                        return;
                    }
                    byte[] pb = new byte[plen];
                    in.readFully(pb);
                    String path = new String(pb, "UTF-8");
                    Log.d(TAG, "ImageServer: share " + path + " from " + ip);
                    boolean ok = LineShare.share(context, path);
                    out.write(ok ? 1 : 0);
                    out.flush();
                    return;
                }
                if (cmd == CTRL_REGION_SHOT) {
                    // 回傳 [4B len][png]，len 是「有號」整數：
                    //   >0            後面接 PNG
                    //   0             使用者取消（PC 什麼都別做）
                    //   SHOT_NO_SVC   無障礙沒開（PC 退回全螢幕 screencap）
                    //   SHOT_FAILED   截圖失敗／逾時（同上）
                    // 以前三種都回 0，PC 一律當取消 → 任何失敗都變成「按了沒反應」
                    s.setSoTimeout((SHOT_TIMEOUT_S + 10) * 1000);
                    DataOutputStream dos = new DataOutputStream(out);
                    if (!ShotService.available()) {
                        Log.w(TAG, "ImageServer: ShotService 未啟用（無障礙權限沒開）");
                        dos.writeInt(SHOT_NO_SVC);
                        dos.flush();
                        return;
                    }
                    ShotResult r = regionShot();
                    if (r.png != null && r.png.length > 0) {
                        dos.writeInt(r.png.length);
                        dos.write(r.png);
                    } else {
                        dos.writeInt(r.cancelled ? 0 : SHOT_FAILED);
                    }
                    dos.flush();
                    return;
                }
                if (cmd == CTRL_SELECT_TEXT || cmd == CTRL_COPY_TEXT) {
                    int x = in.readInt();
                    int y = in.readInt();
                    boolean mask = (cmd == CTRL_SELECT_TEXT) && in.read() == 1;
                    boolean ok = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            && ShotService.available()) {
                        ok = (cmd == CTRL_SELECT_TEXT)
                                ? ShotService.selectText(x, y, mask)
                                : ShotService.copyText(x, y);
                    } else {
                        Log.w(TAG, "ImageServer: 取字需要無障礙服務（ShotService）");
                    }
                    out.write(ok ? 1 : 0);
                    out.flush();
                    return;
                }
                Log.d(TAG, "ImageServer: DropZone ctrl " + cmd + " from " + ip);
                DropZone.get(context).onControl(cmd, ip);
                out.write(1);
                out.flush();
                return;
            }
            if (len < 0 || len > MAX_BYTES) {
                Log.e(TAG, "ImageServer: bad length " + len);
                out.write(0);
                return;
            }
            byte[] buf = new byte[len];
            in.readFully(buf);
            Log.d(TAG, "ImageServer: received " + len + " bytes");

            Bitmap bmp = BitmapFactory.decodeByteArray(buf, 0, len);
            if (bmp == null) {
                Log.e(TAG, "ImageServer: bitmap decode failed");
                out.write(0);
                return;
            }
            Uri uri = MediaStoreUtils.saveBitmap(context, bmp);
            bmp.recycle();
            if (uri == null) {
                out.write(0);
                return;
            }

            boolean ok = setClipboard(uri);
            // 保留這張（剪貼簿指向它），刪掉之前累積的舊圖
            MediaStoreUtils.deleteOthers(context, uri);
            if (!ok) {
                // 背景寫剪貼簿被擋 → 交給前景服務（仍不搶焦點）
                try {
                    Intent i = new Intent(context, ClipboardService.class);
                    i.putExtra(ClipboardService.EXTRA_IMAGE_URI, uri.toString());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(i);
                    } else {
                        context.startService(i);
                    }
                    ok = true;
                } catch (Exception e) {
                    Log.e(TAG, "ImageServer fallback service failed: " + e.getMessage());
                }
            }
            out.write(ok ? 1 : 0);
            out.flush();
        } catch (Exception e) {
            Log.e(TAG, "ImageServer handle error: " + e.getMessage());
        }
    }

    /**
     * 叫出框選遮罩並等使用者框完。
     *
     * 沒圖時 cancelled 要誠實：只有使用者自己取消（Esc/右鍵/框太小）才算取消，
     * 逾時和系統太舊都算失敗，讓 PC 端退回全螢幕 screencap 而不是靜默放棄。
     */
    private ShotResult regionShot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "ImageServer: 系統版本不支援 takeScreenshot");
            return new ShotResult(null, false);
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final byte[][] box = new byte[1][];
        final boolean[] cancelled = new boolean[1];
        boolean started = ShotService.trigger((png, wasCancelled) -> {
            box[0] = png;
            cancelled[0] = wasCancelled;
            latch.countDown();
        });
        if (!started) {
            // 理論上呼叫端已先擋掉，留著防呼叫端漏檢
            Log.w(TAG, "ImageServer: ShotService 未啟用（無障礙權限沒開）");
            return new ShotResult(null, false);
        }
        try {
            if (!latch.await(SHOT_TIMEOUT_S, TimeUnit.SECONDS)) {
                Log.w(TAG, "ImageServer: 框選逾時");
                return new ShotResult(null, false);
            }
        } catch (InterruptedException e) {
            return new ShotResult(null, false);
        }
        return new ShotResult(box[0], cancelled[0]);
    }

    /** 在主執行緒 setPrimaryClip，等最多 3 秒回報成功與否。 */
    private boolean setClipboard(final Uri uri) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm =
                        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newUri(
                            context.getContentResolver(), "image", uri));
                    ok.set(true);
                    Log.d(TAG, "ImageServer: clipboard set " + uri);
                    Toast.makeText(context, "✓ 圖片已就緒，可貼上",
                            Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "ImageServer setPrimaryClip failed: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) { }
        return ok.get();
    }
}
