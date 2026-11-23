package me.lyrimuse.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Scrolling karaoke lyric view with word-by-word fill. */
public class KaraokeView extends View {

    public interface Listener {
        void onSeek(long ms);
    }

    private static final int SUNG = 0xFF66D9A0;
    private static final int UNSUNG_CURRENT = 0xD9FFFFFF;
    private static final int FUTURE = 0x59FFFFFF;

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
    private Listener listener;

    private float lastGap;
    private float lastCenter;

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
        fillPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fillPaint.setColor(SUNG);
        transPaint.setColor(0x8AFFFFFF);
        statusPaint.setColor(0x8AFFFFFF);
        statusPaint.setTextSize(dp(14));
        measurePaint.setTypeface(linePaint.getTypeface());
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

    public void setState(Lyrics l, long pos, String statusText, float textScale, boolean translation, long offset) {
        boolean structural = l != lyrics;
        lyrics = l;
        position = pos;
        status = statusText == null ? "" : statusText;
        scale = textScale;
        showTranslation = translation;
        offsetMs = offset;
        float ts = sp(21) * scale;
        if (Math.abs(linePaint.getTextSize() - ts) > 0.5f) {
            linePaint.setTextSize(ts);
            fillPaint.setTextSize(ts);
            transPaint.setTextSize(ts * 0.62f);
        }
        if (structural || l != null) invalidate();
    }

    private float lineGap() {
        float ts = linePaint.getTextSize();
        return ts * (showTranslation ? 2.35f : 1.75f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = dp(18);
        float width = getWidth() - pad * 2;
        if (lyrics == null || lyrics.lines.isEmpty()) {
            String msg = status.isEmpty() ? "Waiting for a song…" : status;
            statusPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(msg, getWidth() / 2f, getHeight() / 2f, statusPaint);
            return;
        }

        long pos = position - offsetMs;

        if (!lyrics.synced) {
            float ts = linePaint.getTextSize();
            float y = ts * 1.8f;
            float gap = ts * 1.65f;
            for (Lyrics.Line l : lyrics.lines) {
                if (y > getHeight()) break;
                linePaint.setColor(FUTURE);
                canvas.drawText(ellipsize(l.text, linePaint, width), pad, y, linePaint);
                y += gap;
            }
            return;
        }

        int n = lyrics.lines.size();
        int idx = lyrics.indexAt(pos);
        if (idx < 0) idx = 0;
        int cur = idx;
        Lyrics.Line current = lyrics.lines.get(Math.min(cur, n - 1));
        float frac = 0f;
        long span = Math.max(1, current.end - current.start);
        if (pos > current.start) frac = Math.min(1f, (pos - current.start) / (float) span);
        if (pos < lyrics.lines.get(0).start) frac = 0f;

        float ts = linePaint.getTextSize();
        float gap = lineGap();
        float centerY = getHeight() * 0.42f;
        lastGap = gap;
        lastCenter = centerY;

        int from = Math.max(0, cur - 5);
        int to = Math.min(n - 1, cur + 6);
        for (int i = from; i <= to; i++) {
            Lyrics.Line l = lyrics.lines.get(i);
            float y = centerY + (i - cur - frac) * gap;
            if (y < -gap || y > getHeight() + gap) continue;

            boolean isCurrent = i == cur;
            int alpha;
            if (isCurrent) alpha = 255;
            else if (i == cur + 1) alpha = 150;
            else alpha = 80;

            String text = ellipsize(l.text, linePaint, width);
            float sungChars = sungChars(l, pos, text);

            linePaint.setColor(isCurrent ? UNSUNG_CURRENT : FUTURE);
            linePaint.setAlpha(alpha);
            canvas.drawText(text, pad, y, linePaint);

            if (sungChars > 0f) {
                float fillWidth = measurePrefix(text, sungChars, linePaint);
                canvas.save();
                canvas.clipRect(pad, y - ts * 1.25f, pad + fillWidth, y + ts * 0.45f);
                fillPaint.setAlpha(alpha);
                canvas.drawText(text, pad, y, fillPaint);
                canvas.restore();
            }

            boolean showTr = showTranslation && l.trans != null && !l.trans.isEmpty();
            if (showTr) {
                transPaint.setAlpha(isCurrent ? 200 : 110);
                canvas.drawText(ellipsize(l.trans, transPaint, width), pad, y + ts * 0.92f, transPaint);
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
        CharSequence out = TextUtils.ellipsize(text, new TextPaint(p), width, TextUtils.TruncateAt.END);
        return out.toString();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && lyrics != null && lyrics.synced && listener != null) {
            int n = lyrics.lines.size();
            int idx = lyrics.indexAt(position - offsetMs);
            if (idx < 0) idx = 0;
            float gap = lineGap();
            float centerY = getHeight() * 0.42f;
            int tapped = Math.round((e.getY() - centerY) / gap) + idx;
            if (tapped >= 0 && tapped < n && Math.abs(e.getY() - centerY) <= gap * 6f) {
                listener.onSeek(lyrics.lines.get(tapped).start);
            }
            return true;
        }
        return true;
    }
}
