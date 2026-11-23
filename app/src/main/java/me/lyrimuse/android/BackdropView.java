package me.lyrimuse.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Apple-Music-style backdrop: the cover art heavily blurred behind everything,
 * an accent glow sampled from the artwork, and a scrim so lyrics stay readable.
 */
public class BackdropView extends View {

    private static final int BASE = 0xFF06070B;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    private final RectF dst = new RectF();

    private Bitmap art;
    private Bitmap blurred;
    private int accent = 0xFF7BE8B3;
    private boolean accentEnabled = true;
    private float density = 1f;
    private float zoom = 1f;
    private ValueAnimator zoomAnim;

    public BackdropView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
    }

    public BackdropView(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
    }

    public int accent() {
        return accent;
    }

    public void setAccentEnabled(boolean on) {
        accentEnabled = on;
        if (!on) accent = 0xFF7BE8B3;
        invalidate();
    }

    public void setArtwork(Bitmap bitmap) {
        if (bitmap == art) return;
        art = bitmap;
        blurred = blur(bitmap);
        if (accentEnabled) accent = accentOf(bitmap);
        invalidate();
    }

    private Bitmap blur(Bitmap src) {
        if (src == null || src.getWidth() <= 0) return null;
        try {
            int w = 16;
            int h = Math.max(16, Math.round(16f * src.getHeight() / src.getWidth()));
            Bitmap tiny = Bitmap.createScaledBitmap(src, w, h, true);
            return Bitmap.createScaledBitmap(tiny, w * 12, h * 12, true);
        } catch (Exception e) {
            return null;
        }
    }

    private int accentOf(Bitmap b) {
        if (b == null) return 0xFF7BE8B3;
        try {
            long r = 0, g = 0, bl = 0;
            int n = 0;
            int stepX = Math.max(1, b.getWidth() / 12);
            int stepY = Math.max(1, b.getHeight() / 12);
            for (int y = 0; y < b.getHeight(); y += stepY) {
                for (int x = 0; x < b.getWidth(); x += stepX) {
                    int c = b.getPixel(x, y);
                    r += Color.red(c);
                    g += Color.green(c);
                    bl += Color.blue(c);
                    n++;
                }
            }
            if (n == 0) return 0xFF7BE8B3;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
            hsv[1] = Math.max(0.45f, Math.min(0.9f, hsv[1]));
            hsv[2] = Math.max(0.72f, Math.min(0.98f, hsv[2]));
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF7BE8B3;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.drawColor(BASE);

        if (blurred != null) {
            float scale = Math.max(w / (float) blurred.getWidth(), h / (float) blurred.getHeight()) * zoom;
            float dw = blurred.getWidth() * scale;
            float dh = blurred.getHeight() * scale;
            dst.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
            paint.setAlpha(205);
            canvas.drawBitmap(blurred, null, dst, paint);
        }

        // soft accent bloom in the corner
        RadialGradient rg = new RadialGradient(
                w * 0.78f, h * 0.08f, Math.max(w, h) * 0.75f,
                (accent & 0x00FFFFFF) | 0x66000000, 0x00000000, Shader.TileMode.CLAMP);
        glow.setShader(rg);
        canvas.drawRect(0, 0, w, h, glow);

        LinearGradient lg = new LinearGradient(0, 0, 0, h,
                new int[]{0x4006070B, 0xB306070B, 0xFF06070B},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP);
        scrim.setShader(lg);
        canvas.drawRect(0, 0, w, h, scrim);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (zoomAnim != null) return;
        zoomAnim = ValueAnimator.ofFloat(1f, 1.07f);
        zoomAnim.setDuration(26000);
        zoomAnim.setRepeatCount(ValueAnimator.INFINITE);
        zoomAnim.setRepeatMode(ValueAnimator.REVERSE);
        zoomAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        zoomAnim.addUpdateListener(a -> {
            zoom = (float) a.getAnimatedValue();
            invalidate();
        });
        zoomAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (zoomAnim != null) {
            zoomAnim.cancel();
            zoomAnim = null;
        }
        super.onDetachedFromWindow();
    }
}
