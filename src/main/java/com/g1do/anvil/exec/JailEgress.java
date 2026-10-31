package com.g1do.anvil.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Default-deny egress broker for untrusted execution (issues #23 and #73).
 *
 * <p>A jailed child has no network of its own: {@link ExecJail} runs it in an
 * empty network namespace. This class is the only way out, and it runs in the
 * parent.
 *
 * <p>Contract:
 * <ul>
 * <li>HTTPS only: plaintext {@code http} and every other scheme are blocked, so
 * nothing on the path can read or rewrite what the jail fetches.</li>
 * <li>Default-deny: {@link #checkAllowed(String)} denies every URL. With an
 * allowlist, a URL passes only when its host is listed <em>and</em> isn't a
 * blocked target. The check never resolves DNS, so a denied URL takes the same
 * time whether its host exists or not.</li>
 * <li>IP literals are decoded, not pattern-matched: POSIX forms
 * ({@code 2130706433}, {@code 0x7f.1}, {@code 0177.0.0.1}, {@code 127.1}) and
 * IPv6 forms are parsed to the address they name, and that address is checked.
 * A numeric-looking host that doesn't parse is denied. IPv4-mapped IPv6 is
 * always denied.</li>
 * <li>Blocked addresses ({@link #isBlockedAddress}): unspecified, loopback,
 * private, carrier-grade NAT, link-local (metadata), benchmarking,
 * documentation, multicast and reserved IPv4. For IPv6, anything outside
 * global unicast ({@code 2000::/3}), which covers unspecified, loopback,
 * IPv4-compatible and -mapped, unique-local, link-local, site-local and
 * multicast; plus documentation, the {@code 2001::/23} protocol block
 * (Teredo, benchmarking, ORCHID) and 6to4 addresses that embed a blocked
 * IPv4 address. NAT64 ({@code 64:ff9b::/96}) passes only for a public IPv4
 * address.</li>
 * <li>Connect-time check: {@link #open} resolves the host again when it
 * connects, denies when <em>any</em> resolved address is blocked, and
 * connects to the vetted address itself, so DNS can't rebind between the check
 * and the connection. The connection is TLS with SNI and host-name
 * verification against the URL's host.</li>
 * <li>Redirects are never followed: {@link #fetch} denies any 3xx response.</li>
 * <li>Denials throw {@link EgressDeniedException} with a generic reason only
 * (no URL or host echo).</li>
 * </ul>
 */
public final class JailEgress {

    /** Connect timeout for the brokered connection. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Read timeout for the brokered connection. */
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** Max response body bytes {@link #fetch} keeps; beyond this it truncates. */
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    /** Resolves a host name at connect time. */
    @FunctionalInterface
    public interface Resolver {
        List<InetAddress> resolve(String host) throws IOException;
    }

    /**
     * Opens a connection to an already-vetted address. {@code address} carries the
     * URL's host as its name, so it never resolves; {@code host} is that name, for
     * SNI and certificate checks.
     */
    @FunctionalInterface
    public interface Connector {
        Socket connect(InetSocketAddress address, String host, Duration timeout) throws IOException;
    }

    /** The system resolver. */
    public static final Resolver SYSTEM_RESOLVER = host -> List.of(InetAddress.getAllByName(host));

    /** {@link #tls} with the JVM's default trust store: the production connector. */
    public static final Connector TLS = (address, host, timeout) ->
            tls((SSLSocketFactory) SSLSocketFactory.getDefault()).connect(address, host, timeout);

    /**
     * TLS through {@code factory} to the vetted address, with SNI and host-name
     * verification against {@code host}. It never resolves: the certificate is
     * checked against {@code host} whatever name {@code address} carries.
     */
    public static Connector tls(SSLSocketFactory factory) {
        return (address, host, timeout) -> {
            if (address.isUnresolved()) {
                throw new IOException("unresolved address");
            }
            // Name the vetted address after the URL's host: the JDK checks the
            // certificate against this name, and never looks it up.
            InetSocketAddress named = new InetSocketAddress(
                    InetAddress.getByAddress(host, address.getAddress().getAddress()), address.getPort());
            SSLSocket socket = (SSLSocket) factory.createSocket();
            try {
                SSLParameters params = socket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                if (decodeLiteralOrNull(host) == null) {
                    params.setServerNames(List.of(new SNIHostName(host)));
                }
                socket.setSSLParameters(params);
                socket.connect(named, (int) timeout.toMillis());
                socket.setSoTimeout((int) READ_TIMEOUT.toMillis());
                socket.startHandshake();
                return socket;
            } catch (IOException | RuntimeException e) {
                socket.close();
                throw e;
            }
        };
    }

    /** A fetched response: status, headers (lower-case names), capped body. */
    public record Response(int status, Map<String, List<String>> headers, byte[] body,
            boolean truncated) {
    }

    private record Target(String host, int port, String pathAndQuery,
            InetAddress literal) {
    }

    private static final Pattern REGISTRY_AUTHORITY = Pattern.compile("[A-Za-z0-9._-]+(:[0-9]{1,5})?");

    private static final Pattern NUMERIC_PART = Pattern.compile("(?i)0x[0-9a-f]*|[0-9]+");

    private static final Set<String> BLOCKED_NAMES = Set.of(
            "localhost", "metadata.google.internal", "metadata.google", "metadata",
            "instance-data", "instance-data-compute");

    private JailEgress() {
    }

    /**
     * Default-deny check: denies every URL.
     *
     * @throws EgressDeniedException always.
     */
    public static void checkAllowed(String url) {
        checkAllowed(url, Set.of());
    }

    /**
     * Allowlisted check without DNS: allows only when the host is in
     * {@code allowedHosts} (case-insensitive, trailing-dot-insensitive; IP
     * entries compare as addresses) <em>and</em> the target is not blocked.
     *
     * @throws EgressDeniedException when the URL must not be fetched.
     */
    public static void checkAllowed(String url, Set<String> allowedHosts) {
        check(url, allowedHosts);
    }

    /**
     * Returns {@code true} when the URL's target is blocked (internal,
     * link-local, loopback, metadata, not https, userinfo, or unparseable).
     * Public hosts return {@code false} here but are still denied by
     * {@link #checkAllowed(String)} unless allowlisted.
     */
    public static boolean isBlockedTarget(String url) {
        try {
            parse(url);
            return false;
        } catch (EgressDeniedException e) {
            return true;
        }
    }

    /**
     * Opens a connection with the system resolver and the {@link #TLS} connector.
     *
     * @see #open(String, Set, Resolver, Connector)
     */
    public static Socket open(String url, Set<String> allowedHosts) throws IOException {
        return open(url, allowedHosts, SYSTEM_RESOLVER, TLS);
    }

    /**
     * Checks {@code url}, resolves its host now, denies when any resolved
     * address is blocked, and connects to the first vetted address through
     * {@code connector} ({@link #TLS} in production).
     *
     * @throws EgressDeniedException when the URL or any resolved address is denied.
     */
    public static Socket open(String url, Set<String> allowedHosts, Resolver resolver,
            Connector connector) throws IOException {
        Target target = check(url, allowedHosts);
        List<InetAddress> addresses;
        if (target.literal() != null) {
            addresses = List.of(target.literal());
        } else {
            try {
                addresses = resolver.resolve(target.host());
            } catch (IOException e) {
                throw new EgressDeniedException("unresolvable");
            }
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new EgressDeniedException("unresolvable");
        }
        for (InetAddress address : addresses) {
            if (address == null || isBlockedAddress(address)) {
                throw new EgressDeniedException("blocked target");
            }
        }
        InetAddress vetted = addresses.get(0);
        InetAddress pinned = InetAddress.getByAddress(target.host(), vetted.getAddress());
        Socket socket = connector.connect(new InetSocketAddress(pinned, target.port()), target.host(),
                CONNECT_TIMEOUT);
        try {
            socket.setSoTimeout((int) READ_TIMEOUT.toMillis());
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /**
     * {@code GET} with the system resolver and the {@link #TLS} connector.
     *
     * @see #fetch(String, Set, Resolver, Connector)
     */
    public static Response fetch(String url, Set<String> allowedHosts) throws IOException {
        return fetch(url, allowedHosts, SYSTEM_RESOLVER, TLS);
    }

    /**
     * Sends one {@code GET} over {@link #open} and reads the response. A 3xx
     * response is denied: redirects are never followed.
     *
     * @throws EgressDeniedException when the URL is denied or the response redirects.
     */
    public static Response fetch(String url, Set<String> allowedHosts, Resolver resolver,
            Connector connector) throws IOException {
        Target target = check(url, allowedHosts);
        try (Socket socket = open(url, allowedHosts, resolver, connector)) {
            String hostHeader = target.literal() instanceof Inet6Address
                    ? "[" + target.literal().getHostAddress() + "]" : target.host();
            if (target.port() != 443) {
                hostHeader += ":" + target.port();
            }
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + target.pathAndQuery() + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "Accept-Encoding: identity\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String statusLine = readLine(in);
            String[] status = statusLine.split(" ", 3);
            if (status.length < 2 || !status[0].startsWith("HTTP/")) {
                throw new IOException("malformed response");
            }
            int code;
            try {
                code = Integer.parseInt(status[1]);
            } catch (NumberFormatException e) {
                throw new IOException("malformed response");
            }
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.computeIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            k -> new ArrayList<>()).add(line.substring(colon + 1).trim());
                }
            }
            if (code >= 300 && code < 400) {
                throw new EgressDeniedException("redirect not followed");
            }
            return readBody(in, code, headers);
        }
    }

    /** Returns {@code true} when egress must never reach {@code address}. */
    public static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isBlockedIpv4(b, 0);
        }
        if (((Inet6Address) address).getScopeId() != 0 || ((Inet6Address) address).getScopedInterface() != null) {
            return true;
        }
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        int b2 = b[2] & 0xff;
        int b3 = b[3] & 0xff;
        if (b0 == 0x00 && b1 == 0x64 && b2 == 0xff && b3 == 0x9b) {
            // 64:ff9b::/96 NAT64 passes only for a public IPv4 address;
            // 64:ff9b:1::/48 local-use NAT64 is blocked whole.
            return !allZero(b, 4, 12) || isBlockedIpv4(b, 12);
        }
        if ((b0 & 0xe0) != 0x20) {
            // Outside 2000::/3 global unicast: ::/96 (unspecified, loopback,
            // IPv4-compatible), ::ffff:0:0/96 mapped, 100::/64 discard,
            // fc00::/7 unique-local, fe80::/10 link-local, fec0::/10
            // site-local, ff00::/8 multicast, and everything unassigned.
            return true;
        }
        if (b0 == 0x20 && b1 == 0x01 && (b2 & 0xfe) == 0) {
            return true; // 2001::/23 protocol assignments: Teredo, benchmarking, ORCHID
        }
        if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0d && b3 == 0xb8) {
            return true; // 2001:db8::/32 documentation
        }
        if (b0 == 0x3f && b1 == 0xff && (b2 & 0xf0) == 0) {
            return true; // 3fff::/20 documentation
        }
        if (b0 == 0x20 && b1 == 0x02) {
            return isBlockedIpv4(b, 2); // 2002::/16 6to4
        }
        return false;
    }

    private static Target check(String url, Set<String> allowedHosts) {
        Target target = parse(url);
        if (!isAllowlisted(target, allowedHosts)) {
            throw new EgressDeniedException("default-deny");
        }
        return target;
    }

    private static Target parse(String url) {
        if (url == null || url.isBlank()) {
            throw new EgressDeniedException("blocked target");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new EgressDeniedException("blocked target");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new EgressDeniedException("blocked target");
        }
        if (uri.getRawUserInfo() != null) {
            throw new EgressDeniedException("blocked target");
        }
        String rawHost = uri.getHost();
        int port = uri.getPort();
        if (rawHost == null) {
            // java.net.URI leaves short and mixed numeric forms (127.1, 0x7f.1) without a
            // host; take it from the authority so they're decoded, not just refused.
            String authority = uri.getRawAuthority();
            if (authority == null || !REGISTRY_AUTHORITY.matcher(authority).matches()) {
                throw new EgressDeniedException("blocked target");
            }
            int colon = authority.lastIndexOf(':');
            rawHost = colon >= 0 ? authority.substring(0, colon) : authority;
            try {
                port = colon >= 0 ? Integer.parseInt(authority.substring(colon + 1)) : -1;
            } catch (NumberFormatException e) {
                throw new EgressDeniedException("blocked target");
            }
        }
        if (rawHost.isBlank() || rawHost.contains("%") || rawHost.contains("\\")
                || rawHost.contains("@") || rawHost.contains("\0")
                || rawHost.chars().anyMatch(Character::isWhitespace)) {
            throw new EgressDeniedException("blocked target");
        }
        String host = normalizeHost(rawHost);
        if (host.isEmpty()) {
            throw new EgressDeniedException("blocked target");
        }
        InetAddress literal = decodeLiteral(host);
        if (literal != null) {
            if (isBlockedAddress(literal)) {
                throw new EgressDeniedException("blocked target");
            }
            // From here on the host is the address it decodes to, in canonical form,
            // for the Host header and the certificate check.
            host = literal.getHostAddress();
        } else if (BLOCKED_NAMES.contains(host) || host.endsWith(".localhost")) {
            throw new EgressDeniedException("blocked target");
        }
        port = port == -1 ? 443 : port;
        if (port < 1 || port > 65535) {
            throw new EgressDeniedException("blocked target");
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String pathAndQuery = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        return new Target(host, port, pathAndQuery, literal);
    }

    /**
     * Decodes an IP literal to the address it names; {@code null} for a host
     * name. Denies a numeric-looking host that doesn't parse, and any
     * IPv4-mapped IPv6 form.
     */
    private static InetAddress decodeLiteral(String host) {
        if (host.contains(":")) {
            InetAddress address;
            try {
                address = InetAddress.ofLiteral(host);
            } catch (IllegalArgumentException e) {
                throw new EgressDeniedException("blocked target");
            }
            if (address instanceof Inet4Address) {
                throw new EgressDeniedException("blocked target"); // IPv4-mapped IPv6
            }
            return address;
        }
        for (String part : host.split("\\.", -1)) {
            if (!NUMERIC_PART.matcher(part).matches()) {
                return null;
            }
        }
        try {
            return Inet4Address.ofPosixLiteral(host);
        } catch (IllegalArgumentException e) {
            throw new EgressDeniedException("blocked target");
        }
    }

    private static InetAddress decodeLiteralOrNull(String host) {
        try {
            return decodeLiteral(normalizeHost(host));
        } catch (EgressDeniedException e) {
            return null;
        }
    }

    private static boolean isAllowlisted(Target target, Set<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        for (String allowed : allowedHosts) {
            if (allowed == null || allowed.isBlank()) {
                continue;
            }
            String entry = normalizeHost(allowed);
            if (target.literal() == null) {
                if (entry.equals(target.host())) {
                    return true;
                }
                continue;
            }
            try {
                InetAddress listed = decodeLiteral(entry);
                if (target.literal().equals(listed)) {
                    return true;
                }
            } catch (EgressDeniedException e) {
                // A blocked or malformed allowlist entry never matches.
            }
        }
        return false;
    }

    private static String normalizeHost(String host) {
        String lower = host.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]") && lower.length() > 2) {
            lower = lower.substring(1, lower.length() - 1);
        }
        while (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        return lower;
    }

    private static boolean isBlockedIpv4(byte[] b, int off) {
        int a = b[off] & 0xff;
        int c = b[off + 1] & 0xff;
        int d = b[off + 2] & 0xff;
        return a == 0                                   // 0/8 this network
                || a == 10                              // 10/8 private
                || (a == 100 && (c & 0xc0) == 64)       // 100.64/10 carrier-grade NAT
                || a == 127                             // 127/8 loopback
                || (a == 169 && c == 254)               // 169.254/16 link-local, metadata
                || (a == 172 && (c & 0xf0) == 16)       // 172.16/12 private
                || (a == 192 && c == 0 && (d == 0 || d == 2)) // 192.0.0/24, 192.0.2/24
                || (a == 192 && c == 88 && d == 99)     // 192.88.99/24 6to4 relay
                || (a == 192 && c == 168)               // 192.168/16 private
                || (a == 198 && (c & 0xfe) == 18)       // 198.18/15 benchmarking
                || (a == 198 && c == 51 && d == 100)    // 198.51.100/24 documentation
                || (a == 203 && c == 0 && d == 113)     // 203.0.113/24 documentation
                || a >= 224;                            // 224/4 multicast, 240/4 reserved
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.write(c);
            }
            if (line.size() > 16 * 1024) {
                throw new IOException("header line too long");
            }
        }
        if (c == -1 && line.size() == 0) {
            throw new IOException("connection closed");
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    private static Response readBody(InputStream in, int code, Map<String, List<String>> headers)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean truncated;
        List<String> encoding = headers.getOrDefault("transfer-encoding", List.of());
        if (encoding.stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("chunked"))) {
            truncated = false;
            while (true) {
                String sizeLine = readLine(in);
                int semi = sizeLine.indexOf(';');
                long size;
                try {
                    size = Long.parseLong((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("malformed chunk");
                }
                if (size == 0) {
                    break;
                }
                truncated |= copy(in, body, size);
                readLine(in);
                if (truncated) {
                    break;
                }
            }
        } else {
            List<String> length = headers.getOrDefault("content-length", List.of());
            long limit = Long.MAX_VALUE;
            if (!length.isEmpty()) {
                try {
                    limit = Long.parseLong(length.get(0));
                } catch (NumberFormatException e) {
                    throw new IOException("malformed content-length");
                }
            }
            truncated = copy(in, body, limit);
        }
        return new Response(code, headers, body.toByteArray(), truncated);
    }

    /** Copies up to {@code limit} bytes, keeping at most the cap; returns whether it truncated. */
    private static boolean copy(InputStream in, ByteArrayOutputStream out, long limit) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = limit;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n == -1) {
                return false;
            }
            int room = MAX_RESPONSE_BYTES - out.size();
            if (n > room) {
                out.write(buf, 0, room);
                return true;
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
        return false;
    }
}
