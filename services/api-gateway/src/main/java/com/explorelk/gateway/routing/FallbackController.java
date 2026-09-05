package com.explorelk.gateway.routing;

import com.explorelk.gateway.common.ApiError;
import com.explorelk.gateway.security.RequestIdFilter;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;

import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a caller gets when a service behind the gateway is down.
 *
 * <p>The circuit breaker on each route sends here after repeated failures or a
 * timeout. The alternative — letting the failure through — is a stack trace, an
 * HTML error page, or a request that hangs until the client gives up. None of
 * those is something a mobile app can act on.
 *
 * <p><strong>503, not 500.</strong> The distinction is the whole message: 500
 * says "this request is broken, do not repeat it", 503 says "this service is
 * temporarily away, try again shortly". A client that retries a 500 is wrong; a
 * client that gives up on a 503 loses a request that would have worked a second
 * later. {@code Retry-After} makes that explicit rather than a matter of
 * interpretation.
 *
 * <p>These paths are public in {@code SecurityConfig} on purpose. A caller whose
 * request never carried a token still deserves to be told the catalog is down,
 * rather than being asked to authenticate to hear it.
 */
@RestController
@Slf4j
public class FallbackController {

    @RequestMapping("/fallback/auth-service")
    public ResponseEntity<ApiError> authServiceDown(HttpServletRequest request) {
        return unavailable(request, "auth-service",
                "Sign-in is temporarily unavailable. Please try again shortly.");
    }

    @RequestMapping("/fallback/destination-service")
    public ResponseEntity<ApiError> destinationServiceDown(HttpServletRequest request) {
        return unavailable(request, "destination-service",
                "The destination catalog is temporarily unavailable. Please try again shortly.");
    }

    private ResponseEntity<ApiError> unavailable(HttpServletRequest request, String service, String message) {
        String traceId = String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));

        // The service name goes in the log, not in the response. A client cannot
        // act on which internal component failed, and publishing the topology of
        // a platform is free reconnaissance.
        log.error("Circuit open or call failed for {} — returning 503 [{}]", service, traceId);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(ApiError.of("SERVICE_UNAVAILABLE", message, originalPath(request), traceId));
    }

    /**
     * The path the client asked for, not {@code /fallback/destination-service}.
     *
     * <p>Two reasons, and the second is the one that matters. The client asked
     * about a destination and should be told that <em>that</em> request failed;
     * an internal fallback path in the error body is meaningless to them. And
     * the fallback path is named after the service, so echoing it back would put
     * the platform's internal topology in a response body — undoing the care
     * taken above to keep the service name out of it.
     */
    private static String originalPath(HttpServletRequest request) {
        Object original = request.getAttribute(MvcUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (original instanceof URI uri) {
            return uri.getPath();
        }
        // Reached only if the circuit opened before routing set the attribute.
        // A generic path is a worse answer than the real one, but a far better
        // one than leaking the route id.
        return "/";
    }
}
