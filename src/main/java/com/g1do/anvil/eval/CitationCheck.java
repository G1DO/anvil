package com.g1do.anvil.eval;

/**
 * The citation rule for extraction (#36). Extraction (#37) emits a fact only
 * when this check passes.
 *
 * <p>A citation is supported when it is a non-empty, exact substring of the
 * text it cites: the same characters, case and whitespace. There's no
 * normalization, trimming or fuzzy match, so a model can't paraphrase,
 * re-case or stitch together a citation and still pass.
 */
public final class CitationCheck {

    private CitationCheck() {
    }

    /** True when {@code citation} is non-empty and occurs verbatim in {@code source}. */
    public static boolean isExactSubstring(String source, String citation) {
        return source != null && citation != null && !citation.isEmpty() && source.contains(citation);
    }
}
