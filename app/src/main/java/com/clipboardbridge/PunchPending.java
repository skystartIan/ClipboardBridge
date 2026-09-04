package com.clipboardbridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 「待確認的打卡」暫存。
 *
 * 存的是**已經組好的 payload**，不是 cookie 也不是參數——這樣「確認送出」時
 * 送出的內容與預覽裡看到的**完全一致**，中間不會再重算一次而產生落差
 * （例如跨過午休、上下班別翻轉）。這是 vm-windows 那側既有的語意，照抄。
 *
 * TTL 10 分鐘：太久的話 Imperva 的 incap_ses 會過期，而且使用者早忘了自己按過。
 * 取消會直接清掉 pending —— 清掉之後就算誤按確認也只會回「沒有待確認的打卡」，
 * 不可能送出。
 *
 * 用一般 SharedPreferences 而非加密版：這裡面沒有憑證，只有座標與地點 id，
 * 而且是 App 私有目錄。加密留給 PunchCreds。
 */
class PunchPending {

    private static final String FILE  = "punch_pending";
    private static final String K_BODY    = "body";
    private static final String K_CREATED = "created_at";
    private static final String K_META    = "meta";

    /** 毫秒。與 auto_punch.py 的 PENDING_TTL 一致。 */
    static final long TTL_MS = 10 * 60 * 1000L;

    private PunchPending() { }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext()
                  .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static void write(Context ctx, JSONObject body, JSONObject meta) {
        sp(ctx).edit()
                .putString(K_BODY, body.toString())
                .putString(K_META, meta == null ? "{}" : meta.toString())
                .putLong(K_CREATED, System.currentTimeMillis())
                .apply();
    }

    /**
     * 讀出 pending。**回傳 null 代表沒有或已過期**，呼叫端據此回
     * 「沒有待確認的打卡」而不是送出。過期時順手清掉，避免下次又撿到。
     */
    static JSONObject read(Context ctx) throws Exception {
        SharedPreferences p = sp(ctx);
        String body = p.getString(K_BODY, "");
        if (body.isEmpty()) return null;
        long age = System.currentTimeMillis() - p.getLong(K_CREATED, 0);
        if (age > TTL_MS) {
            clear(ctx);
            return null;
        }
        return new JSONObject()
                .put("body", new JSONObject(body))
                .put("meta", new JSONObject(p.getString(K_META, "{}")))
                .put("age_ms", age)
                .put("remain_ms", TTL_MS - age);
    }

    static boolean exists(Context ctx) throws Exception {
        return read(ctx) != null;
    }

    static void clear(Context ctx) {
        sp(ctx).edit().remove(K_BODY).remove(K_META).remove(K_CREATED).apply();
    }
}
