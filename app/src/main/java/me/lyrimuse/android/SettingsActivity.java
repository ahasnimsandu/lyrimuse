package me.lyrimuse.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings: overlay, translation, text size, sync offset, permissions, cache. */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch overlay = findViewById(R.id.swOverlay);
        Switch translation = findViewById(R.id.swTranslation);
        SeekBar size = findViewById(R.id.seekSize);
        SeekBar offset = findViewById(R.id.seekOffset);
        TextView offsetValue = findViewById(R.id.offsetValue);
        TextView cacheInfo = findViewById(R.id.cacheInfo);

        overlay.setChecked(Prefs.overlay(this));
        translation.setChecked(Prefs.translation(this));
        size.setProgress(Math.round((Prefs.textScale(this) - 0.7f) * 100));
        offset.setProgress((int) (Prefs.offsetMs(this) + 2000));
        offsetValue.setText(offsetLabel(Prefs.offsetMs(this)));
        cacheInfo.setText(Cache.count(this) + " songs cached offline");

        overlay.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setOverlay(this, checked);
            if (checked && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow “Display over other apps”", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
            OverlayService.sync(this);
        });

        translation.setOnCheckedChangeListener((b, checked) -> Prefs.setTranslation(this, checked));

        size.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                Prefs.setTextScale(SettingsActivity.this, 0.7f + value / 100f);
            }
        });

        offset.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                long ms = value - 2000L;
                Prefs.setOffsetMs(SettingsActivity.this, ms);
                offsetValue.setText(offsetLabel(ms));
            }
        });

        Button notif = findViewById(R.id.btnNotification2);
        notif.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        findViewById(R.id.btnClearCache).setOnClickListener(v -> {
            Cache.clear(this);
            cacheInfo.setText("0 songs cached offline");
            Toast.makeText(this, "Offline lyrics cleared", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnDone).setOnClickListener(v -> finish());
    }

    private String offsetLabel(long ms) {
        if (ms == 0) return "0 ms (in sync)";
        return (ms > 0 ? "+" : "") + ms + " ms";
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
