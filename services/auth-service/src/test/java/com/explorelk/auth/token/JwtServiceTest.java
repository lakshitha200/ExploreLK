package com.explorelk.auth.token;

import com.explorelk.auth.config.JwtProperties;
import com.explorelk.auth.user.User;
import com.explorelk.auth.user.UserRole;
import com.explorelk.auth.user.UserStatus;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 4 checkpoint.
 *
 * <p>The claim worth proving is not "a token was produced" but "the key we publish
 * verifies the token we signed" — that round trip is the entire basis on which every
 * other service trusts this one. It is asserted here rather than eyeballed on jwt.io,
 * and no key-minting endpoint has to exist at runtime for it.
 *
 * <p>Uses a keypair generated in-test, so it neither depends on {@code keys/} being
 * present nor touches the real signing key.
 */
class JwtServiceTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    private RSAKey rsaKey;
    private JwtService jwtService;

    @BeforeEach
    void setUp() throws Exception {
        rsaKey = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyIDFromThumbprint(true)
                .generate();

        JwtProperties properties =
                new JwtProperties("explorelk-auth", TTL, Duration.ofDays(30), null, null);

        // Denylisting is Redis-backed and irrelevant to signing; a no-op stand-in keeps
        // this a unit test rather than dragging a container in.
        TokenDenylistService noDenylist = new TokenDenylistService(null) {
            @Override
            public void deny(String jti, Instant expiresAt) {
            }

            @Override
            public boolean isDenied(String jti) {
                return false;
            }
        };

        jwtService = new JwtService(rsaKey, properties, noDenylist);
    }

    @Test
    @DisplayName("the published public key verifies a token signed with the private key")
    void publicKeyVerifiesSignedToken() throws Exception {
        User user = traveler();

        JwtService.AccessToken issued = jwtService.issueAccessToken(user);
        SignedJWT parsed = SignedJWT.parse(issued.value());

        // Verify using ONLY what the JWKS endpoint hands out — public parameters,
        // reconstructed the way another service would.
        JWKSet published = new JWKSet(rsaKey.toPublicJWK());
        RSAKey publicOnly = (RSAKey) JWKSet.parse(published.toJSONObject(true))
                .getKeyByKeyId(rsaKey.getKeyID());

        assertThat(publicOnly).isNotNull();
        assertThat(publicOnly.isPrivate())
                .as("the JWKS document must never contain private key material")
                .isFalse();
        assertThat(parsed.verify(new RSASSAVerifier(publicOnly)))
                .as("signature must verify against the published key")
                .isTrue();
    }

    @Test
    @DisplayName("token carries exactly the claims other services rely on")
    void tokenCarriesExpectedClaims() throws Exception {
        User user = traveler();

        JwtService.AccessToken issued = jwtService.issueAccessToken(user);
        SignedJWT parsed = SignedJWT.parse(issued.value());
        var claims = parsed.getJWTClaimsSet();

        assertThat(claims.getIssuer()).isEqualTo("explorelk-auth");
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.getStringClaim(JwtService.CLAIM_ROLE)).isEqualTo("TRAVELER");
        assertThat(claims.getBooleanClaim(JwtService.CLAIM_EMAIL_VERIFIED)).isTrue();

        // jti is what makes logout revocation possible in Step 6.
        assertThat(claims.getJWTID()).isNotBlank().isEqualTo(issued.jti());

        // The kid tells a verifier which published key to use.
        assertThat(parsed.getHeader().getKeyID()).isEqualTo(rsaKey.getKeyID());
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
    }

    @Test
    @DisplayName("no personal data leaks into the token")
    void tokenOmitsPersonalData() throws Exception {
        User user = traveler();

        String token = jwtService.issueAccessToken(user).value();
        var claims = SignedJWT.parse(token).getJWTClaimsSet();

        // The token rides on every request to every service. A password hash or an
        // address in here would be handed to all of them, and to anything logging headers.
        assertThat(claims.getClaims()).doesNotContainKeys("email", "fullName", "phone", "passwordHash");
        assertThat(token).doesNotContain(user.getEmail());
    }

    @Test
    @DisplayName("expiry is set from the configured TTL")
    void expirySetFromTtl() {
        Instant before = Instant.now();

        JwtService.AccessToken issued = jwtService.issueAccessToken(traveler());

        assertThat(issued.expiresAt())
                .isAfter(before.plus(TTL).minusSeconds(5))
                .isBefore(before.plus(TTL).plusSeconds(5));
    }

    private static User traveler() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("nimal@explorelk.lk")
                .passwordHash("$2a$12$notarealhash")
                .fullName("Nimal Perera")
                .role(UserRole.TRAVELER)
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(Instant.now())
                .build();
    }
}
