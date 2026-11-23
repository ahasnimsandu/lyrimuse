package me.lyrimuse.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** Player: blurred artwork backdrop, cover, Apple-Music lyric fill and controls. */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private BackdropView backdrop;
    private RoundedImageView coverMini, coverLarge;
    private KaraokeView karaoke;
    private TextView title, artist, source, eyebrow, permTitle, elapsed, total, btnFloat;
    private ImageButton play, prev, next;
    private ProgressBar progress;
    private LinearLayout permBar;

    private int accent = 0xFF7BE8B3;
    private String lastMeta = "";
    private Bitmap lastArt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        backdrop = findViewById(R.id.backdrop);
        coverMini = findViewById(R.id.coverMini);
        coverLarge = findViewById(R.id.coverLarge);
        karaoke = findViewById(R.id.karaoke);
        title = findViewById(R.id.title);
        artist = findViewById(R.id.artist);
        source = findViewById(R.id.source);
        eyebrow = findViewById(R.id.eyebrow);
        permBar = findViewById(R.id.permBar);
        permTitle = findViewById(R.id.permTitle);
        progress = findViewById(R.id.progress);
        elapsed = findViewById(R.id.elapsed);
        total = findViewById(R.id.total);
        play = findViewById(R.id.btnPlay);
        prev = findViewById(R.id.btnPrev);
        next = findViewById(R.id.btnNext);
        btnFloat = findViewById(R.id.btnFloat);

        karaoke.setListener(ms -> PlayerService.seekTo(ms));

        play.setOnClickListener(v -> PlayerService.togglePlay());
        prev.setOnClickListener(v -> PlayerService.skipPrevious());
        next.setOnClickListener(v -> PlayerService.skipNext());
        findViewById(R.id.btnFind).setOnClickListener(v -> {
            PlayerService.refreshLyrics();
            Toast.makeText(this, "Searching all lyric sources…", Toast.LENGTH_SHORT).show();
        });
        btnFloat.setOnClickListener(v -> toggleOverlay());
        findViewById(R.id.btnSearch).setOnClickListener(v ->
                startActivity(new Intent(this, LyricsFinderActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnNotification).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        findViewById(R.id.btnOverlayPerm).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }

        applyAccent(accent);
        PlayerService.start(this);
    }

    private void toggleOverlay() {
        boolean on = !Prefs.overlay(this);
        if (on && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps” first", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Prefs.setOverlay(this, on);
        OverlayService.sync(this);
        updateFloatLabel();
        Toast.makeText(this, on ? "Floating lyrics on" : "Floating lyrics off", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlayerService.start(this);
        updatePermissionBar();
        OverlayService.sync(this);
        if (Prefs.keepAwake(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        karaoke.setSeekEnabled(Prefs.tapSeek(this));
        karaoke.setLyricFont(Prefs.lyricFont(this));
        backdrop.setAccentEnabled(Prefs.accentFromArt(this));
        lastMeta = "";
        updateFloatLabel();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void updateFloatLabel() {
        btnFloat.setText(Prefs.overlay(this) ? "Floating on" : "Floating");
    }

    private void applyAccent(int color) {
        accent = color;
        karaoke.setAccent(color);
        play.setBackgroundTintList(ColorStateList.valueOf(color));
        progress.setProgressTintList(ColorStateList.valueOf(color));
        btnFloat.setTextColor(color);
    }

    private void updatePermissionBar() {
        boolean needsListener = !PlayerService.hasNotificationAccess(this);
        boolean needsOverlay = Prefs.overlay(this) && !Settings.canDrawOverlays(this);
        if (!needsListener && !needsOverlay) {
            permBar.setVisibility(View.GONE);
            return;
        }
        permBar.setVisibility(View.VISIBLE);
        if (needsListener) {
            permTitle.setText("Grant notification access so Lyrimuse can read what every player is playing.");
            findViewById(R.id.btnNotification).setVisibility(View.VISIBLE);
        } else {
            permTitle.setText("Allow “Display over other apps” for the floating lyric bar.");
            findViewById(R.id.btnNotification).setVisibility(View.GONE);
        }
        findViewById(R.id.btnOverlayPerm).setVisibility(needsOverlay ? View.VISIBLE : View.GONE);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 110);
        }
    };

    private void render() {
        String meta = PlayerService.title + "\u0000" + PlayerService.artist + "\u0000"
                + PlayerService.playerApp + "\u0000" + PlayerService.source;
        if (!meta.equals(lastMeta)) {
            lastMeta = meta;
            title.setText(PlayerService.title.isEmpty() ? "Nothing playing" : PlayerService.title);
            artist.setText(PlayerService.artist.isEmpty() ? "Start a song in any player" : PlayerService.artist);
            source.setText(PlayerService.source.isEmpty() ? PlayerService.status : PlayerService.source);
            eyebrow.setText(PlayerService.playerApp.isEmpty()
                    ? "NOW PLAYING"
                    : "NOW PLAYING · " + PlayerService.playerApp.toUpperCase());
        }
        if (PlayerService.art != lastArt) {
            lastArt = PlayerService.art;
            coverMini.setBitmap(lastArt);
            coverLarge.setBitmap(lastArt);
            backdrop.setArtwork(lastArt);
            if (Prefs.accentFromArt(this) && backdrop.accent() != accent) {
                applyAccent(backdrop.accent());
            }
        }

        play.setImageResource(PlayerService.playing ? R.drawable.ic_pause : R.drawable.ic_play);

        long pos = PlayerService.positionMs();
        long dur = PlayerService.duration();
        karaoke.setState(PlayerService.lyrics, pos, PlayerService.status,
                Prefs.textScale(this), Prefs.translation(this), Prefs.offsetMs(this));

        if (dur > 0) {
            progress.setProgress((int) Math.min(1000, pos * 1000 / dur));
            elapsed.setText(fmt(pos));
            total.setText(fmt(dur));
        } else {
            progress.setProgress(0);
            elapsed.setText("0:00");
            total.setText("0:00");
        }
    }

    private String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
