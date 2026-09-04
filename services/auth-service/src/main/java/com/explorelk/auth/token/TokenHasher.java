package com.explorelk.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates opaque tokens and hashes them for storage.
 *
 * <p>Used for refresh tokens, email verification and password reset — anything where
 * a random secret is emailed or handed to a client but must not sit readable in the
 * database.
 *
 * <p><b>Why SHA-256 and not BCrypt.</b> These are 256 bits of output from a CSPRNG,
 * not something a human chose, so there is no dictionary to attack and nothing for a
 * slow hash to buy. BCrypt would only make every token lookup cost ~250ms. Passwords
 * are the opposite case, and use BCrypt.
 */
public final class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256 bits

    private TokenHasher() {
    }

    /**
     * A fresh URL-safe token. This value is handed out once — in a response body or an
     * email link — and never again, because only its hash is kept.
     */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256, lowercase hex. Stable, so it can be a unique index. */
    public static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and always present", e);
        }
    }
}
