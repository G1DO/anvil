package com.g1do.anvil.upload;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Enforces the upload byte limit before a JSON document or invoice body is
 * parsed.
 *
 * <p>A declared {@code Content-Length} over the limit is refused without
 * reading any of the body. A streamed body with no length is wrapped so the
 * read fails as soon as it crosses the limit. Either way the response is
 * {@code 413}, and the controller never runs, so no document, version or job
 * row is written.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATHS = new UrlPathHelper();
    private static final Set<String> LIMITED_PATHS = Set.of("/api/documents", "/api/invoices");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !LIMITED_PATHS.contains(PATHS.getRequestUri(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > UploadLimits.MAX_BYTES) {
            tooLarge(response);
            return;
        }
        try {
            filterChain.doFilter(new LimitedRequest(request), response);
        } catch (RequestTooLargeException e) {
            if (response.isCommitted()) {
                throw e;
            }
            response.resetBuffer();
            tooLarge(response);
        }
    }

    private static void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"request body exceeds " + UploadLimits.MAX_BYTES + " bytes\"}");
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream());
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private long consumed;

        LimitedInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value == -1) {
                return -1;
            }
            if (consumed == UploadLimits.MAX_BYTES) {
                throw new RequestTooLargeException();
            }
            consumed++;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            long allowed = Math.min(length, UploadLimits.MAX_BYTES - consumed + 1);
            int read = delegate.read(bytes, offset, (int) allowed);
            if (read > 0) {
                consumed += read;
                if (consumed > UploadLimits.MAX_BYTES) {
                    throw new RequestTooLargeException();
                }
            }
            return read;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
