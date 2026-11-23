package me.lyrimuse.android;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

/** Floating always-on-top lyric bar, like the desktop overlay in the original app. */
public class OverlayService extends Service {

    public static volatile boolean running = false;

    private static final long TICK_PLAYING_MS = 80;
    private static final long TICK_IDLE_MS = 320;

    private WindowManager wm;
    private OverlayView view;
    private WindowManager.LayoutParams lp;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static boolean canDraw(Context c) {
        return Settings.canDrawOverlays(c);
    }

    public static void sync(Context c) {
        if (Prefs.overlay(c) && canDraw(c)) {
            try {
                c.startService(new Intent(c, OverlayService.class));
            } catch (Exception ignored) {
            }
        } else {
            try {
                c.stopService(new Intent(c, OverlayService.class));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!canDraw(this)) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        view = new OverlayView(this);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int savedX = Prefs.overlayX(this);
        int savedY = Prefs.overlayY(this);
        lp.x = savedX != Integer.MIN_VALUE ? savedX : 0;
        lp.y = savedY != Integer.MIN_VALUE ? savedY : (int) (72 * getResources().getDisplayMetrics().density);
        lp.windowAnimations = android.R.style.Animation_Toast;
        lp.alpha = Math.max(0.25f, Prefs.overlayOpacity(this) / 255f);
        view.setTextScale(Prefs.overlayScale(this));
        view.setLyricFont(Prefs.lyricFont(this));
        view.setTranslationEnabled(Prefs.translation(this));

        view.setListener(new OverlayView.Listener() {
            @Override
            public void onDrag(float dx, float dy) {
                lp.x += (int) dx;
                lp.y += (int) dy;
                try {
                    wm.updateViewLayout(view, lp);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onDragEnd(int x, int y) {
                // remember where the bar was put, so a reboot or service restart doesn't
                // snap it back to the top of the screen
                Prefs.setOverlayPosition(OverlayService.this, lp.x, lp.y);
            }

            @Override
            public void onTap() {
                Intent open = new Intent(OverlayService.this, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(open);
            }

            @Override
            public void onLongPress() {
                Prefs.setOverlay(OverlayService.this, false);
                stopSelf();
            }
        });

        try {
            wm.addView(view, lp);
            running = true;
        } catch (Exception e) {
            stopSelf();
            return;
        }
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (view != null) {
                float alpha = Math.max(0.25f, Prefs.overlayOpacity(OverlayService.this) / 255f);
                if (lp != null && Math.abs(lp.alpha - alpha) > 0.01f) {
                    lp.alpha = alpha;
                    try {
                        wm.updateViewLayout(view, lp);
                    } catch (Exception ignored) {
                    }
                }
                view.setTextScale(Prefs.overlayScale(OverlayService.this));
                view.setLyricFont(Prefs.lyricFont(OverlayService.this));
                view.setTranslationEnabled(Prefs.translation(OverlayService.this));
                String fallback = PlayerService.title.isEmpty()
                        ? "Lyriu — waiting for a song"
                        : PlayerService.title + (PlayerService.artist.isEmpty() ? "" : " — " + PlayerService.artist);
                view.setState(PlayerService.lyrics, PlayerService.positionMs(),
                        PlayerService.effectiveOffset(OverlayService.this), fallback);
            }
            handler.postDelayed(this, PlayerService.playing ? TICK_PLAYING_MS : TICK_IDLE_MS);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (wm != null && view != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
        }
        view = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
