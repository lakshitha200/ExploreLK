package com.explorelk.auth.token;

import com.explorelk.auth.config.JwtProperties;
import com.explorelk.auth.user.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Mints RS256 access tokens.
 *
 * <p>Signing is the only thing this service does with the private key, and no other
 * ExploreLK service ever holds it. They verify with the published public key instead,
 * which means a request can be authorised without a network call back here — that is
 * what lets the platform scale.
 *
 * <p>Claims are deliberately minimal. This token travels on every request to every
 * service, so anything not needed for an authorisation decision does not belong in it:
 * no name, no phone, no permission list. A service that needs the profile calls
 * {@code /users/me}.
 */
@Service
@Slf4j
public class JwtService {

    /** Non-standard claims, named once so signer and verifiers cannot drift. */
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_EMAIL_VERIFIED = "email_verified";

    private final RSAKey rsaKey;
    private final RSASSASigner signer;
    private final JwtProperties properties;
    private final TokenDenylistService denylist;

    public JwtService(RSAKey rsaKey, JwtProperties properties, TokenDenylistService denylist)
            throws JOSEException {
        this.rsaKey = rsaKey;
        this.properties = properties;
        this.denylist = denylist;
        // Built once — creating a signer per token is pure waste.
        this.signer = new RSASSASigner(rsaKey);
    }

    /**
     * Revokes an access token that has not expired yet.
     *
     * <p>Nothing in the token itself can be changed after signing, so revocation is
     * a note in Redis that the decoder consults. See {@link TokenDenylistService}.
     */
    public void denylist(String jti, Instant expiresAt) {
        denylist.deny(jti, expiresAt);
    }

    /**
     * Issues an access token for a user.
     *
     * @return the compact serialised JWT, and the {@code jti} that identifies it.
     *         The {@code jti} is what Step 6 puts on the Redis denylist at logout,
     *         so the caller needs it alongside the token itself.
     */
    public AccessToken issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.accessTokenTtl());
        String jti = UUID.randomUUID().toString();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_EMAIL_VERIFIED, user.isEmailVerified())
                .jwtID(jti)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();

        // The kid lets a verifier pick the right key once more than one is published.
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsaKey.getKeyID())
                .type(com.nimbusds.jose.JOSEObjectType.JWT)
                .build();

        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            // Signing only fails if the key is wrong, which is a startup-level fault.
            throw new IllegalStateException("Could not sign access token", e);
        }

        return new AccessToken(jwt.serialize(), jti, expiry);
    }

    /**
     * A minted access token.
     *
     * @param value     the compact JWT to hand the client
     * @param jti       its unique id, for revocation
     * @param expiresAt when it stops being accepted
     */
    public record AccessToken(String value, String jti, Instant expiresAt) {
    }
}
