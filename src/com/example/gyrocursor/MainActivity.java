package com.example.gyrocursor;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Settings screen, built in code (no layout XML, no AndroidX). */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView status;
    private int pad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(CursorService.PREFS, MODE_PRIVATE);
        pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Gyro Cursor");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status);

        Button open = new Button(this);
        open.setText("Open accessibility settings");
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(open);

        Button appSettings = new Button(this);
        appSettings.setText("App settings (Autostart, battery)");
        appSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            }
        });
        root.addView(appSettings);

        root.addView(checkBox("Show cursor", CursorService.KEY_ENABLED, true));
        root.addView(checkBox("Aim assist (snap onto buttons)", CursorService.KEY_AIM_ASSIST, true));

        final TextView sensLabel = new TextView(this);
        sensLabel.setPadding(0, dp(16), 0, 0);
        root.addView(sensLabel);
        SeekBar sens = new SeekBar(this);
        sens.setMax(100);
        int current = prefs.getInt(CursorService.KEY_SENSITIVITY, CursorService.DEFAULT_SENSITIVITY);
        sens.setProgress(current);
        sensLabel.setText("Speed: " + current);
        sens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                value = Math.max(1, value);
                sensLabel.setText("Speed: " + value);
                prefs.edit().putInt(CursorService.KEY_SENSITIVITY, value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        root.addView(sens);

        root.addView(checkBox("Invert left / right", CursorService.KEY_INVERT_X, false));
        root.addView(checkBox("Invert up / down", CursorService.KEY_INVERT_Y, false));

        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setPadding(0, dp(20), 0, 0);
        help.setText(
                "How to use\n\n"
              + "1. Tap \"Open accessibility settings\", pick Gyro Cursor and turn it on.\n\n"
              + "2. A red dot appears. Turn the phone left/right and tilt it up/down to move it, "
              + "like pointing a laser out of the back of the phone.\n"
              + "   Aim assist: near a button the dot is pulled onto it and slows down, "
              + "and the button gets a red outline. Turn faster to break free.\n\n"
              + "3. Volume Down twice = your finger on the dot:\n"
              + "   • Quick double press = tap\n"
              + "   • Double press and keep holding = touch and hold (long press)\n"
              + "   • Keep holding and move the phone = drag / scroll\n"
              + "   • Let go = finger up\n\n"
              + "4. Volume Up twice quickly = Back.\n"
              + "   On the lock screen it unlocks instead (swipe up). The screen must be on, "
              + "and your PIN / fingerprint is still asked if you have one.\n\n"
              + "One press of either button still changes the volume; holding works too.\n\n"
              + "It starts by itself when the phone boots.\n\n"
              + "Xiaomi and some other phones stop background apps (for example when "
              + "the camera opens). Tap \"App settings\", turn on Autostart and set "
              + "Battery saver to \"No restrictions\" so it keeps running.\n\n"
              + "If Android says the setting is restricted: open Settings > Apps > Gyro Cursor, "
              + "tap the 3-dot menu > \"Allow restricted settings\", then try again.");
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        // The service may still be starting; check again shortly.
        status.postDelayed(new Runnable() {
            @Override public void run() { refreshStatus(); }
        }, 1000);
    }

    private void refreshStatus() {
        if (isServiceEnabled() && CursorService.running) {
            status.setText("● Running");
            status.setTextColor(0xFF2E9E4F);
        } else if (isServiceEnabled()) {
            status.setText("● Turned on, but not running. The phone stopped it "
                    + "(e.g. when the camera opened). Turn Gyro Cursor off and on again "
                    + "below, and allow Autostart in App settings.");
            status.setTextColor(0xFFE37400);
        } else {
            status.setText("● Service is OFF - turn it on below");
            status.setTextColor(0xFFD93025);
        }
    }

    private CheckBox checkBox(String label, final String key, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(16);
        cb.setPadding(0, dp(8), 0, dp(8));
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(key, checked).apply();
            }
        });
        return cb;
    }

    private boolean isServiceEnabled() {
        String list = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName me = new ComponentName(this, CursorService.class);
        for (String entry : list.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
