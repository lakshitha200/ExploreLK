package com.explorelk.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing.
 *
 * <p>BCrypt at strength 12 — roughly 250ms per hash on typical hardware. That cost is
 * the point: it is negligible once per login and ruinous for an attacker working
 * through a stolen table. Raising the strength is safe at any time, because the cost
 * factor is stored inside each hash and old hashes keep verifying with their original
 * setting.
 *
 * <p>Declared as {@link PasswordEncoder} so the algorithm can change without touching
 * any caller.
 */
@Configuration
public class PasswordConfig {

    private static final int BCRYPT_STRENGTH = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }
}
