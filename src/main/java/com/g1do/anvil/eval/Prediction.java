package com.g1do.anvil.eval;

import java.util.List;

/** An extractor's facts for one document of an eval set. */
public record Prediction(String doc, List<Fact> facts) {

    public Prediction {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
