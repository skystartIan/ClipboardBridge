package com.clipboardbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;

/**
 * VPS → 平板的遠端指令通道（掛在常駐的 NotificationService 內）。
 *
 * 協議：client 送 [4B len][json utf-8]，server 回 [4B len][payload]。
 * len 是「有號」整數，和 ImageServer 的框選截圖同一套慣例：
 *   > 0   後面接 payload（多數指令是 json；shot 是 PNG bytes）
 *   < 0   錯誤碼（見 ERR_*），後面沒有東西
 * 沿用有號長度而不是另外包一層 json 錯誤欄位，是因為 shot 要回幾 MB 的 PNG，
 * 塞進 json 就得 base64，白白多三分之一流量。
 *
 * 為什麼不塞進 ImageServer 的控制訊框：那條協議已綁死 PC 端的 file_drop.py /
 * img_clipboard.py，混用會讓兩個用途互相牽制；而且指令通道要的是完全不同的
 * 安全模型（來源檢查 + 密鑰），不該讓區網內的剪貼簿功能也扛這些。
 *
 * 兩道防線，缺一不可：
 *   1. 來源 IP 必須落在 100.64.0.0/10（tailnet CGNAT）或 loopback。平板在公司
 *      WiFi 上，ServerSocket 綁 0.0.0.0 等於同網段任何人都打得到。
 *   2. 每個請求都要帶正確的 secret。沒設定 secret 就一律拒絕（fail closed），
 *      絕不「沒設定就放行」。
 */
class CommandServer {

    private static final String TAG = ClipboardReceiver.TAG;
    static final int PORT = 9097;

    private static final int MAX_REQ_BYTES = 64 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 30_000;
    /** shot 要等 takeScreenshot 回來，比一般指令寬鬆。 */
    private static final int SHOT_TIMEOUT_S = 20;
    private static final int SHELL_TIMEOUT_S = 30;
    /** 打卡類指令要等 WebView 載頁 + 頁內 fetch，比 shot 還久。 */
    private static final int PUNCH_TIMEOUT_S = 120;
    private static final int MAX_OUT_BYTES = 256 * 1024;
    /** log 指令回傳的行數（已過濾成只剩本 App 的標籤）。 */
    private static final int LOG_LINES = 400;

    // 錯誤碼（負數，與正常 payload 長度不會混淆）
    private static final int ERR_AUTH        = -1;  // secret 不對
    private static final int ERR_UNKNOWN_CMD = -2;
    private static final int ERR_FAILED      = -3;  // 指令執行失敗
    private static final int ERR_NO_SHIZUKU  = -4;  // 需要 Shizuku 但未就緒
    private static final int ERR_NO_A11Y     = -5;  // 需要無障礙服務但未啟用
    private static final int ERR_BAD_REQUEST = -6;  // 長度或 json 壞掉

    private static final String PREFS = "cmdsrv";
    private static final String KEY_SECRET = "secret";

    private final Context context;
    private volatile ServerSocket server;
    private volatile boolean running;
    private Thread thread;

    CommandServer(Context context) {
        this.context = context.getApplicationContext();
    }

    // ── 生命週期 ────────────────────────────────────────
    // 外層迴圈／rebind／per-connection try-catch 直接照抄 ImageServer：
    // 那是踩過「accept 迴圈一次例外就永久死掉」之後修好的骨架，別重寫。

    void start() {
        if (thread != null) return;
        running = true;
        Log.i(TAG, "CommandServer secret=" + secret()
                + "（用 adb logcat 取出後填進 VPS bot 的 .env）");
        thread = new Thread(() -> {
            while (running) {
                ServerSocket srv = null;
                try {
                    srv = new ServerSocket(PORT);
                    server = srv;
                    Log.d(TAG, "CommandServer listening :" + PORT);
                    while (running) {
                        final Socket s = srv.accept();
                        new Thread(() -> {
                            try { handle(s); }
                            catch (Throwable t) { Log.w(TAG, "cmd handle err: " + t); }
                        }).start();
                    }
                } catch (Exception e) {
                    if (running) Log.w(TAG, "CommandServer loop err, rebinding: " + e);
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

    // ── 安全 ────────────────────────────────────────────

    /**
     * 只收 tailnet（100.64.0.0/10）與 loopback。
     *
     * loopback 是為了 adb forward：tailnet 掛掉時這是唯一還能從 PC 打進來
     * 排錯的路。它不放寬安全性——secret 仍然必要，而平板上的其他 App 讀不到
     * 我們 SharedPreferences 裡的 secret。
     */
    private static boolean allowedSource(InetAddress addr) {
        if (addr == null) return false;
        if (addr.isLoopbackAddress()) return true;
        byte[] b = addr.getAddress();
        if (b == null || b.length != 4) return false;          // IPv6 一律拒絕
        int o1 = b[0] & 0xFF, o2 = b[1] & 0xFF;
        return o1 == 100 && o2 >= 64 && o2 <= 127;
    }

    /**
     * secret 在裝置上自己生成，只存進 App 私有的 SharedPreferences。
     *
     * 不從 /sdcard 讀（那裡任何有儲存權限的 App 都看得到），也不開 exported
     * receiver 讓外部設定（那樣別的 App 就能覆寫成自己知道的值）。生成後寫進
     * logcat 一次，用 `adb logcat -d | grep "CommandServer secret"` 取出。
     */
    private String secret() {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_SECRET, null);
        if (s != null && !s.isEmpty()) return s;
        byte[] rnd = new byte[24];
        new SecureRandom().nextBytes(rnd);
        StringBuilder sb = new StringBuilder(48);
        for (byte x : rnd) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                             .append(Character.forDigit(x & 0xF, 16));
        s = sb.toString();
        sp.edit().putString(KEY_SECRET, s).apply();
        return s;
    }

    /** 固定時間比較，不讓回應快慢洩漏前綴猜對幾個字元。 */
    private static boolean secretEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    // ── 請求處理 ────────────────────────────────────────

    private void handle(Socket sock) {
        String ip = sock.getInetAddress() != null
                ? sock.getInetAddress().getHostAddress() : "?";
        if (!allowedSource(sock.getInetAddress())) {
            // 靜默關閉：不回任何東西，區網掃描者連「這裡有個服務」都問不出來
            Log.w(TAG, "CommandServer: 拒絕非 tailnet 來源 " + ip);
            try { sock.close(); } catch (Exception ignore) {}
            return;
        }
        try (Socket s = sock) {
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            int len = in.readInt();
            if (len <= 0 || len > MAX_REQ_BYTES) {
                Log.w(TAG, "CommandServer: bad request length " + len + " from " + ip);
                reply(out, ERR_BAD_REQUEST);
                return;
            }
            byte[] buf = new byte[len];
            in.readFully(buf);

            JSONObject req;
            String cmd;
            try {
                req = new JSONObject(new String(buf, "UTF-8"));
                cmd = req.optString("cmd", "");
            } catch (Exception e) {
                Log.w(TAG, "CommandServer: bad json from " + ip);
                reply(out, ERR_BAD_REQUEST);
                return;
            }

            if (!secretEquals(secret(), req.optString("secret", null))) {
                Log.w(TAG, "CommandServer: secret 不符，來源 " + ip + " 指令 " + cmd);
                reply(out, ERR_AUTH);
                return;
            }

            Log.d(TAG, "CommandServer: " + cmd + " from " + ip);
            dispatch(cmd, req, out, s);
        } catch (Exception e) {
            Log.e(TAG, "CommandServer handle error: " + e.getMessage());
        }
    }

    private void dispatch(String cmd, JSONObject req, DataOutputStream out, Socket s)
            throws Exception {
        switch (cmd) {
            case "ping":
                replyJson(out, new JSONObject().put("ok", true)
                        .put("ts", System.currentTimeMillis()));
                return;

            case "status":
                replyJson(out, status());
                return;

            case "shot": {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                        || !ShotService.available()) {
                    // 無障礙沒開是最常見的「安靜失效」，明確回報讓 bot 能提示去開
                    reply(out, ERR_NO_A11Y);
                    return;
                }
                s.setSoTimeout((SHOT_TIMEOUT_S + 10) * 1000);
                byte[] png = ShotService.fullShot(SHOT_TIMEOUT_S);
                if (png == null || png.length == 0) { reply(out, ERR_FAILED); return; }
                out.writeInt(png.length);
                out.write(png);
                out.flush();
                return;
            }

            case "restart_agent": {
                // 獨立指令而非叫 bot 送 shell：同事有權重拉 agent，但沒有 shell 權限
                if (!AgentStarter.shizukuReady()) { reply(out, ERR_NO_SHIZUKU); return; }
                AgentStarter.ensureAgent(context);
                replyJson(out, new JSONObject().put("ok", true)
                        .put("note", "ensureAgent 已排入（冪等，看門狗會去重）"));
                return;
            }

            case "log": {
                // 固定指令、不吃參數：同事拿得到 log 但拿不到任意 shell
                if (!AgentStarter.shizukuReady()) { reply(out, ERR_NO_SHIZUKU); return; }
                // filterspec 要寫 TAG:優先級，結尾 *:S 把其他標籤靜音。
                // 不能寫成 TAG:*——* 不是合法的優先級，會被 logcat 當成語法錯誤。
                //
                // 也**不能**用 -t N：那是「取整個緩衝區最後 N 行」再套過濾，而平板上
                // 其他 App 的日誌量遠大於我們，過濾完常常只剩一兩行（2026-09-02 實測
                // -t 400 只回 1 行）。要讓 logcat 先過濾整個緩衝區，再自己 tail。
                String text = AgentStarter.runShell(
                        "logcat -d " + ClipboardReceiver.TAG + ":V CBNotification:V "
                                + AgentStarter.TAG + ":V *:S | tail -n " + LOG_LINES,
                        MAX_OUT_BYTES, SHELL_TIMEOUT_S);
                replyJson(out, new JSONObject().put("ok", true).put("log", text));
                return;
            }

            case "shell": {
                String script = req.optString("script", "");
                if (script.isEmpty()) { reply(out, ERR_BAD_REQUEST); return; }
                if (!AgentStarter.shizukuReady()) { reply(out, ERR_NO_SHIZUKU); return; }
                s.setSoTimeout((SHELL_TIMEOUT_S + 10) * 1000);
                String o = AgentStarter.runShell(script, MAX_OUT_BYTES, SHELL_TIMEOUT_S);
                replyJson(out, new JSONObject().put("ok", true).put("out", o));
                return;
            }

            case "punch_set_creds": {
                // 憑證只從這條 tailnet 通道送進來一次，之後就只活在平板的
                // EncryptedSharedPreferences 裡。**回應絕不回傳任何憑證內容。**
                String co = req.optString("company", "");
                String no = req.optString("empno", "");
                String pw = req.optString("password", "");
                if (co.isEmpty() || no.isEmpty() || pw.isEmpty()) {
                    reply(out, ERR_BAD_REQUEST);
                    return;
                }
                PunchCreds.store(context, co, no, pw);
                replyJson(out, new JSONObject().put("ok", true)
                        .put("note", "憑證已加密存入平板（company=" + co + "）"));
                return;
            }

            case "punch_login": {
                s.setSoTimeout((PUNCH_TIMEOUT_S + 10) * 1000);
                replyJson(out, PunchWebView.login(context));
                return;
            }

            case "punch_preview": {
                // 產生待確認的打卡並回預覽，**不送出**。
                s.setSoTimeout((PUNCH_TIMEOUT_S + 10) * 1000);
                replyJson(out, PunchWebView.preview(context,
                        req.optString("loc", "kh"), req.optString("note", "")));
                return;
            }

            case "punch_confirm": {
                // ★★ 這是整支 App 唯一會真的打卡的指令。★★
                // 送出的是 pending 裡存好的 payload，與預覽看到的完全一致。
                s.setSoTimeout((PUNCH_TIMEOUT_S + 10) * 1000);
                replyJson(out, PunchWebView.confirm(context));
                return;
            }

            case "punch_cancel":
                replyJson(out, PunchWebView.cancel(context));
                return;

            case "punch_diag": {
                s.setSoTimeout((PUNCH_TIMEOUT_S + 10) * 1000);
                replyJson(out, PunchWebView.diag(context));
                return;
            }

            case "punch_probe": {
                // MAYOHR 打卡的唯讀健檢。**不會送出任何打卡**，只載入打卡頁再對
                // clockInOut/useNew 發一次 GET。刻意不需要 Shizuku——整個打卡設計
                // 就是要避開「重開機後要有人點一次 Shizuku」那個單點。
                s.setSoTimeout((PUNCH_TIMEOUT_S + 10) * 1000);
                JSONObject r = PunchWebView.probe(context);
                replyJson(out, r);
                return;
            }

            default:
                reply(out, ERR_UNKNOWN_CMD);
        }
    }

    /**
     * 平板的整體健康狀態。刻意全部走「不需要 Shizuku」的本地檢查，
     * 因為 status 正是 Shizuku 掛掉時最需要問得出答案的那一支。
     */
    private JSONObject status() {
        JSONObject j = new JSONObject();
        try {
            j.put("ts", System.currentTimeMillis());
            j.put("tailscale_ip", TailscaleWatch.tailnetIp());   // null = 沒連上
            j.put("shizuku", AgentStarter.shizukuReady());
            j.put("a11y", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && ShotService.available());
            // agent 活著與否用「連得上 127.0.0.1:27210」判斷，不必動用 Shizuku 跑 ps
            j.put("agent", portOpen(AgentStarter.DEFAULT_PORT));
            j.put("image_server", portOpen(ImageServer.PORT));

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            j.put("screen_on", pm != null && pm.isInteractive());

            BatteryManager bm =
                    (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                j.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                j.put("charging", bm.isCharging());
            }
            j.put("device", Build.MODEL);
            j.put("sdk", Build.VERSION.SDK_INT);
        } catch (Exception e) {
            Log.w(TAG, "CommandServer status err: " + e);
        }
        return j;
    }

    /** 本機某個 port 有沒有人在聽。用來判斷同 App 內其他元件是否還活著。 */
    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void reply(DataOutputStream out, int errCode) throws Exception {
        out.writeInt(errCode);
        out.flush();
    }

    private static void replyJson(DataOutputStream out, JSONObject j) throws Exception {
        byte[] b = j.toString().getBytes("UTF-8");
        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }
}
