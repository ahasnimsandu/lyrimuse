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

        // query every enabled source at once; word-level results win in pick()
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        java.util.concurrent.Future<Lyrics> neteaseFuture = Prefs.netease(ctx)
                ? pool.submit(() -> fromNetease(t, a, durationMs)) : null;
        java.util.concurrent.Future<Lyrics> lrclibFuture = Prefs.lrclib(ctx)
                ? pool.submit(() -> fromLrclib(t, a, album, durationMs)) : null;
        java.util.concurrent.Future<Lyrics> qqFuture = Prefs.qq(ctx)
                ? pool.submit(() -> fromQQ(t, a, durationMs)) : null;

        Lyrics netease = await(neteaseFuture, "NetEase");
        Lyrics lrclib = await(lrclibFuture, "LRCLIB");
        Lyrics qq = await(qqFuture, "QQ Music");
        pool.shutdownNow();

        Lyrics best = pick(pick(netease, lrclib), qq);
        Log.i(TAG, "match '" + t + "' by '" + a + "' -> netease=" + describe(netease)
                + " lrclib=" + describe(lrclib) + " qq=" + describe(qq) + " best=" + describe(best));
        if (best != null && !best.lines.isEmpty()) Cache.save(ctx, key, best);
        return best;
    }

    private static Lyrics await(java.util.concurrent.Future<Lyrics> future, String source) {
        if (future == null) return null;
        try {
            return future.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, source + " lookup failed: " + e);
            return null;
        }
    }

    // ---------------- QQ Music ----------------

    private static Lyrics fromQQ(String title, String artist, long durationMs) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://y.qq.com/");
        headers.put("User-Agent", "Mozilla/5.0");

        String body = Http.get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=10&w="
                + enc(title + " " + artist) + "&format=json", headers);
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("data");
        JSONObject songBlock = data == null ? null : data.optJSONObject("song");
        JSONArray list = songBlock == null ? null : songBlock.optJSONArray("list");
        if (list == null) return null;

        String bestMid = null;
        double bestScore = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            String name = s.optString("songname", "");
            StringBuilder artists = new StringBuilder();
            JSONArray singers = s.optJSONArray("singer");
            if (singers != null) {
                for (int j = 0; j < singers.length(); j++) {
                    JSONObject singer = singers.optJSONObject(j);
                    if (singer == null) continue;
                    if (artists.length() > 0) artists.append(" / ");
                    artists.append(singer.optString("name", ""));
                }
            }
            double score = similarity(title, name) * 1.6 + similarity(artist, artists.toString());
            long d = s.optLong("interval", 0) * 1000L;
            if (durationMs > 0 && d > 0) {
                long diff = Math.abs(d - durationMs);
                if (diff > 15000) continue;
                score += 0.8 - (diff / 20000.0);
            }
            String mid = s.optString("songmid", "");
            if (mid.isEmpty()) continue;
            if (score > bestScore) {
                bestScore = score;
                bestMid = mid;
            }
        }
        if (bestMid == null || bestScore < 1.2) return null;

        String lyricBody = Http.get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
                + bestMid + "&format=json&nobase64=1&g_tk=5381", headers);
        JSONObject lo = new JSONObject(lyricBody);
        String lyric = unescapeHtml(lo.optString("lyric", ""));
        String trans = unescapeHtml(lo.optString("trans", ""));
        if (lyric.trim().isEmpty()) return null;
        return Lyrics.fromLrc(lyric, trans, "QQ Music");
    }

    private static String unescapeHtml(String s) {
        return s.replace("&apos;", "'").replace("&#39;", "'")
                .replace("&quot;", "\"").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }

    private static String describe(Lyrics l) {
        if (l == null) return "none";
        return "[" + l.source + " lines=" + l.lines.size() + " words=" + (l.wordLevel ? "yes" : "no") + "]";
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

    // ---------------- manual search ----------------

    /** One selectable lyric result for the manual finder. */
    public static class Candidate {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String source = "";
        public long durationMs = 0;
        public boolean wordLevel = false;
        public int lines = 0;
        public Lyrics lyrics;

        public String subtitle() {
            String a = artist.isEmpty() ? "Unknown artist" : artist;
            String dur = durationMs > 0
                    ? String.format(Locale.ROOT, " · %d:%02d", durationMs / 60000, (durationMs / 1000) % 60)
                    : "";
            return a + dur + " · " + source;
        }
    }

    /** Searches both sources for a free-text query and returns ranked, timed results. */
    public static List<Candidate> searchCandidates(String query, long durationMs) {
        List<Candidate> out = new ArrayList<>();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return out;
        // both sources at once: the finder feels roughly twice as fast
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        java.util.concurrent.Future<List<Candidate>> neteaseFuture = pool.submit(() -> neteaseCandidates(q));
        java.util.concurrent.Future<List<Candidate>> lrclibFuture = pool.submit(() -> lrclibCandidates(q));
        java.util.concurrent.Future<List<Candidate>> qqFuture = pool.submit(() -> qqCandidates(q));
        try {
            out.addAll(neteaseFuture.get(15, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception e) {
            Log.w(TAG, "manual NetEase search failed: " + e);
        }
        try {
            out.addAll(lrclibFuture.get(15, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception e) {
            Log.w(TAG, "manual LRCLIB search failed: " + e);
        }
        try {
            out.addAll(qqFuture.get(15, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception e) {
            Log.w(TAG, "manual QQ search failed: " + e);
        }
        pool.shutdownNow();

        List<Candidate> unique = new ArrayList<>();
        for (Candidate c : out) {
            boolean dup = false;
            for (int i = 0; i < unique.size(); i++) {
                Candidate u = unique.get(i);
                if (norm(u.title).equals(norm(c.title)) && norm(u.artist).equals(norm(c.artist))) {
                    dup = true;
                    if (c.wordLevel && !u.wordLevel) unique.set(i, c);
                    break;
                }
            }
            if (!dup) unique.add(c);
        }

        final long dur = durationMs;
        final String queryNorm = norm(q);
        Collections.sort(unique, (a, b) -> {
            int c = Double.compare(score(b, queryNorm, dur), score(a, queryNorm, dur));
            if (c != 0) return c;
            return Integer.compare(b.lines, a.lines);
        });
        return unique.size() > 20 ? new ArrayList<>(unique.subList(0, 20)) : unique;
    }

    /** Ranks an exact-title match above remixes/covers; word timings and duration refine it. */
    private static double score(Candidate c, String queryNorm, long durationMs) {
        double s = 0;
        String title = norm(c.title);
        String artist = norm(c.artist);
        if (!title.isEmpty() && queryNorm.contains(title)) s += 2.2;
        if (!artist.isEmpty() && queryNorm.contains(artist)) s += 0.8;
        if (c.wordLevel) s += 0.7;
        if (durationMs > 0 && c.durationMs > 0) {
            long diff = Math.abs(c.durationMs - durationMs);
            if (diff <= 4000) s += 0.9;
            else if (diff <= 15000) s += 0.4;
            else if (diff > 60000) s -= 0.6;
        }
        // "(Remix)" / "(Live)" variants should not outrank the plain track
        if (c.title.matches(".*[\\(（\\[【].*[\\)\\)\\]）】].*") && !queryNorm.contains("remix")
                && !queryNorm.contains("live")) {
            s -= 0.5;
        }
        return s;
    }

    private static List<Candidate> neteaseCandidates(String query) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://music.163.com/");
        headers.put("Cookie", "appver=2.0.2; os=pc");
        headers.put("Origin", "https://music.163.com");

        String body = Http.get("https://music.163.com/api/search/get?s=" + enc(query)
                + "&type=1&limit=10&offset=0", headers);
        JSONObject root = new JSONObject(body);
        JSONObject result = root.optJSONObject("result");
        JSONArray songs = result == null ? null : result.optJSONArray("songs");
        List<Candidate> list = new ArrayList<>();
        if (songs == null) return list;

        // fetch up to 8 candidate lyric files concurrently so the finder stays quick
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<Lyrics>> pending = new ArrayList<>();
        List<Integer> pendingIndex = new ArrayList<>();

        for (int i = 0; i < songs.length() && list.size() < 10; i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s == null) continue;
            Candidate c = new Candidate();
            c.title = s.optString("name", "");
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
            c.artist = artists.toString();
            JSONObject alb = s.optJSONObject("album");
            c.album = alb == null ? "" : alb.optString("name", "");
            c.durationMs = s.optLong("duration", 0);
            c.source = "NetEase";
            long id = s.optLong("id", -1);
            list.add(c);
            if (id > 0 && pending.size() < 8) {
                final long songId = id;
                pendingIndex.add(list.size() - 1);
                pending.add(pool.submit(() -> fetchNeteaseLyrics(songId, headers)));
            }
        }

        for (int i = 0; i < pending.size(); i++) {
            try {
                Lyrics l = pending.get(i).get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (l != null) {
                    Candidate c = list.get(pendingIndex.get(i));
                    c.lyrics = l;
                    c.wordLevel = l.wordLevel;
                    c.lines = l.lines.size();
                    if (l.wordLevel) c.source = "NetEase · word-by-word";
                }
            } catch (Exception e) {
                Log.w(TAG, "manual lyric fetch failed: " + e);
            }
        }
        pool.shutdownNow();
        return list;
    }

    private static Lyrics fetchNeteaseLyrics(long id, Map<String, String> headers) {
        try {
            String l = Http.get("https://music.163.com/api/song/lyric?id=" + id
                    + "&lv=1&kv=1&tv=-1&rv=1&yv=1&ytv=1&ytl=1", headers);
            JSONObject lo = new JSONObject(l);
            JSONObject yrcObj = lo.optJSONObject("yrc");
            JSONObject lrcObj = lo.optJSONObject("lrc");
            JSONObject tObj = lo.optJSONObject("tlyric");
            String yrc = yrcObj == null ? "" : yrcObj.optString("lyric", "");
            String lrc = lrcObj == null ? "" : lrcObj.optString("lyric", "");
            String trans = tObj == null ? "" : tObj.optString("lyric", "");
            Lyrics word = Lyrics.fromYrc(yrc, trans, "NetEase · word-by-word");
            if (word != null && word.wordLevel) return word;
            Lyrics line = Lyrics.fromLrc(lrc, trans, "NetEase");
            return line != null ? line : word;
        } catch (Exception e) {
            Log.w(TAG, "manual lyric fetch failed for " + id + ": " + e);
            return null;
        }
    }

    private static List<Candidate> qqCandidates(String query) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://y.qq.com/");
        headers.put("User-Agent", "Mozilla/5.0");
        JSONObject root = new JSONObject(Http.get(
                "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=10&w=" + enc(query) + "&format=json",
                headers));
        JSONObject data = root.optJSONObject("data");
        JSONObject songBlock = data == null ? null : data.optJSONObject("song");
        JSONArray list = songBlock == null ? null : songBlock.optJSONArray("list");
        List<Candidate> out = new ArrayList<>();
        if (list == null) return out;

        for (int i = 0; i < list.length() && out.size() < 8; i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            String mid = s.optString("songmid", "");
            if (mid.isEmpty()) continue;
            Candidate c = new Candidate();
            c.title = s.optString("songname", "");
            StringBuilder artists = new StringBuilder();
            JSONArray singers = s.optJSONArray("singer");
            if (singers != null) {
                for (int j = 0; j < singers.length(); j++) {
                    JSONObject singer = singers.optJSONObject(j);
                    if (singer == null) continue;
                    if (artists.length() > 0) artists.append(" / ");
                    artists.append(singer.optString("name", ""));
                }
            }
            c.artist = artists.toString();
            c.album = s.optString("albumname", "");
            c.durationMs = s.optLong("interval", 0) * 1000L;
            c.source = "QQ Music";
            try {
                JSONObject lo = new JSONObject(Http.get(
                        "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + mid
                                + "&format=json&nobase64=1&g_tk=5381", headers));
                String lyric = unescapeHtml(lo.optString("lyric", ""));
                String trans = unescapeHtml(lo.optString("trans", ""));
                if (!lyric.trim().isEmpty()) {
                    c.lyrics = Lyrics.fromLrc(lyric, trans, "QQ Music");
                }
            } catch (Exception e) {
                Log.w(TAG, "manual QQ lyric failed: " + e);
            }
            if (c.lyrics != null) {
                c.lines = c.lyrics.lines.size();
                c.wordLevel = c.lyrics.wordLevel;
            }
            out.add(c);
        }
        return out;
    }

    private static List<Candidate> lrclibCandidates(String query) throws Exception {
        JSONArray arr = new JSONArray(Http.get("https://lrclib.net/api/search?q=" + enc(query), null));
        List<Candidate> list = new ArrayList<>();
        for (int i = 0; i < arr.length() && list.size() < 10; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String synced = o.optString("syncedLyrics", "");
            String plain = o.optString("plainLyrics", "");
            if (synced.isEmpty() && plain.isEmpty()) continue;
            Candidate c = new Candidate();
            c.title = o.optString("trackName", "");
            c.artist = o.optString("artistName", "");
            c.album = o.optString("albumName", "");
            c.durationMs = o.optLong("duration", 0) * 1000L;
            c.lyrics = !synced.isEmpty()
                    ? Lyrics.fromLrc(synced, null, "LRCLIB")
                    : Lyrics.fromPlain(plain, "LRCLIB");
            if (c.lyrics == null) continue;
            c.lines = c.lyrics.lines.size();
            c.wordLevel = c.lyrics.wordLevel;
            c.source = c.wordLevel ? "LRCLIB · word-by-word" : "LRCLIB";
            list.add(c);
        }
        return list;
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
