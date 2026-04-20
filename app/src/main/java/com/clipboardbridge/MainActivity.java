package com.clipboardbridge;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
            "2. Grant permission below (overlay)",
            "3. Run pc_clipboard_monitor.py on PC",
            "4. Ctrl+C any image on PC",
            "5. Long-press -> Paste on tablet \u2713"
        };
        for (String s : steps) {
            TextView tv = new TextView(this);
            tv.setText(s);
            tv.setTextSize(14);
            tv.setTextColor(0xFFCCCCEE);
            tv.setPadding(0, 10, 0, 10);
            layout.addView(tv);
        }

        Button permBtn = new Button(this);
        permBtn.setText("Grant Overlay Permission");
        permBtn.setBackgroundColor(0xFF4A90E2);
        permBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 48, 0, 16);
        permBtn.setLayoutParams(p);
        permBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivityForResult(new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), 1001);
                } else {
                    Toast.makeText(this, "Permission already granted", Toast.LENGTH_SHORT).show();
                }
            }
        });
        layout.addView(permBtn);

        TextView adbHint = new TextView(this);
        adbHint.setText("ADB command:\nadb shell am broadcast -a com.clipboardbridge.SET_IMAGE -n com.clipboardbridge/.ClipboardReceiver --es image_path /sdcard/cb_tmp.png");
        adbHint.setTextSize(11);
        adbHint.setTextColor(0xFF666688);
        adbHint.setPadding(0, 32, 0, 0);
        layout.addView(adbHint);

        setContentView(layout);
    }
}
