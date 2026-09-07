package com.clipboardbridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 取平板自己的定位，給打卡用。
 *
 * <p>為什麼要這個：原本 {@link PunchWebView} 送的是寫死的辦公室中心座標，
 * 每天每次**位元完全相同**。平板本來就整天放在辦公室，直接讀它的定位，
 * 送出去的就是真的量測值，不是模擬的隨機。
 *
 * <p>用平台的 {@link LocationManager}，**不引入 Play Services**——這個 App
 * 的原則是零額外依賴，而且 fused provider 從 API 31 起平台自己就有。
 *
 * <h3>兩道閘門</h3>
 * <ul>
 *   <li><b>新鮮度 ≤ {@value #MAX_AGE_MS} ms</b>：用 elapsedRealtime 比對，
 *       <b>不要用 wall clock</b>——系統時間會被 NTP 調整，定位時戳跟著跳。</li>
 *   <li><b>距中心 ≤ {@value #MAX_DIST_M} 公尺</b>：MAYOHR 的圍籬是 200m，
 *       留 50m 餘裕。超過就當定位飄掉。</li>
 * </ul>
 *
 * <p>⚠️ <b>刻意不拿回報精確度（accuracy）當閘門。</b>
 * 2026-09-07 在 tab-a11 實測到一筆距中心僅 11 公尺的好定位，它回報的是
 * {@code hAcc=100.0}——Wi-Fi/網路定位回報 100 公尺是常態，不代表定位不準。
 * 拿 {@code accuracy <= 50} 之類的條件去擋，會把完全正確的定位擋掉。
 * 要判斷的是「這個點合不合理」，而距中心直接回答了這個問題。
 *
 * <p>★ 任何失敗都回 null，<b>絕不丟例外</b>。拿不到定位就退回固定座標照常打卡——
 * 人就在辦公室，那個座標本來就是真的。定位只是讓它更精確，不是打卡的前提。
 */
final class PunchGps {

    private static final String TAG = "PunchGps";

    static final long MAX_AGE_MS = 10 * 60 * 1000L;   // 定位最舊可接受 10 分鐘
    static final double MAX_DIST_M = 150.0;           // 距中心上限（圍籬 200m）
    private static final long FRESH_TIMEOUT_MS = 15_000L;  // 主動要一次新定位的上限

    /** 這次取定位的結果。fix 為 null 時看 reason 知道為什麼。 */
    static final class Result {
        final Location fix;        // null = 沒有可用定位
        final double distM;        // fix != null 時才有意義
        final String reason;       // fix == null 時的原因（ASCII，方便經 JSON/log）
        final String provider;

        Result(Location fix, double distM, String reason, String provider) {
            this.fix = fix;
            this.distM = distM;
            this.reason = reason;
            this.provider = provider;
        }
    }

    private PunchGps() {}

    private static boolean granted(Context ctx, String perm) {
        return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** 定位的年紀（毫秒）。用 elapsedRealtime，不受系統時間調整影響。 */
    private static long ageMs(Location l) {
        return (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
    }

    /**
     * 取一個可用的定位。
     *
     * @param centerLat 地點中心緯度（用來判斷定位合不合理）
     * @param centerLng 地點中心經度
     */
    static Result get(Context ctx, double centerLat, double centerLng) {
        try {
            if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                    && !granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                return new Result(null, 0, "no_permission", "");
            }
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return new Result(null, 0, "no_service", "");
            }

            // 先撿現成的。平板整天躺在辦公室連著 Wi-Fi，多半已經有夠新的定位，
            // 這條路 0 秒就回來了。
            Location best = null;
            for (String p : providers()) {
                Location l = lastKnown(lm, p);
                if (l == null) continue;
                if (best == null || l.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos()) {
                    best = l;
                }
            }
            if (best != null && ageMs(best) <= MAX_AGE_MS) {
                return judge(best, centerLat, centerLng);
            }

            // 沒有夠新的就主動要一次。API 30+ 才有 getCurrentLocation；
            // 這兩台都是 Android 16，走不到下面的 fallback，但留著以防換機。
            Location fresh = (Build.VERSION.SDK_INT >= 30) ? currentLocation(lm) : null;
            if (fresh != null) {
                return judge(fresh, centerLat, centerLng);
            }
            if (best != null) {
                // 要不到新的，只剩一筆過舊的——寧可退回固定座標，也不要拿
                // 幾小時前（可能還在別的地方）的定位去打卡。
                return new Result(null, 0, "stale_" + (ageMs(best) / 1000) + "s", best.getProvider());
            }
            return new Result(null, 0, "no_fix", "");
        } catch (Throwable t) {
            // 定位再怎麼樣都不該讓打卡掛掉
            Log.w(TAG, "取定位失敗", t);
            return new Result(null, 0, "error_" + t.getClass().getSimpleName(), "");
        }
    }

    private static List<String> providers() {
        List<String> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) out.add(LocationManager.FUSED_PROVIDER);
        out.add(LocationManager.NETWORK_PROVIDER);   // 室內主力：Wi-Fi 定位
        out.add(LocationManager.GPS_PROVIDER);       // 室內常常拿不到
        return out;
    }

    private static Location lastKnown(LocationManager lm, String provider) {
        try {
            return lm.getLastKnownLocation(provider);
        } catch (Throwable t) {
            return null;   // provider 不存在或沒權限
        }
    }

    /** 主動要一次新定位，最多等 {@value #FRESH_TIMEOUT_MS} ms。 */
    private static Location currentLocation(LocationManager lm) {
        for (String p : providers()) {
            try {
                final Location[] box = new Location[1];
                final CountDownLatch latch = new CountDownLatch(1);
                CancellationSignal cancel = new CancellationSignal();
                lm.getCurrentLocation(p, cancel, Executors.newSingleThreadExecutor(), loc -> {
                    box[0] = loc;
                    latch.countDown();
                });
                if (!latch.await(FRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    cancel.cancel();
                    continue;
                }
                if (box[0] != null) return box[0];
            } catch (Throwable ignored) {
                // 換下一個 provider
            }
        }
        return null;
    }

    /** 距中心太遠就不採用——那多半是定位飄掉，不是人真的在那裡。 */
    private static Result judge(Location l, double centerLat, double centerLng) {
        float[] r = new float[1];
        Location.distanceBetween(centerLat, centerLng, l.getLatitude(), l.getLongitude(), r);
        double d = r[0];
        if (d > MAX_DIST_M) {
            return new Result(null, d, "too_far_" + Math.round(d) + "m", l.getProvider());
        }
        return new Result(l, d, "", l.getProvider());
    }
}
