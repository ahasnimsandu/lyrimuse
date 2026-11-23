package me.lyrimuse.android;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings: playback, lyric sources, floating bar, storage and permissions. */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch keepAwake = findViewById(R.id.swKeepAwake);
        Switch tapSeek = findViewById(R.id.swTapSeek);
        Switch translation = findViewById(R.id.swTranslation);
        Switch netease = findViewById(R.id.swNetease);
        Switch lrclib = findViewById(R.id.swLrclib);
        Switch accent = findViewById(R.id.swAccent);
        Switch autoRetry = findViewById(R.id.swAutoRetry);
        Switch qq = findViewById(R.id.swQq);
        Switch overlay = findViewById(R.id.swOverlay);

        SeekBar size = findViewById(R.id.seekSize);
        SeekBar offset = findViewById(R.id.seekOffset);
        TextView offsetValue = findViewById(R.id.offsetValue);
        SeekBar opacity = findViewById(R.id.seekOpacity);
        TextView opacityValue = findViewById(R.id.opacityValue);
        SeekBar overlaySize = findViewById(R.id.seekOverlaySize);
        TextView overlaySizeValue = findViewById(R.id.overlaySizeValue);
        SeekBar songOffset = findViewById(R.id.seekSongOffset);
        TextView songOffsetValue = findViewById(R.id.songOffsetValue);
        Button resetSong = findViewById(R.id.btnResetSong);
        TextView languageValue = findViewById(R.id.languageValue);
        TextView cacheInfo = findViewById(R.id.cacheInfo);
        TextView version = findViewById(R.id.version);

        keepAwake.setChecked(Prefs.keepAwake(this));
        tapSeek.setChecked(Prefs.tapSeek(this));
        translation.setChecked(Prefs.translation(this));
        netease.setChecked(Prefs.netease(this));
        lrclib.setChecked(Prefs.lrclib(this));
        accent.setChecked(Prefs.accentFromArt(this));
        autoRetry.setChecked(Prefs.autoRetry(this));
        qq.setChecked(Prefs.qq(this));
        overlay.setChecked(Prefs.overlay(this));

        size.setProgress(Math.round((Prefs.textScale(this) - 0.7f) * 100));
        offset.setProgress((int) (Prefs.offsetMs(this) + 2000));
        offsetValue.setText(offsetLabel(Prefs.offsetMs(this)));
        opacity.setProgress(Prefs.overlayOpacity(this));
        opacityValue.setText(Prefs.overlayOpacity(this) + "%");
        overlaySize.setProgress(Math.round((Prefs.overlayScale(this) - 0.7f) * 100));
        overlaySizeValue.setText(Math.round(Prefs.overlayScale(this) * 100) + "%");
        cacheInfo.setText(Cache.count(this) + " songs cached offline");

        languageValue.setText(languageLabel(Prefs.lyricLanguage(this)));
        findViewById(R.id.rowLanguage).setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Lyrics language")
                        .setSingleChoiceItems(LANGUAGE_LABELS, languageIndex(Prefs.lyricLanguage(this)),
                                (dialog, which) -> {
                                    Prefs.setLyricLanguage(this, LANGUAGE_KEYS[which]);
                                    languageValue.setText(LANGUAGE_LABELS[which]);
                                    dialog.dismiss();
                                    // the cache key includes the language, so this re-fetches
                                    // the current song in the new presentation
                                    PlayerService.refreshLyrics();
                                })
                        .show());

        // per-song offset: every lyric file drifts differently, so a global shift only
        // ever averages those errors out
        final String songKey = Cache.key(this, PlayerService.title, PlayerService.artist,
                PlayerService.album, PlayerService.duration());
        final boolean hasSong = !PlayerService.title.isEmpty();
        long perSong = hasSong ? Prefs.songOffset(this, songKey) : 0;
        songOffset.setEnabled(hasSong);
        resetSong.setEnabled(hasSong);
        songOffset.setProgress(clamp(2000 + (int) (perSong / 50), 0, 4000));
        songOffsetValue.setText(hasSong ? offsetLabel(perSong) : "No song playing");
        songOffset.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                if (!fromUser || !hasSong) return;
                long ms = (value - 2000L) * 50L;
                // recompute: the language may have changed the cache key since onCreate
                Prefs.setSongOffset(SettingsActivity.this, currentSongKey(), ms);
                songOffsetValue.setText(offsetLabel(ms));
            }
        });
        resetSong.setOnClickListener(v -> {
            if (!hasSong) return;
            Prefs.setSongOffset(SettingsActivity.this, currentSongKey(), 0);
            songOffset.setProgress(2000);
            songOffsetValue.setText(offsetLabel(0));
        });

        keepAwake.setOnCheckedChangeListener((b, on) -> Prefs.setKeepAwake(this, on));
        tapSeek.setOnCheckedChangeListener((b, on) -> Prefs.setTapSeek(this, on));
        translation.setOnCheckedChangeListener((b, on) -> Prefs.setTranslation(this, on));
        netease.setOnCheckedChangeListener((b, on) -> Prefs.setNetease(this, on));
        lrclib.setOnCheckedChangeListener((b, on) -> Prefs.setLrclib(this, on));
        accent.setOnCheckedChangeListener((b, on) -> Prefs.setAccentFromArt(this, on));
        autoRetry.setOnCheckedChangeListener((b, on) -> Prefs.setAutoRetry(this, on));
        qq.setOnCheckedChangeListener((b, on) -> Prefs.setQq(this, on));

        TextView fontValue = findViewById(R.id.fontValue);
        fontValue.setText(Fonts.label(Prefs.lyricFont(this)));
        findViewById(R.id.rowFont).setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Lyrics font")
                        .setSingleChoiceItems(Fonts.LABELS, Fonts.indexOf(Prefs.lyricFont(this)),
                                (dialog, which) -> {
                                    Prefs.setLyricFont(this, Fonts.KEYS[which]);
                                    fontValue.setText(Fonts.LABELS[which]);
                                    dialog.dismiss();
                                })
                        .show());

        overlay.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setOverlay(this, checked);
            if (checked && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow “Display over other apps”", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
            OverlayService.sync(this);
        });

        size.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                Prefs.setTextScale(SettingsActivity.this, 0.7f + value / 100f);
            }
        });

        offset.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                long ms = value - 2000L;
                Prefs.setOffsetMs(SettingsActivity.this, ms);
                offsetValue.setText(offsetLabel(ms));
            }
        });

        opacity.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                Prefs.setOverlayOpacity(SettingsActivity.this, Math.max(60, value));
                opacityValue.setText(Math.max(60, value) + "%");
            }
        });

        overlaySize.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                float scale = 0.7f + value / 100f;
                Prefs.setOverlayScale(SettingsActivity.this, scale);
                overlaySizeValue.setText(Math.round(scale * 100) + "%");
            }
        });

        Button notification = findViewById(R.id.btnNotification2);
        notification.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        findViewById(R.id.btnOverlayPerm2).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));

        findViewById(R.id.btnClearCache).setOnClickListener(v -> {
            Cache.clear(this);
            cacheInfo.setText("0 songs cached offline");
            Toast.makeText(this, "Offline lyrics cleared", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnDone).setOnClickListener(v -> finish());

        String v = "1.0.0";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        version.setText("Lyriu " + v + " · GPL-3.0 · lyrics by NetEase, LRCLIB & QQ Music");
    }

    private static final String[] LANGUAGE_KEYS = {"english", "original", "romanized"};
    private static final String[] LANGUAGE_LABELS = {
            "English only", "Original + translation", "Romanised (Korean)"
    };

    private static int languageIndex(String mode) {
        for (int i = 0; i < LANGUAGE_KEYS.length; i++) {
            if (LANGUAGE_KEYS[i].equals(mode)) return i;
        }
        return 0;
    }

    private static String languageLabel(String mode) {
        return LANGUAGE_LABELS[languageIndex(mode)];
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private String currentSongKey() {
        return Cache.key(this, PlayerService.title, PlayerService.artist,
                PlayerService.album, PlayerService.duration());
    }

    private String offsetLabel(long ms) {
        if (ms == 0) return "0 ms (in sync)";
        return (ms > 0 ? "+" : "") + ms + " ms";
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
