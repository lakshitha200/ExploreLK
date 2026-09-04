package com.explorelk.destination.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Where this service gets the key that verifies tokens, and how long it is
 * willing to keep using it.
 *
 * <p>Deliberately <em>not</em> Spring's
 * {@code spring.security.oauth2.resourceserver.jwt.*} block. Two reasons:
 * <ul>
 *   <li>{@code issuer-uri} means OIDC discovery over HTTP, and our issuer is the
 *       bare name {@code explorelk-auth}, not a URL. Configuring it there would
 *       make Spring try to fetch {@code /.well-known/openid-configuration} from a
 *       host that does not exist.</li>
 *   <li>The cache and outage windows below are the whole point of the JWKS
 *       design (see {@link SecurityConfig}), so they belong in configuration a
 *       reader can find, not in library defaults that change between versions.</li>
 * </ul>
 *
 * @param jwksUri        the Auth Service's public key set
 * @param issuer         the {@code iss} claim every accepted token must carry, so
 *                       a token minted by a dev Auth Service cannot be replayed
 *                       against production
 * @param jwksCacheTtl   how long a fetched key set is used before refreshing
 * @param jwksOutageTtl  how long a <em>stale</em> key set keeps being used while
 *                       the Auth Service is unreachable. This is what makes
 *                       "auth is down, already-issued tokens still work" true
 *                       rather than merely likely.
 * @param jwksRefreshTimeout how long to wait on the JWKS HTTP call before giving
 *                       up and falling back to the cached key set
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
