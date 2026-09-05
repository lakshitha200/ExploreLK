package com.explorelk.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

/**
 * Gives every request an id, and makes sure it travels inward.
 *
 * <p>The moment there is more than one service, "what happened to this request"
 * stops being answerable from one log file. A traveler reports a failure; the
 * gateway logged it, the catalog logged something, auth logged something else,
 * and nothing ties the three together. One id, generated here and forwarded as
 * {@code X-Request-Id}, is what turns three unrelated log files into one story.
 *
 * <p>It is generated <strong>here</strong> rather than accepted from the client:
 * {@link IdentityHeaderFilter} runs first, and a client-supplied id would let
 * anyone collide with — or forge — somebody else's trace. This is the cheap end
 * of distributed tracing; OpenTelemetry is the real answer and is explicitly
 * deferred until there are more services to trace across.
 *
 * <p>The id is also put in the SLF4J {@link MDC}, so every log line the gateway
 * writes while handling the request carries it without anyone remembering to
 * include it, and returned to the caller so it can be quoted in a bug report.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** Where {@code SecurityConfig} finds the id when writing an error body. */
    public static final String REQUEST_ID_ATTRIBUTE = "explorelk.requestId";

    private static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // Short enough to read out over a phone, long enough not to collide
        // within any window anyone cares about.
        String requestId = UUID.randomUUID().toString().substring(0, 8);

        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);

        try {
            chain.doFilter(new RequestWithId(request, requestId), response);
        } finally {
            // Tomcat reuses threads. Left behind, this id would be stamped on
            // the next unrelated request's log lines — which is worse than no id
            // at all, because it is confidently wrong.
            MDC.remove(MDC_KEY);
        }
    }

    /** Adds the header to the request the routing filters will forward. */
    private static final class RequestWithId extends HttpServletRequestWrapper {

        private final String requestId;

        RequestWithId(HttpServletRequest request, String requestId) {
            super(request);
            this.requestId = requestId;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? requestId : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(requestId))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(HEADER::equalsIgnoreCase)) {
                names.add(HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
