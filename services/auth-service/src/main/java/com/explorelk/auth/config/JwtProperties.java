package com.explorelk.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;

/**
 * JWT settings, bound from {@code explorelk.jwt.*}.
 *
 * @param issuer         the {@code iss} claim. Other services reject tokens whose issuer
 *                       is not theirs, so a token minted against a dev environment cannot
 *                       be replayed at production.
 * @param accessTokenTtl how long an access token stays valid. Short on purpose: an access
 *                       token cannot be revoked by anything except the Redis denylist
 *                       (Step 6), so its lifetime is the real blast radius of a leak.
 * @param refreshTokenTtl how long a refresh token stays valid. Long, because it is
 *                       checked against the database on every use and can therefore be
 *                       revoked the moment anything looks wrong — unlike an access token.
 * @param privateKey     PKCS#8 PEM. Signs. Never leaves this service.
 * @param publicKey      SPKI PEM. Verifies. Published at {@code /.well-known/jwks.json}.
 */
@ConfigurationProperties(prefix = "explorelk.jwt")
public record JwtProperties(
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Resource privateKey,
        Resource publicKey
) {
}
