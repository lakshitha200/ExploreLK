package com.explorelk.auth.token;

import com.nimbusds.jose.jwk.JWKSet;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Publishes the public half of the signing key.
 *
 * <p>This endpoint is how every other ExploreLK service learns to verify tokens.
 * Instead of copying a key file into Trip, Booking and Experience, each one points
 * Spring Security at this URL and caches what it finds:
 *
 * <pre>
 * spring:
 *   security:
 *     oauth2:
 *       resourceserver:
 *         jwt:
 *           jwk-set-uri: http://auth-service:8081/.well-known/jwks.json
 * </pre>
 *
 * <p>It is public and unauthenticated by design — a public key is meant to be public,
 * and requiring a token to fetch the key needed to verify tokens would be circular.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JWKSet jwkSet;

    /**
     * The JWKS document, at the path RFC 8615 reserves for it.
     *
     * <p>{@code toJSONObject(true)} restricts output to public parameters. Calling the
     * no-argument overload here would serialise the private exponent and hand out the
     * signing key — so the argument is not optional.
     */
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                // Keys change only on rotation. Let verifiers cache, but not so long
                // that a rotation takes hours to propagate.
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(jwkSet.toJSONObject(true));
    }
}
