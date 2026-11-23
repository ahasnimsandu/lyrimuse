package me.lyrimuse.android;

import android.content.Context;
import android.content.SharedPreferences;

/** User preferences. */
public final class Prefs {

    private static final String FILE = "lyrimuse";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean overlay(Context c) {
        return sp(c).getBoolean("overlay", true);
    }

    public static void setOverlay(Context c, boolean on) {
        sp(c).edit().putBoolean("overlay", on).apply();
    }

    public static boolean translation(Context c) {
        return sp(c).getBoolean("translation", true);
    }

    public static void setTranslation(Context c, boolean on) {
        sp(c).edit().putBoolean("translation", on).apply();
    }

    public static float textScale(Context c) {
        return sp(c).getFloat("textScale", 1f);
    }

    public static void setTextScale(Context c, float v) {
        sp(c).edit().putFloat("textScale", v).apply();
    }

    public static boolean keepAwake(Context c) {
        return sp(c).getBoolean("keepAwake", false);
    }

    public static void setKeepAwake(Context c, boolean on) {
        sp(c).edit().putBoolean("keepAwake", on).apply();
    }

    public static boolean tapSeek(Context c) {
        return sp(c).getBoolean("tapSeek", true);
    }

    public static void setTapSeek(Context c, boolean on) {
        sp(c).edit().putBoolean("tapSeek", on).apply();
    }

    public static int overlayOpacity(Context c) {
        return sp(c).getInt("overlayOpacity", 235);
    }

    public static void setOverlayOpacity(Context c, int v) {
        sp(c).edit().putInt("overlayOpacity", v).apply();
    }

    public static float overlayScale(Context c) {
        return sp(c).getFloat("overlayScale", 1f);
    }

    public static void setOverlayScale(Context c, float v) {
        sp(c).edit().putFloat("overlayScale", v).apply();
    }

    public static boolean netease(Context c) {
        return sp(c).getBoolean("sourceNetease", true);
    }

    public static void setNetease(Context c, boolean on) {
        sp(c).edit().putBoolean("sourceNetease", on).apply();
    }

    public static boolean lrclib(Context c) {
        return sp(c).getBoolean("sourceLrclib", true);
    }

    public static void setLrclib(Context c, boolean on) {
        sp(c).edit().putBoolean("sourceLrclib", on).apply();
    }

    public static boolean qq(Context c) {
        return sp(c).getBoolean("sourceQq", true);
    }

    public static void setQq(Context c, boolean on) {
        sp(c).edit().putBoolean("sourceQq", on).apply();
    }

    public static String lyricFont(Context c) {
        return sp(c).getString("lyricFont", "sans");
    }

    public static void setLyricFont(Context c, String key) {
        sp(c).edit().putString("lyricFont", key).apply();
    }

    public static boolean autoRetry(Context c) {
        return sp(c).getBoolean("autoRetry", true);
    }

    public static void setAutoRetry(Context c, boolean on) {
        sp(c).edit().putBoolean("autoRetry", on).apply();
    }

    public static boolean accentFromArt(Context c) {
        return sp(c).getBoolean("accentFromArt", true);
    }

    public static void setAccentFromArt(Context c, boolean on) {
        sp(c).edit().putBoolean("accentFromArt", on).apply();
    }

    /** Positive values make lyrics appear earlier. */
    public static long offsetMs(Context c) {
        return sp(c).getLong("offsetMs", 0);
    }

    public static void setOffsetMs(Context c, long v) {
        sp(c).edit().putLong("offsetMs", v).apply();
    }
}
