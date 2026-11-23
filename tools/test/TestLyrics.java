import me.lyrimuse.android.Cache;
import me.lyrimuse.android.Lyrics;

/** Plain-JVM checks for the parsers and language modes, run by tools/test.sh. */
public final class TestLyrics {

    private static int passed;
    private static int failed;

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name);
        }
    }

    private static boolean near(long a, long b) {
        return Math.abs(a - b) <= 2;
    }

    public static void main(String[] args) {
        yrc();
        english();
        original();
        romanized();
        enhancedLrc();
        plain();
        indexAt();
        cacheKeys();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void yrc() {
        Lyrics.setLanguage("english");
        Lyrics y = Lyrics.fromYrc("[1000,2000](1000,500,0)Hello (1500,500,0)world\n", null, "Test");
        check("YRC parses one line", y != null && y.lines.size() == 1);
        check("YRC keeps word timings", y != null && y.wordLevel && y.lines.get(0).words.size() == 2);
        check("YRC text is joined", y != null && "Hello world".equals(y.lines.get(0).text));
    }

    private static void english() {
        Lyrics.setLanguage("english");
        Lyrics zh = Lyrics.fromLrc("[00:01.00]你好世界\n[00:03.00]Hello there\n", null, "Test");
        check("English mode drops a Chinese-only line", zh != null && zh.lines.size() == 1);
        check("English mode keeps the English line", zh != null && "Hello there".equals(zh.lines.get(0).text));

        Lyrics tr = Lyrics.fromLrc("[00:01.00]你好世界\n", "[00:01.00]Hello world\n", "Test");
        check("English mode substitutes an English translation", tr != null
                && tr.lines.size() == 1 && "Hello world".equals(tr.lines.get(0).text)
                && tr.lines.get(0).trans.isEmpty());

        Lyrics empty = Lyrics.fromLrc("[00:01.00]こんにちは\n", null, "Test");
        check("English mode returns null when nothing is English", empty == null);
    }

    private static void original() {
        Lyrics.setLanguage("original");
        Lyrics l = Lyrics.fromLrc("[00:01.00]你好世界\n", "[00:01.00]Hello world\n", "Test");
        check("Original mode keeps the source line", l != null && "你好世界".equals(l.lines.get(0).text));
        check("Original mode keeps the translation", l != null && !l.lines.get(0).trans.isEmpty());
        Lyrics.setLanguage("english");
    }

    private static void romanized() {
        Lyrics.setLanguage("romanized");
        Lyrics r = Lyrics.fromYrc("[0,2000](0,1000,0)안녕\n", null, "Test");
        check("Romanised mode keeps Korean lines", r != null && r.lines.size() == 1);
        check("Romanised mode writes Revised Romanization", r != null
                && "annyeong".equals(r.lines.get(0).text));
        Lyrics.setLanguage("english");
    }

    private static void enhancedLrc() {
        Lyrics.setLanguage("english");
        Lyrics l = Lyrics.fromLrc("[00:01.00]<00:01.00>Hello <00:01.50>world\n", null, "Test");
        check("enhanced LRC carries words", l != null && l.wordLevel
                && l.lines.get(0).words.size() == 2 && "Hello world".equals(l.lines.get(0).text));
        check("enhanced LRC sets word ends", l != null
                && near(l.lines.get(0).words.get(0).end, 1500));
    }

    private static void plain() {
        Lyrics p = Lyrics.fromPlain("Just a line\nAnother line\n", "Test");
        check("plain lyrics parse", p != null && p.lines.size() == 2);
        check("plain lyrics are unsynchronised", p != null && !p.synced);
    }

    private static void indexAt() {
        Lyrics l = Lyrics.fromLrc("[00:01.00]One\n[00:03.00]Two\n", null, "Test");
        check("indexAt before the first line", l != null && l.indexAt(500) == -1);
        check("indexAt first line", l != null && l.indexAt(1500) == 0);
        check("indexAt second line", l != null && l.indexAt(3500) == 1);
    }

    private static void cacheKeys() {
        String base = Cache.key("Song", "Artist", "Album", 180000, "english");
        String album = Cache.key("Song", "Artist", "Live", 180000, "english");
        String longer = Cache.key("Song", "Artist", "Album", 240000, "english");
        String language = Cache.key("Song", "Artist", "Album", 180000, "original");
        String same = Cache.key("Song", "Artist", "Album", 180000, "english");
        check("cache key is stable", base.equals(same));
        check("cache key separates albums", !base.equals(album));
        check("cache key separates durations", !base.equals(longer));
        check("cache key separates languages", !base.equals(language));
        check("legacy key is distinct", !base.equals(Cache.legacyKey("Song", "Artist")));
    }
}
