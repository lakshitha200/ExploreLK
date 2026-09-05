package com.explorelk.auth;

import org.springframework.boot.SpringApplication;
import com.explorelk.auth.ratelimit.RateLimitProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * ExploreLK Auth Service.
 *
 * <p>Owns identity, authentication, account security and roles for the whole platform.
 * Signs JWTs with a private key that never leaves this service; every other service
 * verifies them with the public key served at {@code /.well-known/jwks.json}.
 */
@SpringBootApplication
@EnableJpaAuditing
// Rate-limit settings are read by a filter, a service and the verification
// flow, none of which is a @Configuration class of its own — so they are bound
// here rather than hidden inside whichever one happened to be written first.
@EnableConfigurationProperties(RateLimitProperties.class)
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
