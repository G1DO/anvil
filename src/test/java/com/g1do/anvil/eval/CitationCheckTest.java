package com.g1do.anvil.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance check for issue #36: a citation passes only as an exact substring of its source. */
class CitationCheckTest {

    private static final String SOURCE = "Amount due: EUR 4,250.00, payable within 30 days.\nSigned, Ingrid Solberg";

    @Test
    void anExactSubstringPasses() {
        assertThat(CitationCheck.isExactSubstring(SOURCE, "EUR 4,250.00")).isTrue();
        assertThat(CitationCheck.isExactSubstring(SOURCE, SOURCE)).isTrue();
        assertThat(CitationCheck.isExactSubstring(SOURCE, "30 days.\nSigned")).isTrue();
    }

    @Test
    void anythingButTheExactCharactersFails() {
        assertThat(CitationCheck.isExactSubstring(SOURCE, "eur 4,250.00")).as("case").isFalse();
        assertThat(CitationCheck.isExactSubstring(SOURCE, "EUR  4,250.00")).as("extra space").isFalse();
        assertThat(CitationCheck.isExactSubstring(SOURCE, "30 days. Signed")).as("newline as space").isFalse();
        assertThat(CitationCheck.isExactSubstring(SOURCE, " EUR 4,250.00 ")).as("padding").isFalse();
        assertThat(CitationCheck.isExactSubstring(SOURCE, "EUR 4250.00")).as("reformatted number").isFalse();
        assertThat(CitationCheck.isExactSubstring(SOURCE, "EUR 4,250.00 Ingrid Solberg")).as("stitched").isFalse();
        assertThat(CitationCheck.isExactSubstring(SOURCE, "Amount due: EUR 4,250.00 within 30 days"))
                .as("paraphrase").isFalse();
        assertThat(CitationCheck.isExactSubstring(SOURCE, "EUR 4,250.00")).as("no-break space").isFalse();
    }

    @Test
    void emptyOrMissingFails() {
        assertThat(CitationCheck.isExactSubstring(SOURCE, "")).isFalse();
        assertThat(CitationCheck.isExactSubstring(SOURCE, null)).isFalse();
        assertThat(CitationCheck.isExactSubstring(null, "EUR")).isFalse();
    }
}
