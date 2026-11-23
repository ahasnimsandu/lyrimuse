package me.lyrimuse.android;

import android.graphics.Typeface;

/** Selectable lyric typefaces (system families, so no font files are needed). */
public final class Fonts {

    public static final String[] KEYS = {"sans", "light", "serif", "condensed", "mono", "black"};
    public static final String[] LABELS = {
            "Modern", "Light", "Serif", "Condensed", "Monospace", "Heavy"
    };

    private Fonts() {}

    public static int indexOf(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return i;
        }
        return 0;
    }

    public static String label(String key) {
        return LABELS[indexOf(key)];
    }

    public static Typeface typeface(String key) {
        switch (key == null ? "sans" : key) {
            case "light":
                return Typeface.create("sans-serif-light", Typeface.NORMAL);
            case "serif":
                return Typeface.create("serif", Typeface.NORMAL);
            case "condensed":
                return Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            case "mono":
                return Typeface.create("monospace", Typeface.NORMAL);
            case "black":
                return Typeface.create("sans-serif-black", Typeface.NORMAL);
            default:
                return Typeface.create("sans-serif-medium", Typeface.NORMAL);
        }
    }
}
