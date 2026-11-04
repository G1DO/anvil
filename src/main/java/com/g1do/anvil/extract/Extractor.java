package com.g1do.anvil.extract;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.eval.CitationCheck;
import com.g1do.anvil.eval.Fact;
import com.g1do.anvil.eval.RulesBaseline;
import com.g1do.anvil.eval.Text;

/**
 * The one extraction path (#37, record 0022): bound the text, ask the model
 * once, and keep only facts whose citation is an exact substring of what the
 * model was shown.
 *
 * <p>The service and the eval harness both go through here, and it is the
 * only caller of {@link ExtractionModel#complete}. Inputs that can't be
 * extracted safely, and model output that fails a check, are quarantined with
 * a {@link Reason}; quarantined facts are never indexed.
 *
 * <p>{@link #extract} prepares a document and asks the model. The service
 * (#38, record 0023) prepares first, then reuses a cached result
 * ({@link #reuse}), asks the model ({@link #ask}), or falls back to the rules
 * ({@link #rules}); every path keeps only facts that pass the same checks
 * against the same bounded text.
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
            List<Quarantined> quarantined, ModelReply.Usage usage) {

        public Outcome {
            accepted = List.copyOf(accepted);
            quarantined = List.copyOf(quarantined);
        }

        public Outcome(Status status, boolean truncated, boolean modelCalled, List<Fact> accepted,
                List<Quarantined> quarantined) {
            this(status, truncated, modelCalled, accepted, quarantined, null);
        }
    }

    /**
     * A document made ready for extraction, before anything decides its facts
     * (#38). {@code screened} is set when the document was quarantined without
     * a model call; otherwise {@code bounded} is the text a model or the rules
     * would see and {@code contentSha256} its SHA-256, the cache key's content.
     */
    public record Prepared(BoundedText bounded, String contentSha256, Outcome screened) {

        public boolean ready() {
            return screened == null;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ExtractionModel model;

    public Extractor(ExtractionModel model) {
        this.model = model;
    }

    /** Whether a call can be attempted; false when no credential is configured. */
    public boolean modelConfigured() {
        return model.configured();
    }

    public String modelId() {
        return model.id();
    }

    /**
     * Extracts facts from one document with the model. Throws
     * {@link ModelUnavailableException} when the model can't be asked; nothing
     * is decided in that case.
     */
    public Outcome extract(String text, String mimeType) {
        Prepared p = prepare(text, mimeType);
        return p.ready() ? ask(p) : p.screened();
    }

    /**
     * Extracts from raw document bytes, which must be UTF-8. Bytes that aren't
     * are quarantined as {@link Reason#NOT_TEXT} without a model call.
     */
    public Outcome extract(byte[] utf8, String mimeType) {
        Prepared p = prepare(utf8, mimeType);
        return p.ready() ? ask(p) : p.screened();
    }

    /**
     * Bounds and screens a document. Everything decided here is decided
     * without a model call: the media type, empty text, and hidden or control
     * characters.
     */
    public Prepared prepare(String text, String mimeType) {
        if (!isText(mimeType)) {
            return screened(poisoned(false, false, Reason.UNSUPPORTED_MEDIA_TYPE, "media type " + safe(mimeType)));
        }
        // Leading whitespace is dropped before the cut, so padding can't push the text out of the window.
        BoundedText bounded = BoundedText.of(text == null ? "" : text.stripLeading());
        if (bounded.text().isBlank()) {
            return screened(poisoned(bounded.truncated(), false, Reason.EMPTY_TEXT, "no text"));
        }
        int bad = firstHiddenChar(bounded.text());
        if (bad >= 0) {
            int cp = bounded.text().codePointAt(bad);
            return screened(poisoned(bounded.truncated(), false, Reason.NOT_TEXT, String.format(Locale.ROOT,
                    "%s character U+%04X at %d", Character.getType(cp) == Character.CONTROL ? "control" : "hidden",
                    cp, bad)));
        }
        return new Prepared(bounded, sha256(bounded.text()), null);
    }

    /** As {@link #prepare(String, String)}, from bytes that must be UTF-8. */
    public Prepared prepare(byte[] utf8, String mimeType) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8 == null ? new byte[0] : utf8))
                    .toString();
        } catch (CharacterCodingException e) {
            if (!isText(mimeType)) {
                return screened(poisoned(false, false, Reason.UNSUPPORTED_MEDIA_TYPE, "media type " + safe(mimeType)));
            }
            return screened(poisoned(false, false, Reason.NOT_TEXT, "not UTF-8"));
        }
        return prepare(text, mimeType);
    }

    /** Asks the model once about a prepared document and keeps only facts that pass every check. */
    public Outcome ask(Prepared p) {
        requireReady(p);
        BoundedText bounded = p.bounded();
        ModelReply reply = model.complete(bounded);
        ModelReply.Usage usage = reply.usage();
        if ("refusal".equals(reply.stopReason())) {
            return poisoned(bounded.truncated(), true, Reason.MODEL_REFUSED, clip(reply.text()), usage);
        }
        if (!"end_turn".equals(reply.stopReason())) {
            return poisoned(bounded.truncated(), true, Reason.MALFORMED_OUTPUT,
                    "stop reason " + reply.stopReason() + ": " + clip(reply.text()), usage);
        }
        List<Fact> facts;
        try {
            facts = parse(reply.text());
        } catch (IllegalArgumentException e) {
            return poisoned(bounded.truncated(), true, Reason.MALFORMED_OUTPUT,
                    e.getMessage() + ": " + clip(reply.text()), usage);
        }
        return checked(bounded, facts, true, usage);
    }

    /**
     * The fallback (#38, record 0023): the frozen {@link RulesBaseline} over
     * the same bounded text, with no model call. Its facts go through the same
     * checks as the model's, so a fallback fact is indexed only with an exact
     * citation too.
     */
    public Outcome rules(Prepared p) {
        requireReady(p);
        return checked(p.bounded(), RulesBaseline.extract(p.bounded().text()), false, null);
    }

    /**
     * A cached result reused for a prepared document with the same content,
     * with no model call. Every cached fact is checked again against this
     * document's text; if any fails, or there are more than {@link #MAX_FACTS},
     * the entry is poisoned and empty is returned, so it is never served.
     */
    public Optional<Outcome> reuse(Prepared p, List<Fact> cached) {
        requireReady(p);
        if (cached.size() > MAX_FACTS) {
            return Optional.empty();
        }
        for (Fact f : cached) {
            if (check(p.bounded().text(), f) != null) {
                return Optional.empty();
            }
        }
        return Optional.of(new Outcome(Status.ACCEPTED, p.bounded().truncated(), false, cached, List.of()));
    }

    private static Outcome checked(BoundedText bounded, List<Fact> facts, boolean modelCalled,
            ModelReply.Usage usage) {
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
        return new Outcome(Status.ACCEPTED, bounded.truncated(), modelCalled, accepted, quarantined, usage);
    }

    private static void requireReady(Prepared p) {
        if (!p.ready()) {
            throw new IllegalArgumentException("document was screened out: " + p.screened().status());
        }
    }

    private static Prepared screened(Outcome o) {
        return new Prepared(null, null, o);
    }

    /** SHA-256 of the text's UTF-8 bytes, in lower-case hex. */
    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
        if (!containsWord(f.citation(), f.value())) {
            return Reason.VALUE_NOT_IN_CITATION;
        }
        return null;
    }

    /**
     * Whether {@code value} is non-blank and appears in {@code citation} exactly,
     * case included, as whole words: a match may not start or end inside a word,
     * so {@code $5} isn't in {@code $500} and {@code 1 May} isn't in {@code 11 May}.
     * The value served is then text the document holds as written.
     */
    static boolean containsWord(String citation, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = citation.indexOf(value); i >= 0; i = citation.indexOf(value, i + 1)) {
            int end = i + value.length();
            boolean startsInWord = i > 0 && isWordChar(citation.codePointBefore(i))
                    && isWordChar(value.codePointAt(0));
            boolean endsInWord = end < citation.length() && isWordChar(citation.codePointAt(end))
                    && isWordChar(value.codePointBefore(value.length()));
            if (!startsInWord && !endsInWord) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWordChar(int cp) {
        return Character.isLetterOrDigit(cp);
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

    /**
     * Index of the first character that can carry text a reader doesn't see, or
     * -1: a control character other than tab, line feed and carriage return; a
     * Unicode tag character (U+E0000 to U+E007F, used to smuggle hidden
     * instructions); a bidirectional embedding, override or isolate (U+202A to
     * U+202E, U+2066 to U+2069); or an invisible operator (U+2061 to U+2064).
     * Zero-width joiners and marks used by real scripts are allowed.
     */
    static int firstHiddenChar(String s) {
        for (int i = 0; i < s.length(); i += Character.charCount(s.codePointAt(i))) {
            int cp = s.codePointAt(i);
            boolean control = Character.getType(cp) == Character.CONTROL && cp != '\t' && cp != '\n' && cp != '\r';
            boolean hidden = (cp >= 0xE0000 && cp <= 0xE007F) || (cp >= 0x202A && cp <= 0x202E)
                    || (cp >= 0x2066 && cp <= 0x2069) || (cp >= 0x2061 && cp <= 0x2064);
            if (control || hidden) {
                return i;
            }
        }
        return -1;
    }

    /** A whole-document quarantine decided before any model call, such as a stored body that can't be read. */
    public static Outcome quarantine(Reason reason, String detail) {
        return poisoned(false, false, reason, detail);
    }

    private static Outcome poisoned(boolean truncated, boolean modelCalled, Reason r, String detail) {
        return poisoned(truncated, modelCalled, r, detail, null);
    }

    private static Outcome poisoned(boolean truncated, boolean modelCalled, Reason r, String detail,
            ModelReply.Usage usage) {
        return new Outcome(Status.QUARANTINED, truncated, modelCalled, List.of(),
                List.of(new Quarantined(r, null, detail)), usage);
    }

    static String clip(String s) {
        return s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL);
    }

    private static String safe(String s) {
        return s == null ? "(none)" : clip(s);
    }
}
