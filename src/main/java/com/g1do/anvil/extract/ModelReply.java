package com.g1do.anvil.extract;

/**
 * What a model returned for one document: its text and why it stopped.
 *
 * @param text the reply text, or "" when there was none
 * @param stopReason the provider's stop reason, such as {@code end_turn},
 *        {@code max_tokens} or {@code refusal}
 */
public record ModelReply(String text, String stopReason) {

    public ModelReply {
        text = text == null ? "" : text;
        stopReason = stopReason == null ? "" : stopReason;
    }
}
