package com.g1do.anvil.tenant;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Minimal auth for the single-tenant submit slice.
 *
 * <p>Flow: {@code X-Tenant-Id} header -&gt; lookup {@code tenants.id} -&gt; set
 * {@link TenantContext}. The transaction uses that value for all rows and the
 * idempotency dupe scope is {@code (tenant_id, idempotency_key)}.
 *
 * <p>No JWT/OAuth here by design (issue #3 constraints).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantAuthFilter extends OncePerRequestFilter {

    private final JdbcTemplate jdbc;

    public TenantAuthFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Only enforce on the submit slice; leave any future/other paths alone.
        return !(path.startsWith("/api/") || path.startsWith("/v1/")
                || path.equals("/documents") || path.startsWith("/documents/")
                || path.equals("/submit") || path.startsWith("/submit/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String tenantId = request.getHeader("X-Tenant-Id");
        if (tenantId == null || tenantId.isBlank()) {
            unauthorized(response, "missing X-Tenant-Id");
            return;
        }
        tenantId = tenantId.trim();
        Integer count;
        try {
            count = jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Integer.class, tenantId);
        } catch (Exception e) {
            // DB unavailable during filtering: fail closed.
            unauthorized(response, "tenant lookup failed");
            return;
        }
        if (count == null || count == 0) {
            unauthorized(response, "unknown tenant");
            return;
        }
        TenantContext.setTenantId(tenantId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
