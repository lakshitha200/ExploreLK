package com.explorelk.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
