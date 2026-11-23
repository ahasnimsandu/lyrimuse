package me.lyrimuse.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Album artwork with Apple-Music-style rounded corners and a soft ring. */
public class RoundedImageView extends View {

    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Path clip = new Path();
    private float radiusDp = 22f;

    public RoundedImageView(Context c) {
        super(c);
        init(c);
    }

    public RoundedImageView(Context c, AttributeSet a) {
        super(c, a);
        init(c);
    }

    private void init(Context c) {
        bg.setColor(0x1FFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(c.getResources().getDisplayMetrics().density);
        ring.setColor(0x33FFFFFF);
        glyph.setColor(0x66FFFFFF);
        glyph.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(0xFF0A0B10);
        shadowPaint.setShadowLayer(26f * c.getResources().getDisplayMetrics().density,
                0, 12f * c.getResources().getDisplayMetrics().density, 0xB3000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** corner radius in dp */
    public void setCornerRadiusDp(float dp) {
        radiusDp = dp;
        invalidate();
    }

    public void setBitmap(Bitmap b) {
        if (b != null && b != bitmap) {
            setAlpha(0.35f);
            animate().alpha(1f).setDuration(320).start();
        }
        bitmap = b;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = radiusDp * getResources().getDisplayMetrics().density;
        rect.set(0, 0, w, h);

        canvas.drawRoundRect(rect, r, r, shadowPaint);

        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clip);
        if (bitmap == null) {
            canvas.drawColor(0x14FFFFFF);
            float cx = w / 2f;
            float cy = h / 2f;
            float size = Math.min(w, h) * 0.22f;
            glyph.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx - size * 0.18f, cy + size * 0.9f, size * 0.62f, glyph);
            canvas.drawCircle(cx + size * 1.34f, cy + size * 0.42f, size * 0.62f, glyph);
            canvas.drawRect(cx + size * 0.7f, cy - size, cx + size * 1.96f, cy + size * 0.5f, glyph);
        } else {
            float scale = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
            float sw = w / scale;
            float sh = h / scale;
            int left = Math.round((bitmap.getWidth() - sw) / 2f);
            int top = Math.round((bitmap.getHeight() - sh) / 2f);
            src.set(left, top, left + Math.round(sw), top + Math.round(sh));
            canvas.drawBitmap(bitmap, src, rect, paint);
        }
        canvas.restore();

        canvas.drawRoundRect(rect, r, r, ring);
    }
}
