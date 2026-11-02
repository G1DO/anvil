package com.g1do.anvil.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.g1do.anvil.AnvilApplication;
import com.g1do.anvil.extract.ExtractionModel;

/**
 * Load runs on AI extraction (#79): 140 requests per second, a 10x flood,
 * and a soak of 10,000 documents a day.
 *
 * <p>Each run starts a fresh {@link LoadDb}, the app in this JVM as it is
 * configured for deployment except for the model, which is
 * {@link LatencyStubModel}, and k6 (pinned by digest) on the host network
 * running {@code load/extract.js}. Twenty tenants each get a budget large
 * enough never to run out, and the kill-switch is on, so every extraction is
 * a model call. Table sizes and tuple counts are read before the run, at its
 * end, and again after the database has been idle long enough for
 * autovacuum to visit, so the report shows what the run left behind.
 *
 * <p>The soak refuses to run, and so writes no soak numbers, unless record
 * 0025's GIN budget verdict says the budget is met and the schema carries
 * the index that verdict chose ({@link #requireGinBudget}).
 *
 * <p>Usage: {@code LoadRig <steady|flood|soak> <out dir>}; run as
 * {@code task load:steady}, {@code task load:flood} or {@code task load:soak}.
 */
public final class LoadRig {

    static final String K6 = "grafana/k6:1.4.2@sha256:3656673de3f30424e8ebcfa46acd9558d83b6a43612d0f668ffeac953950c6c7";
    static final int PORT = 18080;
    static final int TENANTS = 20;
    static final Path GIN_VERDICT = Path.of("load/runs/gin-budget/gin-budget.json");
    static final Duration SETTLE = Duration.ofSeconds(90);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A run's offered load. */
    record Scenario(String name, String what, Map<String, String> k6) {

        static Scenario of(String name) {
            return switch (name) {
                case "steady" -> new Scenario(name, "140 extraction requests per second for 5 minutes",
                        env("RATE", "140", "DURATION", "5m", "PRE_VUS", "300", "MAX_VUS", "1500"));
                case "flood" -> new Scenario(name, "a 10x flood: 1,400 extraction requests per second for 60 seconds, "
                        + "then 14 per second for 60 seconds to show recovery",
                        env("RATE", "1400", "DURATION", "60s", "PRE_VUS", "1500", "MAX_VUS", "3000",
                                "RECOVERY_RATE", "14", "RECOVERY_DURATION", "60s", "RECOVERY_START", "100s"));
                case "soak" -> new Scenario(name, "10,000 documents, the soak target for a day, spread evenly over "
                        + "60 minutes (2.8 per second)",
                        env("RATE", "10000", "TIME_UNIT", "60m", "DURATION", "60m", "PRE_VUS", "20", "MAX_VUS", "200"));
                case "smoke" -> new Scenario(name, "a check of the rig itself: 5 requests per second for 10 seconds",
                        env("RATE", "5", "DURATION", "10s", "PRE_VUS", "10", "MAX_VUS", "50"));
                default -> throw new IllegalArgumentException("scenario must be steady, flood, soak or smoke: " + name);
            };
        }

        private static Map<String, String> env(String... kv) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put(kv[i], kv[i + 1]);
            }
            return m;
        }
    }

    /** The app's model: the stub, preferred over the configured DeepSeek model. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubModelConfig {

        @Bean
        @Primary
        ExtractionModel loadStubModel() {
            return new LatencyStubModel();
        }
    }

    private LoadRig() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: LoadRig <steady|flood|soak> <out dir>");
            System.exit(2);
        }
        Scenario sc = Scenario.of(args[0]);
        Path out = Path.of(args[1]);
        JsonNode gin = sc.name().equals("soak") ? requireGinBudget(GIN_VERDICT) : null;
        Files.createDirectories(out);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", sc.name());
        result.put("offered", sc.what());
        result.put("k6", K6);
        result.put("postgres", LoadDb.IMAGE + ", default settings");
        result.put("model", "LatencyStubModel: median " + (long) LatencyStubModel.MEDIAN_MS + " ms, p95 "
                + (long) LatencyStubModel.P95_MS + " ms, at most " + (long) LatencyStubModel.MAX_MS
                + " ms (the cost run's model-call latency); no network");
        result.put("host", Runtime.getRuntime().availableProcessors() + " CPUs, app, Postgres and k6 on one host");
        try (LoadDb db = new LoadDb().start()) {
            String database = db.migrated("anvil");
            try (Connection c = db.connect(database)) {
                if (gin != null) {
                    requireIndex(c, gin);
                    result.put("gin_budget", Map.of("measured_at", gin.path("measured_at").asText(), "chosen",
                            gin.path("chosen").asText(), "verdict", GIN_VERDICT.toString()));
                }
                seed(c);
                ConfigurableApplicationContext app = startApp(db.url(database));
                try {
                    Map<String, Map<String, Object>> before = tables(c);
                    result.put("started_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
                    int exit = k6(sc, out);
                    result.put("finished_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
                    result.put("k6_exit", exit);
                    // Each backend publishes its statistics within about ten seconds of going idle.
                    Thread.sleep(12_000);
                    Map<String, Map<String, Object>> end = tables(c);
                    Thread.sleep(SETTLE.toMillis());
                    Map<String, Map<String, Object>> settled = tables(c);
                    result.put("model_calls", app.getBean(LatencyStubModel.class).calls());
                    result.put("tables", growth(before, end, settled, bloat(c)));
                } finally {
                    app.close();
                }
            }
        }
        result.put("load", summary(JSON.readTree(out.resolve("k6-summary.json").toFile())));
        Files.writeString(out.resolve("result.json"),
                JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(result) + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(out.resolve("report.md"), report(result), StandardCharsets.UTF_8);
        System.out.print(report(result));
        System.exit(0);
    }

    /**
     * Record 0025: no soak numbers before the GIN budget is measured and met.
     * Returns the verdict, or throws when it's missing or says the budget
     * isn't met.
     */
    static JsonNode requireGinBudget(Path verdict) throws IOException {
        if (!Files.exists(verdict)) {
            throw new IllegalStateException("record 0025: no GIN budget verdict at " + verdict
                    + "; run task load:gin first. No soak numbers are written.");
        }
        JsonNode v = JSON.readTree(verdict.toFile());
        if (!v.path("within_budget").asBoolean(false)) {
            throw new IllegalStateException("record 0025: the GIN index is over budget (" + verdict
                    + "); its residual is reported there, and no soak numbers are claimed.");
        }
        return v;
    }

    /** The soak measures the schema the budget approved: the chosen index must be in place. */
    static void requireIndex(Connection c, JsonNode gin) throws SQLException {
        String chosen = gin.path("chosen").asText();
        String expected = chosen.equals("gin_fastupdate_off") ? "fastupdate=off"
                : chosen.equals("gin_fastupdate_on") ? "fastupdate=on" : null;
        try (ResultSet r = c.createStatement().executeQuery("SELECT coalesce(array_to_string(c.reloptions, ','), '') "
                + "FROM pg_class c WHERE c.relname = '" + GinBudget.INDEX + "'")) {
            if (expected != null && (!r.next() || !r.getString(1).contains(expected))) {
                throw new IllegalStateException("record 0025 chose " + chosen + ", but the schema doesn't carry "
                        + GinBudget.INDEX + " with " + expected + "; no soak numbers are written.");
            }
        }
    }

    private static void seed(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS pgstattuple");
            s.execute("INSERT INTO tenants (id) SELECT 't_load_' || lpad(i::text, 2, '0') FROM generate_series(0, "
                    + (TENANTS - 1) + ") i");
            s.execute("INSERT INTO extraction_budgets (tenant_id, calls_remaining) SELECT id, 1000000000 FROM tenants "
                    + "WHERE id LIKE 't_load_%'");
            s.execute("UPDATE extraction_switch SET model_calls_enabled = TRUE, reason = 'load run'");
            s.execute("VACUUM ANALYZE");
        }
    }

    private static ConfigurableApplicationContext startApp(String url) {
        return SpringApplication.from(AnvilApplication::main).with(StubModelConfig.class).run(
                "--server.port=" + PORT,
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + LoadDb.USER,
                "--spring.datasource.password=" + LoadDb.PASSWORD,
                "--logging.level.root=WARN").getApplicationContext();
    }

    private static int k6(Scenario sc, Path out) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--network", "host",
                "-v", Path.of("load").toAbsolutePath() + ":/load:ro",
                "-v", Path.of("eval/sets").toAbsolutePath() + ":/sets:ro",
                "-v", out.toAbsolutePath() + ":/out",
                "-u", currentUser()));
        Map<String, String> env = new LinkedHashMap<>(sc.k6());
        env.put("BASE", "http://localhost:" + PORT);
        env.put("TENANTS", Integer.toString(TENANTS));
        env.put("RUN", sc.name());
        env.forEach((k, v) -> cmd.addAll(List.of("-e", k + "=" + v)));
        cmd.addAll(List.of(K6, "run", "--quiet", "/load/extract.js"));
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        return p.waitFor();
    }

    private static String currentUser() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("id", "-u").start();
        String uid = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        p.waitFor();
        return uid;
    }

    /** Size and tuple counts of every table, from the statistics views. */
    static Map<String, Map<String, Object>> tables(Connection c) throws SQLException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        try (Statement s = c.createStatement()) {
            s.execute("SELECT pg_stat_force_next_flush()");
            try (ResultSet r = s.executeQuery("SELECT relname, n_live_tup, n_dead_tup, n_tup_ins, n_tup_upd, "
                    + "n_tup_hot_upd, autovacuum_count, pg_total_relation_size(relid) AS total "
                    + "FROM pg_stat_user_tables WHERE schemaname = 'public' ORDER BY relname")) {
                while (r.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (String col : List.of("n_live_tup", "n_dead_tup", "n_tup_ins", "n_tup_upd", "n_tup_hot_upd",
                            "autovacuum_count", "total")) {
                        m.put(col, r.getLong(col));
                    }
                    out.put(r.getString("relname"), m);
                }
            }
        }
        return out;
    }

    /** Free and dead space of each table that holds rows, measured exactly with pgstattuple. */
    static Map<String, Double> bloat(Connection c) throws SQLException {
        Map<String, Double> out = new LinkedHashMap<>();
        try (ResultSet r = c.createStatement().executeQuery("SELECT relname, "
                + "(t).dead_tuple_percent + (t).free_percent AS wasted FROM (SELECT relname, pgstattuple(relid) AS t "
                + "FROM pg_stat_user_tables WHERE schemaname = 'public' AND n_live_tup + n_dead_tup > 0) x "
                + "ORDER BY relname")) {
            while (r.next()) {
                out.put(r.getString(1), r.getDouble(2));
            }
        }
        return out;
    }

    /** Each table the run touched: its growth, dead tuples at the end and after settling, and wasted space. */
    static Map<String, Object> growth(Map<String, Map<String, Object>> before, Map<String, Map<String, Object>> end,
            Map<String, Map<String, Object>> settled, Map<String, Double> wasted) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String table : end.keySet()) {
            Map<String, Object> b = before.getOrDefault(table, Map.of());
            Map<String, Object> e = end.get(table);
            Map<String, Object> s = settled.getOrDefault(table, e);
            long writes = (long) e.get("n_tup_ins") - (long) b.getOrDefault("n_tup_ins", 0L)
                    + (long) e.get("n_tup_upd") - (long) b.getOrDefault("n_tup_upd", 0L);
            if (writes == 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("inserted", (long) e.get("n_tup_ins") - (long) b.getOrDefault("n_tup_ins", 0L));
            m.put("updated", (long) e.get("n_tup_upd") - (long) b.getOrDefault("n_tup_upd", 0L));
            m.put("hot_updated", (long) e.get("n_tup_hot_upd") - (long) b.getOrDefault("n_tup_hot_upd", 0L));
            m.put("bytes_before", b.getOrDefault("total", 0L));
            m.put("bytes_after", s.get("total"));
            m.put("live_after", s.get("n_live_tup"));
            m.put("dead_at_end", e.get("n_dead_tup"));
            m.put("dead_after_settle", s.get("n_dead_tup"));
            m.put("autovacuums", (long) s.get("autovacuum_count") - (long) b.getOrDefault("autovacuum_count", 0L));
            m.put("wasted_percent_after_settle", wasted.getOrDefault(table, 0.0));
            out.put(table, m);
        }
        return out;
    }

    /** The numbers the report needs from k6's summary, overall and per phase. */
    static Map<String, Object> summary(JsonNode k6) {
        JsonNode m = k6.path("metrics");
        Map<String, Object> out = new LinkedHashMap<>();
        for (String phase : List.of("main", "recovery")) {
            JsonNode extract = m.path("extract_ms{phase:" + phase + "}").path("values");
            JsonNode errors = m.path("iteration_errors{phase:" + phase + "}").path("values");
            if (extract.isMissingNode() || errors.path("passes").asLong() + errors.path("fails").asLong() == 0) {
                continue;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("iterations", errors.path("passes").asLong() + errors.path("fails").asLong());
            p.put("errors", errors.path("passes").asLong());
            p.put("extract_ms", trend(extract));
            out.put(phase, p);
        }
        long iterations = m.path("iteration_errors").path("values").path("passes").asLong()
                + m.path("iteration_errors").path("values").path("fails").asLong();
        long errors = m.path("iteration_errors").path("values").path("passes").asLong();
        long dropped = m.path("dropped_iterations").path("values").path("count").asLong();
        out.put("iterations", iterations);
        out.put("errors", errors);
        out.put("dropped", dropped);
        out.put("error_rate", iterations + dropped == 0 ? 0.0 : round4((double) (errors + dropped) / (iterations + dropped)));
        out.put("timeouts", m.path("timeouts").path("values").path("count").asLong());
        out.put("server_errors", m.path("server_errors").path("values").path("count").asLong());
        out.put("client_errors", m.path("client_errors").path("values").path("count").asLong());
        out.put("fallbacks", m.path("fallbacks").path("values").path("count").asLong());
        out.put("extract_ms", trend(m.path("extract_ms").path("values")));
        out.put("submit_ms", trend(m.path("submit_ms").path("values")));
        out.put("iterations_per_s", round4(m.path("iterations").path("values").path("rate").asDouble()));
        return out;
    }

    private static Map<String, Object> trend(JsonNode v) {
        Map<String, Object> t = new LinkedHashMap<>();
        for (String k : List.of("med", "p(95)", "p(99)", "max")) {
            t.put(k.equals("med") ? "p50" : k.replace("(", "").replace(")", ""), Math.round(v.path(k).asDouble()));
        }
        return t;
    }

    private static double round4(double d) {
        return Math.round(d * 10_000) / 10_000.0;
    }

    @SuppressWarnings("unchecked")
    static String report(Map<String, Object> r) {
        StringBuilder s = new StringBuilder();
        Map<String, Object> load = (Map<String, Object>) r.get("load");
        s.append("# Load run: ").append(r.get("scenario")).append("\n\n");
        s.append("Offered: ").append(r.get("offered")).append(". Each request submits a document and extracts it.\n");
        s.append("Run ").append(r.get("started_at")).append(" to ").append(r.get("finished_at")).append(", k6 ")
                .append(r.get("k6")).append(".\n");
        s.append("Model: ").append(r.get("model")).append(". Postgres: ").append(r.get("postgres")).append(". Host: ")
                .append(r.get("host")).append(".\n");
        if (r.containsKey("gin_budget")) {
            Map<String, Object> g = (Map<String, Object>) r.get("gin_budget");
            s.append("GIN budget (record 0025): measured ").append(g.get("measured_at")).append(", within budget with ")
                    .append(g.get("chosen")).append("; the index was in place for this run.\n");
        }
        s.append("\n| Requests | Completed | Errors | Dropped | Error rate | Extract p50 | p95 | p99 | max | Submit p95 |\n");
        s.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        Map<String, Object> ex = (Map<String, Object>) load.get("extract_ms");
        Map<String, Object> sub = (Map<String, Object>) load.get("submit_ms");
        long iterations = (long) load.get("iterations");
        long errors = (long) load.get("errors");
        long dropped = (long) load.get("dropped");
        s.append("| ").append(iterations + dropped).append(" | ").append(iterations - errors).append(" | ")
                .append(errors).append(" | ").append(dropped).append(" | ")
                .append(pct((double) load.get("error_rate"))).append(" | ").append(ex.get("p50")).append(" ms | ")
                .append(ex.get("p95")).append(" ms | ").append(ex.get("p99")).append(" ms | ").append(ex.get("max"))
                .append(" ms | ").append(sub.get("p95")).append(" ms |\n\n");
        for (String phase : List.of("main", "recovery")) {
            if (load.containsKey(phase) && load.containsKey("recovery")) {
                Map<String, Object> p = (Map<String, Object>) load.get(phase);
                Map<String, Object> pm = (Map<String, Object>) p.get("extract_ms");
                s.append("- ").append(phase.equals("main") ? "Flood" : "Recovery").append(": ")
                        .append(p.get("iterations")).append(" requests, ").append(p.get("errors"))
                        .append(" errors; extract p50 ").append(pm.get("p50")).append(" ms, p95 ").append(pm.get("p95"))
                        .append(" ms.\n");
            }
        }
        s.append("- Errors by kind: ").append(load.get("timeouts")).append(" timeouts (30 s), ")
                .append(load.get("server_errors")).append(" 5xx, ").append(load.get("client_errors"))
                .append(" 4xx. Dropped: k6 had no free virtual user to start the request at its time.\n");
        s.append("- Model calls: ").append(r.get("model_calls")).append(". Results marked as fallback: ")
                .append(load.get("fallbacks")).append(".\n\n");
        s.append("Table growth (sizes include indexes and TOAST; dead tuples at the end of the run and after ")
                .append(SETTLE.toSeconds()).append(" s idle; wasted space is pgstattuple's dead plus free percent "
                        + "after the idle period):\n\n");
        s.append("| Table | Inserted | Updated (HOT) | Size before | Size after | Per 1,000 requests | Dead at end | "
                + "Dead after idle | Autovacuums | Wasted |\n");
        s.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        long done = Math.max(1, iterations - errors);
        for (Map.Entry<String, Object> e : ((Map<String, Object>) r.get("tables")).entrySet()) {
            Map<String, Object> t = (Map<String, Object>) e.getValue();
            long grew = (long) t.get("bytes_after") - (long) t.get("bytes_before");
            s.append("| ").append(e.getKey()).append(" | ").append(t.get("inserted")).append(" | ")
                    .append(t.get("updated")).append(" (").append(t.get("hot_updated")).append(") | ")
                    .append(size((long) t.get("bytes_before"))).append(" | ").append(size((long) t.get("bytes_after")))
                    .append(" | ").append(size(grew * 1000 / done)).append(" | ").append(t.get("dead_at_end"))
                    .append(" | ").append(t.get("dead_after_settle")).append(" | ").append(t.get("autovacuums"))
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f%%", (double) t.get("wasted_percent_after_settle")))
                    .append(" |\n");
        }
        return s.toString();
    }

    private static String pct(double d) {
        return String.format(Locale.ROOT, "%.2f%%", d * 100);
    }

    private static String size(long bytes) {
        if (bytes >= 1048576) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
        }
        return String.format(Locale.ROOT, "%.0f KiB", bytes / 1024.0);
    }
}
