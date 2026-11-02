package com.g1do.anvil.extract;

/**
 * A document's text cut to at most {@value #MAX_CHARS} characters (#37).
 *
 * <p>{@link ExtractionModel} takes only this type, and the only way to make
 * one is {@link #of}, which truncates. So no code path can send a model more
 * than {@value #MAX_CHARS} characters of a document. A character is a Java
 * {@code char}, the unit the eval set's limit uses. A cut never splits a
 * surrogate pair: when the last kept {@code char} would be the first half of
 * one, the cut moves one earlier.
 */
public final class BoundedText {

    public static final int MAX_CHARS = 500;

    private final String text;
    private final boolean truncated;

    private BoundedText(String text, boolean truncated) {
        this.text = text;
        this.truncated = truncated;
    }

    public static BoundedText of(String full) {
        String s = full == null ? "" : full;
        if (s.length() <= MAX_CHARS) {
            return new BoundedText(s, false);
        }
        int end = MAX_CHARS;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return new BoundedText(s.substring(0, end), true);
    }

    public String text() {
        return text;
    }

    /** True when the document was longer than {@value #MAX_CHARS} characters. */
    public boolean truncated() {
        return truncated;
    }

    @Override
    public String toString() {
        return "BoundedText[" + text.length() + " chars" + (truncated ? ", truncated" : "") + "]";
    }
}
