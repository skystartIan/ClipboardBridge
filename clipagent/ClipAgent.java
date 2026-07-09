package com.clipboardbridge.agent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 平板 → PC 剪貼簿橋接 agent（以 shell uid 執行，透過 app_process 啟動）。
 *
 * Android 10+ 禁止背景 app 讀剪貼簿，但 shell uid（com.android.shell）不受限。
 * 本 agent 借用 scrcpy 的 ActivityThread 反射手法建立系統 Context，
 * 取得 ClipboardManager，監聽 primary clip 變更：
 *   - 含 URI（圖片）→ stdout 印 "CLIPURI:<uri>"，PC 端用 content read 拉回
 *   - 純文字      → stdout 印 "CLIPTEXT:<base64>"（可選，PC 端已有 scrcpy 文字路徑）
 *
 * 啟動：
 *   CLASSPATH=/data/local/tmp/clipagent.dex app_process / \
 *     com.clipboardbridge.agent.ClipAgent
 */
public final class ClipAgent {

    private static final String PACKAGE_NAME = "com.android.shell";
    private static Class<?> ACTIVITY_THREAD_CLASS;
    private static Object ACTIVITY_THREAD;

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            setupActivityThread();
            Context ctx = new FakeContext(getSystemContext());

            final ClipboardManager cm =
                    (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                System.out.println("CLIPERR:no clipboard service");
                return;
            }
            fixContextField(cm, ctx);

            cm.addPrimaryClipChangedListener(
                new ClipboardManager.OnPrimaryClipChangedListener() {
                    @Override
                    public void onPrimaryClipChanged() {
                        try {
                            report(cm);
                        } catch (Throwable t) {
                            System.out.println("CLIPERR:" + t);
                            System.out.flush();
                        }
                    }
                });

            System.out.println("CLIPRDY:listening");
            System.out.flush();
            Looper.loop();
        } catch (Throwable t) {
            System.out.println("CLIPERR:fatal " + t);
            t.printStackTrace();
        }
    }

    private static void report(ClipboardManager cm) {
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }
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
                    text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP);
            System.out.println("CLIPTEXT:" + b64);
            System.out.flush();
        }
    }

    // ── scrcpy 式 ActivityThread 反射（精簡版）────────────────────────
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

        // Samsung / API31+：ConfigurationController 必須非空
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
        // ClipboardManager 內部持有的 mContext 需指向我們的 FakeContext，
        // 否則權限檢查用到的 package 會不對
        for (String fieldName : new String[]{"mContext"}) {
            try {
                Field f = service.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(service, ctx);
            } catch (ReflectiveOperationException ignore) {
            }
        }
    }

    // ── 假 Context：對外宣稱自己是 com.android.shell ─────────────────
    private static final class FakeContext extends ContextWrapper {
        FakeContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return PACKAGE_NAME;
        }

        @Override
        public String getOpPackageName() {
            return PACKAGE_NAME;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public android.content.AttributionSource getAttributionSource() {
            // API 31+ 剪貼簿權限檢查會用到；宣告成 shell uid
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
