package com.clipboardbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int SHIZUKU_REQUEST_CODE = 1001;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
        (requestCode, grantResult) -> {
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "✓ Shizuku 已授權", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "✗ Shizuku 授權被拒絕", Toast.LENGTH_SHORT).show();
                }
            }
        };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 96, 48, 48);
        layout.setBackgroundColor(0xFF1A1A2E);

        TextView title = new TextView(this);
        title.setText("Clipboard Bridge");
        title.setTextSize(26);
        title.setTextColor(0xFFE0E0FF);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(title);

        TextView sub = new TextView(this);
        sub.setText("PC \u2192 Android image clipboard sync via ADB");
        sub.setTextSize(13);
        sub.setTextColor(0xFF9090BB);
        sub.setPadding(0, 8, 0, 48);
        layout.addView(sub);

        String[] steps = {
            "1. Enable USB debugging on tablet",
            "2. Grant Shizuku permission below",
            "3. Grant Overlay permission below",
            "4. Run pc_clipboard_monitor.py on PC",
            "5. Ctrl+C any image on PC",
            "6. Ctrl+V or long-press to paste \u2713"
        };
        for (String s : steps) {
            TextView tv = new TextView(this);
            tv.setText(s);
            tv.setTextSize(14);
            tv.setTextColor(0xFFCCCCEE);
            tv.setPadding(0, 10, 0, 10);
            layout.addView(tv);
        }

        // Shizuku 授權按鈕
        Button shizukuBtn = new Button(this);
        shizukuBtn.setText("Grant Shizuku Permission");
        shizukuBtn.setBackgroundColor(0xFF9B59B6);
        shizukuBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p1.setMargins(0, 48, 0, 16);
        shizukuBtn.setLayoutParams(p1);
        shizukuBtn.setOnClickListener(v -> requestShizukuPermission());
        layout.addView(shizukuBtn);

        // Overlay 授權按鈕
        Button overlayBtn = new Button(this);
        overlayBtn.setText("Grant Overlay Permission");
        overlayBtn.setBackgroundColor(0xFF4A90E2);
        overlayBtn.setTextColor(0xFFFFFFFF);
        overlayBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivityForResult(new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), 1002);
                } else {
                    Toast.makeText(this, "✓ Overlay permission already granted", Toast.LENGTH_SHORT).show();
                }
            }
        });
        layout.addView(overlayBtn);

        // 狀態顯示
        TextView status = new TextView(this);
        boolean shizukuOk = isShizukuGranted();
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        status.setText(
            "\nShizuku: " + (shizukuOk ? "✓ Granted" : "✗ Not granted") +
            "\nOverlay: " + (overlayOk ? "✓ Granted" : "✗ Not granted")
        );
        status.setTextSize(13);
        status.setTextColor(shizukuOk && overlayOk ? 0xFF2ECC71 : 0xFFE74C3C);
        status.setPadding(0, 24, 0, 0);
        layout.addView(status);

        setContentView(layout);
    }

    private boolean isShizukuGranted() {
        try {
            return Shizuku.pingBinder() &&
                   Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private void requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku 未執行，請先啟動 Shizuku", Toast.LENGTH_LONG).show();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✓ Shizuku 已授權", Toast.LENGTH_SHORT).show();
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(this, "請在 Shizuku App 中手動授權", Toast.LENGTH_LONG).show();
                return;
            }
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
    }
}
