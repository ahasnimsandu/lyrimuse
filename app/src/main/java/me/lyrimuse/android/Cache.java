package me.lyrimuse.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Offline lyric cache: every fetched song is stored so it works without a network. */
public final class Cache {

    private Cache() {}

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "lyrics");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String key(String title, String artist) {
        String raw = (artist == null ? "" : artist).toLowerCase(Locale.ROOT).trim()
                + "|" + (title == null ? "" : title).toLowerCase(Locale.ROOT).trim();
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
        try {
            FileOutputStream out = new FileOutputStream(new File(dir(c), key + ".json"));
            out.write(lyrics.toJson().toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
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
