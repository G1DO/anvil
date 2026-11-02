package com.g1do.anvil.extract;

/**
 * A model that reads one bounded document and replies with facts as JSON
 * (#37). {@link Extractor} is the only caller.
 *
 * <p>An implementation keeps no state between calls: nothing from one call,
 * and so nothing from one tenant, reaches another call.
 */
public interface ExtractionModel {

    /** The model's identifier, recorded with each extraction. */
    String id();

    /** Asks the model once. Throws {@link ModelUnavailableException} when it can't be reached or isn't configured. */
    ModelReply complete(BoundedText document);
}
