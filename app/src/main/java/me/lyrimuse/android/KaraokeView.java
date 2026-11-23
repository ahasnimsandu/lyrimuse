package me.lyrimuse.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Scrolling karaoke lyrics: the active line grows, fills with the art accent word by word.
 *  Modelled on the Apple Music lyrics page. */
public class KaraokeView extends View {

    public interface Listener {
        void onSeek(long ms);
    }

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint transPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint measurePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private Lyrics lyrics;
    private long position;
    private long offsetMs;
    private String status = "";
    private float scale = 1f;
    private boolean showTranslation = true;
    private boolean seekEnabled = true;
    private int accent = 0xFF7BE8B3;
    private Listener listener;

    public KaraokeView(Context c) {
        super(c);
        init();
    }

    public KaraokeView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        linePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fillPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        transPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        statusPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        statusPaint.setTextSize(sp(14));
        statusPaint.setColor(0x99FFFFFF);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
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
        fillPaint.setTypeface(Typeface.create(tf, Typeface.BOLD));
        transPaint.setTypeface(Typeface.create(tf, Typeface.NORMAL));
        invalidate();
    }

    public void setAccent(int color) {
        accent = color;
        fillPaint.setColor(color);
        invalidate();
    }

    public void setState(Lyrics l, long pos, String statusText, float textScale,
                         boolean translation, long offset) {
        lyrics = l;
        position = pos;
        status = statusText == null ? "" : statusText;
        scale = textScale;
        showTranslation = translation;
        offsetMs = offset;
        invalidate();
    }

    private float lineGap() {
        return sp(21) * scale * (showTranslation ? 2.45f : 1.95f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = dp(4);
        float width = getWidth() - pad * 2;

        if (lyrics == null || lyrics.lines.isEmpty()) {
            String msg = status.isEmpty() ? "Waiting for a song…" : status;
            statusPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(msg, getWidth() / 2f, getHeight() / 2f, statusPaint);
            return;
        }

        long pos = position - offsetMs;

        if (!lyrics.synced) {
            float ts = sp(19) * scale;
            linePaint.setTextSize(ts);
            linePaint.setColor(0xCCFFFFFF);
            float y = ts * 1.8f;
            float gap = ts * 1.7f;
            for (Lyrics.Line l : lyrics.lines) {
                if (y > getHeight()) break;
                canvas.drawText(ellipsize(l.text, linePaint, width), pad, y, linePaint);
                y += gap;
            }
            return;
        }

        int n = lyrics.lines.size();
        int idx = lyrics.indexAt(pos);
        if (idx < 0) idx = 0;
        int cur = Math.min(idx, n - 1);
        Lyrics.Line current = lyrics.lines.get(cur);
        float frac = 0f;
        if (pos > current.start) {
            frac = Math.min(1f, (pos - current.start) / (float) Math.max(1, current.end - current.start));
        }

        float base = sp(21) * scale;
        float gap = lineGap();
        float centerY = getHeight() * 0.40f;

        int from = Math.max(0, cur - 4);
        int to = Math.min(n - 1, cur + 5);
        for (int i = from; i <= to; i++) {
            Lyrics.Line l = lyrics.lines.get(i);
            float y = centerY + (i - cur - frac) * gap;
            if (y < -gap || y > getHeight() + gap) continue;

            boolean isCurrent = i == cur;
            float ts = isCurrent ? base * 1.1f : base * 0.95f;
            linePaint.setTextSize(ts);
            fillPaint.setTextSize(ts);
            transPaint.setTextSize(ts * 0.56f);

            int alpha = isCurrent ? 255 : (i == cur + 1 ? 120 : 55);
            linePaint.setColor(0xFFFFFFFF);
            linePaint.setAlpha(alpha);

            String text = ellipsize(l.text, linePaint, width);
            canvas.drawText(text, pad, y, linePaint);

            float sungChars = sungChars(l, pos, text);
            if (sungChars > 0f) {
                float fillWidth = measurePrefix(text, sungChars, fillPaint);
                canvas.save();
                canvas.clipRect(pad, y - ts * 1.25f, pad + fillWidth, y + ts * 0.4f);
                fillPaint.setAlpha(isCurrent ? 255 : 210);
                if (isCurrent) {
                    fillPaint.setShadowLayer(ts * 0.45f, 0, 0, (accent & 0x00FFFFFF) | 0x66000000);
                }
                canvas.drawText(text, pad, y, fillPaint);
                fillPaint.clearShadowLayer();
                canvas.restore();
            }

            if (showTranslation && l.trans != null && !l.trans.isEmpty()) {
                transPaint.setColor(0xFFFFFFFF);
                transPaint.setAlpha(isCurrent ? 170 : 80);
                canvas.drawText(ellipsize(l.trans, transPaint, width), pad, y + ts * 0.9f, transPaint);
            }
        }
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

    private String ellipsize(String text, Paint p, float width) {
        if (text == null) return "";
        if (p.measureText(text) <= width) return text;
        return TextUtils.ellipsize(text, new TextPaint(p), width, TextUtils.TruncateAt.END).toString();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && lyrics != null && lyrics.synced
                && seekEnabled && listener != null) {
            int n = lyrics.lines.size();
            int idx = lyrics.indexAt(position - offsetMs);
            if (idx < 0) idx = 0;
            float gap = lineGap();
            float centerY = getHeight() * 0.40f;
            int tapped = Math.round((e.getY() - centerY) / gap) + idx;
            if (tapped >= 0 && tapped < n && Math.abs(e.getY() - centerY) <= gap * 6f) {
                listener.onSeek(lyrics.lines.get(tapped).start);
            }
            return true;
        }
        return true;
    }
}
