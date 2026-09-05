package com.explorelk.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * {@code explorelk.auth.*} — where the public key comes from, and how long it is
 * trusted for.
 *
 * <p>Note that {@code jwksUri} points at the Auth Service <em>directly</em>, not
 * at this gateway's own {@code /.well-known} route. A service that proxies to
 * itself in order to start up is a circular dependency, and it deadlocks the
 * first time the routing changes.
 *
 * @param jwksUri            the Auth Service's published key set
 * @param issuer             the {@code iss} claim every accepted token must carry,
 *                           so a token minted by a dev auth service cannot be
 *                           replayed against production
 * @param jwksCacheTtl       how long a fetched key set is reused. Every
 *                           verification in between is local math
 * @param jwksOutageTtl      how long a stale key set keeps being accepted when a
 *                           refresh fails. RSA signing keys do not rotate on the
 *                           hour, and rejecting valid tokens because auth is
 *                           restarting is the coupling this design removes
 * @param jwksRefreshTimeout also the rate-limit window: a flood of tokens with an
 *                           unknown kid must not become a flood of requests at
 *                           the Auth Service
 */
@ConfigurationProperties(prefix = "explorelk.auth")
public record AuthServerProperties(
        URI jwksUri,
        String issuer,
        Duration jwksCacheTtl,
        Duration jwksOutageTtl,
        Duration jwksRefreshTimeout
) {
}
