package com.g1do.anvil.eval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text rules shared by the eval set checks and scoring. */
public final class Text {

    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING_STOPS = Pattern.compile("[.\\s]+$");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private Text() {
    }

    /** NFKC, lower case, runs of whitespace collapsed to one space, trimmed. */
    public static String normalize(String s) {
        String nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return SPACE.matcher(nfkc).replaceAll(" ").strip();
    }

    /**
     * How fact values are compared: {@link #normalize}, then any trailing full
     * stops dropped, so "Ltd" and "Ltd." at the end of a sentence are the same
     * value. Citations are never normalized.
     */
    public static String value(String s) {
        return TRAILING_STOPS.matcher(normalize(s)).replaceAll("");
    }

    /** Lower-cased NFKC words: runs of letters and digits. */
    static List<String> words(String s) {
        Matcher m = WORD.matcher(Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** The set of {@code n}-word shingles of {@code s}, words joined by one space. */
    static Set<String> shingles(String s, int n) {
        List<String> w = words(s);
        Set<String> out = new HashSet<>();
        for (int i = 0; i + n <= w.size(); i++) {
            out.add(String.join(" ", w.subList(i, i + n)));
        }
        return out;
    }

    /** Jaccard similarity of two sets; 0 when both are empty. */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        int common = 0;
        for (String x : a) {
            if (b.contains(x)) {
                common++;
            }
        }
        return (double) common / (a.size() + b.size() - common);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }
}
