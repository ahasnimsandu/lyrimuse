package me.lyrimuse.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** Main screen: current song, word-by-word lyrics, playback and lyric controls. */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private KaraokeView karaoke;
    private TextView title, artist, source, player, permTitle, playButton, elapsed, total;
    private ImageView art;
    private ProgressBar progress;
    private LinearLayout permBar;

    private String lastMeta = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        karaoke = findViewById(R.id.karaoke);
        title = findViewById(R.id.title);
        artist = findViewById(R.id.artist);
        source = findViewById(R.id.source);
        player = findViewById(R.id.player);
        art = findViewById(R.id.art);
        progress = findViewById(R.id.progress);
        elapsed = findViewById(R.id.elapsed);
        total = findViewById(R.id.total);
        permBar = findViewById(R.id.permBar);
        permTitle = findViewById(R.id.permTitle);
        playButton = findViewById(R.id.btnPlay);

        karaoke.setListener(ms -> PlayerService.seekTo(ms));

        findViewById(R.id.btnPlay).setOnClickListener(v -> PlayerService.togglePlay());
        findViewById(R.id.btnPrev).setOnClickListener(v -> PlayerService.skipPrevious());
        findViewById(R.id.btnNext).setOnClickListener(v -> PlayerService.skipNext());
        findViewById(R.id.btnFind).setOnClickListener(v -> {
            PlayerService.refreshLyrics();
            Toast.makeText(this, "Searching all lyric sources…", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnFloat).setOnClickListener(v -> {
            boolean on = !Prefs.overlay(this);
            if (on && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow “Display over other apps” first", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            Prefs.setOverlay(this, on);
            OverlayService.sync(this);
            Toast.makeText(this, on ? "Floating lyrics on" : "Floating lyrics off", Toast.LENGTH_SHORT).show();
        });
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

        PlayerService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlayerService.start(this);
        updatePermissionBar();
        OverlayService.sync(this);
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
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
            ui.postDelayed(this, 120);
        }
    };

    private void render() {
        String meta = PlayerService.title + "\u0000" + PlayerService.artist + "\u0000"
                + PlayerService.playerApp + "\u0000" + PlayerService.source;
        if (!meta.equals(lastMeta)) {
            lastMeta = meta;
            title.setText(PlayerService.title.isEmpty() ? "Nothing playing" : PlayerService.title);
            artist.setText(PlayerService.artist);
            source.setText(PlayerService.source.isEmpty() ? PlayerService.status : PlayerService.source);
            player.setText(PlayerService.playerApp);
            art.setImageBitmap(PlayerService.art);
            if (PlayerService.art == null) art.setImageResource(R.drawable.ic_note);
            String floatState = Prefs.overlay(this) ? "Floating ✓" : "Floating";
            ((TextView) findViewById(R.id.btnFloat)).setText(floatState);
        }
        playButton.setText(PlayerService.playing ? "❚❚" : "▶");

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
