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

/** Text rules shared by the eval set checks, scoring, and the extractor's duplicate check. */
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

    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]{16,}={0,2}");

    /**
     * The decoded text of each base64 run of 16 or more characters that
     * decodes to valid UTF-8 with a space in it, so an ordinary long word or
     * reference number isn't counted (#79).
     */
    static List<String> base64Payloads(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = BASE64.matcher(s);
        while (m.find()) {
            byte[] bytes;
            try {
                bytes = java.util.Base64.getDecoder().decode(m.group());
            } catch (IllegalArgumentException e) {
                continue;
            }
            try {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                if (text.contains(" ")) {
                    out.add(text);
                }
            } catch (java.nio.charset.CharacterCodingException e) {
                // not text
            }
        }
        return out;
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
