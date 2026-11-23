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
import android.widget.SeekBar;
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
    private SeekBar progress;
    private LinearLayout permBar;
    private boolean scrubbing;

    private int accent = 0xFFE8C9A0;
    private LinearLayout controlsCard;
    private String lastMeta = "";
    private Bitmap lastArt;
    private Boolean lastPlaying;

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
        controlsCard = findViewById(R.id.controlsCard);

        karaoke.setListener(ms -> PlayerService.seekTo(ms));

        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                long dur = PlayerService.duration();
                if (dur > 0) elapsed.setText(fmt(value * dur / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
                long dur = PlayerService.duration();
                if (dur > 0) PlayerService.seekTo(bar.getProgress() * dur / 1000);
            }
        });

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
        widenForLargeScreens();
        checkForUpdate();
    }

    /** Tablets, foldables and landscape phones get a centred column instead of edge-to-edge. */
    private void widenForLargeScreens() {
        View root = findViewById(R.id.root);
        if (root == null) return;
        if (getResources().getConfiguration().screenWidthDp < 600) return;
        int pad = (int) (getResources().getDisplayMetrics().widthPixels * 0.12f);
        root.setPadding(pad, root.getPaddingTop(), pad, root.getPaddingBottom());
    }

    private void checkForUpdate() {
        final TextView bar = findViewById(R.id.updateBar);
        if (bar == null) return;
        Updates.check(this, (version, url) -> {
            if (isFinishing() || isDestroyed()) return;
            bar.setText("Lyriu " + version + " is available · Tap to update");
            bar.setVisibility(View.VISIBLE);
            bar.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
            });
        });
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
        progress.setThumbTintList(ColorStateList.valueOf(color));
        btnFloat.setTextColor(color);
        source.setTextColor(color);
        eyebrow.setTextColor(mix(0xFFFFFFFF, color, 0.45f));

        int card = mix(0xFF0B0D12, color, 0.14f);
        if (controlsCard != null) {
            controlsCard.setBackgroundTintList(ColorStateList.valueOf(card));
        }
        if (permBar != null) {
            permBar.setBackgroundTintList(ColorStateList.valueOf(card));
        }
        int chrome = mix(0xFF06070B, color, 0.07f);
        getWindow().setStatusBarColor(chrome);
        getWindow().setNavigationBarColor(chrome);
    }

    /** Blends two ARGB colours, {@code amount} = share of {@code b}. */
    private int mix(int a, int b, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int alpha = (int) (android.graphics.Color.alpha(a) * (1 - t) + android.graphics.Color.alpha(b) * t);
        int red = (int) (android.graphics.Color.red(a) * (1 - t) + android.graphics.Color.red(b) * t);
        int green = (int) (android.graphics.Color.green(a) * (1 - t) + android.graphics.Color.green(b) * t);
        int blue = (int) (android.graphics.Color.blue(a) * (1 - t) + android.graphics.Color.blue(b) * t);
        return android.graphics.Color.argb(alpha, red, green, blue);
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
            permTitle.setText("Grant notification access so Lyriu can read what every player is playing.");
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

        if (lastPlaying == null || lastPlaying != PlayerService.playing) {
            lastPlaying = PlayerService.playing;
            play.setImageResource(PlayerService.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        }

        long pos = PlayerService.positionMs();
        long dur = PlayerService.duration();
        karaoke.setPlaying(PlayerService.playing);
        karaoke.setState(PlayerService.lyrics, pos, PlayerService.status,
                Prefs.textScale(this), Prefs.translation(this), PlayerService.effectiveOffset(this));

        if (dur > 0) {
            if (!scrubbing) {
                progress.setProgress((int) Math.min(1000, pos * 1000 / dur));
                elapsed.setText(fmt(pos));
            }
            total.setText(fmt(dur));
        } else {
            if (!scrubbing) {
                progress.setProgress(0);
                elapsed.setText("0:00");
            }
            total.setText("0:00");
        }
    }

    private String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
