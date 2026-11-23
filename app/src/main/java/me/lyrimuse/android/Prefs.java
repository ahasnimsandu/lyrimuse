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

    /** Positive values make lyrics appear earlier. */
    public static long offsetMs(Context c) {
        return sp(c).getLong("offsetMs", 0);
    }

    public static void setOffsetMs(Context c, long v) {
        sp(c).edit().putLong("offsetMs", v).apply();
    }
}
