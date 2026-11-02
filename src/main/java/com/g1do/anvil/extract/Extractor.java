package com.g1do.anvil.extract;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.eval.CitationCheck;
import com.g1do.anvil.eval.Fact;
import com.g1do.anvil.eval.Text;

/**
 * The one extraction path (#37, record 0022): bound the text, ask the model
 * once, and keep only facts whose citation is an exact substring of what the
 * model was shown.
 *
 * <p>The service and the eval harness both call {@link #extract}, and it is
 * the only caller of {@link ExtractionModel#complete}. Inputs that can't be
 * extracted safely, and model output that fails a check, are quarantined with
 * a {@link Reason}; quarantined facts are never indexed.
 */
public final class Extractor {

    /** More facts than this in one reply is treated as malformed output. */
    public static final int MAX_FACTS = 64;

    /** At most this much of a malformed reply is kept with its dead letter. */
    static final int MAX_DETAIL = 2000;

    public static final Set<String> FIELDS = Set.of("party", "person", "date", "amount");

    /** Why an input or a fact was quarantined. */
    public enum Reason {
        // The whole document: nothing from it is indexed.
        UNSUPPORTED_MEDIA_TYPE(true),
        NOT_TEXT(true),
        EMPTY_TEXT(true),
        MODEL_REFUSED(true),
        MALFORMED_OUTPUT(true),
        // One fact: the document's other facts can still be indexed.
        UNKNOWN_FIELD(false),
        UNCITED(false),
        CITATION_NOT_IN_SOURCE(false),
        VALUE_NOT_IN_CITATION(false);

        private final boolean wholeDocument;

        Reason(boolean wholeDocument) {
            this.wholeDocument = wholeDocument;
        }

        public boolean wholeDocument() {
            return wholeDocument;
        }
    }

    public enum Status {
        ACCEPTED, QUARANTINED
    }

    /** A quarantined input or fact. {@code fact} is null when the whole document was quarantined. */
    public record Quarantined(Reason reason, Fact fact, String detail) {
    }

    /**
     * The result of one extraction.
     *
     * @param status QUARANTINED when the whole document was, otherwise ACCEPTED
     * @param truncated whether the text was cut to {@link BoundedText#MAX_CHARS}
     * @param modelCalled whether the model was asked
     * @param accepted facts that passed every check, safe to index
     * @param quarantined what failed, with reasons
     */
    public record Outcome(Status status, boolean truncated, boolean modelCalled, List<Fact> accepted,
            List<Quarantined> quarantined) {

        public Outcome {
            accepted = List.copyOf(accepted);
            quarantined = List.copyOf(quarantined);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ExtractionModel model;

    public Extractor(ExtractionModel model) {
        this.model = model;
    }

    public String modelId() {
        return model.id();
    }

    /**
     * Extracts facts from one document. Throws {@link ModelUnavailableException}
     * when the model can't be asked; nothing is decided in that case.
     */
    public Outcome extract(String text, String mimeType) {
        if (!isText(mimeType)) {
            return poisoned(false, false, Reason.UNSUPPORTED_MEDIA_TYPE, "media type " + safe(mimeType));
        }
        BoundedText bounded = BoundedText.of(text);
        if (bounded.text().isBlank()) {
            return poisoned(bounded.truncated(), false, Reason.EMPTY_TEXT, "no text");
        }
        int bad = firstControlChar(bounded.text());
        if (bad >= 0) {
            return poisoned(bounded.truncated(), false, Reason.NOT_TEXT,
                    String.format(Locale.ROOT, "control character U+%04X at %d", (int) bounded.text().charAt(bad), bad));
        }

        ModelReply reply = model.complete(bounded);
        if ("refusal".equals(reply.stopReason())) {
            return poisoned(bounded.truncated(), true, Reason.MODEL_REFUSED, clip(reply.text()));
        }
        if (!"end_turn".equals(reply.stopReason())) {
            return poisoned(bounded.truncated(), true, Reason.MALFORMED_OUTPUT,
                    "stop reason " + reply.stopReason() + ": " + clip(reply.text()));
        }
        List<Fact> facts;
        try {
            facts = parse(reply.text());
        } catch (IllegalArgumentException e) {
            return poisoned(bounded.truncated(), true, Reason.MALFORMED_OUTPUT,
                    e.getMessage() + ": " + clip(reply.text()));
        }

        List<Fact> accepted = new ArrayList<>();
        List<Quarantined> quarantined = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Fact f : facts) {
            Reason r = check(bounded.text(), f);
            if (r != null) {
                quarantined.add(new Quarantined(r, f, r.name()));
            } else if (seen.add(f.field() + "\u0000" + Text.value(f.value()))) {
                accepted.add(f);
            }
        }
        return new Outcome(Status.ACCEPTED, bounded.truncated(), true, accepted, quarantined);
    }

    /**
     * Extracts from raw document bytes, which must be UTF-8. Bytes that aren't
     * are quarantined as {@link Reason#NOT_TEXT} without a model call.
     */
    public Outcome extract(byte[] utf8, String mimeType) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8 == null ? new byte[0] : utf8))
                    .toString();
        } catch (CharacterCodingException e) {
            if (!isText(mimeType)) {
                return poisoned(false, false, Reason.UNSUPPORTED_MEDIA_TYPE, "media type " + safe(mimeType));
            }
            return poisoned(false, false, Reason.NOT_TEXT, "not UTF-8");
        }
        return extract(text, mimeType);
    }

    /** The first check {@code f} fails against the text the model was shown, or null. */
    static Reason check(String shown, Fact f) {
        if (!FIELDS.contains(f.field())) {
            return Reason.UNKNOWN_FIELD;
        }
        if (f.citation().isBlank()) {
            return Reason.UNCITED;
        }
        if (!CitationCheck.isExactSubstring(shown, f.citation())) {
            return Reason.CITATION_NOT_IN_SOURCE;
        }
        String value = Text.value(f.value());
        if (value.isEmpty() || !Text.normalize(f.citation()).contains(value)) {
            return Reason.VALUE_NOT_IN_CITATION;
        }
        return null;
    }

    /** Parses {@code {"facts":[{"field","value","citation"}]}} and nothing else. */
    static List<Fact> parse(String reply) {
        JsonNode root;
        try {
            root = JSON.readTree(reply);
        } catch (Exception e) {
            throw new IllegalArgumentException("not JSON");
        }
        if (root == null || !root.isObject() || root.size() != 1 || !root.path("facts").isArray()) {
            throw new IllegalArgumentException("not an object with only a facts array");
        }
        JsonNode list = root.get("facts");
        if (list.size() > MAX_FACTS) {
            throw new IllegalArgumentException(list.size() + " facts, more than " + MAX_FACTS);
        }
        List<Fact> out = new ArrayList<>();
        for (JsonNode n : list) {
            if (!n.isObject() || n.size() != 3 || !n.path("field").isTextual() || !n.path("value").isTextual()
                    || !n.path("citation").isTextual()) {
                throw new IllegalArgumentException("a fact isn't {field, value, citation} strings");
            }
            out.add(new Fact(n.get("field").asText(), n.get("value").asText(), n.get("citation").asText()));
        }
        return out;
    }

    static boolean isText(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String base = mimeType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return base.startsWith("text/") && base.length() > "text/".length();
    }

    /** Index of the first control character other than tab, line feed and carriage return, or -1. */
    static int firstControlChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.getType(c) == Character.CONTROL && c != '\t' && c != '\n' && c != '\r') {
                return i;
            }
        }
        return -1;
    }

    private static Outcome poisoned(boolean truncated, boolean modelCalled, Reason r, String detail) {
        return new Outcome(Status.QUARANTINED, truncated, modelCalled, List.of(),
                List.of(new Quarantined(r, null, detail)));
    }

    static String clip(String s) {
        return s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL);
    }

    private static String safe(String s) {
        return s == null ? "(none)" : clip(s);
    }
}
