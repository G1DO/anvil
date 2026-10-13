package com.g1do.anvil.exec;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Default-deny egress policy plus a redirect-never HTTP client for untrusted
 * execution (issue #23).
 *
 * <p>Contract (narrowest reasonable):
 * <ul>
 * <li>Default-deny: {@link #checkAllowed(String)} with no allowlist denies every
 * URL, including public hosts. An explicit allowlist ({@link #checkAllowed(String, Set)})
 * still denies unless the host is listed <em>and</em> not a blocked target.</li>
 * <li>Blocked targets (always denied, even when allowlisted): {@code localhost}
 * and {@code *.localhost}, well-known metadata names, IPv4 loopback
 * ({@code 127/8}), private ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}),
 * link-local ({@code 169.254/16}), {@code 0.0.0.0/8}, IPv6 loopback
 * ({@code ::1} plus expanded {@code 0:0:0:0:0:0:0:1}), unspecified
 * ({@code ::} plus expanded {@code 0:0:0:0:0:0:0:0}), link-local
 * ({@code fe80::/10}), unique-local ({@code fc00::/7}), multicast
 * ({@code ff00::/8}), IPv4-mapped IPv6 ({@code ::ffff:/96}), and non-canonical
 * numeric forms (single-integer like {@code 2130706433}, hex integer like
 * {@code 0x7f000001}, hex-dotted like {@code 0x7f.0.0.1}). Only
 * {@code http}/{@code https} schemes are considered; anything else denies. URLs
 * with userinfo ({@code http://user@host}) deny.</li>
 * <li>Redirects are never followed: {@link #newClient()} builds a
 * {@link HttpClient} with {@code followRedirects(NEVER)}. Callers on the
 * untrusted path must use this client <em>and</em> {@link #checkAllowed} before
 * sending; the client alone does not enforce the allowlist, and the allowlist
 * alone does not rewrite redirect policy.</li>
 * <li>Failure is fail-closed without an oracle: denials throw
 * {@link EgressDeniedException} with a generic reason only (no URL/host echo).</li>
 * </ul>
 *
 * <p>Residual (explicitly out of scope for v1, see issue #23): DNS-rebind
 * (no DNS resolution is performed; a name that resolves to an internal address
 * at connect time is not re-checked), non-canonical IP encodings
 * (decimal/octal/hex/integer forms are denied as non-canonical rather than
 * decoded), nftables-level DROP, timing oracles, and the full SsrfMatrix. The
 * filesystem jail likewise has no mount/user namespace in v1; see
 * {@link ExecJail} for that residual.
 */
public final class JailEgress {

    private JailEgress() {
    }

    /**
     * HTTP client for the untrusted path: never follows redirects.
     *
     * @return a client with {@code followRedirects(NEVER)} and a 5s connect timeout.
     */
    public static HttpClient newClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Default-deny check: denies every URL (no allowlist in v1).
     *
     * @throws EgressDeniedException always (default-deny), or when the target is blocked.
     */
    public static void checkAllowed(String url) {
        checkAllowed(url, Set.of());
    }

    /**
     * Allowlisted check: allows only when the host is in {@code allowedHosts}
     * (case-insensitive, trailing-dot-insensitive) <em>and</em> the target is not
     * blocked. A blocked target denies even when allowlisted.
     *
     * @param url the absolute URL the untrusted path wants to fetch.
     * @param allowedHosts explicit allowlist; {@code null} or empty denies everything.
     * @throws EgressDeniedException when the URL must not be fetched.
     */
    public static void checkAllowed(String url, Set<String> allowedHosts) {
        Parsed parsed = parse(url);
        if (parsed == null) {
            throw new EgressDeniedException("blocked target");
        }
        if (isBlockedHost(parsed.host())) {
            throw new EgressDeniedException("blocked target");
        }
        if (!isAllowlisted(parsed.host(), allowedHosts)) {
            throw new EgressDeniedException("default-deny");
        }
    }

    /**
     * Returns {@code true} when the URL's target is blocked (internal,
     * link-local, loopback, metadata, non-http(s), userinfo, or unparseable).
     * Public canonical hosts return {@code false} here but are still denied by
     * {@link #checkAllowed(String)} via default-deny unless allowlisted.
     */
    public static boolean isBlockedTarget(String url) {
        Parsed parsed = parse(url);
        if (parsed == null) {
            return true;
        }
        return isBlockedHost(parsed.host());
    }

    private record Parsed(String host) {
    }

    private static Parsed parse(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return null;
        }
        if (uri.getUserInfo() != null) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }
        if (host.contains("%") || host.contains("/") || host.contains("\\")
                || host.contains("@") || host.contains("\0")
                || host.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return new Parsed(host);
    }

    private static boolean isAllowlisted(String host, Set<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        String normalized = normalizeHost(host);
        for (String allowed : allowedHosts) {
            if (allowed != null && normalizeHost(allowed).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeHost(String host) {
        String lower = host.trim().toLowerCase(Locale.ROOT);
        // Strip IPv6 brackets when present (URI.getHost usually already strips them).
        if (lower.startsWith("[") && lower.endsWith("]") && lower.length() > 2) {
            lower = lower.substring(1, lower.length() - 1);
        }
        while (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        return lower;
    }

    static boolean isBlockedHost(String host) {
        String h = normalizeHost(host);
        if (h.isEmpty() || h.contains("\0") || h.contains("%") || h.contains("/") || h.contains("\\")) {
            return true;
        }
        // Well-known names: localhost hierarchy plus cloud metadata names.
        if (h.equals("localhost") || h.endsWith(".localhost")) {
            return true;
        }
        if (h.equals("metadata.google.internal")
                || h.equals("metadata.google")
                || h.equals("instance-data")
                || h.equals("instance-data-compute")
                || h.equals("metadata")) {
            return true;
        }
        // Fail-closed non-canonical numeric forms (denied without decoding):
        // single-integer (2130706433), hex integer (0x7f000001), and hex-dotted
        // (0x7f.0.0.1) encodings never reach DNS as allowlisted names.
        if (h.matches("^[0-9]+$")) {
            return true;
        }
        if (h.matches("(?i)^0x[0-9a-f]+$")) {
            return true;
        }
        if (h.matches("(?i)^[0-9a-fx.]+$") && h.toLowerCase(Locale.ROOT).contains("0x")) {
            return true;
        }
        // IPv4 literal (canonical dotted-decimal only; anything else that looks
        // like digits-and-dots but does not parse is blocked fail-closed, which
        // also denies decimal/octal/hex/integer encodings without decoding them).
        if (h.matches("^[0-9.]+$") && h.contains(".")) {
            return isBlockedIpv4(h);
        }
        // IPv6 literal.
        if (h.contains(":")) {
            return isBlockedIpv6(h);
        }
        return false;
    }

    private static boolean isBlockedIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return true;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return true;
            }
            // Reject leading zeros beyond a single "0" (octal-encoding residual:
            // deny rather than decode).
            if (parts[i].length() > 1 && parts[i].startsWith("0")) {
                return true;
            }
            for (int j = 0; j < parts[i].length(); j++) {
                if (!Character.isDigit(parts[i].charAt(j))) {
                    return true;
                }
            }
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return true;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return true;
            }
        }
        if (octets[0] == 127) {
            return true;
        }
        if (octets[0] == 10) {
            return true;
        }
        if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) {
            return true;
        }
        if (octets[0] == 192 && octets[1] == 168) {
            return true;
        }
        if (octets[0] == 169 && octets[1] == 254) {
            return true;
        }
        if (octets[0] == 0) {
            return true;
        }
        return false;
    }

    private static boolean isBlockedIpv6(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        // Strip a trailing zone id ("%eth0") fail-closed: zone ids never valid here.
        int percent = h.indexOf('%');
        if (percent >= 0) {
            return true;
        }
        if (h.equals("::1") || h.equals("::")) {
            return true;
        }
        // IPv4-mapped IPv6 (any ::ffff:/0:0:0:0:0:ffff: form hides an IPv4
        // target): denied without decoding the inner address.
        if (h.startsWith("::ffff:") || h.startsWith("0:0:0:0:0:ffff:")) {
            return true;
        }
        // Expanded loopback/unspecified (0:0:0:0:0:0:0:1, 0:0:0:0:0:0:0:0,
        // including zero-padded groups): denied like their compressed forms.
        if (isExpandedLoopbackOrUnspecified(h)) {
            return true;
        }
        // fe80::/10 link-local covers fe80-febf; check the top nibble textually.
        if (h.startsWith("fe80") || h.startsWith("fe81") || h.startsWith("fe82")
                || h.startsWith("fe83") || h.startsWith("fe84") || h.startsWith("fe85")
                || h.startsWith("fe86") || h.startsWith("fe87") || h.startsWith("fe88")
                || h.startsWith("fe89") || h.startsWith("fe8a") || h.startsWith("fe8b")
                || h.startsWith("fe8c") || h.startsWith("fe8d") || h.startsWith("fe8e")
                || h.startsWith("fe8f") || h.startsWith("fe9") || h.startsWith("fea")
                || h.startsWith("feb")) {
            return true;
        }
        // fc00::/7 unique-local covers fc00-fdff.
        if (h.startsWith("fc") || h.startsWith("fd")) {
            return true;
        }
        // ff00::/8 multicast is never a valid HTTP egress target.
        if (h.startsWith("ff")) {
            return true;
        }
        // Any other colon-bearing literal that fails to parse as an address is
        // blocked fail-closed rather than passed to DNS.
        if (!h.matches("^[0-9a-f:.]+$")) {
            return true;
        }
        return false;
    }

    private static boolean isExpandedLoopbackOrUnspecified(String h) {
        if (h.contains("::") || !h.contains(":")) {
            return false;
        }
        String addr = h;
        int dot = addr.indexOf('.');
        if (dot >= 0) {
            // Embedded-IPv4 expanded form (e.g. 0:0:0:0:0:ffff:1.2.3.4) is
            // already covered by the mapped prefix above; any other dotted
            // colon form is non-canonical here.
            return addr.startsWith("0:0:0:0:0:ffff:");
        }
        String[] groups = addr.split(":", -1);
        if (groups.length != 8) {
            return false;
        }
        for (int i = 0; i < 7; i++) {
            if (!isZeroGroup(groups[i])) {
                return false;
            }
        }
        try {
            int last = Integer.parseInt(groups[7].isEmpty() ? "0" : groups[7], 16);
            return last == 0 || last == 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isZeroGroup(String group) {
        if (group.isEmpty()) {
            return false;
        }
        if (group.length() > 4) {
            return false;
        }
        for (int i = 0; i < group.length(); i++) {
            char c = group.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        try {
            return Integer.parseInt(group, 16) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
