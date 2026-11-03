package com.g1do.anvil.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.g1do.anvil.replay.ChainExport;
import com.g1do.anvil.replay.OfflineVerifier;
import com.g1do.anvil.replay.ReplayController.ReplayDto;
import com.g1do.anvil.replay.ReplayService;
import com.g1do.anvil.replay.ReplayService.ReplayNotFoundException;
import com.g1do.anvil.replay.ReplayService.ReplayResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * The operator's command line for a job's chain, without the running API
 * (issue #75, record 0026).
 *
 * <pre>
 * anvil replay --tenant T --job J             replay from the database; prints the API's result
 * anvil export --tenant T --job J [--out F]   write the job's chain as an export file
 * anvil verify --tenant T --job J FILE        verify an export, with no database
 * </pre>
 *
 * <p>{@code replay} and {@code export} connect to Postgres with
 * {@code SPRING_DATASOURCE_URL}, {@code SPRING_DATASOURCE_USERNAME} and
 * {@code SPRING_DATASOURCE_PASSWORD}, the variables the service reads. The
 * session is read-only, and a login that bypasses row-level security (a
 * superuser or {@code BYPASSRLS}) is dropped to {@code anvil_app} first, so
 * RLS always applies; a login that cannot be dropped is refused. The tenant
 * must exist, as {@code TenantAuthFilter} requires; then {@link ReplayService}
 * reads the chain in that tenant's context, so another tenant's job is "not
 * found" exactly like a missing one. {@code verify} is given no database at all.
 *
 * <p>{@code replay} and {@code verify} print one JSON line on stdout, and
 * {@code export} without {@code --out} prints the export; errors go to
 * stderr, with control characters shown escaped. Exit status:
 * {@code 0} match, {@code 1} no match, {@code 2} usage or malformed input,
 * {@code 3} not found, {@code 4} unknown tenant, {@code 5} database error,
 * {@code 6} failure: the output could not be written, or an unexpected error
 * occurred. A crash is never {@code 1}, which means a verdict of no match.
 */
public final class AnvilCli {

    public static final int MATCH = 0;
    public static final int NO_MATCH = 1;
    public static final int USAGE = 2;
    public static final int NOT_FOUND = 3;
    public static final int UNKNOWN_TENANT = 4;
    public static final int DATABASE = 5;
    public static final int FAILURE = 6;

    /** The runtime role a login that bypasses RLS is dropped to. */
    public static final String RUNTIME_ROLE = "anvil_app";

    private static final String USAGE_TEXT = """
            usage: anvil replay --tenant T --job J
                   anvil export --tenant T --job J [--out FILE]
                   anvil verify --tenant T --job J FILE
            """;

    /** The service's own JSON (Jackson 3), so replay prints the bytes the API sends. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Opens the database for {@code replay} and {@code export}. */
    @FunctionalInterface
    public interface Database {
        Connection connect() throws SQLException;
    }

    private final Database database;
    private final PrintStream out;
    private final PrintStream err;

    public AnvilCli(Database database, PrintStream out, PrintStream err) {
        this.database = database;
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new AnvilCli(AnvilCli::fromEnvironment, System.out, System.err).run(args));
    }

    /** Runs one command and returns its exit status. */
    public int run(String... args) {
        if (args.length == 0) {
            return usage("no command");
        }
        Args a;
        try {
            a = Args.parse(args);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        try {
            return switch (args[0]) {
                case "replay" -> replay(a);
                case "export" -> export(a);
                case "verify" -> verify(a);
                default -> usage("unknown command '" + args[0] + "'");
            };
        } catch (ReplayNotFoundException e) {
            // The API's 404: the same words for a missing job and another tenant's.
            complain("not found");
            return NOT_FOUND;
        } catch (UnknownTenantException e) {
            complain("unknown tenant");
            return UNKNOWN_TENANT;
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        } catch (ChainExport.MalformedExportException e) {
            complain("malformed export: " + e.getMessage());
            return USAGE;
        } catch (IOException e) {
            complain("i/o error: " + e.getMessage());
            return USAGE;
        } catch (SQLException | DataAccessException e) {
            complain("database error: " + e.getMessage());
            return DATABASE;
        } catch (RuntimeException | OutOfMemoryError e) {
            // Not a verdict. Only a verdict may exit 1, so a crash can't look like no match.
            complain("failed: unexpected " + e.getClass().getName());
            return FAILURE;
        }
    }

    private int replay(Args a) throws SQLException {
        a.noFile();
        if (a.out() != null) {
            return usage("replay takes no --out");
        }
        ReplayResult r = withDatabase(a.tenant(), svc -> svc.replay(a.tenant(), a.job()));
        // The API's response body: the same record, the same mapper.
        out.println(JSON.writeValueAsString(new ReplayDto(r.inputSha(), r.outputSha(), r.match())));
        return printed(r.match() ? MATCH : NO_MATCH);
    }

    private int export(Args a) throws SQLException {
        a.noFile();
        ReplayService.JobChain chain = withDatabase(a.tenant(), svc -> svc.load(a.tenant(), a.job()));
        if (a.out() == null) {
            try {
                ChainExport.write(chain, out);
            } catch (IOException e) {
                complain("could not write the export: " + e.getMessage());
                return FAILURE;
            }
            return printed(MATCH);
        }
        try {
            writeNewExport(Path.of(a.out()), chain);
        } catch (FileAlreadyExistsException e) {
            complain("refusing to overwrite " + a.out());
            return USAGE;
        } catch (IOException e) {
            complain("could not write " + a.out() + ": " + e.getMessage());
            return FAILURE;
        }
        complain("exported " + chain.events().size() + " events to " + a.out());
        return MATCH;
    }

    private int verify(Args a) throws IOException {
        if (a.file() == null) {
            return usage("verify needs the export FILE");
        }
        if (a.out() != null) {
            return usage("verify takes no --out");
        }
        ReplayService.JobChain export;
        try (InputStream in = Files.newInputStream(Path.of(a.file()))) {
            export = ChainExport.read(in);
        }
        OfflineVerifier.Verdict v = OfflineVerifier.verify(export, a.tenant(), a.job());
        // The fields hold text from the file, so it is escaped as the terminal output is.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input_sha", printable(v.inputSha()));
        body.put("output_sha", printable(v.outputSha()));
        body.put("match", v.match());
        body.put("first_bad_seq", v.firstBadSeq());
        body.put("problem", printable(v.problem()));
        body.put("head", printable(v.head()));
        out.println(JSON.writeValueAsString(body));
        return printed(v.match() ? MATCH : NO_MATCH);
    }

    /**
     * Writes one export to a new file, owner-only. An existing file, a symlink
     * included, is never overwritten, and a write that fails removes what it
     * made, so a retry isn't blocked by a partial file.
     *
     * @throws FileAlreadyExistsException when the path already exists
     */
    public static void writeNewExport(Path path, ReplayService.JobChain chain) throws IOException {
        createNew(path);
        try (OutputStream f = Files.newOutputStream(path, StandardOpenOption.WRITE)) {
            ChainExport.write(chain, f);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private static void createNew(Path path) throws IOException {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException notPosix) {
            Files.createFile(path);
        }
    }

    /** The status, unless the output did not reach stdout: a result that was not printed is not a success. */
    private int printed(int status) {
        out.flush();
        return out.checkError() ? FAILURE : status;
    }

    private interface Read<T> {
        T apply(ReplayService service);
    }

    /** One read-only connection under RLS, in which the tenant must exist; closed after. */
    private <T> T withDatabase(String tenant, Read<T> read) throws SQLException {
        try (Connection c = database.connect()) {
            restrict(c);
            SingleConnectionDataSource ds = new SingleConnectionDataSource(c, true);
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            Integer known = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, tenant);
            if (known == null || known == 0) {
                throw new UnknownTenantException();
            }
            return read.apply(new ReplayService(jdbc, new DataSourceTransactionManager(ds)));
        }
    }

    /**
     * Makes the session read-only and subject to RLS: a login that bypasses it
     * becomes {@link #RUNTIME_ROLE}, and the CLI refuses if it can't become it.
     */
    public static void restrict(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
            if (bypassesRls(s)) {
                try {
                    s.execute("SET ROLE " + RUNTIME_ROLE);
                } catch (SQLException e) {
                    throw new SQLException("the database role bypasses row-level security and cannot become "
                            + RUNTIME_ROLE + "; refusing to read", e);
                }
            }
            if (bypassesRls(s)) {
                throw new SQLException("the database role bypasses row-level security; refusing to read");
            }
        }
    }

    private static boolean bypassesRls(Statement s) throws SQLException {
        try (var rs = s.executeQuery(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")) {
            return !rs.next() || rs.getBoolean(1);
        }
    }

    private static Connection fromEnvironment() throws SQLException {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || url.isBlank()) {
            throw new SQLException("SPRING_DATASOURCE_URL is not set");
        }
        return open(url, System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"));
    }

    /**
     * Opens a connection. A driver's failure message can name the URL, and a URL
     * can carry a password, so the message the caller sees has neither.
     */
    public static Connection open(String url, String user, String password) throws SQLException {
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            throw new SQLException(redact(e.getMessage(), url, password), e.getSQLState());
        }
    }

    static String redact(String message, String url, String password) {
        String text = message == null ? "connection failed" : message;
        if (url != null && !url.isEmpty()) {
            text = text.replace(url, "<SPRING_DATASOURCE_URL>");
        }
        if (password != null && !password.isEmpty()) {
            text = text.replace(password, "***");
        }
        return text.replaceAll("(?i)password=[^&;\\s]*", "password=***");
    }

    /**
     * Text from an export or the command line may hold control characters, which
     * a terminal would act on, so they are shown escaped. Returns {@code null} for {@code null}.
     */
    static String printable(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isISOControl(c) || Character.isSurrogate(c) || c == ' ' || c == ' ') {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private void complain(String message) {
        err.println(printable(message));
    }

    private int usage(String why) {
        complain(why == null ? "invalid arguments" : why);
        err.print(USAGE_TEXT);
        return USAGE;
    }

    private static final class UnknownTenantException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    /** {@code --tenant}, {@code --job} and {@code --out} after the command, and at most one file. */
    private record Args(String tenant, UUID job, String out, String file) {

        static Args parse(String[] args) {
            String tenant = null;
            String job = null;
            String out = null;
            String file = null;
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--tenant" -> tenant = value(args, ++i, arg);
                    case "--job" -> job = value(args, ++i, arg);
                    case "--out" -> out = value(args, ++i, arg);
                    default -> {
                        if (arg.startsWith("--") || file != null) {
                            throw new IllegalArgumentException("unexpected argument '" + arg + "'");
                        }
                        file = arg;
                    }
                }
            }
            if (tenant == null || tenant.isBlank()) {
                throw new IllegalArgumentException("--tenant is required");
            }
            if (job == null) {
                throw new IllegalArgumentException("--job is required");
            }
            UUID jobId;
            try {
                jobId = UUID.fromString(job.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("--job is not a UUID");
            }
            return new Args(tenant.trim(), jobId, out, file);
        }

        void noFile() {
            if (file != null) {
                throw new IllegalArgumentException("unexpected argument '" + file + "'");
            }
        }

        private static String value(String[] args, int i, String flag) {
            if (i >= args.length) {
                throw new IllegalArgumentException(flag + " needs a value");
            }
            return args[i];
        }
    }
}
