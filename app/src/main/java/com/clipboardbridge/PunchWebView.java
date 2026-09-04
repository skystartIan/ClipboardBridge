package com.clipboardbridge;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MAYOHR Apollo 打卡用的常駐 WebView。
 *
 * 為什麼用 WebView 而不是 Java 直接發 HTTP：
 *
 *   1. **登入頁是 JS 渲染的 SPA**。實測 `asiaauth.mayohr.com/HRM/Account/Login`
 *      的 HTML 只有 1052 bytes，裡面只有 `__RequestVerificationToken`，
 *      `companyCode` / `employeeNo` / `password` 三個欄位是 JS 跑完才長出來的。
 *      沒有瀏覽器引擎就填不了表。
 *   2. **cookie 有兩層壽命**：`__ModuleSessionCookie` 的 JWT 約 7 天，但同送的
 *      Imperva WAF cookie `incap_ses_*` 只有幾十分鐘。若把 cookie 撈出來自己發
 *      HTTP，等一下再送就會拿到死的 WAF cookie。**在頁面裡面 fetch** 則由引擎
 *      自己維護，WAF 挑戰也由它處理——這個坑直接消失。
 *   3. httpOnly 的 session cookie 本來就讀不到，但頁內 fetch 會自動帶上。
 *
 * 為什麼要掛進 WindowManager 當 1x1 隱形 overlay：
 *   沒有附加到 window 的 WebView，JS 計時器與部分排程會被大幅節流，非同步的
 *   fetch 可能永遠不回。App 已經有 SYSTEM_ALERT_WINDOW 權限，掛一個 1x1、
 *   不可觸控的 overlay 是最省事又可靠的做法。使用者看不到它。
 *
 * ★ 執行緒：WebView 的所有操作**都必須在主執行緒**。CommandServer 是在自己的
 *   工作執行緒上呼叫本類別的，所以每個公開方法都用 latch 把結果同步回去。
 */
class PunchWebView {

    private static final String TAG = ClipboardReceiver.TAG;

    /** 打卡頁。登入後停在這裡，之後所有 fetch 都在這個 origin 底下發。 */
    static final String APOLLO_URL = "https://apollo.mayohr.com/ta?id=webpunch";

    /** 唯讀預檢：只問「這組憑證打卡服務認不認」，不會打卡。 */
    private static final String PRECHECK_URL =
            "https://apollo.mayohr.com/backend/platform-bff/api/clockInOut/useNew";

    /**
     * 登入頁。`original_target` 讓它登完自己導回打卡頁，與 auto_login.py:10 同一組參數。
     *
     * 注意這頁是 JS 渲染的：實測純 HTTP 抓回來只有 1052 bytes，裡面只有
     * `__RequestVerificationToken`，三個帳密欄位是跑完 JS 才長出來的。
     */
    private static final String LOGIN_URL =
            "https://asiaauth.mayohr.com/HRM/Account/Login"
            + "?original_target=https%3A%2F%2Fapollo.mayohr.com%2Fta%3Fid%3Dwebpunch"
            + "&lang=zh-tw";

    private static final int PAGE_LOAD_TIMEOUT_MS = 45_000;
    private static final int JS_EVAL_TIMEOUT_MS   = 10_000;
    /** 頁內 fetch 的等待上限。WAF 偶爾會插一段挑戰，給寬一點。 */
    private static final int FETCH_TIMEOUT_MS     = 30_000;
    private static final int POLL_INTERVAL_MS     = 400;
    /** 登入頁的表單是 JS 渲染的，最多等這麼久讓它長出來。 */
    private static final int LOGIN_FORM_WAIT_MS   = 20_000;

    private static WebView web;              // 只在主執行緒碰
    private static volatile boolean attached;
    /** 由 WebViewClient 在 onPageFinished 時倒數；每次 load 前換新的。 */
    private static volatile CountDownLatch pageLatch;

    private PunchWebView() { }

    // ── 生命週期 ────────────────────────────────────────

    /**
     * 確保 WebView 存在且已掛上 overlay。可重複呼叫（冪等）。
     *
     * 沿用 ImageServer / CommandServer 的慣例：失敗回 false 而不是丟例外，
     * 讓呼叫端能回一個明確的錯誤碼給 bot，而不是讓整條連線斷掉。
     */
    private static boolean ensureWeb(final Context ctx) {
        if (attached && web != null) return true;
        final Context app = ctx.getApplicationContext();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Boolean> ok = new AtomicReference<>(false);

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (web == null) {
                    web = new WebView(app);
                    WebSettings st = web.getSettings();
                    st.setJavaScriptEnabled(true);
                    // Apollo 是 SPA，localStorage 少不了
                    st.setDomStorageEnabled(true);
                    st.setDatabaseEnabled(true);
                    // 不覆寫 User-Agent：頁面與頁內 fetch 用同一個 UA 才一致。
                    // punch.py 在「自己發 HTTP」時要偽造桌面 UA，是因為那不是真的
                    // 瀏覽器；這裡是真的，反而不該騙。
                    CookieManager cm = CookieManager.getInstance();
                    cm.setAcceptCookie(true);
                    cm.setAcceptThirdPartyCookies(web, true);

                    web.setWebViewClient(new WebViewClient() {
                        @Override public void onPageFinished(WebView v, String url) {
                            Log.d(TAG, "PunchWebView 載入完成：" + url);
                            CountDownLatch l = pageLatch;
                            if (l != null) l.countDown();
                        }
                    });
                }
                if (!attached) {
                    WindowManager wm =
                            (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                    int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            1, 1, type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    wm.addView(web, lp);
                    attached = true;
                }
                ok.set(true);
            } catch (Throwable t) {
                Log.e(TAG, "PunchWebView 建立失敗：" + t);
            } finally {
                done.countDown();
            }
        });

        try { done.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
        return ok.get();
    }

    // ── 基本操作（都會把結果同步回呼叫端執行緒）──────────

    /** 載入網址並等到 onPageFinished。逾時回 false。 */
    private static boolean load(final String url) {
        final CountDownLatch l = new CountDownLatch(1);
        pageLatch = l;
        new Handler(Looper.getMainLooper()).post(() -> web.loadUrl(url));
        try {
            return l.await(PAGE_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * 在頁面裡跑一段 JS，回傳結果字串（已去掉 evaluateJavascript 的 JSON 引號）。
     * 逾時或失敗回 null。
     */
    private static String eval(final String js) {
        final CountDownLatch l = new CountDownLatch(1);
        final AtomicReference<String> ref = new AtomicReference<>(null);
        new Handler(Looper.getMainLooper()).post(() ->
                web.evaluateJavascript(js, (ValueCallback<String>) value -> {
                    ref.set(value);
                    l.countDown();
                }));
        try {
            if (!l.await(JS_EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            return null;
        }
        return unquote(ref.get());
    }

    /**
     * evaluateJavascript 回來的是 JSON 字面值——字串會多一層引號與跳脫。
     * 這裡只還原最常見的幾種，夠我們傳 JSON 結果用。
     */
    private static String unquote(String v) {
        if (v == null || "null".equals(v)) return null;
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
            v = v.replace("\\\"", "\"").replace("\\\\", "\\")
                 .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                 .replace("\\u003C", "<").replace("\\u003E", ">").replace("\\u0026", "&");
        }
        return v;
    }

    /**
     * 送出一段非同步 JS，然後輪詢它寫進 `window.__cbResult` 的結果。
     *
     * 為什麼不用 addJavascriptInterface：那會把一個 Java 物件曝露給**這個 WebView
     * 載入的所有頁面**，包含我們無法完全掌控的 apollo.mayohr.com。輪詢一個全域
     * 變數雖然土，但不增加任何攻擊面。
     */
    private static String runAsync(String asyncJs, int timeoutMs) {
        eval("window.__cbResult='';" + asyncJs);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String r = eval("window.__cbResult||''");
            if (r != null && !r.isEmpty()) return r;
            try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException e) { break; }
        }
        return null;
    }

    // ── 對外：唯讀探針 ──────────────────────────────────

    /**
     * 唯讀健檢。**絕對不會送出打卡**——只做兩件事：
     *   1. 載入打卡頁，看最後停在哪個網址（被導去 asiaauth 就代表沒登入）
     *   2. 對 `clockInOut/useNew` 發一次 GET，看打卡服務認不認這組憑證
     *
     * 回傳的 JSON 同時帶回 UA 與最終網址，這是驗證「WebView 會不會被 Imperva
     * 另眼相待」與「背景 WebView 能不能正常跑」的第一手證據。
     */
    static JSONObject probe(Context ctx) throws Exception {
        JSONObject j = new JSONObject();
        if (!ensureWeb(ctx)) {
            return j.put("ok", false).put("stage", "ensureWeb")
                    .put("error", "WebView 建立或掛載失敗（檢查懸浮視窗權限）");
        }
        if (!load(APOLLO_URL)) {
            return j.put("ok", false).put("stage", "load")
                    .put("error", "載入 " + APOLLO_URL + " 逾時");
        }
        // SPA 載完 onPageFinished 後還要跑一會兒才會轉址／渲染，給它一點時間
        Thread.sleep(3000);

        String js =
                "(async()=>{try{"
              + "const r=await fetch(" + jsStr(PRECHECK_URL) + ",{credentials:'include'});"
              + "const t=await r.text();"
              + "window.__cbResult=JSON.stringify({status:r.status,len:t.length,"
              + "body:t.slice(0,200),href:location.href,ua:navigator.userAgent});"
              + "}catch(e){window.__cbResult=JSON.stringify({error:String(e),"
              + "href:location.href,ua:navigator.userAgent});}})();";

        String raw = runAsync(js, FETCH_TIMEOUT_MS);
        if (raw == null) {
            return j.put("ok", false).put("stage", "fetch")
                    .put("error", "頁內 fetch 逾時（可能被 WAF 擋在挑戰頁）");
        }
        JSONObject r = new JSONObject(raw);
        j.put("ok", true);
        j.put("href", r.optString("href"));
        j.put("ua", r.optString("ua"));
        if (r.has("error")) {
            j.put("precheck_error", r.optString("error"));
        } else {
            j.put("precheck_status", r.optInt("status"));
            j.put("precheck_len", r.optInt("len"));
            j.put("precheck_body", r.optString("body"));
        }

        // 登入與否**只看預檢的狀態碼**。未登入時它回的是乾淨的
        // 401 {"data":"Invalid token!"}（2026-09-04 實測），這比比對網址可靠得多——
        // SPA 不一定會轉址，實測未登入時 href 仍停在 apollo.mayohr.com。
        boolean loggedIn = r.optInt("status") == 200;
        j.put("logged_in", loggedIn);

        // 沒登入才順便看登入頁的欄位還在不在。這是唯讀檢查，不需要任何憑證，
        // 目的是確認 auto_login.py:131 那組 selector 在 WebView 裡仍然成立。
        if (!loggedIn) j.put("login_form", loginFormShape());
        return j;
    }

    /**
     * 唯讀：載入登入頁，回報三個帳密欄位在不在。**不填任何東西、不送出。**
     *
     * 欄位名沿用 `auto_login.py:131` 已在生產環境驗證過的
     * `companyCode` / `employeeNo` / `password`。
     */
    private static JSONObject loginFormShape() throws Exception {
        JSONObject o = new JSONObject();
        if (!load(LOGIN_URL)) return o.put("error", "載入登入頁逾時");

        // 表單是 JS 渲染的，固定 sleep 猜不準——輪詢等 password 欄位出現。
        // 等不到也不放棄，照樣把 DOM 現況 dump 回來，才有東西可以判斷是
        // 「還沒渲染完」還是「結構跟桌面版不一樣」。
        int waited = 0;
        while (waited < LOGIN_FORM_WAIT_MS) {
            String hit = eval("document.querySelector('input[name=password]')?'1':'0'");
            if ("1".equals(hit)) break;
            Thread.sleep(1500);
            waited += 1500;
        }
        o.put("waited_ms", waited);

        // 回報 DOM 現況而不只是布林值：欄位找不到時，這些數字才說得出原因
        // （0 個 input＋短 body＝還沒渲染；有 iframe＝表單在框裡；有 input
        //  但名字不同＝手機版結構不一樣）。
        String js =
                "(()=>{try{"
              + "const all=[...document.querySelectorAll('input')];"
              + "const list=all.slice(0,12).map(i=>((i.getAttribute('name')||'-')"
              + "+'/'+(i.id||'-')+'/'+(i.type||'-')));"
              + "const q=n=>!!document.querySelector('input[name='+n+']');"
              + "window.__cbResult=JSON.stringify({href:location.href,"
              + "title:document.title,ready:document.readyState,"
              + "inputs_total:all.length,inputs:list,"
              + "iframes:document.querySelectorAll('iframe').length,"
              + "forms:document.querySelectorAll('form').length,"
              + "buttons:document.querySelectorAll('button').length,"
              + "body_len:(document.body?document.body.innerText.length:0),"
              + "body_head:(document.body?document.body.innerText.slice(0,120):''),"
              + "companyCode:q('companyCode'),employeeNo:q('employeeNo'),"
              + "password:q('password'),"
              + "checkboxes:document.querySelectorAll('input[type=checkbox]').length,"
              + "submit_buttons:[...document.querySelectorAll('button[type=submit]')]"
              + ".filter(b=>b.offsetWidth||b.offsetHeight).length});"
              + "}catch(e){window.__cbResult=JSON.stringify({error:String(e)});}})();";

        String raw = runAsync(js, JS_EVAL_TIMEOUT_MS);
        if (raw == null) return o.put("error", "查詢登入頁欄位逾時");
        JSONObject r = new JSONObject(raw);
        for (String k : new String[]{"href", "title", "ready", "inputs_total", "inputs",
                "iframes", "forms", "buttons", "body_len", "body_head",
                "companyCode", "employeeNo", "password", "checkboxes", "submit_buttons"}) {
            if (r.has(k)) o.put(k, r.get(k));
        }
        o.put("usable", r.optBoolean("companyCode") && r.optBoolean("employeeNo")
                && r.optBoolean("password") && r.optInt("submit_buttons") > 0);
        return o;
    }

    /** 把字串包成 JS 字面值。 */
    private static String jsStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
