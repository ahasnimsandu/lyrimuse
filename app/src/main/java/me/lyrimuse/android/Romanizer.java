package me.lyrimuse.android;

/** Hangul → Revised Romanization, so Korean songs can be sung along (Apple Music style). */
public final class Romanizer {

    private static final String[] INITIAL = {
            "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"
    };

    private static final String[] VOWEL = {
            "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe",
            "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i"
    };

    private static final String[] FINAL = {
            "", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p",
            "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t"
    };

    private Romanizer() {}

    public static boolean hasHangul(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (isHangul(s.charAt(i))) return true;
        }
        return false;
    }

    private static boolean isHangul(char ch) {
        return ch >= 0xAC00 && ch <= 0xD7A3;
    }

    public static String romanize(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (isHangul(ch)) {
                int index = ch - 0xAC00;
                int initial = index / 588;
                int vowel = (index % 588) / 28;
                int fin = index % 28;
                out.append(INITIAL[initial]).append(VOWEL[vowel]);
                String tail = FINAL[fin];
                char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                // a final consonant before a vowel sound reads as the next syllable's onset
                if (!tail.isEmpty() && next != 0 && isHangul(next)) {
                    int ni = (next - 0xAC00) / 588;
                    if (ni == 11) { // silent ㅇ onset
                        out.append(tail);
                        continue;
                    }
                }
                out.append(tail);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
