package com.explorelk.auth.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the RSA keypair used to sign access tokens.
 *
 * <p>Generate a pair with:
 * <pre>
 *   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/private.pem
 *   openssl rsa -in keys/private.pem -pubout -out keys/public.pem
 * </pre>
 *
 * <p>{@code openssl genpkey} is used rather than {@code openssl genrsa} because it emits
 * PKCS#8 ({@code BEGIN PRIVATE KEY}), which Java reads directly. {@code genrsa} on older
 * OpenSSL emits PKCS#1 ({@code BEGIN RSA PRIVATE KEY}), which it cannot.
 *
 * <p>The keypair is exposed as a single {@link RSAKey}: the private half signs, and
 * {@code toPublicJWK()} produces exactly what belongs in the JWKS document — so the
 * published key can never drift from the signing key.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class})
@Slf4j
public class JwtKeyConfig {

    /**
     * Signing keypair, tagged with a {@code kid} derived from the key's own thumbprint.
     *
     * <p>The thumbprint matters for key rotation: publish old and new keys together,
     * each token names the key that signed it, and verifiers pick the right one. Without
     * a {@code kid} every rotation is a hard cutover that invalidates live tokens.
     */
    @Bean
    public RSAKey rsaKey(JwtProperties properties) throws JOSEException {
        RSAPublicKey publicKey = readPublicKey(properties.publicKey());
        RSAPrivateKey privateKey = readPrivateKey(properties.privateKey());

        RSAKey key = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyIDFromThumbprint()
                .build();

        log.info("Loaded RSA signing key kid={} size={} bits", key.getKeyID(), publicKey.getModulus().bitLength());
        return key;
    }

    /** What {@code /.well-known/jwks.json} serves. Public halves only. */
    @Bean
    public JWKSet jwkSet(RSAKey rsaKey) {
        return new JWKSet(rsaKey.toPublicJWK());
    }

    // ── PEM reading ──────────────────────────────────────────────────────────

    private static RSAPrivateKey readPrivateKey(Resource resource) {
        byte[] der = decodePem(resource, "PRIVATE KEY");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                    "Private key is not a readable PKCS#8 RSA key. Regenerate it with "
                            + "'openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048'", e);
        }
    }

    private static RSAPublicKey readPublicKey(Resource resource) {
        byte[] der = decodePem(resource, "PUBLIC KEY");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Public key is not a readable X.509/SPKI RSA key", e);
        }
    }

    /** Strips the PEM armour and base64-decodes the body. */
    private static byte[] decodePem(Resource resource, String expectedLabel) {
        if (resource == null || !resource.exists()) {
            throw new IllegalStateException(
                    "JWT key not found: " + resource + ". Generate the keypair into keys/ — "
                            + "see JwtKeyConfig for the two openssl commands.");
        }

        String pem;
        try (InputStream in = resource.getInputStream()) {
            pem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read JWT key " + resource, e);
        }

        if (!pem.contains("BEGIN " + expectedLabel)) {
            throw new IllegalStateException(
                    "Expected a '" + expectedLabel + "' PEM in " + resource + " but found a different header");
        }

        String body = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");

        return Base64.getDecoder().decode(body);
    }
}
