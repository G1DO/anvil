package com.g1do.anvil.extract;

/**
 * What a model returned for one document: its text, why it stopped, and the
 * tokens it billed.
 *
 * @param text the reply text, or "" when there was none
 * @param stopReason the provider's stop reason, such as {@code end_turn},
 *        {@code max_tokens} or {@code refusal}
 * @param usage the billed tokens, or null when the provider didn't report them
 */
public record ModelReply(String text, String stopReason, Usage usage) {

    /**
     * Tokens billed for one call (#38, record 0023). Input tokens are split by
     * whether they hit the provider's own context cache, since the two are
     * priced differently.
     */
    public record Usage(long inputCacheHit, long inputCacheMiss, long output) {

        public Usage {
            if (inputCacheHit < 0 || inputCacheMiss < 0 || output < 0) {
                throw new IllegalArgumentException("negative token count");
            }
        }
    }

    public ModelReply {
        text = text == null ? "" : text;
        stopReason = stopReason == null ? "" : stopReason;
    }

    public ModelReply(String text, String stopReason) {
        this(text, stopReason, null);
    }
}
