package com.explorelk.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Removes identity headers a client sent, before anything downstream sees them.
 *
 * <p><strong>This is the security property that makes a gateway safe to put
 * identity headers behind at all.</strong> The pattern is common and useful: the
 * gateway verifies a token and passes {@code X-User-Id} inward so services do
 * not each re-parse it. The trap is that the header is just a header — if a
 * client can send {@code X-User-Id: <someone else>} and have it survive to the
 * service, then the entire authentication system has been bypassed by typing.
 *
 * <p>So every header in {@link #STRIPPED} is removed on the way in, unconditionally,
 * whether or not the request is authenticated and whether or not this gateway
 * would have set it. Stripping only when a token is present is the subtle version
 * of the same bug: an unauthenticated request to a public endpoint would keep its
 * forged header.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE}, ahead of Spring Security and the
 * routing filters, so nothing can read the forged value before it is gone.
 *
 * <p>This gateway does not currently <em>add</em> {@code X-User-Id} — the services
 * verify the token themselves and read the subject from it, which is stronger than
 * trusting a header from a neighbour on the network. The stripping exists anyway,
 * because the day somebody does start setting these headers, the protection has to
 * already be in place rather than be remembered.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class IdentityHeaderFilter extends OncePerRequestFilter {

    /**
     * Lower-cased, because {@code getHeader} is case-insensitive but a naive
     * {@code equals} on the name is not — and {@code x-user-id} must not slip
     * past a filter looking for {@code X-User-Id}.
     */
    private static final Set<String> STRIPPED = Set.of(
            "x-user-id",
            "x-user-email",
            "x-user-role",
            "x-authenticated",
            // Trusting an inbound X-Forwarded-For lets a caller pick their own
            // rate-limit bucket, and pick somebody else's to poison. The gateway
            // decides what the client address is.
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-proto",
            "x-real-ip");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        boolean forged = false;
        for (String name : STRIPPED) {
            if (request.getHeader(name) != null) {
                forged = true;
                break;
            }
        }

        if (forged) {
            // Worth a log line: a legitimate client has no reason to send these,
            // so their presence is either a misconfigured proxy in front of the
            // gateway or somebody probing.
            log.warn("Stripped client-supplied identity headers from {} {}",
                    request.getMethod(), request.getRequestURI());
        }

        chain.doFilter(new StrippedRequest(request), response);
    }

    /**
     * A view of the request with those headers absent.
     *
     * <p>The servlet API has no "remove header", so the request is wrapped and
     * the header accessors filtered. All four must be overridden together:
     * something downstream will use {@code getHeaderNames}, and a header that
     * disappears from one accessor but not another is worse than one that never
     * disappeared, because it looks handled.
     */
    private static final class StrippedRequest extends HttpServletRequestWrapper {

        StrippedRequest(HttpServletRequest request) {
            super(request);
        }

        private static boolean stripped(String name) {
            return name != null && STRIPPED.contains(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String getHeader(String name) {
            return stripped(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return stripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> kept = Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !stripped(name))
                    .toList();
            return Collections.enumeration(kept);
        }
    }
}
