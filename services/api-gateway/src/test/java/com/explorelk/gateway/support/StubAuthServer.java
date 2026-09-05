package com.explorelk.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * A throwaway Auth Service: one RSA keypair, generated here, published at a
 * {@code /.well-known/jwks.json} on a random port.
 *
 * <p><strong>The point is that this test never imports the Auth Service or its
 * keys.</strong> Reaching into {@code services/auth-service/keys/private.pem}
 * would make these tests pass for the wrong reason — they would prove the two
 * services share a file, when what needs proving is that this service can verify
 * a token from <em>any</em> issuer that publishes a JWKS. It would also mean a
 * key rotation over there silently breaks the build over here.
 *
 * <p>The server is {@code com.sun.net.httpserver.HttpServer} from the JDK rather
 * than WireMock, because serving one static JSON document does not justify a
 * dependency.
 */
public final class StubAuthServer {

    /** Matches {@code explorelk.auth.issuer}, and auth-service's own default. */
    public static final String ISSUER = "explorelk-auth";

    private static final StubAuthServer INSTANCE = new StubAuthServer();

    private final RSAKey signingKey;
    private final RSASSASigner signer;
    private final HttpServer server;

    private StubAuthServer() {
        try {
            this.signingKey = new RSAKeyGenerator(2048)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(UUID.randomUUID().toString())
                    .generate();
            this.signer = new RSASSASigner(signingKey);

            // Port 0: the OS picks a free one, so parallel builds on the same
            // machine do not fight over a hard-coded number.
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // Public half only. Publishing the private key would defeat the point
            // of the exercise even in a test.
            byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString()
                    .getBytes(StandardCharsets.UTF_8);

            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(jwks);
                }
            });
            server.start();

        } catch (JOSEException | IOException e) {
            throw new IllegalStateException("Could not start the stub auth server", e);
        }
    }

    public static StubAuthServer instance() {
        return INSTANCE;
    }

    public static String jwksUri() {
        return "http://127.0.0.1:" + INSTANCE.server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    /** A valid access token for the given role, shaped exactly like auth-service's. */
    public static String tokenFor(String role) {
        return INSTANCE.mint(role, ISSUER, Duration.ofMinutes(15));
    }

    /** A token from somewhere else entirely — used to prove {@code iss} is checked. */
    public static String tokenFromForeignIssuer(String role) {
        return INSTANCE.mint(role, "some-other-service", Duration.ofMinutes(15));
    }

    /** A token that was valid an hour ago. */
    public static String expiredToken(String role) {
        return INSTANCE.mint(role, ISSUER, Duration.ofMinutes(-60));
    }

    private String mint(String role, String issuer, Duration ttl) {
        Instant now = Instant.now();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(UUID.randomUUID().toString())
                // A single string, not a list — the same shape auth-service signs,
                // and the reason the stock authorities converter cannot be used.
                .claim("role", role)
                .claim("email_verified", true)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now.minus(Duration.ofMinutes(1))))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the test token", e);
        }
        return jwt.serialize();
    }
}
