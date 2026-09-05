package com.explorelk.gateway.routing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions.circuitBreaker;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

/**
 * The public URL space, and which service owns each part of it.
 *
 * <p><strong>The paths are not split by service prefix, and cannot be.</strong>
 * Both services already own routes under {@code /api/v1/admin}: users and
 * providers belong to auth, destinations and attractions to the catalog. The
 * obvious gateway design — {@code /auth/**} to one, {@code /catalog/**} to the
 * other — would mean rewriting every URL the services already publish, breaking
 * the direct-access URLs their own tests and READMEs use.
 *
 * <p>So routing is by <em>specific</em> prefix, and the order below matters:
 *
 * <pre>
 *   /api/v1/auth/**                 ─┐
 *   /api/v1/users/**                 │
 *   /api/v1/super-admin/**           ├─► auth-service        :8081
 *   /api/v1/admin/users/**           │
 *   /api/v1/admin/providers/**      ─┘
 *   /.well-known/jwks.json          ─┘
 *
 *   /api/v1/destinations/**         ─┐
 *   /api/v1/attractions/**           │
 *   /api/v1/categories/**            ├─► destination-service :8082
 *   /api/v1/admin/destinations/**    │
 *   /api/v1/admin/attractions/**     │
 *   /api/v1/admin/categories/**     ─┘
 * </pre>
 *
 * <p>There is deliberately <strong>no catch-all</strong>. An unmatched path is a
 * 404 from the gateway rather than a guess at which service might want it: a
 * default route is how a typo in one service's path silently starts reaching
 * another, and how a service that was never meant to be public becomes public
 * the day someone adds it to the network.
 *
 * <p>No path rewriting happens either. What the client asked for is what the
 * service receives, so a URL in a log line means the same thing on both sides of
 * the gateway — and a developer can bypass the gateway while debugging and get
 * identical behaviour.
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class RouteConfig {

    /**
     * Identity, sessions, and the administration of users.
     *
     * <p>{@code /.well-known/jwks.json} is routed too, so a client — or a future
     * service outside this network — can fetch the public key through the one
     * address the platform advertises. The gateway itself does not use this
     * route: it reads the JWKS directly from the auth service, because a service
     * proxying to itself to start up is a circular dependency waiting to
     * deadlock.
     */
    @Bean
    public RouterFunction<ServerResponse> authServiceRoutes(GatewayProperties properties) {
        return route("auth-service")
                .route(path("/api/v1/auth/**"), http())
                .route(path("/api/v1/users/**"), http())
                .route(path("/api/v1/super-admin/**"), http())
                // The two halves of /admin that belong to auth. Listed explicitly
                // rather than as /api/v1/admin/** — that would swallow the
                // catalog's admin endpoints as well.
                .route(path("/api/v1/admin/users/**"), http())
                .route(path("/api/v1/admin/providers/**"), http())
                .route(path("/.well-known/**"), http())
                .before(uri(properties.authServiceUri()))
                // Named per service, so the two trip independently: a broken
                // catalog must not stop anyone logging in.
                .filter(breaker("auth-service"))
                .build();
    }

    /** The catalog: destinations, attractions, categories, and their administration. */
    @Bean
    public RouterFunction<ServerResponse> destinationServiceRoutes(GatewayProperties properties) {
        return route("destination-service")
                .route(path("/api/v1/destinations/**"), http())
                .route(path("/api/v1/attractions/**"), http())
                .route(path("/api/v1/categories/**"), http())
                .route(path("/api/v1/admin/destinations/**"), http())
                .route(path("/api/v1/admin/attractions/**"), http())
                .route(path("/api/v1/admin/categories/**"), http())
                .before(uri(properties.destinationServiceUri()))
                .filter(breaker("destination-service"))
                .build();
    }

    /**
     * One breaker per service, and a deliberate choice about what counts as a
     * failure.
     *
     * <p>A dropped connection or a timeout always trips it — those say the
     * service is not there. The status codes added here are the ones that mean
     * the same thing: {@code 502}, {@code 503} and {@code 504} are a service
     * saying, in HTTP, that it cannot serve right now.
     *
     * <p><strong>{@code 500} is deliberately not in that list.</strong> A 500 is
     * an application bug on one endpoint — a null pointer in one handler, a bad
     * query on one path. Counting it would let a single broken endpoint trip the
     * breaker for the whole service, so every other endpoint of a mostly-healthy
     * service would start returning 503. That turns one bug into an outage, which
     * is the opposite of what a breaker is for.
     */
    private static HandlerFilterFunction<ServerResponse, ServerResponse> breaker(String service) {
        return circuitBreaker(config -> config
                .setId(service)
                .setFallbackPath("/fallback/" + service)
                .setStatusCodes("502", "503", "504"));
    }
}
