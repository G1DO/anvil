package com.g1do.anvil.health;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Correlation IDs across service boundaries (Starter Kit logging standard).
 *
 * <p>Reads {@code X-Request-Id} or {@code X-Correlation-Id} when the caller
 * supplies one, otherwise generates a UUID. Stored in SLF4J MDC as
 * {@code correlationId} so JSON logs carry it; echoed as response header
 * {@code X-Correlation-Id}. No secrets are logged (only method, path,
 * status, and the correlation id).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "correlationId";
    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String correlation = request.getHeader("X-Request-Id");
        if (correlation == null || correlation.isBlank()) {
            correlation = request.getHeader(HEADER);
        }
        if (correlation == null || correlation.isBlank()) {
            correlation = UUID.randomUUID().toString();
        } else {
            correlation = correlation.trim();
        }
        MDC.put(MDC_KEY, correlation);
        response.setHeader(HEADER, correlation);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
