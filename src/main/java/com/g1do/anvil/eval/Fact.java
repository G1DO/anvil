package com.g1do.anvil.eval;

/**
 * One extracted fact: a field from the set's schema, its value as written in
 * the document, and the citation that supports it.
 */
public record Fact(String field, String value, String citation) {
}
