package me.lyrimuse.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** The floating two-line lyric bar shown over other apps. */
public class OverlayView extends View {

    public interface Listener {
        void onDrag(float dx, float dy);

        void onDragEnd(int x, int y);

        void onTap();

        void onLongPress();
    }

    private static final int SUNG = 0xFFE8C9A0;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trans = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint next = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Lyrics lyrics;
    private long position;
    private long offset;
    private String fallback = "";
    private Listener listener;
    private boolean showTranslation = true;

    private float downX, downY, lastX, lastY;
    private long downAt;
    private boolean dragging;

    public OverlayView(Context c) {
        super(c);
        init();
    }

    public OverlayView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        bg.setColor(0xE60B0D12);
        stroke.setColor(0x33FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1));
        text.setColor(0xE6FFFFFF);
        text.setTypeface(Fonts.typeface("serif"));
        fill.setColor(SUNG);
        fill.setTypeface(text.getTypeface());
        trans.setColor(0x99FFFFFF);
        trans.setTypeface(text.getTypeface());
        next.setColor(0x80FFFFFF);
        next.setTypeface(text.getTypeface());
        float ts = sp(16);
        text.setTextSize(ts);
        fill.setTextSize(ts);
        trans.setTextSize(ts * 0.78f);
        next.setTextSize(ts * 0.8f);
        setPadding((int) dp(18), (int) dp(14), (int) dp(18), (int) dp(14));
        setClickable(true);
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

    public void setLyricFont(String key) {
        android.graphics.Typeface tf = Fonts.typeface(key);
        text.setTypeface(tf);
        fill.setTypeface(tf);
        trans.setTypeface(tf);
        next.setTypeface(tf);
        requestLayout();
        invalidate();
    }

    public void setTextScale(float scale) {
        float base = sp(16) * scale;
        if (Math.abs(text.getTextSize() - base) < 0.5f) return;
        text.setTextSize(base);
        fill.setTextSize(base);
        trans.setTextSize(base * 0.78f);
        next.setTextSize(base * 0.8f);
        requestLayout();
        invalidate();
    }

    public void setTranslationEnabled(boolean on) {
        if (showTranslation == on) return;
        showTranslation = on;
        requestLayout();
        invalidate();
    }

    public void setState(Lyrics l, long pos, long offsetMs, String fallbackText) {
        Lyrics previous = lyrics;
        lyrics = l;
        position = pos;
        offset = offsetMs;
        fallback = fallbackText == null ? "" : fallbackText;
        if (previous != l) requestLayout();
        invalidate();
    }

    private boolean hasLyrics() {
        return lyrics != null && !lyrics.lines.isEmpty() && lyrics.synced;
    }

    private Lyrics.Line currentLine() {
        if (!hasLyrics()) return null;
        int idx = lyrics.indexAt(position - offset);
        if (idx < 0) idx = 0;
        return lyrics.lines.get(Math.min(idx, lyrics.lines.size() - 1));
    }

    /**
     * Reserve the translation line for the whole song, not just lines that have one:
     * otherwise the bar grew and shrank between lines as the song played.
     */
    private boolean translationReserved() {
        if (!showTranslation || lyrics == null) return false;
        for (Lyrics.Line line : lyrics.lines) {
            if (line.trans != null && !line.trans.trim().isEmpty()) return true;
        }
        return false;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        float ts = text.getTextSize();
        float h = getPaddingTop() + getPaddingBottom() + ts * 1.45f;
        if (hasLyrics()) {
            h += next.getTextSize() * 1.3f;
            if (translationReserved()) h += ts * 0.95f;
        }
        setMeasuredDimension(w, (int) Math.ceil(h));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RectF r = new RectF(0, 0, getWidth(), getHeight());
        float rad = dp(18);
        canvas.drawRoundRect(r, rad, rad, bg);
        canvas.drawRoundRect(r, rad, rad, stroke);

        float left = getPaddingLeft();
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float ts = text.getTextSize();
        float y = getPaddingTop() + ts * 1.05f;

        Lyrics.Line current = currentLine();
        if (current == null) {
            String msg = fallback.isEmpty() ? "Lyriu — waiting for a song" : fallback;
            canvas.drawText(ellipsize(msg, text, width), left, y, text);
            return;
        }

        long pos = position - offset;
        Lyrics.Line upcoming = null;
        int idx = lyrics.lines.indexOf(current);
        if (idx >= 0 && idx + 1 < lyrics.lines.size()) upcoming = lyrics.lines.get(idx + 1);

        float fraction = sungFraction(current, pos);
        String line = ellipsize(current.text, text, width);
        canvas.drawText(line, left, y, text);
        if (fraction > 0.01f) {
            int k = Math.min(line.length(), (int) Math.floor(fraction * line.length()));
            float w = k >= line.length() ? text.measureText(line) : text.measureText(line, 0, k);
            canvas.save();
            canvas.clipRect(left, y - ts * 1.2f, left + w, y + ts * 0.4f);
            canvas.drawText(line, left, y, fill);
            canvas.restore();
        }

        if (translationReserved()) {
            y += ts * 0.95f;
            if (current.trans != null && !current.trans.trim().isEmpty()) {
                canvas.drawText(ellipsize(current.trans, trans, width), left, y, trans);
            }
        }
        if (upcoming != null) {
            y += next.getTextSize() * 1.3f;
            canvas.drawText(ellipsize(upcoming.text, next, width), left, y, next);
        }
    }

    /** Fraction of the line already sung, using real word timings when the file has them. */
    private float sungFraction(Lyrics.Line line, long pos) {
        int total = line.text == null ? 0 : line.text.length();
        if (total == 0) return 0f;
        if (pos >= line.end) return 1f;
        if (pos <= line.start) return 0f;
        if (line.hasWords()) {
            int wordChars = 0;
            for (Lyrics.Word w : line.words) wordChars += w.text == null ? 0 : w.text.length();
            if (wordChars > 0) {
                float sung = 0f;
                for (Lyrics.Word w : line.words) {
                    int len = w.text == null ? 0 : w.text.length();
                    if (pos >= w.end) sung += len;
                    else if (pos > w.start) sung += len * ((pos - w.start) / (float) Math.max(1, w.end - w.start));
                }
                return Math.min(1f, sung / wordChars);
            }
        }
        return Math.min(1f, (pos - line.start) / (float) Math.max(1, line.end - line.start));
    }

    private String ellipsize(String s, Paint p, float width) {
        if (s == null) return "";
        if (p.measureText(s) <= width) return s;
        return TextUtils.ellipsize(s, new TextPaint(p), width, TextUtils.TruncateAt.END).toString();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getRawX();
                downY = lastY = e.getRawY();
                downAt = System.currentTimeMillis();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float x = e.getRawX();
                float y = e.getRawY();
                if (Math.abs(x - downX) > dp(6) || Math.abs(y - downY) > dp(6)) dragging = true;
                if (dragging && listener != null) {
                    listener.onDrag(x - lastX, y - lastY);
                    lastX = x;
                    lastY = y;
                }
                return true;
            case MotionEvent.ACTION_UP:
                long held = System.currentTimeMillis() - downAt;
                if (dragging) {
                    if (listener != null) listener.onDragEnd((int) e.getRawX(), (int) e.getRawY());
                } else if (listener != null) {
                    if (held > 650) listener.onLongPress();
                    else listener.onTap();
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging && listener != null) listener.onDragEnd((int) e.getRawX(), (int) e.getRawY());
                dragging = false;
                return true;
        }
        return super.onTouchEvent(e);
    }
}
