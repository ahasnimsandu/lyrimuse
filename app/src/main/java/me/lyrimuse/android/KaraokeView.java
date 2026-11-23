package me.lyrimuse.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Scrolling karaoke lyrics, modelled on the Apple Music lyrics page.
 *
 * <p>Drag to scroll: while a finger is down (and for a short while after) the view stops
 * following the song, so it no longer snaps back. Otherwise the view eases toward the
 * active line and inflates it, and the fill runs word by word at 60 fps by extrapolating
 * the playback position between the player's updates.
 */
public class KaraokeView extends View {

    public interface Listener {
        void onSeek(long ms);
    }

    /** How long a manual scroll stays put before it glides back to the live line. */
    private static final long RESUME_AFTER_DRAG_MS = 1500;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint transPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint ellipsizePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private Lyrics lyrics;
    private String status = "";
    private float scale = 1f;
    private boolean showTranslation = true;
    private boolean seekEnabled = true;
    private boolean playing = false;
    private int accent = 0xFFE8C9A0;
    private Listener listener;

    private long offsetMs = 0;
    private long anchorPos = 0;
    private long anchorAt = 0;

    /** Scroll position in line units (line index + fraction), eased toward the song. */
    private float scroll = 0f;
    private boolean scrollInitialised = false;

    private boolean dragging = false;
    private boolean tapCandidate = false;
    private float downY = 0f;
    private float lastY = 0f;
    private long downAt = 0L;
    private long lastInteractionAt = 0L;
    private final int touchSlop;

    /** Manual scroll offset in line units; decays back to 0 after a pause. */
    private float dragLines = 0f;
    private android.animation.ValueAnimator returnAnim;
    private final Runnable returnToSong = new Runnable() {
        @Override
        public void run() {
            if (dragging) return;
            if (Math.abs(dragLines) < 0.02f) {
                dragLines = 0f;
                invalidate();
                return;
            }
            cancelReturnAnim();
            returnAnim = android.animation.ValueAnimator.ofFloat(dragLines, 0f);
            returnAnim.setDuration(420);
            returnAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            returnAnim.addUpdateListener(a -> {
                dragLines = (float) a.getAnimatedValue();
                invalidate();
            });
            returnAnim.start();
        }
    };

    private final Map<String, String> ellipsisCache = new HashMap<>();
    private int cacheWidth = -1;
    private float cacheSize = -1f;
    private Lyrics cacheLyrics;

    public KaraokeView(Context c) {
        super(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        init();
    }

    public KaraokeView(Context c, AttributeSet a) {
        super(c, a);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        init();
    }

    private void init() {
        Typeface serif = Fonts.typeface("serif");
        linePaint.setTypeface(serif);
        fillPaint.setTypeface(serif);
        transPaint.setTypeface(serif);
        statusPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        statusPaint.setTextSize(sp(14));
        statusPaint.setColor(0x99FFFFFF);
        // Hardware acceleration: a full-screen software layer (needed only for glow
        // shadows) is what made scrolling and the word fill stutter on device.
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setSeekEnabled(boolean on) {
        seekEnabled = on;
    }

    public void setLyricFont(String key) {
        Typeface tf = Fonts.typeface(key);
        linePaint.setTypeface(tf);
        fillPaint.setTypeface(tf);
        transPaint.setTypeface(tf);
        ellipsisCache.clear();
        invalidate();
    }

    public void setAccent(int color) {
        accent = color;
        fillPaint.setColor(color);
        invalidate();
    }

    public void setPlaying(boolean on) {
        if (playing == on) return;
        playing = on;
        anchorPos = currentPosition();
        anchorAt = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    public void setState(Lyrics l, long pos, String statusText, float textScale,
                         boolean translation, long offset) {
        if (l != lyrics) scrollInitialised = false;
        lyrics = l;
        anchorPos = pos;
        anchorAt = SystemClock.uptimeMillis();
        status = statusText == null ? "" : statusText;
        scale = textScale;
        showTranslation = translation;
        offsetMs = offset;
        // keep the frame loop alive while the song plays
        if (playing) postInvalidateOnAnimation();
        invalidate();
    }

    private long currentPosition() {
        long now = SystemClock.uptimeMillis();
        long p = anchorPos;
        if (playing) p += now - anchorAt;
        return Math.max(0, p);
    }

    private float lineGap() {
        return sp(21) * scale * (showTranslation ? 2.45f : 1.95f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = dp(6);
        float width = getWidth() - pad * 2;
        if (width <= 0) return;

        if (lyrics == null || lyrics.lines.isEmpty()) {
            String msg = status.isEmpty() ? "Waiting for a song…" : status;
            statusPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(msg, getWidth() / 2f, getHeight() / 2f, statusPaint);
            return;
        }

        if (!lyrics.synced) {
            // Unsynchronised lyrics: plain scrollable text.
            float ts = sp(19) * scale;
            linePaint.setTextSize(ts);
            linePaint.setColor(0xCCFFFFFF);
            linePaint.setTextAlign(Paint.Align.LEFT);
            float gap = ts * 1.7f;
            float baseY = ts * 1.8f + (scroll + dragLines) * gap;
            int first = Math.max(0, (int) Math.floor(-baseY / gap));
            for (int i = first; i < lyrics.lines.size(); i++) {
                float y = baseY + i * gap;
                if (y > getHeight() + gap) break;
                if (y < -gap) continue;
                String text = ellipsize(i, 0, lyrics.lines.get(i).text, linePaint, width);
                canvas.drawText(text, pad, y, linePaint);
            }
            if (playing) postInvalidateOnAnimation();
            return;
        }

        long now = SystemClock.uptimeMillis();
        long pos = currentPosition() - offsetMs;

        int n = lyrics.lines.size();
        int idx = lyrics.indexAt(pos);
        if (idx < 0) idx = 0;
        int cur = Math.min(idx, n - 1);
        Lyrics.Line current = lyrics.lines.get(cur);
        float frac = 0f;
        if (pos > current.start) {
            frac = Math.min(1f, (pos - current.start)
                    / (float) Math.max(1, current.end - current.start));
        }
        float target = cur + frac;

        if (!scrollInitialised) {
            scroll = target;
            scrollInitialised = true;
        } else {
            // the live line always eases smoothly; manual drag is added on top
            scroll += (target - scroll) * 0.18f;
        }
        if (scroll < -1f) scroll = -1f;
        if (scroll > n - 1 + 1f) scroll = n - 1 + 1f;

        float base = sp(21) * scale;
        float gap = lineGap();
        float centerY = getHeight() * 0.40f;
        float top = -gap * 1.5f;
        float bottom = getHeight() + gap * 1.5f;

        float viewCenter = scroll + dragLines;
        for (int i = 0; i < n; i++) {
            float y = centerY + (i - viewCenter) * gap;
            if (y < top) continue;
            if (y > bottom) break;

            Lyrics.Line l = lyrics.lines.get(i);
            float dist = Math.abs(i - viewCenter);
            float near = Math.max(0f, 1f - dist);          // 1 on the active line, 0 elsewhere
            float ts = base * (0.95f + 0.17f * near);
            linePaint.setTextSize(ts);
            fillPaint.setTextSize(ts);
            transPaint.setTextSize(ts * 0.56f);

            int alpha = (int) Math.max(58, 255 - 190 * Math.min(1f, dist / 3f));
            linePaint.setColor(0xFFFFFFFF);
            linePaint.setAlpha(alpha);
            linePaint.setTextAlign(Paint.Align.LEFT);

            String text = ellipsize(i, 0, l.text, linePaint, width);
            canvas.drawText(text, pad, y, linePaint);

            boolean isCurrent = i == cur;
            float sungChars = sungChars(l, pos, text);
            if (sungChars > 0f) {
                float fillWidth = measurePrefix(text, sungChars, fillPaint);
                canvas.save();
                canvas.clipRect(pad, y - ts * 1.25f, pad + fillWidth, y + ts * 0.4f);
                fillPaint.setAlpha(255);
                canvas.drawText(text, pad, y, fillPaint);
                canvas.restore();
            }

            if (showTranslation && l.trans != null && !l.trans.isEmpty()) {
                transPaint.setColor(0xFFFFFFFF);
                transPaint.setAlpha((int) Math.max(42, 170 - 120 * Math.min(1f, dist / 3f)));
                canvas.drawText(ellipsize(i, 1, l.trans, transPaint, width), pad, y + ts * 0.92f, transPaint);
            }
        }

        if (playing) postInvalidateOnAnimation();
    }

    /** Fraction (in characters) of the line that has been sung. */
    private float sungChars(Lyrics.Line line, long pos, String display) {
        if (pos <= line.start) return 0f;
        int total = display.length();
        if (total == 0) return 0f;
        if (pos >= line.end) return total;
        if (line.hasWords()) {
            int wordChars = 0;
            for (Lyrics.Word w : line.words) wordChars += w.text.length();
            if (wordChars == 0) return 0f;
            float sung = 0f;
            for (Lyrics.Word w : line.words) {
                int len = w.text.length();
                if (pos >= w.end) sung += len;
                else if (pos > w.start) sung += len * ((pos - w.start) / (float) Math.max(1, w.end - w.start));
            }
            return Math.min(1f, sung / wordChars) * total;
        }
        float frac = (pos - line.start) / (float) Math.max(1, line.end - line.start);
        return Math.min(1f, frac) * total;
    }

    private float measurePrefix(String text, float chars, Paint p) {
        int k = (int) Math.floor(chars);
        if (k <= 0) return 0f;
        if (k >= text.length()) return p.measureText(text);
        float w = p.measureText(text, 0, k);
        float partial = chars - k;
        w += partial * p.measureText(text.substring(k, k + 1));
        return w;
    }

    /** Ellipsized text is cached per line, because TextUtils.ellipsize allocates on every call. */
    private String ellipsize(int index, int kind, String text, Paint p, float width) {
        if (text == null) return "";
        if (lyrics != cacheLyrics || (int) width != cacheWidth || p.getTextSize() != cacheSize) {
            ellipsisCache.clear();
            cacheLyrics = lyrics;
            cacheWidth = (int) width;
            cacheSize = p.getTextSize();
        }
        String key = index + "|" + kind + "|" + (int) p.getTextSize();
        String cached = ellipsisCache.get(key);
        if (cached != null) return cached;
        String result;
        if (p.measureText(text) <= width) {
            result = text;
        } else {
            ellipsizePaint.set(p);
            result = TextUtils.ellipsize(text, ellipsizePaint, width, TextUtils.TruncateAt.END).toString();
        }
        ellipsisCache.put(key, result);
        return result;
    }

    private void cancelReturnAnim() {
        if (returnAnim != null) {
            returnAnim.cancel();
            returnAnim = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(returnToSong);
        cancelReturnAnim();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                lastY = downY;
                downAt = now;
                tapCandidate = true;
                dragging = false;
                lastInteractionAt = now;
                removeCallbacks(returnToSong);
                cancelReturnAnim();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dy = lastY - e.getY();
                lastY = e.getY();
                if (!dragging && Math.abs(e.getY() - downY) > touchSlop) {
                    dragging = true;
                    tapCandidate = false;
                }
                if (dragging) {
                    float gap = lineGap();
                    if (gap > 1f) dragLines += dy / gap;
                    float limit = Math.max(3f, lyrics == null ? 3f : lyrics.lines.size() + 2f);
                    if (dragLines > limit) dragLines = limit;
                    if (dragLines < -limit) dragLines = -limit;
                    lastInteractionAt = now;
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                lastInteractionAt = now;
                boolean wasTap = tapCandidate && !dragging && now - downAt < 450
                        && Math.abs(e.getY() - downY) <= touchSlop;
                dragging = false;
                tapCandidate = false;
                removeCallbacks(returnToSong);
                postDelayed(returnToSong, RESUME_AFTER_DRAG_MS);
                if (wasTap && lyrics != null && lyrics.synced && seekEnabled && listener != null) {
                    float gap = lineGap();
                    float centerY = getHeight() * 0.40f;
                    int tapped = Math.round((e.getY() - centerY) / gap + scroll + dragLines);
                    int n = lyrics.lines.size();
                    if (tapped >= 0 && tapped < n) {
                        listener.onSeek(lyrics.lines.get(tapped).start);
                        lastInteractionAt = 0; // follow the new position straight away
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                tapCandidate = false;
                lastInteractionAt = now;
                postDelayed(returnToSong, RESUME_AFTER_DRAG_MS);
                return true;

            default:
                return true;
        }
    }
}
