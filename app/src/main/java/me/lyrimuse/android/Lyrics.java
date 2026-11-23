package me.lyrimuse.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyrics model plus parsers for every timed format Lyrimuse's engine understands:
 * plain LRC, enhanced / A2 LRC with inline word tags, and NetEase YRC (word timings).
 */
public class Lyrics {

    public static class Word {
        public long start;
        public long end;
        public String text;

        Word(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    public static class Line {
        public long start;
        public long end;
        public String text = "";
        public String trans = "";
        public final List<Word> words = new ArrayList<>();

        public boolean hasWords() {
            return words.size() > 1;
        }
    }

    public final List<Line> lines = new ArrayList<>();
    public String source = "";
    public boolean wordLevel = false;
    public boolean synced = true;

    private static final Pattern TIME_TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern WORD_TAG =
            Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern META_TAG = Pattern.compile("\\[(ar|ti|al|by|offset):([^\\]]*)\\]");
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),-?\\d+\\)([^()]*)");

    private static long ms(long min, long sec, String frac) {
        long f = 0;
        if (frac != null && !frac.isEmpty()) {
            if (frac.length() == 1) f = Long.parseLong(frac) * 100;
            else if (frac.length() == 2) f = Long.parseLong(frac) * 10;
            else f = Long.parseLong(frac.substring(0, 3));
        }
        return min * 60000L + sec * 1000L + f;
    }

    /** index of the line playing at ms, or -1 before the first line. */
    public int indexAt(long ms) {
        int lo = 0, hi = lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).start <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    private void finish() {
        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Long.compare(a.start, b.start);
            }
        });
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            long next = i + 1 < lines.size() ? lines.get(i + 1).start : l.start + 6000;
            if (l.end <= l.start) l.end = Math.max(l.start + 700, next);
        }
    }

    /** Parses standard or enhanced LRC, merging translation lines when supplied. */
    public static Lyrics fromLrc(String lrc, String translation, String source) {
        Lyrics out = new Lyrics();
        out.source = source;
        if (lrc == null) lrc = "";
        long offset = 0;
        for (String rawLine : lrc.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher meta = META_TAG.matcher(line);
            if (meta.find() && "offset".equals(meta.group(1))) {
                try {
                    offset = Long.parseLong(meta.group(2).trim());
                } catch (Exception ignored) {
                }
                continue;
            }
            Matcher tm = TIME_TAG.matcher(line);
            List<Long> stamps = new ArrayList<>();
            int textStart = 0;
            while (tm.find()) {
                if (tm.start() > textStart && !line.substring(textStart, tm.start()).trim().isEmpty()) break;
                stamps.add(ms(Long.parseLong(tm.group(1)), Long.parseLong(tm.group(2)), tm.group(3)));
                textStart = tm.end();
            }
            if (stamps.isEmpty()) continue;
            String content = line.substring(textStart).trim();
            if (content.isEmpty()) continue;

            for (Long stamp : stamps) {
                Line l = new Line();
                l.start = stamp + offset;
                l.text = content;

                Matcher wm = WORD_TAG.matcher(content);
                StringBuilder clean = new StringBuilder();
                List<Word> words = new ArrayList<>();
                int lastEnd = 0;
                while (wm.find()) {
                    String before = content.substring(lastEnd, wm.start());
                    if (!before.isEmpty() && words.isEmpty()) clean.append(before);
                    long wStart = ms(Long.parseLong(wm.group(1)), Long.parseLong(wm.group(2)), wm.group(3)) + offset;
                    words.add(new Word(wStart, -1, ""));
                    lastEnd = wm.end();
                }
                if (!words.isEmpty()) {
                    // second pass: word text runs until the next tag
                    List<long[]> markers = new ArrayList<>();
                    Matcher wm2 = WORD_TAG.matcher(content);
                    while (wm2.find()) {
                        markers.add(new long[]{
                                ms(Long.parseLong(wm2.group(1)), Long.parseLong(wm2.group(2)), wm2.group(3)) + offset,
                                wm2.start(), wm2.end()});
                    }
                    words.clear();
                    clean.setLength(0);
                    for (int i = 0; i < markers.size(); i++) {
                        long[] m = markers.get(i);
                        int endIdx = i + 1 < markers.size() ? (int) markers.get(i + 1)[1] : content.length();
                        String wtext = content.substring((int) m[2], endIdx);
                        words.add(new Word(m[0], -1, wtext));
                        clean.append(wtext);
                    }
                    l.text = clean.toString().trim();
                    l.words.addAll(words);
                    out.wordLevel = true;
                }
                out.lines.add(l);
            }
        }
        if (out.lines.isEmpty()) return null;
        out.finish();
        for (Line l : out.lines) {
            if (!l.words.isEmpty()) {
                for (int i = 0; i < l.words.size(); i++) {
                    Word w = l.words.get(i);
                    w.end = i + 1 < l.words.size() ? l.words.get(i + 1).start : l.end;
                    if (w.end <= w.start) w.end = w.start + 120;
                }
            }
        }
        if (translation != null && !translation.trim().isEmpty()) {
            out.attachTranslation(translation);
        }
        return out;
    }

    /** Parses NetEase YRC (word-by-word) with optional translation. */
    public static Lyrics fromYrc(String yrc, String translation, String source) {
        if (yrc == null || yrc.trim().isEmpty()) return null;
        Lyrics out = new Lyrics();
        out.source = source;
        for (String rawLine : yrc.split("\r?\n")) {
            Matcher lm = YRC_LINE.matcher(rawLine.trim());
            if (!lm.find()) continue;
            Line line = new Line();
            line.start = Long.parseLong(lm.group(1));
            line.end = line.start + Long.parseLong(lm.group(2));
            String rest = lm.group(3);
            Matcher wm = YRC_WORD.matcher(rest);
            StringBuilder sb = new StringBuilder();
            while (wm.find()) {
                long ws = Long.parseLong(wm.group(1));
                long wd = Long.parseLong(wm.group(2));
                String t = wm.group(3);
                if (t == null) continue;
                line.words.add(new Word(ws, ws + wd, t));
                sb.append(t);
            }
            if (line.words.isEmpty()) {
                String plain = rest.replaceAll("\\(\\d+,\\d+,-?\\d+\\)", "").trim();
                if (plain.isEmpty()) continue;
                line.text = plain;
            } else {
                line.text = sb.toString().trim();
                out.wordLevel = true;
            }
            if (line.end <= line.start) line.end = line.start + 800;
            out.lines.add(line);
        }
        if (out.lines.isEmpty()) return null;
        out.finish();
        if (translation != null && !translation.trim().isEmpty()) out.attachTranslation(translation);
        return out;
    }

    /** Unsynchronised plain lyrics (still shown, just without timings). */
    public static Lyrics fromPlain(String text, String source) {
        if (text == null || text.trim().isEmpty()) return null;
        Lyrics out = new Lyrics();
        out.source = source;
        out.synced = false;
        for (String raw : text.split("\r?\n")) {
            Line l = new Line();
            l.text = raw.trim();
            l.start = 0;
            l.end = 0;
            out.lines.add(l);
        }
        return out.lines.isEmpty() ? null : out;
    }

    private void attachTranslation(String translation) {
        Map<Long, String> map = new HashMap<>();
        for (String raw : translation.split("\r?\n")) {
            Matcher tm = TIME_TAG.matcher(raw);
            if (!tm.find()) continue;
            long t = ms(Long.parseLong(tm.group(1)), Long.parseLong(tm.group(2)), tm.group(3));
            String content = raw.substring(tm.end()).trim();
            if (!content.isEmpty()) map.put(t, content);
        }
        if (map.isEmpty()) return;
        for (Line l : lines) {
            String t = map.get(l.start);
            if (t == null) {
                // tolerate a few ms of drift between lyric files
                for (Map.Entry<Long, String> e : map.entrySet()) {
                    if (Math.abs(e.getKey() - l.start) <= 250) {
                        t = e.getValue();
                        break;
                    }
                }
            }
            if (t != null) l.trans = t;
        }
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("source", source);
            o.put("wordLevel", wordLevel);
            o.put("synced", synced);
            JSONArray arr = new JSONArray();
            for (Line l : lines) {
                JSONObject lo = new JSONObject();
                lo.put("s", l.start);
                lo.put("e", l.end);
                lo.put("t", l.text);
                lo.put("tr", l.trans);
                JSONArray wa = new JSONArray();
                for (Word w : l.words) {
                    JSONObject wo = new JSONObject();
                    wo.put("s", w.start);
                    wo.put("e", w.end);
                    wo.put("t", w.text);
                    wa.put(wo);
                }
                lo.put("w", wa);
                arr.put(lo);
            }
            o.put("lines", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Lyrics fromJson(JSONObject o) {
        if (o == null) return null;
        Lyrics out = new Lyrics();
        out.source = o.optString("source", "");
        out.wordLevel = o.optBoolean("wordLevel", false);
        out.synced = o.optBoolean("synced", true);
        JSONArray arr = o.optJSONArray("lines");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject lo = arr.optJSONObject(i);
            if (lo == null) continue;
            Line l = new Line();
            l.start = lo.optLong("s");
            l.end = lo.optLong("e");
            l.text = lo.optString("t", "");
            l.trans = lo.optString("tr", "");
            JSONArray wa = lo.optJSONArray("w");
            if (wa != null) {
                for (int j = 0; j < wa.length(); j++) {
                    JSONObject wo = wa.optJSONObject(j);
                    if (wo == null) continue;
                    l.words.add(new Word(wo.optLong("s"), wo.optLong("e"), wo.optString("t", "")));
                }
            }
            out.lines.add(l);
        }
        return out;
    }
}
