package com.g1do.anvil.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.g1do.anvil.eval.Fact;
import com.g1do.anvil.eval.Prediction;
import com.g1do.anvil.extract.Prompt;

/**
 * Measures what a full-text (GIN) index on fact values costs, against the
 * budget record 0025 set before measuring (#79).
 *
 * <p>Each variant gets a fresh database on {@link LoadDb} at the latest
 * schema: no index, the index with {@code fastupdate} off, and with it on.
 * Each then stores one soak day, {@value #EXTRACTIONS} extractions, exactly
 * as {@code ExtractionService.store} does: one transaction as
 * {@code anvil_app} with the tenant set, an {@code extractions} row and one
 * {@code extracted_facts} row per fact. The facts are the accepted facts of
 * the committed live run over the frozen full-v1 set, document by document,
 * spread over {@value #TENANTS} tenants. A throwaway run first warms the
 * JVM and the server.
 *
 * <p>Writes {@code gin-budget.json}, whose {@code within_budget} the load
 * rig reads before it writes soak numbers, and {@code report.md}.
 * Usage: {@code GinBudget <out dir> [run predictions] [extractions]}; run as
 * {@code task load:gin}, which uses the defaults. A shorter run is for trying
 * the harness, and its verdict says how many extractions it measured.
 */
public final class GinBudget {

    static final int EXTRACTIONS = 10_000;
    static final int WARMUP = 2_000;
    static final int TENANTS = 20;
    static final Path RUN = Path.of("eval/runs/extract-v1-deepseek-flash-full-v1/predictions.jsonl");
    static final String INDEX = "extracted_facts_value_fts_idx";

    /** Record 0025's budget, set before measuring. */
    static final double MAX_P95_OVERHEAD_MS = 2.0;
    static final double MAX_STORE_MS = 100.0;
    static final double MAX_INDEX_MIB_PER_10K = 10.0;
    static final double MAX_INDEX_TO_HEAP = 1.0;
    static final double MAX_WAL_RATIO = 2.0;

    private static final ObjectMapper JSON = new ObjectMapper();

    private GinBudget() {
    }

    /** One variant's measurement. */
    record Variant(String name, String index, int extractions, long facts, double p50, double p95, double p99,
            double max, long heapBytes, long indexBytes, long walBytes, long maxPendingPages, String plan,
            double queryMedianMs) {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "load/runs/gin-budget");
        Path run = args.length > 1 ? Path.of(args[1]) : RUN;
        int n = args.length > 2 ? Integer.parseInt(args[2]) : EXTRACTIONS;
        List<List<Fact>> facts = facts(run);
        Map<String, Object> result;
        try (LoadDb db = new LoadDb().start()) {
            measure(db, "warmup", null, facts, Math.min(WARMUP, n));
            Variant none = measure(db, "none", null, facts, n);
            Variant off = measure(db, "gin_fastupdate_off", "off", facts, n);
            Variant on = measure(db, "gin_fastupdate_on", "on", facts, n);
            String version;
            try (Connection c = db.connect("postgres"); ResultSet r = c.createStatement().executeQuery("SHOW server_version")) {
                r.next();
                version = r.getString(1);
            }
            result = verdict(none, off, on, version, facts.size(), run);
        }
        Files.createDirectories(out);
        Files.writeString(out.resolve("gin-budget.json"),
                JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(result) + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(out.resolve("report.md"), report(result), StandardCharsets.UTF_8);
        System.out.print(report(result));
    }

    /** The accepted facts of each quality document of the run, in file order. */
    static List<List<Fact>> facts(Path run) throws IOException {
        List<List<Fact>> out = new ArrayList<>();
        for (String line : Files.readAllLines(run, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Prediction p = JSON.readValue(line, Prediction.class);
            if (p.doc().startsWith("q")) {
                out.add(p.facts());
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(run + " holds no quality documents");
        }
        return out;
    }

    static Variant measure(LoadDb db, String name, String fastupdate, List<List<Fact>> facts, int n)
            throws SQLException {
        String database = db.migrated("gin_" + name);
        try (Connection c = db.connect(database)) {
            Statement s = c.createStatement();
            s.execute("CREATE EXTENSION IF NOT EXISTS pgstattuple");
            s.execute("DROP INDEX IF EXISTS " + INDEX);
            if (fastupdate != null) {
                s.execute("CREATE INDEX " + INDEX + " ON extracted_facts USING gin (to_tsvector('simple', value))"
                        + " WITH (fastupdate = " + fastupdate + ")");
            }
            seed(s, n);
            s.execute("CHECKPOINT");
            long walStart = lsn(c);
            List<Long> nanos = new ArrayList<>(n);
            long maxPending = 0;
            c.setAutoCommit(false);
            try (PreparedStatement role = c.prepareStatement("SET LOCAL ROLE anvil_app");
                    PreparedStatement tenant = c.prepareStatement("SELECT set_config('app.tenant', ?, true)");
                    PreparedStatement extraction = c.prepareStatement(
                            "INSERT INTO extractions (tenant_id, document_id, version_id, model, prompt_sha256, status, "
                                    + "truncated, source, content_sha256) VALUES (?, ?, ?, 'deepseek-flash', ?, "
                                    + "'ACCEPTED', false, 'MODEL', ?) RETURNING id");
                    PreparedStatement fact = c.prepareStatement(
                            "INSERT INTO extracted_facts (tenant_id, extraction_id, ordinal, field, value, citation) "
                                    + "VALUES (?, ?, ?, ?, ?, ?)")) {
                for (int i = 0; i < n; i++) {
                    String t = tenant(i);
                    long t0 = System.nanoTime();
                    role.execute();
                    tenant.setString(1, t);
                    tenant.execute();
                    extraction.setString(1, t);
                    extraction.setObject(2, id("d", i));
                    extraction.setObject(3, id("v", i));
                    extraction.setString(4, Prompt.SHA256);
                    extraction.setString(5, sha(i));
                    UUID eid;
                    try (ResultSet r = extraction.executeQuery()) {
                        r.next();
                        eid = r.getObject(1, UUID.class);
                    }
                    int ordinal = 0;
                    for (Fact f : facts.get(i % facts.size())) {
                        fact.setString(1, t);
                        fact.setObject(2, eid);
                        fact.setInt(3, ordinal++);
                        fact.setString(4, f.field());
                        fact.setString(5, f.value());
                        fact.setString(6, f.citation());
                        fact.executeUpdate();
                    }
                    c.commit();
                    nanos.add(System.nanoTime() - t0);
                    if (fastupdate != null && i % 250 == 249) {
                        maxPending = Math.max(maxPending, pending(c));
                    }
                }
            }
            c.setAutoCommit(true);
            long wal = lsn(c) - walStart;
            if (fastupdate != null) {
                maxPending = Math.max(maxPending, pending(c));
            }
            long rows = scalar(c, "SELECT count(*) FROM extracted_facts");
            long heap = scalar(c, "SELECT pg_relation_size('extracted_facts')");
            long index = fastupdate == null ? 0 : scalar(c, "SELECT pg_relation_size('" + INDEX + "')");
            String plan = "";
            double query = 0;
            if (fastupdate != null) {
                plan = plan(c);
                query = queryMedianMs(c);
            }
            List<Long> sorted = nanos.stream().sorted().toList();
            return new Variant(name, fastupdate == null ? "none" : "gin (fastupdate = " + fastupdate + ")", n, rows,
                    ms(rank(sorted, 0.50)), ms(rank(sorted, 0.95)), ms(rank(sorted, 0.99)),
                    ms(sorted.get(sorted.size() - 1)), heap, index, wal, maxPending, plan, query);
        }
    }

    private static void seed(Statement s, int n) throws SQLException {
        s.execute("INSERT INTO tenants (id) SELECT 't_gin_' || lpad(i::text, 2, '0') FROM generate_series(0, "
                + (TENANTS - 1) + ") i");
        s.execute("INSERT INTO documents (id, tenant_id, title, mime_type) SELECT md5('d' || i)::uuid, "
                + "'t_gin_' || lpad((i % " + TENANTS + ")::text, 2, '0'), 'doc ' || i, 'text/plain' "
                + "FROM generate_series(0, " + (n - 1) + ") i");
        s.execute("INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) "
                + "SELECT md5('v' || i)::uuid, 't_gin_' || lpad((i % " + TENANTS + ")::text, 2, '0'), "
                + "md5('d' || i)::uuid, 1, encode(sha256(('v' || i)::bytea), 'hex') FROM generate_series(0, "
                + (n - 1) + ") i");
        s.execute("ANALYZE");
    }

    private static String tenant(int i) {
        return String.format(Locale.ROOT, "t_gin_%02d", i % TENANTS);
    }

    /** Matches the seed's {@code md5(prefix || i)::uuid}. */
    private static UUID id(String prefix, int i) {
        try {
            byte[] md5 = java.security.MessageDigest.getInstance("MD5")
                    .digest((prefix + i).getBytes(StandardCharsets.UTF_8));
            String hex = java.util.HexFormat.of().formatHex(md5);
            return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                    + hex.substring(16, 20) + "-" + hex.substring(20));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha(int i) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("content " + i).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long lsn(Connection c) throws SQLException {
        return scalar(c, "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint");
    }

    private static long pending(Connection c) throws SQLException {
        return scalar(c, "SELECT pending_pages FROM pgstatginindex('" + INDEX + "')");
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (ResultSet r = c.createStatement().executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    /** The plan for a tenant's full-text lookup, as the runtime role. */
    private static String plan(Connection c) throws SQLException {
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.execute("SET LOCAL ROLE anvil_app");
            s.execute("SELECT set_config('app.tenant', 't_gin_03', true)");
            StringBuilder plan = new StringBuilder();
            try (ResultSet r = s.executeQuery("EXPLAIN SELECT id FROM extracted_facts WHERE "
                    + "to_tsvector('simple', value) @@ to_tsquery('simple', 'halvane')")) {
                while (r.next()) {
                    plan.append(r.getString(1).strip()).append('\n');
                }
            }
            c.rollback();
            return plan.toString().strip();
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** Median time of a tenant's full-text lookup over 21 words, as the runtime role. */
    private static double queryMedianMs(Connection c) throws SQLException {
        List<Long> nanos = new ArrayList<>();
        String[] words = { "halvane", "brightmoor", "quillon", "ostervik", "calderwick", "fennimore", "glenmoor",
            "brackwater", "ridgeline", "bellweather", "larkspur", "thornbury", "kestrel", "solberg", "brenner",
            "raman", "tanaka", "ltd", "gmbh", "eur", "march" };
        c.setAutoCommit(false);
        try (PreparedStatement q = c.prepareStatement("SELECT count(*) FROM extracted_facts WHERE "
                + "to_tsvector('simple', value) @@ to_tsquery('simple', ?)"); Statement s = c.createStatement()) {
            for (String w : words) {
                s.execute("SET LOCAL ROLE anvil_app");
                s.execute("SELECT set_config('app.tenant', 't_gin_03', true)");
                q.setString(1, w);
                long t0 = System.nanoTime();
                try (ResultSet r = q.executeQuery()) {
                    r.next();
                }
                nanos.add(System.nanoTime() - t0);
                c.rollback();
            }
        } finally {
            c.setAutoCommit(true);
        }
        return ms(rank(nanos.stream().sorted().toList(), 0.50));
    }

    static long rank(List<Long> sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    /** Checks each GIN variant against the budget and picks the first that meets it, fastupdate off first. */
    static Map<String, Object> verdict(Variant none, Variant off, Variant on, String version, int docs, Path run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("measured_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        out.put("postgres", LoadDb.IMAGE + " (server " + version + "), default settings");
        out.put("extractions", none.extractions());
        out.put("workload", none.extractions() + " extractions over " + TENANTS + " tenants; facts from the "
                + "accepted facts of " + docs + " quality documents of " + run + ", reused in order");
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("max_p95_overhead_ms", MAX_P95_OVERHEAD_MS);
        budget.put("max_store_ms", MAX_STORE_MS);
        budget.put("max_index_mib_per_10k", MAX_INDEX_MIB_PER_10K);
        budget.put("max_index_to_heap", MAX_INDEX_TO_HEAP);
        budget.put("max_wal_ratio", MAX_WAL_RATIO);
        out.put("budget", budget);
        Map<String, Object> variants = new LinkedHashMap<>();
        String chosen = null;
        for (Variant v : List.of(none, off, on)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", v.index());
            m.put("extractions", v.extractions());
            m.put("facts", v.facts());
            Map<String, Object> store = new LinkedHashMap<>();
            store.put("p50", v.p50());
            store.put("p95", v.p95());
            store.put("p99", v.p99());
            store.put("max", v.max());
            m.put("store_ms", store);
            m.put("heap_bytes", v.heapBytes());
            m.put("index_bytes", v.indexBytes());
            m.put("wal_bytes", v.walBytes());
            if (v != none) {
                Map<String, Object> checks = checks(none, v);
                m.put("max_pending_pages", v.maxPendingPages());
                m.put("lookup_plan", v.plan());
                m.put("lookup_median_ms", v.queryMedianMs());
                m.put("checks", checks);
                boolean within = checks.values().stream().allMatch(c -> Boolean.TRUE.equals(((Map<?, ?>) c).get("ok")));
                m.put("within_budget", within);
                if (within && chosen == null) {
                    chosen = v.name();
                }
            }
            variants.put(v.name(), m);
        }
        out.put("variants", variants);
        out.put("chosen", chosen == null ? "none" : chosen);
        out.put("within_budget", chosen != null);
        return out;
    }

    static Map<String, Object> checks(Variant none, Variant v) {
        Map<String, Object> c = new LinkedHashMap<>();
        double overhead = Math.round((v.p95() - none.p95()) * 100) / 100.0;
        c.put("p95_overhead_ms", check(overhead, MAX_P95_OVERHEAD_MS));
        c.put("max_store_ms", check(v.max(), MAX_STORE_MS));
        double mib = Math.round(v.indexBytes() / 1048576.0 * 10_000 / v.extractions() * 100) / 100.0;
        c.put("index_mib_per_10k", check(mib, MAX_INDEX_MIB_PER_10K));
        double toHeap = Math.round((double) v.indexBytes() / v.heapBytes() * 100) / 100.0;
        c.put("index_to_heap", check(toHeap, MAX_INDEX_TO_HEAP));
        double wal = Math.round((double) v.walBytes() / none.walBytes() * 100) / 100.0;
        c.put("wal_ratio", check(wal, MAX_WAL_RATIO));
        return c;
    }

    private static Map<String, Object> check(double value, double max) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("max", max);
        m.put("ok", value <= max);
        return m;
    }

    @SuppressWarnings("unchecked")
    static String report(Map<String, Object> r) {
        StringBuilder s = new StringBuilder();
        s.append("# Full-text (GIN) index budget\n\n");
        s.append("Measured ").append(r.get("measured_at")).append(" on ").append(r.get("postgres")).append(".\n");
        s.append("Workload: ").append(r.get("workload")).append(".\n");
        s.append("Each variant ran on a fresh database at the latest schema, after a throwaway warm-up run. "
                + "Budget: record 0025, set before measuring.\n\n");
        s.append("| Variant | Store p50 | p95 | p99 | max | Facts table | Index | WAL | Max pending pages |\n");
        s.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        Map<String, Object> variants = (Map<String, Object>) r.get("variants");
        for (Map.Entry<String, Object> e : variants.entrySet()) {
            Map<String, Object> v = (Map<String, Object>) e.getValue();
            Map<String, Object> ms = (Map<String, Object>) v.get("store_ms");
            s.append("| ").append(v.get("index")).append(" | ").append(ms.get("p50")).append(" ms | ")
                    .append(ms.get("p95")).append(" ms | ").append(ms.get("p99")).append(" ms | ").append(ms.get("max"))
                    .append(" ms | ").append(mib(v.get("heap_bytes"))).append(" | ").append(mib(v.get("index_bytes")))
                    .append(" | ").append(mib(v.get("wal_bytes"))).append(" | ")
                    .append(v.getOrDefault("max_pending_pages", "-")).append(" |\n");
        }
        for (Map.Entry<String, Object> e : variants.entrySet()) {
            Map<String, Object> v = (Map<String, Object>) e.getValue();
            if (!v.containsKey("checks")) {
                continue;
            }
            s.append("\n## ").append(v.get("index")).append(": ")
                    .append(Boolean.TRUE.equals(v.get("within_budget")) ? "within budget" : "over budget").append("\n\n");
            s.append("| Check | Measured | Budget | Result |\n| --- | --- | --- | --- |\n");
            for (Map.Entry<String, Object> c : ((Map<String, Object>) v.get("checks")).entrySet()) {
                Map<String, Object> m = (Map<String, Object>) c.getValue();
                s.append("| ").append(c.getKey()).append(" | ").append(m.get("value")).append(" | at most ")
                        .append(m.get("max")).append(" | ").append(Boolean.TRUE.equals(m.get("ok")) ? "pass" : "FAIL")
                        .append(" |\n");
            }
            s.append("\nA tenant's lookup of one word: median ").append(v.get("lookup_median_ms"))
                    .append(" ms. Plan:\n\n```\n").append(v.get("lookup_plan")).append("\n```\n");
        }
        s.append("\nVerdict: ").append(Boolean.TRUE.equals(r.get("within_budget"))
                ? "within budget with " + r.get("chosen") + ". Soak numbers may be published with this index in place."
                : "no variant meets the budget. The residual is above; no soak numbers are claimed.").append('\n');
        return s.toString();
    }

    private static String mib(Object bytes) {
        return String.format(Locale.ROOT, "%.2f MiB", ((Number) bytes).longValue() / 1048576.0);
    }
}
