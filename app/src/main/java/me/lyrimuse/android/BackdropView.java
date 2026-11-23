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
    private int accent = 0xFFE8C9A0;
    private int deep = 0xFF14100C;
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

    /** Dark tint of the artwork colour, used behind cards. */
    public int deep() {
        return deep;
    }

    public void setAccentEnabled(boolean on) {
        accentEnabled = on;
        if (!on) {
            accent = 0xFFE8C9A0;
            deep = 0xFF14100C;
        }
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

    /**
     * Picks the artwork's dominant *vibrant* hue instead of averaging (averaging turns
     * covers grey), then normalises it so accents stay legible on the dark theme.
     */
    private int accentOf(Bitmap b) {
        if (b == null) return 0xFFE8C9A0;
        try {
            final int buckets = 24;
            int[] weight = new int[buckets];
            long[] rSum = new long[buckets];
            long[] gSum = new long[buckets];
            long[] bSum = new long[buckets];
            int stepX = Math.max(1, b.getWidth() / 24);
            int stepY = Math.max(1, b.getHeight() / 24);
            float[] hsv = new float[3];
            for (int y = 0; y < b.getHeight(); y += stepY) {
                for (int x = 0; x < b.getWidth(); x += stepX) {
                    int c = b.getPixel(x, y);
                    Color.colorToHSV(c, hsv);
                    if (hsv[1] < 0.14f || hsv[2] < 0.12f) continue; // ignore greys and shadows
                    int bucket = Math.min(buckets - 1, (int) (hsv[0] / (360f / buckets)));
                    int w = 1 + (int) (hsv[1] * hsv[2] * 12f);
                    weight[bucket] += w;
                    rSum[bucket] += (long) Color.red(c) * w;
                    gSum[bucket] += (long) Color.green(c) * w;
                    bSum[bucket] += (long) Color.blue(c) * w;
                }
            }
            int best = -1;
            for (int i = 0; i < buckets; i++) {
                if (weight[i] > 0 && (best < 0 || weight[i] > weight[best])) best = i;
            }
            if (best < 0) return 0xFFE8C9A0;
            int r = (int) (rSum[best] / weight[best]);
            int g = (int) (gSum[best] / weight[best]);
            int bl = (int) (bSum[best] / weight[best]);
            Color.RGBToHSV(r, g, bl, hsv);
            hsv[1] = Math.max(0.34f, Math.min(0.82f, hsv[1]));
            hsv[2] = Math.max(0.74f, Math.min(0.96f, hsv[2]));
            int bright = Color.HSVToColor(hsv);

            float[] deepHsv = hsv.clone();
            deepHsv[1] = Math.min(0.62f, hsv[1] * 0.9f);
            deepHsv[2] = 0.13f;
            deep = Color.HSVToColor(deepHsv);
            return bright;
        } catch (Exception e) {
            return 0xFFE8C9A0;
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

        // vignette: darkens the edges so the centre feels lit and the lyrics stay readable
        RadialGradient vignette = new RadialGradient(
                w * 0.5f, h * 0.42f, Math.max(w, h) * 0.62f,
                0x00000000, 0x66000000, Shader.TileMode.CLAMP);
        glow.setShader(vignette);
        canvas.drawRect(0, 0, w, h, glow);
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
