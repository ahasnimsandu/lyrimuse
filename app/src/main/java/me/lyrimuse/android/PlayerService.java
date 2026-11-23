package me.lyrimuse.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core engine: reads the active MediaSession of whatever music app is playing
 * (Spotify, YouTube Music, Apple Music, NetEase, QQ Music, Poweramp, ...),
 * keeps an accurate playback clock, and matches word-by-word lyrics.
 */
public class PlayerService extends Service {

    private static final String TAG = "LyrimusePlayer";
    private static final String CHANNEL_ID = "lyrimuse_now_playing";

    public static volatile PlayerService instance;

    public static volatile String title = "";
    public static volatile String artist = "";
    public static volatile String album = "";
    public static volatile String playerApp = "";
    public static volatile String playerPackage = "";
    public static volatile String status = "Play a song in any music app";
    public static volatile String source = "";
    public static volatile Bitmap art;
    public static volatile long durationMs = 0;
    public static volatile boolean playing = false;
    public static volatile Lyrics lyrics;

    private static volatile long lastPos = 0;
    private static volatile long lastPosAt = 0;
    private static volatile float speed = 1f;

    private MediaSessionManager sessionManager;
    private MediaController controller;
    private Handler handler;
    private HandlerThread thread;
    private ExecutorService io;
    private int generation = 0;
    private String songKey = "";
    private long lastRetryAt = 0;
    private int retryCount = 0;

    public static long positionMs() {
        long p = lastPos;
        if (playing) {
            p += (long) ((SystemClock.elapsedRealtime() - lastPosAt) * speed);
        }
        return Math.max(0, p);
    }

    public static long duration() {
        return durationMs;
    }

    public static boolean hasNotificationAccess(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        return flat.contains(c.getPackageName());
    }

    public static void start(Context c) {
        Intent i = new Intent(c, PlayerService.class);
        try {
            c.startForegroundService(i);
        } catch (Exception e) {
            try {
                c.startService(i);
            } catch (Exception ignored) {
            }
        }
    }

    public static void seekTo(long ms) {
        PlayerService s = instance;
        if (s != null && s.controller != null) {
            try {
                s.controller.getTransportControls().seekTo(Math.max(0, ms));
            } catch (Exception ignored) {
            }
        }
    }

    public static void togglePlay() {
        PlayerService s = instance;
        if (s == null || s.controller == null) return;
        try {
            if (playing) s.controller.getTransportControls().pause();
            else s.controller.getTransportControls().play();
        } catch (Exception ignored) {
        }
    }

    public static void skipNext() {
        PlayerService s = instance;
        if (s == null || s.controller == null) return;
        try {
            s.controller.getTransportControls().skipToNext();
        } catch (Exception ignored) {
        }
    }

    public static void skipPrevious() {
        PlayerService s = instance;
        if (s == null || s.controller == null) return;
        try {
            s.controller.getTransportControls().skipToPrevious();
        } catch (Exception ignored) {
        }
    }

    /** Applies a manually chosen search result to the current song and caches it. */
    public static void applyManualLyrics(LyricsRepository.Candidate c) {
        PlayerService s = instance;
        if (s == null || c == null || c.lyrics == null) return;
        s.generation++;
        lyrics = c.lyrics;
        source = "Manual · " + c.source;
        status = "";
        Cache.save(s.getApplicationContext(), Cache.key(title, artist), c.lyrics);
        Log.i(TAG, "manual lyrics applied: " + c.title + " / " + c.artist + " (" + c.source + ")");
        s.updateForeground();
    }

    /** Force a fresh lyric lookup for the current song (ignores the offline cache). */
    public static void refreshLyrics() {
        PlayerService s = instance;
        if (s == null) return;
        Cache.clear(PlayerService.instance.getApplicationContext());
        s.songKey = "";
        s.requestTick();
        s.handler.postDelayed(s::poll, 300);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        io = Executors.newSingleThreadExecutor();
        thread = new HandlerThread("lyrimuse-poll");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            startForeground(1, buildNotification());
        } catch (Exception e) {
            // Android 14 can refuse a dataSync FGS when started from the background;
            // the service still polls and drives the overlay.
            Log.w(TAG, "startForeground refused: " + e);
        }
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                poll();
            } catch (Exception e) {
                Log.w(TAG, "poll failed", e);
            }
            handler.postDelayed(this, 400);
        }
    };

    public void requestTick() {
        if (handler != null) handler.post(() -> {
            try {
                poll();
            } catch (Exception ignored) {
            }
        });
    }

    private void poll() {
        if (sessionManager == null) return;
        if (!hasNotificationAccess(this)) {
            status = "Notification access is off";
            playing = false;
            controller = null;
            updateForeground();
            return;
        }
        List<MediaController> sessions;
        try {
            sessions = sessionManager.getActiveSessions(new ComponentName(this, MediaListenerService.class));
        } catch (SecurityException e) {
            Log.w(TAG, "no media session access: " + e.getMessage());
            status = "Notification access is off";
            playing = false;
            controller = null;
            updateForeground();
            return;
        }
        MediaController next = choose(sessions);
        if (next == null) {
            if (controller != null) {
                controller = null;
                playing = false;
                status = "No song playing";
            }
            updateForeground();
            return;
        }
        boolean changedApp = controller == null || !next.getPackageName().equals(controller.getPackageName());
        controller = next;
        playerPackage = next.getPackageName();
        if (changedApp) {
            playerApp = labelFor(playerPackage);
            Log.i(TAG, "player: " + playerApp + " (" + playerPackage + ")");
        }

        MediaMetadata md = null;
        PlaybackState ps = null;
        try {
            md = next.getMetadata();
            ps = next.getPlaybackState();
        } catch (Exception ignored) {
        }

        String newTitle = title;
        String newArtist = artist;
        String newAlbum = album;
        long newDuration = durationMs;
        Bitmap newArt = art;

        if (md != null) {
            String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (t == null) t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (t != null && !t.trim().isEmpty()) newTitle = t.trim();
            String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (a != null) newArtist = a.trim();
            String al = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
            if (al != null) newAlbum = al.trim();
            long d = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (d > 0) newDuration = d;
            Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b != null) newArt = b;
        }

        String key = newTitle.toLowerCase() + "\u0000" + newArtist.toLowerCase();
        boolean songChanged = !key.equals(songKey);

        if (ps != null) {
            float sp = ps.getPlaybackSpeed();
            speed = sp > 0.1f ? sp : 1f;
            playing = ps.getState() == PlaybackState.STATE_PLAYING;
            long p = ps.getPosition();
            long stamp = ps.getLastPositionUpdateTime();
            if (stamp > 0) {
                lastPos = p + (long) ((SystemClock.elapsedRealtime() - stamp) * speed);
            } else {
                lastPos = p;
            }
            lastPosAt = SystemClock.elapsedRealtime();
        } else {
            playing = false;
            lastPos = 0;
            lastPosAt = SystemClock.elapsedRealtime();
        }

        title = newTitle;
        artist = newArtist;
        album = newAlbum;
        durationMs = newDuration;
        art = newArt;

        if (songChanged && !newTitle.isEmpty()) {
            Log.i(TAG, "song: " + newTitle + " | " + newArtist + " | " + newAlbum
                    + " | " + newDuration + "ms | pkg=" + playerPackage + " | playing=" + playing);
            songKey = key;
            lyrics = null;
            source = "";
            status = "Matching lyrics…";
            retryCount = 0;
            lastRetryAt = 0;
            fetchLyrics(newTitle, newArtist, newAlbum, newDuration);
        } else if (lyrics == null && !newTitle.isEmpty() && "No lyrics found".equals(status)
                && Prefs.autoRetry(this) && retryCount < 4
                && SystemClock.elapsedRealtime() - lastRetryAt > 15000) {
            // live detection: sources sometimes publish a match a little later
            retryCount++;
            lastRetryAt = SystemClock.elapsedRealtime();
            status = "Re-checking lyrics…";
            Log.i(TAG, "auto re-detect #" + retryCount + " for " + newTitle);
            fetchLyrics(newTitle, newArtist, newAlbum, newDuration);
        }
        if (lyrics == null && status.isEmpty()) status = "Matching lyrics…";
        updateForeground();
    }

    private MediaController choose(List<MediaController> sessions) {
        if (sessions == null || sessions.isEmpty()) return null;
        MediaController playingOne = null;
        MediaController sameApp = null;
        MediaController any = null;
        for (MediaController c : sessions) {
            String pkg = c.getPackageName();
            if (pkg != null && pkg.equals(getPackageName())) continue;
            MediaMetadata md;
            try {
                md = c.getMetadata();
            } catch (Exception e) {
                continue;
            }
            if (md == null) continue;
            String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (t == null || t.trim().isEmpty()) continue;
            PlaybackState ps = null;
            try {
                ps = c.getPlaybackState();
            } catch (Exception ignored) {
            }
            boolean isPlaying = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
            if (any == null) any = c;
            if (isPlaying && pkg != null && pkg.equals(playerPackage)) return c;
            if (isPlaying && playingOne == null) playingOne = c;
            if (pkg != null && pkg.equals(playerPackage) && sameApp == null) sameApp = c;
        }
        if (playingOne != null) return playingOne;
        if (sameApp != null) return sameApp;
        return any;
    }

    private void fetchLyrics(final String t, final String a, final String al, final long d) {
        final int gen = ++generation;
        final Context ctx = getApplicationContext();
        io.execute(() -> {
            Lyrics result = null;
            try {
                result = LyricsRepository.fetch(ctx, t, a, al, d);
            } catch (Exception e) {
                Log.w(TAG, "lyrics fetch failed", e);
            }
            if (gen != generation) return;
            lyrics = result;
            if (result == null) {
                source = "No lyrics found";
                status = "No lyrics found";
                Log.i(TAG, "lyrics: none for " + t + " | " + a + " | " + d + "ms");
            } else {
                source = result.source;
                status = "";
                Log.i(TAG, "lyrics: " + result.source + " lines=" + result.lines.size()
                        + " wordLevel=" + result.wordLevel + " synced=" + result.synced
                        + " for " + t + " | " + a);
            }
            updateForeground();
        });
    }

    private String labelFor(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Now playing lyrics",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = title.isEmpty() ? "Waiting for a song…" : title + (artist.isEmpty() ? "" : " — " + artist);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Lyrimuse")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private String lastNotificationText = "";

    private void updateForeground() {
        String text = title + "\u0000" + artist + "\u0000" + playing;
        if (text.equals(lastNotificationText)) return;
        lastNotificationText = text;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(1, buildNotification());
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        generation++;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (io != null) io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
