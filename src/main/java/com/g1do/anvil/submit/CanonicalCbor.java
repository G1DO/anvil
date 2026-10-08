package com.g1do.anvil.submit;

import java.security.MessageDigest;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

/**
 * RFC 8949 core-deterministic CBOR for the submit slice.
 *
 * <p>Participating fields only: {@code {title:tstr, mime_type:tstr, body:bstr}}.
 * Excluded: {@code tenant_id, idempotency_key, timestamps, request_id}.
 *
 * <p>Determinism comes from sorted text keys, definite lengths, shortest-form
 * ints and UTF-8. Backed by {@code jackson-dataformat-cbor} with
 * {@code SORT_PROPERTIES_ALPHABETICALLY}; duplicate enforcement remains the DB
 * {@code UNIQUE(tenant_id, idempotency_key)} — canonical bytes only ensure the
 * same logical doc maps to the same bytes/sha.
 */
public final class CanonicalCbor {

    private static final ObjectMapper MAPPER = createMapper();

    private CanonicalCbor() {
    }

    private static ObjectMapper createMapper() {
        return JsonMapper.builder(new CBORFactory())
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    /**
     * Canonical form holder. Field names are the CBOR text keys; Jackson sorts
     * them alphabetically (body, mime_type, title) for determinism.
     */
    public static class CanonicalForm {
        public byte[] body;
        public String mime_type;
        public String title;

        public CanonicalForm() {
        }

        public CanonicalForm(String title, String mimeType, byte[] body) {
            this.title = title;
            this.mime_type = mimeType;
            this.body = body;
        }
    }

    public record Encoded(byte[] cbor, String sha256Hex) {
    }

    public static Encoded encode(String title, String mimeType, byte[] body) {
        try {
            byte[] cbor = MAPPER.writeValueAsBytes(new CanonicalForm(title, mimeType, body));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cbor);
            String hex = HexFormat.of().formatHex(hash);
            return new Encoded(cbor, hex);
        } catch (Exception e) {
            throw new IllegalStateException("canonical CBOR encoding failed", e);
        }
    }
}
