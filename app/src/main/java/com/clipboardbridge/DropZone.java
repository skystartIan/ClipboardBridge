package com.clipboardbridge;

import android.content.ClipData;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 平板 → PC 檔案拖放的「返回邊緣投放區」（overlay）。
 *
 * PC 透過 ImageServer 的控制訊框（len==0 + 1 byte 指令）控制：
 *   0 = 隱藏；1 = 右緣細條；2 = 左緣細條；3 = 拖曳中加寬亮起；4 = 恢復細條。
 * PC 進平板模式就先叫出細條（DRAG_STARTED 只會廣播給「當下已存在且
 * 可見/touchable」的視窗，必須在拖曳開始前就位）；使用者長按拖檔案時
 * PC 會送 3 讓它加寬亮起；把檔案推到返回邊緣、跨屏瞬間 UHID 放開左鍵
 * → drop 落在本投放區 → 讀出 ClipData URI → TCP 串流到 PC :9996。
 *
 * URI 讀取：overlay 視窗拿不到 DragAndDropPermissions（那需要 Activity），
 * 所以優先把 URI 解析成實體路徑直接讀（app 有全檔案存取權，My Files 拖的
 * 幾乎都是 /sdcard 上的檔案），失敗才試 ContentResolver。
 */
class DropZone {

    private static final String TAG = ClipboardReceiver.TAG;
    private static final int PC_FILE_PORT = 9996;

    private static DropZone instance;

    static synchronized void init(Context c) {
        if (instance == null) instance = new DropZone(c);
    }

    static synchronized DropZone get(Context c) {
        init(c);
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView view;
    private WindowManager.LayoutParams lp;
    private boolean added = false;
    private int edgeGravity = Gravity.RIGHT;
    private volatile String pcIp;

    private static final int BG_FAINT = 0x33007AFF;    // 細條（微透藍）
    private static final int BG_ACTIVE = 0x99007AFF;   // 拖曳中
    private static final int BG_HOVER = 0xCC007AFF;    // 拖到上面

    private DropZone(Context c) {
        this.ctx = c.getApplicationContext();
    }

    /** ImageServer 控制訊框入口（socket 執行緒）。ip = PC 位址。 */
    void onControl(final int cmd, final String ip) {
        if (ip != null && !ip.isEmpty()) pcIp = ip;
        main.post(() -> {
            switch (cmd) {
                case 1: showStrip(Gravity.RIGHT); break;
                case 2: showStrip(Gravity.LEFT); break;
                case 3: setHighlight(true); break;
                case 4: setHighlight(false); break;
                default: hide(); break;
            }
        });
    }

    // ── overlay 視窗 ────────────────────────────────────
    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    private boolean ensureAdded() {
        if (added) return true;
        if (!Settings.canDrawOverlays(ctx)) {
            Log.w(TAG, "DropZone: 無 overlay 權限（PC 連線時會自動 appops 授權）");
            return false;
        }
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        view = new TextView(ctx);
        view.setText("傳\n到\n電\n腦");
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setVisibility(View.INVISIBLE);
        view.setOnDragListener(this::onDrag);
        lp = new WindowManager.LayoutParams(
                dp(10), WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = edgeGravity | Gravity.FILL_VERTICAL;
        try {
            wm.addView(view, lp);
            added = true;
            Log.d(TAG, "DropZone: overlay added");
        } catch (Exception e) {
            Log.e(TAG, "DropZone addView failed: " + e);
            view = null;
        }
        return added;
    }

    private void showStrip(int gravity) {
        if (!ensureAdded()) return;
        edgeGravity = gravity;
        lp.gravity = gravity | Gravity.FILL_VERTICAL;
        lp.width = dp(10);
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        view.setBackgroundColor(BG_FAINT);
        view.setText("");
        view.setVisibility(View.VISIBLE);
        update();
        Log.d(TAG, "DropZone: showStrip " + state());
    }

    private void setHighlight(boolean on) {
        if (!added || view.getVisibility() != View.VISIBLE) {
            if (on) showStrip(edgeGravity);
            if (!added) return;
        }
        // 加寬到 48dp：PC 會在游標離邊緣 ~80px 時提早放開左鍵，
        // drop 要落在本投放區內（避開 One UI 邊緣分割畫面熱區）
        lp.width = dp(on ? 48 : 10);
        view.setBackgroundColor(on ? BG_ACTIVE : BG_FAINT);
        view.setText(on ? "傳\n到\n電\n腦" : "");
        update();
    }

    private void hide() {
        if (!added) return;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp.width = dp(10);
        view.setVisibility(View.INVISIBLE);
        view.setText("");
        update();
    }

    private void update() {
        try {
            wm.updateViewLayout(view, lp);
        } catch (Exception e) {
            Log.w(TAG, "DropZone update failed: " + e);
        }
    }

    // ── 拖放事件 ────────────────────────────────────────
    private boolean locLogged = false;   // 每次拖曳只記一次 LOCATION

    private String state() {
        return "vis=" + (view != null ? view.getVisibility() : -1)
                + " touchable=" + (lp != null && (lp.flags
                & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0)
                + " w=" + (lp != null ? lp.width : -1);
    }

    private boolean onDrag(View v, DragEvent e) {
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                locLogged = false;
                Log.d(TAG, "DropZone: DRAG_STARTED " + state()
                        + " desc=" + e.getClipDescription());
                notifyPc(1);   // 告訴 PC：平板拖曳開始（供提早放開左鍵）
                return true;   // 一律表達興趣（是否收得到 drop 由 touchable 決定）
            case DragEvent.ACTION_DRAG_LOCATION:
                if (!locLogged) {
                    locLogged = true;
                    Log.d(TAG, "DropZone: DRAG_LOCATION 首次進入 ("
                            + e.getX() + "," + e.getY() + ")");
                }
                return true;
            case DragEvent.ACTION_DRAG_ENTERED:
                Log.d(TAG, "DropZone: DRAG_ENTERED");
                view.setBackgroundColor(BG_HOVER);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                Log.d(TAG, "DropZone: DRAG_EXITED");
                view.setBackgroundColor(BG_ACTIVE);
                return true;
            case DragEvent.ACTION_DROP:
                Log.d(TAG, "DropZone: DROP " + state());
                handleDrop(e);
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                Log.d(TAG, "DropZone: DRAG_ENDED result=" + e.getResult());
                notifyPc(0);   // 拖曳結束
                if (view.getVisibility() == View.VISIBLE)
                    view.setBackgroundColor(BG_FAINT);
                return true;
        }
        return false;
    }

    private void handleDrop(DragEvent e) {
        ClipData cd = e.getClipData();
        if (cd == null || cd.getItemCount() == 0) {
            Log.w(TAG, "DropZone: drop 沒有 ClipData");
            return;
        }
        final List<Uri> uris = new ArrayList<>();
        for (int i = 0; i < cd.getItemCount(); i++) {
            Uri u = cd.getItemAt(i).getUri();
            if (u != null) uris.add(u);
        }
        Log.d(TAG, "DropZone: drop " + uris.size() + " uris");
        if (uris.isEmpty()) {
            toast("拖進來的不是檔案");
            return;
        }
        final String ip = pcIp;
        if (ip == null || ip.isEmpty()) {
            toast("不知道 PC 位址，無法傳送");
            return;
        }
        new Thread(() -> sendFiles(uris, ip)).start();
    }

    // ── 檔案來源解析 ────────────────────────────────────
    private static class FileSource {
        final String name;
        final long size;
        final InputStream stream;
        final File tmp;    // cache 落地檔（傳完刪）

        FileSource(String name, long size, InputStream stream, File tmp) {
            this.name = name;
            this.size = size;
            this.stream = stream;
            this.tmp = tmp;
        }
    }

    private static String uriToPath(Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) return uri.getPath();
            if (!"content".equals(uri.getScheme())) return null;
            String auth = uri.getAuthority() == null ? "" : uri.getAuthority();
            String path = uri.getPath() == null ? "" : uri.getPath();
            // Samsung My Files: /device_storage/... → /storage/emulated/0/...
            if (auth.contains("myfiles") && path.startsWith("/device_storage/"))
                return "/storage/emulated/0/"
                        + path.substring("/device_storage/".length());
            // SAF: document/primary:DCIM/x.jpg
            if (auth.equals("com.android.externalstorage.documents")) {
                String[] split = DocumentsContract.getDocumentId(uri).split(":", 2);
                if (split.length == 2 && "primary".equalsIgnoreCase(split[0]))
                    return "/storage/emulated/0/" + split[1];
            }
            // 泛用：path 內含存在的絕對路徑
            int i = path.indexOf("/storage/");
            if (i >= 0 && new File(path.substring(i)).exists())
                return path.substring(i);
            if (new File(path).exists()) return path;
        } catch (Exception ignore) { }
        return null;
    }

    private FileSource resolve(Uri uri) {
        // 1) 實體路徑直讀（全檔案存取權；不需要 drag 的 URI 授權）
        String p = uriToPath(uri);
        if (p != null) {
            File f = new File(p);
            if (f.isFile() && f.canRead()) {
                try {
                    return new FileSource(f.getName(), f.length(),
                            new FileInputStream(f), null);
                } catch (Exception ignore) { }
            }
        }
        // 2) ContentResolver（同 app 或有授權的 URI 才會成功）
        try {
            String name = null;
            long size = -1;
            try (Cursor c = ctx.getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0) name = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                }
            } catch (Exception ignore) { }
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            if (name == null || name.isEmpty()) {
                String seg = uri.getLastPathSegment();
                name = seg == null ? "file" : new File(seg).getName();
            }
            if (size < 0) {   // 大小未知 → 先落地 cache 取得長度
                File tmp = new File(ctx.getCacheDir(),
                        "drop_" + System.currentTimeMillis());
                try (OutputStream o = new FileOutputStream(tmp)) {
                    copy(in, o);
                }
                in.close();
                return new FileSource(name, tmp.length(),
                        new FileInputStream(tmp), tmp);
            }
            return new FileSource(name, size, in, null);
        } catch (Exception e) {
            Log.w(TAG, "DropZone resolve(" + uri + ") failed: " + e);
            return null;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    // ── 傳送到 PC ───────────────────────────────────────
    // 協議：[4B name_len][name utf-8][8B size][bytes]... 名稱長度 0 = 結束；
    // PC 每收完一個檔案回 1 byte（1=成功）。
    private void sendFiles(List<Uri> uris, String ip) {
        int sent = 0, skipped = 0;
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(ip, PC_FILE_PORT), 5000);
            sock.setSoTimeout(120000);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(sock.getOutputStream()));
            InputStream ackIn = sock.getInputStream();
            for (Uri uri : uris) {
                FileSource src = resolve(uri);
                if (src == null) {
                    skipped++;
                    Log.w(TAG, "DropZone: 讀不到 " + uri);
                    continue;
                }
                Log.d(TAG, "DropZone: 傳送 " + src.name + " (" + src.size + " B)");
                byte[] nameB = src.name.getBytes("UTF-8");
                out.writeInt(nameB.length);
                out.write(nameB);
                out.writeLong(src.size);
                byte[] buf = new byte[65536];
                long remaining = src.size;
                int n;
                while (remaining > 0 && (n = src.stream.read(buf, 0,
                        (int) Math.min(buf.length, remaining))) > 0) {
                    out.write(buf, 0, n);
                    remaining -= n;
                }
                src.stream.close();
                out.flush();
                int ack = ackIn.read();
                if (src.tmp != null) src.tmp.delete();
                if (ack == 1) {
                    sent++;
                } else {
                    Log.w(TAG, "DropZone: PC 回應失敗 " + src.name);
                    break;
                }
            }
            out.writeInt(0);
            out.flush();
        } catch (Exception e) {
            Log.e(TAG, "DropZone sendFiles failed: " + e);
        }
        final String msg;
        if (sent == uris.size()) msg = "✓ 已傳 " + sent + " 個檔案到電腦";
        else msg = "傳到電腦 " + sent + "/" + uris.size()
                + (skipped > 0 ? "（" + skipped + " 個無法讀取）" : "");
        toast(msg);
    }

    /** 拖曳開始/結束事件回報 PC（:9996 控制訊框 nlen=-2 + 1B 事件）。 */
    private void notifyPc(int event) {
        final String ip = pcIp;
        if (ip == null || ip.isEmpty()) return;
        new Thread(() -> {
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress(ip, PC_FILE_PORT), 3000);
                DataOutputStream out = new DataOutputStream(sock.getOutputStream());
                out.writeInt(-2);          // 0xFFFFFFFE = 拖曳事件訊框
                out.write(event);
                out.flush();
            } catch (Exception e) {
                Log.w(TAG, "DropZone notifyPc(" + event + ") failed: " + e);
            }
        }).start();
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }
}
