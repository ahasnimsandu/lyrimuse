package me.lyrimuse.android;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Offline lyric cache: every fetched song is stored so it works without a network.
 *
 * <p>The key covers title, artist, album, a 5-second duration bucket and the lyric
 * language, so a live/remix/remastered version can no longer be served another
 * version's (or another language's) timings.
 */
public final class Cache {

    /** Soft cap: older entries are dropped instead of growing without bound. */
    private static final int MAX_FILES = 600;
    private static final int KEEP_FILES = 480;

    private Cache() {}

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "lyrics");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String key(Context c, String title, String artist, String album, long durationMs) {
        return key(title, artist, album, durationMs, Prefs.lyricLanguage(c));
    }

    /** Pure key derivation (no Android types), so it can be unit tested. */
    public static String key(String title, String artist, String album, long durationMs, String language) {
        String lang = language == null ? "english" : language;
        long bucket = durationMs > 0 ? Math.round(durationMs / 5000.0) : 0;
        String raw = lang.toLowerCase(Locale.ROOT)
                + "|" + lower(artist) + "|" + lower(title)
                + "|" + lower(album) + "|" + bucket;
        return hash(raw);
    }

    /** Key used by builds before 1.7 (2-arg title+artist hash), read once for migration. */
    public static String legacyKey(String title, String artist) {
        return hash(lower(artist) + "|" + lower(title));
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }

    private static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] h = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    public static Lyrics load(Context c, String key) {
        if (key == null || key.isEmpty()) return null;
        File f = new File(dir(c), key + ".json");
        if (!f.exists()) return null;
        try {
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            in.close();
            return Lyrics.fromJson(new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(Context c, String key, Lyrics lyrics) {
        if (lyrics == null || key == null || key.isEmpty()) return;
        try {
            FileOutputStream out = new FileOutputStream(new File(dir(c), key + ".json"));
            out.write(lyrics.toJson().toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
            return;
        }
        prune(c);
    }

    /** Deletes one song's cached lyrics (used by Re-match instead of wiping everything). */
    public static void delete(Context c, String... keys) {
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            new File(dir(c), key + ".json").delete();
        }
    }

    /** Drops the oldest files once the cache is over the soft cap. */
    public static void prune(Context c) {
        File[] files = dir(c).listFiles();
        if (files == null || files.length <= MAX_FILES) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (int i = 0; i < files.length - KEEP_FILES; i++) files[i].delete();
    }

    public static void clear(Context c) {
        File d = dir(c);
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    public static int count(Context c) {
        File[] files = dir(c).listFiles();
        return files == null ? 0 : files.length;
    }
}
