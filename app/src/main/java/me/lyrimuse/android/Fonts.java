package me.lyrimuse.android;

import android.graphics.Typeface;

/** Selectable lyric typefaces (system families, so no font files are needed). */
public final class Fonts {

    /** Serif is the default: elegant, Apple-Music-like lyric typography. */
    public static final String[] KEYS = {"serif", "sans", "light", "condensed"};
    public static final String[] LABELS = {"Serif", "Modern", "Light", "Condensed"};

    private Fonts() {}

    /** Old builds offered Mono/Heavy; anything unknown falls back to Serif. */
    public static String normalise(String key) {
        for (String k : KEYS) {
            if (k.equals(key)) return k;
        }
        return "serif";
    }

    public static int indexOf(String key) {
        String k = normalise(key);
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(k)) return i;
        }
        return 0;
    }

    public static String label(String key) {
        return LABELS[indexOf(key)];
    }

    public static Typeface typeface(String key) {
        switch (normalise(key)) {
            case "sans":
                return Typeface.create("sans-serif", Typeface.NORMAL);
            case "light":
                return Typeface.create("sans-serif-light", Typeface.NORMAL);
            case "condensed":
                return Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            case "serif":
            default:
                return Typeface.create("serif", Typeface.NORMAL);
        }
    }

    /** A slightly heavier companion for headings, never the black/3D-looking families. */
    public static Typeface strong(Typeface base) {
        return Typeface.create(base, Typeface.BOLD);
    }
}
