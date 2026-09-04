package com.clipboardbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.Calendar;

/**
 * Apollo 登入憑證的加密儲存，以及自動重登的每日次數上限。
 *
 * ★ 密碼**永遠不進 log**。這個檔案裡沒有任何一行會把 `password()` 的回傳值
 *   印出來，之後改動也請維持——`envcfg.py` 檔頭對 `APOLLO_PASSWORD` 的規定
 *   在這一側同樣適用。
 *
 * 存放位置刻意選 App 私有的 EncryptedSharedPreferences（背後是 Android Keystore）：
 *   - 不放 /sdcard：任何有儲存權限的 App 都看得到
 *   - 不放明文 SharedPreferences：root 或備份取出就是明碼
 *   - 不塞進 APK：那等於公開
 *
 * 憑證只留在平板上。vm-deper 只負責「送一次進來」，不保存；vm-windows 完全不碰。
 */
class PunchCreds {

    private static final String TAG = ClipboardReceiver.TAG;
    private static final String FILE = "punch_creds";

    private static final String K_COMPANY  = "company";
    private static final String K_EMPNO    = "empno";
    private static final String K_PASSWORD = "password";
    private static final String K_DAY      = "attempt_day";
    private static final String K_COUNT    = "attempt_count";
    private static final String K_LAST     = "last_result";

    /**
     * 每日自動登入嘗試上限。
     *
     * **這不是效能考量，是防帳號被鎖。** 移植自 `auto_login.py` 的
     * MAX_ATTEMPTS_PER_DAY：密碼錯了還一直重試，HR 系統會把帳號鎖住，
     * 那要人工去解，比「今天不能自動打卡」嚴重得多。
     */
    static final int MAX_ATTEMPTS_PER_DAY = 5;

    private PunchCreds() { }

    private static SharedPreferences prefs(Context ctx) throws Exception {
        Context app = ctx.getApplicationContext();
        MasterKey key = new MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                app, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    // ── 憑證 ────────────────────────────────────────────

    static void store(Context ctx, String company, String empno, String password)
            throws Exception {
        prefs(ctx).edit()
                .putString(K_COMPANY, company)
                .putString(K_EMPNO, empno)
                .putString(K_PASSWORD, password)
                .apply();
        // 只記「有沒有」與工號長度，不記內容
        Log.i(TAG, "PunchCreds 已更新（company=" + company
                + "，工號長度=" + (empno == null ? 0 : empno.length()) + "）");
    }

    static boolean has(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            return !isEmpty(p.getString(K_COMPANY, ""))
                    && !isEmpty(p.getString(K_EMPNO, ""))
                    && !isEmpty(p.getString(K_PASSWORD, ""));
        } catch (Exception e) {
            Log.w(TAG, "PunchCreds 讀取失敗：" + e);
            return false;
        }
    }

    static String company(Context ctx) throws Exception {
        return prefs(ctx).getString(K_COMPANY, "");
    }

    static String empno(Context ctx) throws Exception {
        return prefs(ctx).getString(K_EMPNO, "");
    }

    /** ★ 呼叫端只能把它交給填表用的 JS，不得記錄、不得回傳給 bot。 */
    static String password(Context ctx) throws Exception {
        return prefs(ctx).getString(K_PASSWORD, "");
    }

    static void clear(Context ctx) throws Exception {
        prefs(ctx).edit()
                .remove(K_COMPANY).remove(K_EMPNO).remove(K_PASSWORD).apply();
        Log.i(TAG, "PunchCreds 已清除");
    }

    // ── 每日嘗試上限 ────────────────────────────────────

    /** 今天已經嘗試幾次（跨日自動歸零）。 */
    static int attemptsToday(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        return today().equals(p.getString(K_DAY, "")) ? p.getInt(K_COUNT, 0) : 0;
    }

    static boolean blocked(Context ctx) throws Exception {
        return attemptsToday(ctx) >= MAX_ATTEMPTS_PER_DAY;
    }

    /** 記一次嘗試。`result` 是簡短狀態字串，**不得包含密碼**。 */
    static void noteAttempt(Context ctx, String result) throws Exception {
        SharedPreferences p = prefs(ctx);
        int n = attemptsToday(ctx) + 1;
        p.edit().putString(K_DAY, today()).putInt(K_COUNT, n)
                .putString(K_LAST, result == null ? "" : result).apply();
        Log.i(TAG, "PunchCreds 登入嘗試 " + n + "/" + MAX_ATTEMPTS_PER_DAY
                + "，結果：" + result);
    }

    /** 登入成功後歸零，讓偶發失敗不會累積到把明天也擋掉。 */
    static void resetAttempts(Context ctx) throws Exception {
        prefs(ctx).edit().putInt(K_COUNT, 0).putString(K_LAST, "ok").apply();
    }

    static String lastResult(Context ctx) throws Exception {
        return prefs(ctx).getString(K_LAST, "");
    }

    private static String today() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1)
                + "-" + c.get(Calendar.DAY_OF_MONTH);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
