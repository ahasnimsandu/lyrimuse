package me.lyrimuse.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** The floating two-line lyric bar shown over other apps. */
public class OverlayView extends View {

    public interface Listener {
        void onDrag(float dx, float dy);

        void onTap();

        void onLongPress();
    }

    private static final int SUNG = 0xFF7BE8B3;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint next = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Lyrics lyrics;
    private long position;
    private long offset;
    private String fallback = "";
    private Listener listener;

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
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fill.setColor(SUNG);
        fill.setTypeface(text.getTypeface());
        next.setColor(0x80FFFFFF);
        float ts = sp(16);
        text.setTextSize(ts);
        fill.setTextSize(ts);
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

    public void setTextScale(float scale) {
        float base = sp(16) * scale;
        if (Math.abs(text.getTextSize() - base) < 0.5f) return;
        text.setTextSize(base);
        fill.setTextSize(base);
        next.setTextSize(base * 0.8f);
        requestLayout();
        invalidate();
    }

    public void setState(Lyrics l, long pos, long offsetMs, String fallbackText) {
        lyrics = l;
        position = pos;
        offset = offsetMs;
        fallback = fallbackText == null ? "" : fallbackText;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = (int) (dp(74));
        setMeasuredDimension(w, h);
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
        float baseY = getHeight() * 0.52f;
        float ts = text.getTextSize();

        if (lyrics == null || lyrics.lines.isEmpty() || !lyrics.synced) {
            String msg = fallback.isEmpty() ? "Lyrimuse — waiting for a song" : fallback;
            canvas.drawText(ellipsize(msg, text, width), left, baseY, text);
            return;
        }

        long pos = position - offset;
        int idx = lyrics.indexAt(pos);
        if (idx < 0) idx = 0;
        Lyrics.Line current = lyrics.lines.get(Math.min(idx, lyrics.lines.size() - 1));
        Lyrics.Line upcoming = idx + 1 < lyrics.lines.size() ? lyrics.lines.get(idx + 1) : null;

        int total = current.text.length();
        float sung = 0f;
        if (total > 0) {
            if (pos >= current.end) sung = total;
            else if (pos > current.start) sung = total * ((pos - current.start) / (float) Math.max(1, current.end - current.start));
        }

        String line = ellipsize(current.text, text, width);
        canvas.drawText(line, left, baseY, text);
        if (sung > 0.01f) {
            int k = Math.min(line.length(), (int) Math.floor(sung / Math.max(1, total) * line.length()));
            float w = k >= line.length() ? text.measureText(line) : text.measureText(line, 0, k);
            canvas.save();
            canvas.clipRect(left, baseY - ts * 1.2f, left + w, baseY + ts * 0.4f);
            canvas.drawText(line, left, baseY, fill);
            canvas.restore();
        }
        if (upcoming != null) {
            canvas.drawText(ellipsize(upcoming.text, next, width), left, baseY + ts * 1.28f, next);
        }
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
                if (!dragging && listener != null) {
                    if (held > 650) listener.onLongPress();
                    else listener.onTap();
                }
                return true;
        }
        return super.onTouchEvent(e);
    }
}
