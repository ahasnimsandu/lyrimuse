package me.lyrimuse.android;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Multi-source lyric matching, mirroring the original engine's approach:
 * query several sources, score the candidates, keep the best timed result
 * (word-by-word wins over line-level), then cache it for offline playback.
 */
public final class LyricsRepository {

    private static final String TAG = "LyrimuseRepo";

    private LyricsRepository() {}

    public static Lyrics fetch(Context ctx, String title, String artist, String album, long durationMs) {
        if (title == null || title.trim().isEmpty()) return null;
        String key = Cache.key(title, artist);
        Lyrics cached = Cache.load(ctx, key);
        if (cached != null && !cached.lines.isEmpty()) {
            if (!cached.source.contains("cached")) cached.source = cached.source + " · cached";
            return cached;
        }

        String t = cleanTitle(title);
        String a = cleanArtist(artist);

        Lyrics netease = null;
        Lyrics lrclib = null;
        try {
            netease = fromNetease(t, a, durationMs);
        } catch (Exception e) {
            Log.w(TAG, "NetEase lookup failed: " + e);
        }
        try {
            lrclib = fromLrclib(t, a, album, durationMs);
        } catch (Exception e) {
            Log.w(TAG, "LRCLIB lookup failed: " + e);
        }

        Lyrics best = pick(netease, lrclib);
        if (best != null && !best.lines.isEmpty()) Cache.save(ctx, key, best);
        return best;
    }

    private static Lyrics pick(Lyrics a, Lyrics b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.wordLevel && !b.wordLevel) return a;
        if (b.wordLevel && !a.wordLevel) return b;
        if (a.synced && !b.synced) return a;
        if (b.synced && !a.synced) return b;
        return a.lines.size() >= b.lines.size() ? a : b;
    }

    // ---------------- LRCLIB ----------------

    private static Lyrics fromLrclib(String title, String artist, String album, long durationMs) {
        String base = "https://lrclib.net/api/";
        String q = enc(title) + "+" + enc(artist);
        if (durationMs > 0) {
            try {
                String url = base + "get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                        + (album != null && !album.isEmpty() ? "&album_name=" + enc(album) : "")
                        + "&duration=" + Math.max(1, Math.round(durationMs / 1000.0));
                JSONObject o = new JSONObject(Http.get(url, null));
                Lyrics l = lrclibFromObject(o);
                if (l != null) return l;
            } catch (Exception ignored) {
            }
        }
        try {
            JSONArray arr = new JSONArray(Http.get(base + "search?q=" + q, null));
            JSONObject best = null;
            double bestScore = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String synced = o.optString("syncedLyrics", "");
                if (synced.isEmpty()) continue;
                double s = similarity(title, o.optString("trackName", ""))
                        + similarity(artist, o.optString("artistName", ""));
                long d = o.optLong("duration", 0) * 1000L;
                if (durationMs > 0 && d > 0) {
                    long diff = Math.abs(d - durationMs);
                    if (diff > 12000) continue;
                    s += 1.0 - (diff / 12000.0);
                }
                if (s > bestScore) {
                    bestScore = s;
                    best = o;
                }
            }
            if (best != null && bestScore > 0.9) return lrclibFromObject(best);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Lyrics lrclibFromObject(JSONObject o) {
        if (o == null) return null;
        String synced = o.optString("syncedLyrics", "");
        if (!synced.isEmpty()) return Lyrics.fromLrc(synced, null, "LRCLIB");
        String plain = o.optString("plainLyrics", "");
        if (!plain.isEmpty()) return Lyrics.fromPlain(plain, "LRCLIB");
        return null;
    }

    // ---------------- NetEase Cloud Music ----------------

    private static Lyrics fromNetease(String title, String artist, long durationMs) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://music.163.com/");
        headers.put("Cookie", "appver=2.0.2; os=pc");
        headers.put("Origin", "https://music.163.com");

        String url = "https://music.163.com/api/search/get?s="
                + enc(title + " " + artist) + "&type=1&limit=15&offset=0";
        String body = Http.get(url, headers);
        JSONObject root = new JSONObject(body);
        JSONObject result = root.optJSONObject("result");
        JSONArray songs = result == null ? null : result.optJSONArray("songs");
        if (songs == null) return null;

        List<double[]> ranked = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s == null) continue;
            String name = s.optString("name", "");
            if (looksLikeCover(name)) continue;
            StringBuilder artists = new StringBuilder();
            JSONArray ar = s.optJSONArray("artists");
            if (ar != null) {
                for (int j = 0; j < ar.length(); j++) {
                    JSONObject a = ar.optJSONObject(j);
                    if (a == null) continue;
                    if (artists.length() > 0) artists.append(" / ");
                    artists.append(a.optString("name", ""));
                }
            }
            double score = similarity(title, name) * 1.6 + similarity(artist, artists.toString());
            long d = s.optLong("duration", 0);
            if (durationMs > 0 && d > 0) {
                long diff = Math.abs(d - durationMs);
                if (diff > 12000) continue;
                score += 1.2 - (diff / 10000.0);
            }
            if (score < 1.2) continue;
            ranked.add(new double[]{score, ids.size()});
            ids.add(s.optLong("id", -1));
        }
        if (ranked.isEmpty()) return null;
        Collections.sort(ranked, (x, y) -> Double.compare(y[0], x[0]));

        // Different NetEase entries of the same song differ: often only one has
        // word-by-word YRC timings, so try the best few and prefer word-level.
        Lyrics fallback = null;
        int tried = 0;
        for (double[] entry : ranked) {
            if (tried++ >= 3) break;
            long id = ids.get((int) entry[1]);
            if (id <= 0) continue;
            String lyricUrl = "https://music.163.com/api/song/lyric?id=" + id
                    + "&lv=1&kv=1&tv=-1&rv=1&yv=1&ytv=1&ytl=1";
            JSONObject lo = new JSONObject(Http.get(lyricUrl, headers));
            JSONObject yrcObj = lo.optJSONObject("yrc");
            JSONObject lrcObj = lo.optJSONObject("lrc");
            JSONObject tObj = lo.optJSONObject("tlyric");
            String yrc = yrcObj == null ? "" : yrcObj.optString("lyric", "");
            String lrc = lrcObj == null ? "" : lrcObj.optString("lyric", "");
            String trans = tObj == null ? "" : tObj.optString("lyric", "");

            Lyrics candidate = Lyrics.fromYrc(yrc, trans, "NetEase · word-by-word");
            if (candidate != null && candidate.wordLevel) return candidate;
            if (fallback == null) {
                fallback = Lyrics.fromLrc(lrc, trans, "NetEase");
                if (fallback == null) fallback = candidate;
            }
        }
        return fallback;
    }

    // ---------------- helpers ----------------

    /** Cover / karaoke / instrumental uploads carry worse or missing timings. */
    private static boolean looksLikeCover(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.matches(".*[\\(（\\[【].*(原唱|翻唱|伴奏|cover|karaoke|instrumental|remix).*[\\)）\\]】].*");
    }

    public static String cleanTitle(String title) {
        if (title == null) return "";
        String t = title;
        // repeated suffix stripping: "Song (Official Video) [4K]"
        String prev;
        do {
            prev = t;
            t = t.replaceAll("\\s*[\\(\\[（【][^\\(\\)\\[\\]（【）】]*[\\)\\]）】]\\s*$", "");
        } while (!t.equals(prev));
        t = t.replaceAll("(?i)\\s*-\\s*(remaster(ed)?|single|live|radio edit|deluxe|bonus track|from .*).*$", "");
        t = t.replaceAll("(?i)\\s*[|｜]\\s*(official|lyrics?|audio|video|mv|visuali[sz]er|hd|4k).*$", "");
        t = t.replaceAll("(?i)\\s*feat\\..*$", "");
        return t.trim();
    }

    public static String cleanArtist(String artist) {
        if (artist == null) return "";
        String a = artist.replaceAll("(?i)\\s*-\\s*topic$", "");
        // YouTube Music / web sessions use "Artist • Album" or "A · B"
        int cut = a.indexOf('•');
        if (cut < 0) cut = a.indexOf('·');
        if (cut > 0) a = a.substring(0, cut);
        a = a.replaceAll("(?i)\\s*(feat\\.|ft\\.).*$", "");
        return a.trim();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 0..1 fuzzy text similarity based on token overlap and containment. */
    static double similarity(String a, String b) {
        if (a == null || b == null) return 0;
        String x = norm(a);
        String y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        if (x.equals(y)) return 1.0;
        if (y.contains(x) || x.contains(y)) return 0.85;
        String[] ta = x.split("\\s+");
        String[] tb = y.split("\\s+");
        int hit = 0;
        for (String t : ta) {
            if (t.length() < 2) continue;
            for (String u : tb) {
                if (u.equals(t) || (t.length() > 3 && u.contains(t)) || (u.length() > 3 && t.contains(u))) {
                    hit++;
                    break;
                }
            }
        }
        int total = 0;
        for (String t : ta) if (t.length() >= 2) total++;
        if (total == 0) return 0;
        return 0.9 * ((double) hit / total);
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}，。！？、·「」『』（）【】《》]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
