package com.clipboardbridge.agent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 平板剪貼簿 agent（shell uid，app_process 啟動）。兩個角色：
 *
 *  1) 對「公司電腦」：把平板剪貼簿變更印到 stdout（CLIPURI/CLIPTEXT），
 *     公司電腦的 Sync 用 content read 拉回。（既有 M7 行為）
 *
 *  2) 對「遠端 PC」（走 Tailscale 直連，公司電腦不碰遠端）：
 *     - 平板剪貼簿出現圖片 → exec `content read` 取 bytes → 送到遠端 :port
 *     - 收到遠端來的圖片 bytes → 印 "PEERIMG:<base64>" 給公司電腦貼上
 *     （文字走既有 RDP 剪貼簿，peer 只處理圖片）
 *
 * 啟動：
 *   CLASSPATH=/data/local/tmp/clipagent.dex app_process / \
 *     com.clipboardbridge.agent.ClipAgent [remoteIp] [port]
 *   不帶 remoteIp = 只做角色 1（純回報，向下相容）。
 */
public final class ClipAgent {

    private static final String PACKAGE_NAME = "com.android.shell";
    private static Class<?> ACTIVITY_THREAD_CLASS;
    private static Object ACTIVITY_THREAD;

    private static String remoteIp = null;
    private static int port = 27210;
    private static ClipboardManager CM;
    private static final List<Socket> OUT_SOCKS = new ArrayList<>();
    private static volatile String lastRelayHash = "";
    private static volatile long suppressClipSendUntil = 0;

    public static void main(String[] args) {
        try {
            if (args.length >= 1 && args[0].length() > 0) remoteIp = args[0];
            if (args.length >= 2) {
                try { port = Integer.parseInt(args[1]); } catch (Exception ignore) {}
            }

            Looper.prepareMainLooper();
            setupActivityThread();
            Context ctx = new FakeContext(getSystemContext());

            final ClipboardManager cm =
                    (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                System.out.println("CLIPERR:no clipboard service");
                return;
            }
            CM = cm;
            fixContextField(cm, ctx);

            cm.addPrimaryClipChangedListener(
                new ClipboardManager.OnPrimaryClipChangedListener() {
                    @Override
                    public void onPrimaryClipChanged() {
                        try {
                            report(cm);
                            if (remoteIp != null) relayClipToRemote(cm);
                        } catch (Throwable t) {
                            System.out.println("CLIPERR:" + t);
                            System.out.flush();
                        }
                    }
                });

            if (remoteIp != null) {
                startServer();
                startClient();
                System.out.println("CLIPPEER:remote=" + remoteIp + ":" + port);
            }
            System.out.println("CLIPRDY:listening");
            System.out.flush();
            Looper.loop();
        } catch (Throwable t) {
            System.out.println("CLIPERR:fatal " + t);
            t.printStackTrace();
        }
    }

    // ── 角色 1：回報給公司電腦（既有）────────────────────────────────
    private static void report(ClipboardManager cm) {
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        ClipData.Item item = clip.getItemAt(0);
        Uri uri = item.getUri();
        if (uri != null) {
            System.out.println("CLIPURI:" + uri);
            System.out.flush();
            return;
        }
        CharSequence text = item.getText();
        if (text != null && text.length() > 0) {
            String b64 = android.util.Base64.encodeToString(
                    text.toString().getBytes(StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP);
            System.out.println("CLIPTEXT:" + b64);
            System.out.flush();
        }
    }

    // ── 角色 2a：平板剪貼簿圖片 → 送遠端 ─────────────────────────────
    private static void relayClipToRemote(ClipboardManager cm) {
        if (System.currentTimeMillis() < suppressClipSendUntil) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        Uri uri = clip.getItemAt(0).getUri();
        if (uri == null) return;                 // 純文字走 RDP，不 relay
        byte[] bytes = execContentRead(uri);
        if (bytes == null || bytes.length < 64) return;
        String h = md5(bytes);
        if (h.equals(lastRelayHash)) return;     // 剛送/剛收過，別重送
        lastRelayHash = h;
        broadcast(bytes);
        System.out.println("PEERSENT:" + bytes.length);
        System.out.flush();
    }

    /** exec `content read --uri <uri>` 取圖片 bytes（shell uid 有權限）。 */
    private static byte[] execContentRead(Uri uri) {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"content", "read", "--uri", uri.toString()});
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            p.waitFor();
            return bos.toByteArray();
        } catch (Exception e) {
            System.out.println("CLIPERR:content read " + e);
            return null;
        }
    }

    // ── 角色 2b：收遠端圖片 → 印給公司電腦 ───────────────────────────
    private static void onRemoteImage(byte[] payload) {
        String h = md5(payload);
        if (h.equals(lastRelayHash)) return;     // echo（我們剛送出去的）
        lastRelayHash = h;
        // 抑制：接下來公司電腦會把這張推回平板剪貼簿，別再送回遠端
        suppressClipSendUntil = System.currentTimeMillis() + 6000;
        String b64 = android.util.Base64.encodeToString(payload,
                android.util.Base64.NO_WRAP);
        System.out.println("PEERIMG:" + b64);
        System.out.flush();
    }

    // ── 網路：server（收）+ client（送）──────────────────────────────
    private static void startServer() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        ServerSocket srv = new ServerSocket(port);
                        while (true) {
                            final Socket s = srv.accept();
                            new Thread(new Runnable() {
                                public void run() { recvLoop(s); }
                            }).start();
                        }
                    } catch (Exception e) {
                        System.out.println("CLIPERR:server " + e);
                        sleep(2000);
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void recvLoop(Socket s) {
        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            while (true) {
                int kind = in.read();
                if (kind < 0) break;
                int len = in.readInt();
                if (len < 0 || len > 64 * 1024 * 1024) break;
                byte[] payload = new byte[len];
                in.readFully(payload);
                if (kind == 'I') onRemoteImage(payload);
                // 'T' 文字忽略（走 RDP）
            }
        } catch (Exception ignore) {
        } finally {
            try { s.close(); } catch (Exception ignore) {}
        }
    }

    private static void startClient() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    boolean connected;
                    synchronized (OUT_SOCKS) { connected = !OUT_SOCKS.isEmpty(); }
                    if (!connected) {
                        try {
                            Socket s = new Socket();
                            s.connect(new java.net.InetSocketAddress(remoteIp, port), 5000);
                            s.setTcpNoDelay(true);
                            synchronized (OUT_SOCKS) { OUT_SOCKS.add(s); }
                            System.out.println("CLIPPEER:connected " + remoteIp);
                            System.out.flush();
                        } catch (Exception e) {
                            sleep(3000);
                            continue;
                        }
                    }
                    sleep(1000);
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void broadcast(byte[] imgBytes) {
        synchronized (OUT_SOCKS) {
            List<Socket> dead = new ArrayList<>();
            for (Socket s : OUT_SOCKS) {
                try {
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    out.writeByte('I');
                    out.writeInt(imgBytes.length);
                    out.write(imgBytes);
                    out.flush();
                } catch (Exception e) {
                    dead.add(s);
                }
            }
            for (Socket s : dead) {
                try { s.close(); } catch (Exception ignore) {}
                OUT_SOCKS.remove(s);
            }
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────
    private static String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) {}
    }

    // ── scrcpy 式 ActivityThread 反射 ────────────────────────────────
    private static void setupActivityThread() throws Exception {
        ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread");
        Constructor<?> c = ACTIVITY_THREAD_CLASS.getDeclaredConstructor();
        c.setAccessible(true);
        ACTIVITY_THREAD = c.newInstance();

        Field cur = ACTIVITY_THREAD_CLASS.getDeclaredField("sCurrentActivityThread");
        cur.setAccessible(true);
        cur.set(null, ACTIVITY_THREAD);

        Field sys = ACTIVITY_THREAD_CLASS.getDeclaredField("mSystemThread");
        sys.setAccessible(true);
        sys.setBoolean(ACTIVITY_THREAD, true);

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Class<?> ccClass = Class.forName("android.app.ConfigurationController");
                Class<?> atiClass = Class.forName("android.app.ActivityThreadInternal");
                Constructor<?> ccCtor = ccClass.getDeclaredConstructor(atiClass);
                ccCtor.setAccessible(true);
                Object cc = ccCtor.newInstance(ACTIVITY_THREAD);
                Field ccField = ACTIVITY_THREAD_CLASS.getDeclaredField("mConfigurationController");
                ccField.setAccessible(true);
                ccField.set(ACTIVITY_THREAD, cc);
            } catch (Throwable ignore) {
            }
        }
    }

    private static Context getSystemContext() throws Exception {
        Method m = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext");
        m.setAccessible(true);
        return (Context) m.invoke(ACTIVITY_THREAD);
    }

    private static void fixContextField(Object service, Context ctx) {
        for (String fieldName : new String[]{"mContext"}) {
            try {
                Field f = service.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(service, ctx);
            } catch (ReflectiveOperationException ignore) {
            }
        }
    }

    private static final class FakeContext extends ContextWrapper {
        FakeContext(Context base) { super(base); }

        @Override public String getPackageName() { return PACKAGE_NAME; }
        @Override public String getOpPackageName() { return PACKAGE_NAME; }
        @Override public Context getApplicationContext() { return this; }

        @Override
        public android.content.AttributionSource getAttributionSource() {
            android.content.AttributionSource.Builder b =
                    new android.content.AttributionSource.Builder(android.os.Process.SHELL_UID);
            b.setPackageName(PACKAGE_NAME);
            return b.build();
        }

        @Override
        public Object getSystemService(String name) {
            Object service = super.getSystemService(name);
            if (service != null &&
                    (Context.CLIPBOARD_SERVICE.equals(name) || "semclipboard".equals(name))) {
                try {
                    Field f = service.getClass().getDeclaredField("mContext");
                    f.setAccessible(true);
                    f.set(service, this);
                } catch (ReflectiveOperationException ignore) {
                }
            }
            return service;
        }
    }
}
