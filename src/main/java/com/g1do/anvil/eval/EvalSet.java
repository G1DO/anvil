package com.g1do.anvil.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A frozen eval set: its manifest, the quality split, the injection subset and
 * the frozen baseline's predictions (#36, record 0021).
 *
 * <p>{@link #load} refuses a set that doesn't match its manifest: a file whose
 * SHA-256 or record count differs, a missing manifest entry, a document over
 * {@value #MAX_CHARS} characters, a gold value that isn't in its document, an
 * exact or near duplicate, or a baseline that doesn't cover every document.
 */
public final class EvalSet {

    /** Extraction (#37) truncates a document to this many characters before any model call. */
    public static final int MAX_CHARS = 500;
    public static final Set<String> FIELDS = Set.of("party", "person", "date", "amount");

    static final String MANIFEST = "manifest.json";
    static final String QUALITY = "quality.jsonl";
    static final String INJECTION = "injection.jsonl";
    static final String BASELINE = "baseline.jsonl";

    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** A fact a document states: one of {@link #FIELDS} and its value as written. */
    public record GoldFact(String field, String value) {
    }

    /** A document of the quality split with every fact it states. */
    public record QualityDoc(String id, String text, List<GoldFact> facts) {
        public QualityDoc {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
    }

    /**
     * A document of the injection subset. {@code attack} says what the embedded
     * instruction tries to do; the attack succeeds when an emitted fact's value
     * contains {@code canary}.
     */
    public record AttackCase(String id, String text, String attack, String canary) {
    }

    /** Paired bootstrap settings, frozen with the set. */
    public record Bootstrap(int resamples, long seed, double confidence) {
    }

    private final String name;
    private final JsonNode manifest;
    private final List<QualityDoc> quality;
    private final List<AttackCase> injection;
    private final Map<String, Prediction> baseline;
    private final Bootstrap bootstrap;

    private EvalSet(String name, JsonNode manifest, List<QualityDoc> quality, List<AttackCase> injection,
            Map<String, Prediction> baseline, Bootstrap bootstrap) {
        this.name = name;
        this.manifest = manifest;
        this.quality = List.copyOf(quality);
        this.injection = List.copyOf(injection);
        this.baseline = Map.copyOf(baseline);
        this.bootstrap = bootstrap;
    }

    public String name() {
        return name;
    }

    public JsonNode manifest() {
        return manifest;
    }

    public List<QualityDoc> quality() {
        return quality;
    }

    public List<AttackCase> injection() {
        return injection;
    }

    /** The frozen baseline's predictions, by document id. */
    public Map<String, Prediction> baseline() {
        return baseline;
    }

    public Bootstrap bootstrap() {
        return bootstrap;
    }

    /** Every document id, quality split first, in file order. */
    public List<String> docIds() {
        List<String> ids = new ArrayList<>();
        quality.forEach(d -> ids.add(d.id()));
        injection.forEach(c -> ids.add(c.id()));
        return ids;
    }

    /** Loads the set in {@code dir} and checks it against its manifest. */
    public static EvalSet load(Path dir) throws IOException {
        List<String> problems = new ArrayList<>();
        JsonNode manifest = JSON.readTree(dir.resolve(MANIFEST).toFile());

        for (String file : List.of(QUALITY, INJECTION, BASELINE)) {
            JsonNode entry = manifest.path("files").path(file);
            byte[] bytes = Files.readAllBytes(dir.resolve(file));
            String sha = Text.sha256(bytes);
            if (!sha.equals(entry.path("sha256").asText())) {
                problems.add(file + ": sha256 " + sha + " is not the manifest's " + entry.path("sha256").asText()
                        + "; a frozen set never changes, so freeze a new version instead");
            }
            long records = jsonlLines(new String(bytes, StandardCharsets.UTF_8)).size();
            if (records != entry.path("records").asLong(-1)) {
                problems.add(file + ": " + records + " records, manifest says " + entry.path("records"));
            }
        }

        List<QualityDoc> quality = readJsonl(dir.resolve(QUALITY), QualityDoc.class);
        List<AttackCase> injection = readJsonl(dir.resolve(INJECTION), AttackCase.class);

        problems.addAll(checkManifest(manifest, quality, injection));
        problems.addAll(checkDocuments(quality, injection, manifest));

        Set<String> ids = new HashSet<>();
        quality.forEach(d -> ids.add(d.id()));
        injection.forEach(c -> ids.add(c.id()));
        Map<String, Prediction> baseline = Map.of();
        try {
            baseline = readPredictions(dir.resolve(BASELINE), ids);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }

        JsonNode b = manifest.path("bootstrap");
        Bootstrap bootstrap = new Bootstrap(b.path("resamples").asInt(), b.path("seed").asLong(),
                b.path("confidence").asDouble());
        if (bootstrap.resamples() < 1000) {
            problems.add("manifest bootstrap.resamples must be at least 1000");
        }
        if (bootstrap.confidence() != Stats.CONFIDENCE) {
            problems.add("manifest bootstrap.confidence must be " + Stats.CONFIDENCE);
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("eval set " + dir + " doesn't match its manifest:\n  - "
                    + String.join("\n  - ", problems));
        }
        return new EvalSet(manifest.path("name").asText(), manifest, quality, injection, baseline, bootstrap);
    }

    /**
     * Reads an extractor's predictions: one JSON line per document of the set.
     * A missing, unknown or repeated document is an error, so an incomplete run
     * can't pass the gate by leaving hard documents out.
     */
    public static Map<String, Prediction> readPredictions(Path file, Set<String> docIds) throws IOException {
        Map<String, Prediction> out = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Prediction p : readJsonl(file, Prediction.class)) {
            if (!docIds.contains(p.doc())) {
                problems.add("unknown document " + p.doc());
            } else if (out.putIfAbsent(p.doc(), p) != null) {
                problems.add("document " + p.doc() + " appears twice");
            }
        }
        for (String id : docIds) {
            if (!out.containsKey(id)) {
                problems.add("no prediction for document " + id);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(file + ": " + String.join("; ", problems));
        }
        return out;
    }

    static List<String> checkManifest(JsonNode m, List<QualityDoc> quality, List<AttackCase> injection) {
        List<String> problems = new ArrayList<>();
        for (String path : List.of("/name", "/sources", "/frozen/date", "/frozen/commit", "/dedup/method",
                "/near_duplicates/method", "/near_duplicates/handling", "/never_tuned_on", "/baseline")) {
            if (m.at(path).asText().isBlank()) {
                problems.add("manifest is missing " + path);
            }
        }
        if (!m.at("/frozen/date").asText().matches("\\d{4}-\\d{2}-\\d{2}")) {
            problems.add("manifest /frozen/date must be YYYY-MM-DD");
        }
        if (!m.at("/frozen/commit").asText().matches("[0-9a-f]{40}")) {
            problems.add("manifest /frozen/commit must be a full commit SHA");
        }
        if (quality.isEmpty() || injection.isEmpty()) {
            problems.add("the quality split and the injection subset must each hold at least one document");
        }
        int facts = quality.stream().mapToInt(d -> d.facts().size()).sum();
        expect(problems, m, "/size/quality_docs", quality.size());
        expect(problems, m, "/size/gold_facts", facts);
        expect(problems, m, "/size/injection_cases", injection.size());
        expect(problems, m, "/size/max_chars", MAX_CHARS);
        double threshold = m.at("/near_duplicates/threshold").asDouble(-1);
        if (threshold <= 0 || threshold >= 1) {
            problems.add("manifest /near_duplicates/threshold must be between 0 and 1");
        }
        if (m.at("/near_duplicates/shingle_words").asInt() < 2) {
            problems.add("manifest /near_duplicates/shingle_words must be at least 2");
        }
        return problems;
    }

    private static void expect(List<String> problems, JsonNode m, String path, int actual) {
        if (m.at(path).asInt(-1) != actual) {
            problems.add("manifest " + path + " is " + m.at(path) + ", the set has " + actual);
        }
    }

    static List<String> checkDocuments(List<QualityDoc> quality, List<AttackCase> injection, JsonNode m) {
        List<String> problems = new ArrayList<>();
        Map<String, String> texts = new LinkedHashMap<>();
        for (QualityDoc d : quality) {
            if (texts.put(d.id(), d.text()) != null) {
                problems.add("document id " + d.id() + " appears twice");
            }
            if (d.facts().isEmpty()) {
                problems.add(d.id() + ": no gold facts");
            }
            Set<String> seen = new HashSet<>();
            for (GoldFact f : d.facts()) {
                if (!FIELDS.contains(f.field())) {
                    problems.add(d.id() + ": unknown field " + f.field());
                }
                if (f.value() == null || f.value().isBlank() || !d.text().contains(f.value())) {
                    problems.add(d.id() + ": gold value '" + f.value() + "' is not written in the document");
                } else if (!seen.add(f.field() + "\u0000" + Text.value(f.value()))) {
                    problems.add(d.id() + ": gold fact " + f.field() + "=" + f.value() + " appears twice");
                }
            }
        }
        for (AttackCase c : injection) {
            if (texts.put(c.id(), c.text()) != null) {
                problems.add("document id " + c.id() + " appears twice");
            }
            if (c.attack() == null || c.attack().isBlank()) {
                problems.add(c.id() + ": no attack description");
            }
            if (c.canary() == null || c.canary().isBlank() || !c.text().contains(c.canary())) {
                problems.add(c.id() + ": canary '" + c.canary() + "' is not in the document");
            }
        }

        Map<String, String> exact = new HashMap<>();
        for (Map.Entry<String, String> e : texts.entrySet()) {
            String text = e.getValue();
            if (text == null || text.isBlank() || text.length() > MAX_CHARS) {
                problems.add(e.getKey() + ": text must be 1 to " + MAX_CHARS + " characters, is "
                        + (text == null ? 0 : text.length()));
                continue;
            }
            String other = exact.putIfAbsent(Text.sha256(Text.normalize(text)), e.getKey());
            if (other != null) {
                problems.add(e.getKey() + " is an exact duplicate of " + other);
            }
        }

        for (NearDuplicate n : nearDuplicates(texts, m.at("/near_duplicates/shingle_words").asInt(3),
                m.at("/near_duplicates/threshold").asDouble(0.5))) {
            problems.add(n.b() + " is a near duplicate of " + n.a() + " (Jaccard " + n.jaccard() + ")");
        }
        return problems;
    }

    record NearDuplicate(String a, String b, double jaccard) {
    }

    /** Pairs whose word-shingle Jaccard similarity is at or above {@code threshold}. */
    static List<NearDuplicate> nearDuplicates(Map<String, String> texts, int words, double threshold) {
        List<NearDuplicate> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : similarities(texts, words).entrySet()) {
            if (e.getValue() >= threshold) {
                String[] pair = e.getKey().split("\u0000");
                out.add(new NearDuplicate(pair[0], pair[1], e.getValue()));
            }
        }
        return out;
    }

    /** Every pair's word-shingle Jaccard similarity, keyed "a\0b" with a before b in input order. */
    static Map<String, Double> similarities(Map<String, String> texts, int words) {
        List<String> ids = new ArrayList<>(texts.keySet());
        List<Set<String>> sh = ids.stream().map(id -> Text.shingles(texts.get(id) == null ? "" : texts.get(id), words))
                .toList();
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                out.put(ids.get(i) + "\u0000" + ids.get(j), Text.jaccard(sh.get(i), sh.get(j)));
            }
        }
        return out;
    }

    static <T> List<T> readJsonl(Path file, Class<T> type) throws IOException {
        List<T> out = new ArrayList<>();
        int n = 0;
        for (String line : jsonlLines(Files.readString(file))) {
            n++;
            try {
                out.add(JSON.readValue(line, type));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(file + " record " + n + ": " + e.getOriginalMessage(), e);
            }
        }
        return out;
    }

    private static List<String> jsonlLines(String content) {
        return content.lines().filter(l -> !l.isBlank()).toList();
    }
}
