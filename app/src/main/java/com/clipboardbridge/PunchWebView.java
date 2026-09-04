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

import java.util.UUID;
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

    /** ★ 唯一會真的打卡的端點。除了 confirm() 之外沒有任何地方該碰它。 */
    private static final String GPS_URL =
            "https://apollo.mayohr.com/backend/platform-bff/api/clockInOut/gps";

    /** 唯讀：問伺服器目前已打過的類型，用來決定這次該打上班還是下班。 */
    private static final String PTYPE_URL =
            "https://pt-be.mayohr.com/api/checkin/punchedType";

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

    /**
     * **必須覆寫 UA，這不是選配的。**
     *
     * 2026-09-04 實測：用 WebView 預設 UA（含 `; wv` 標記）載入 asiaauth 登入頁，
     * 拿回來的是一頁「下載 Google Chrome」的不支援訊息——`inputs_total=1`（只剩
     * 那個 hidden 的 `__RequestVerificationToken`）、`forms=0`、`buttons=0`、
     * `body_len=78`。登入頁會做瀏覽器偵測，`wv` 就是它認出 WebView 的依據。
     *
     * 分界很清楚：**API（apollo）不在意 UA**（同一次實測乖乖回了 401
     * `Invalid token!`），**但登入頁（asiaauth）會擋**。
     *
     * 用的是 `punch.py:80` 網頁模式那組桌面 Chrome UA——它已經在 vm-windows 上
     * 打卡成功過，是已知被接受的字串。附帶好處是伺服器會回桌面版版面，
     * 與 `auto_login.py:131` 當初開發選擇器時的版面一致。
     */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

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
                    st.setUserAgentString(DESKTOP_UA);
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

        JSONObject r = precheck();
        if (r.has("error") && !r.has("status")) {
            return j.put("ok", false).put("stage", "fetch")
                    .put("error", r.optString("error"));
        }
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

    /**
     * 把字串包成 JS 字面值。
     *
     * 用 `JSONObject.quote` 而不是自己 replace：JSON 字串字面值同時也是合法的
     * JS 字串字面值，而且控制字元、引號、反斜線它都處理好了。密碼裡有奇怪字元
     * 時，自己兜的跳脫會產生語法錯誤的 JS——那種錯誤只會表現成「登入莫名失敗」。
     */
    private static String jsStr(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    // ── 登入 ────────────────────────────────────────────

    /**
     * 用保存在平板上的憑證登入一次。**失敗不重試**，並受每日次數上限保護。
     *
     * ★ 密碼只以 JS 字面值的形式進入 `eval()`，不會出現在回傳值、log 或例外訊息裡。
     *   維護時請保持這條——`auto_login.py:143` 那行「expr 內含密碼，絕對不要 print」
     *   的規定在這一側同樣適用。
     */
    static JSONObject login(Context ctx) throws Exception {
        JSONObject j = new JSONObject();
        if (!PunchCreds.has(ctx)) {
            return j.put("ok", false).put("stage", "creds")
                    .put("error", "平板上沒有存憑證，請先用 punch_set_creds 送進來");
        }
        if (PunchCreds.blocked(ctx)) {
            return j.put("ok", false).put("stage", "blocked")
                    .put("error", "今日已嘗試登入 " + PunchCreds.attemptsToday(ctx)
                            + " 次（上限 " + PunchCreds.MAX_ATTEMPTS_PER_DAY
                            + "），不再自動嘗試以免帳號被鎖。上次結果："
                            + PunchCreds.lastResult(ctx));
        }
        if (!ensureWeb(ctx)) {
            return j.put("ok", false).put("stage", "ensureWeb")
                    .put("error", "WebView 建立或掛載失敗");
        }
        if (!load(LOGIN_URL)) {
            PunchCreds.noteAttempt(ctx, "load-timeout");
            return j.put("ok", false).put("stage", "load").put("error", "載入登入頁逾時");
        }

        // 等表單渲染。等不到就不要填——盲填會把值設到不存在的元素上然後靜默失敗。
        int waited = 0;
        while (waited < LOGIN_FORM_WAIT_MS) {
            if ("1".equals(eval("document.querySelector('input[name=password]')?'1':'0'"))) break;
            Thread.sleep(1500);
            waited += 1500;
        }
        j.put("form_wait_ms", waited);

        // 順便查清楚「記住我」到底是什麼元素。實測 input[type=checkbox] 數量是 0，
        // 代表 auto_login.py:135 那段 remember.click() 從來沒有生效過。
        j.put("remember_kind", eval(
                "JSON.stringify([...document.querySelectorAll('*')]"
              + ".filter(e=>e.children.length===0&&(e.textContent||'').trim()==='記住我')"
              + ".slice(0,3).map(e=>e.tagName+'/'+(e.className||'-')+'/'"
              + "+(e.parentElement?e.parentElement.tagName:'-')))"));

        // 填表並送出。用 eval 取回傳值而不是 runAsync 輪詢全域變數——click 會
        // 觸發導頁，導頁一發生 window.__cbResult 就沒了，輪詢會永遠等不到。
        String fill =
                "((c,e,p)=>{"
              + "const set=(el,v)=>{"
              + "const d=Object.getOwnPropertyDescriptor(el.constructor.prototype,'value');"
              + "(d&&d.set?d.set:(x=>el.value=x)).call(el,v);"
              + "el.dispatchEvent(new Event('input',{bubbles:true}));"
              + "el.dispatchEvent(new Event('change',{bubbles:true}));};"
              + "const q=n=>document.querySelector('input[name='+n+']');"
              + "const cc=q('companyCode'),en=q('employeeNo'),pw=q('password');"
              + "if(!cc||!en||!pw)return 'missing-fields';"
              + "set(cc,c);set(en,e);set(pw,p);"
              + "const btn=[...document.querySelectorAll('button[type=submit]')]"
              + ".find(b=>b.offsetWidth||b.offsetHeight);"
              + "if(!btn)return 'no-submit-button';"
              + "btn.click();return 'submitted';})("
              + jsStr(PunchCreds.company(ctx)) + ","
              + jsStr(PunchCreds.empno(ctx)) + ","
              + jsStr(PunchCreds.password(ctx)) + ")";

        // 點下去會導頁，所以先換好 latch 再送出
        CountDownLatch nav = new CountDownLatch(1);
        pageLatch = nav;
        String r = eval(fill);
        j.put("submit", r == null ? "eval-failed" : r);
        if (!"submitted".equals(r)) {
            PunchCreds.noteAttempt(ctx, String.valueOf(r));
            return j.put("ok", false).put("stage", "fill")
                    .put("error", "填表失敗：" + r);
        }
        nav.await(PAGE_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Thread.sleep(4000);          // 導頁完還要讓 SPA 把 session 建起來

        // 成敗**只用唯讀預檢判定**，不看網址也不看畫面：401 就是沒登入成功。
        JSONObject after = precheck();
        j.put("after", after);
        boolean ok = after.optInt("status") == 200;
        j.put("ok", ok);
        if (ok) {
            // 強制把 cookie 寫回磁碟。CookieManager 平常是定期才 flush 的，
            // App 行程若在那之前被系統殺掉，剛拿到的 session 就沒了——那會
            // 白白消耗每日登入額度，而且症狀是「明明剛登入過卻又要重登」。
            new Handler(Looper.getMainLooper()).post(
                    () -> CookieManager.getInstance().flush());
            PunchCreds.resetAttempts(ctx);
        } else {
            PunchCreds.noteAttempt(ctx, "precheck-" + after.optInt("status"));
            j.put("error", "送出後預檢仍未通過（HTTP " + after.optInt("status") + "）");
        }
        j.put("attempts_today", PunchCreds.attemptsToday(ctx));
        return j;
    }

    /**
     * 在**目前頁面裡**發一次 fetch，回 {status, body, href}。
     *
     * 一定要在 `apollo.mayohr.com` 的頁面上呼叫：httpOnly 的
     * `__ModuleSessionCookie` 只有同源請求帶得上，Origin/Referer 也才會正確。
     * 這就是整個設計不用 Java 發 HTTP 的原因。
     */
    private static JSONObject apiFetch(String method, String url,
                                       String bodyJson, JSONObject headers)
            throws Exception {
        String js =
                "(async()=>{try{"
              + "const o={method:" + jsStr(method) + ",credentials:'include',"
              + "headers:" + (headers == null ? "{}" : headers.toString()) + "};"
              + (bodyJson == null ? "" : "o.body=" + jsStr(bodyJson) + ";")
              + "const r=await fetch(" + jsStr(url) + ",o);const t=await r.text();"
              + "window.__cbResult=JSON.stringify({status:r.status,len:t.length,"
              + "body:t.slice(0,600),href:location.href,ua:navigator.userAgent});"
              + "}catch(e){window.__cbResult=JSON.stringify({error:String(e),"
              + "href:location.href,ua:navigator.userAgent});}})();";
        String raw = runAsync(js, FETCH_TIMEOUT_MS);
        return raw == null
                ? new JSONObject().put("error", "頁內 fetch 逾時")
                : new JSONObject(raw);
    }

    /** 唯讀預檢，回 {status,len,body,href}。抽出來給 probe / login / 打卡共用。 */
    private static JSONObject precheck() throws Exception {
        return apiFetch("GET", PRECHECK_URL, null, null);
    }

    /**
     * 確保 WebView 停在 apollo 這個 origin 上——不然 fetch 會變跨源、cookie 帶不上。
     * 已經在上面就不重載，省掉每次幾秒的等待。
     */
    private static boolean ensureOnApollo() throws Exception {
        String href = eval("location.href");
        if (href != null && href.startsWith("https://apollo.mayohr.com/")) return true;
        if (!load(APOLLO_URL)) return false;
        Thread.sleep(2500);
        return true;
    }

    // ── 打卡 ────────────────────────────────────────────

    /**
     * 地點捷徑，值取自 `punch.py:45` 的 LOCATIONS（來源是 AppEnableList，半徑 200m）。
     *
     * 同事只會用到 `kh`；`other`(WFH) 保留著，之後要開給他只是鍵盤多一顆按鈕。
     */
    private static JSONObject location(String loc) throws Exception {
        if ("other".equals(loc)) {
            return new JSONObject().put("name", "其他(WFH)")
                    .put("locid", "00000000-0000-0000-0000-000000000000")
                    .put("lat", 22.64923338).put("lng", 120.30381738);
        }
        return new JSONObject().put("name", "高雄辦公室")
                .put("locid", "b7ed61a6-4e98-4216-abf8-d58da8445b87")
                .put("lat", 22.64923338).put("lng", 120.30381738);
    }

    /**
     * 產生待確認的打卡，**不送出**。
     *
     * 上／下班別由伺服器的 `punchedType` 決定，不自己猜——猜錯會把下班打成上班，
     * 而那要人工去 HR 系統改。
     */
    static JSONObject preview(Context ctx, String loc, String note) throws Exception {
        JSONObject j = new JSONObject();
        if (!ensureWeb(ctx)) {
            return j.put("ok", false).put("error", "WebView 建立失敗");
        }
        if (!ensureOnApollo()) {
            return j.put("ok", false).put("error", "載入打卡頁逾時");
        }
        JSONObject pc = precheck();
        if (pc.optInt("status") != 200) {
            return j.put("ok", false).put("stage", "auth")
                    .put("precheck_status", pc.optInt("status"))
                    .put("error", "憑證無效，需要先重新登入");
        }

        JSONObject pt = apiFetch("GET", PTYPE_URL, null, null);
        j.put("punched_type_status", pt.optInt("status"));
        j.put("punched_type_body", pt.optString("body"));
        int atype = attendanceTypeFrom(pt);
        if (atype == 0) {
            return j.put("ok", false).put("stage", "punchedType")
                    .put("error", "讀不出目前該打上班還是下班，未產生待確認項目");
        }

        JSONObject L = location(loc);
        JSONObject body = new JSONObject()
                .put("Latitude", L.getDouble("lat"))
                .put("Longitude", L.getDouble("lng"))
                .put("AttendanceType", atype)
                .put("PunchesLocationId", L.getString("locid"))
                .put("LocationDetails", note == null ? "" : note)
                .put("IdentifyCode", UUID.randomUUID().toString().toUpperCase())
                .put("IsOverride", false)
                .put("gpstype", "TW");

        JSONObject meta = new JSONObject()
                .put("loc", loc == null ? "kh" : loc)
                .put("loc_name", L.getString("name"))
                .put("atype", atype)
                .put("atype_name", atype == 1 ? "上班" : "下班");
        PunchPending.write(ctx, body, meta);

        return j.put("ok", true).put("meta", meta).put("body", body)
                .put("ttl_min", PunchPending.TTL_MS / 60000);
    }

    /**
     * 從 punchedType 的回應推出這次該打 1（上班）還是 2（下班）。
     * 判斷不出來回 0——寧可不打，也不要打錯別。
     */
    private static int attendanceTypeFrom(JSONObject pt) {
        if (pt.optInt("status") != 200) return 0;
        String b = pt.optString("body", "");
        // 伺服器回的是目前「已打過的」類型：已打上班(1) → 這次該打下班(2)，反之亦然
        if (b.contains("\"Data\": 1") || b.contains("\"Data\":1")
                || b.contains("\"data\": 1") || b.contains("\"data\":1")) return 2;
        if (b.contains("\"Data\": 2") || b.contains("\"Data\":2")
                || b.contains("\"data\": 2") || b.contains("\"data\":2")) return 1;
        if (b.contains("\"Data\": 0") || b.contains("\"Data\":0")
                || b.contains("\"data\": 0") || b.contains("\"data\":0")) return 1;
        return 0;
    }

    /** 送出待確認的打卡。**這是唯一會真的打卡的方法。** */
    static JSONObject confirm(Context ctx) throws Exception {
        JSONObject j = new JSONObject();
        JSONObject pend = PunchPending.read(ctx);
        if (pend == null) {
            return j.put("ok", false).put("stage", "pending")
                    .put("error", "沒有待確認的打卡（可能已取消或超過 "
                            + (PunchPending.TTL_MS / 60000) + " 分鐘）");
        }
        if (!ensureWeb(ctx) || !ensureOnApollo()) {
            return j.put("ok", false).put("error", "WebView 尚未就緒");
        }
        JSONObject headers = new JSONObject()
                .put("Content-Type", "application/json")
                .put("FunctionCode", "APP-LocationCheckin")
                .put("ActionCode", "Default");
        JSONObject body = pend.getJSONObject("body");
        JSONObject r = apiFetch("POST", GPS_URL, body.toString(), headers);

        j.put("meta", pend.getJSONObject("meta"));
        j.put("status", r.optInt("status"));
        j.put("body", r.optString("body"));
        boolean ok = r.optInt("status") == 200;
        j.put("ok", ok);
        // 不論成敗都清掉：成功不必留，失敗留著也只會讓人重按而送出兩次。
        PunchPending.clear(ctx);
        return j;
    }

    /** 取消：清掉 pending。清掉後誤按確認也只會回「沒有待確認的打卡」。 */
    static JSONObject cancel(Context ctx) throws Exception {
        boolean had = PunchPending.exists(ctx);
        PunchPending.clear(ctx);
        return new JSONObject().put("ok", true).put("had_pending", had);
    }

    /** 唯讀健檢：登入狀態、憑證有沒有存、今日登入嘗試次數、有無待確認項目。 */
    static JSONObject diag(Context ctx) throws Exception {
        JSONObject j = probe(ctx);
        j.put("creds_stored", PunchCreds.has(ctx));
        j.put("login_attempts_today", PunchCreds.attemptsToday(ctx));
        j.put("login_last_result", PunchCreds.lastResult(ctx));
        JSONObject pend = PunchPending.read(ctx);
        j.put("pending", pend == null ? JSONObject.NULL : pend.getJSONObject("meta"));
        return j;
    }
}
