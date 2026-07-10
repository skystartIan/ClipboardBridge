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
            if (len <= 0 || len > MAX_BYTES) {
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
