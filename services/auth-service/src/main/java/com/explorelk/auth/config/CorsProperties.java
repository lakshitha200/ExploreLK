package com.explorelk.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * CORS origins, bound from {@code explorelk.cors.allowed-origins}.
 *
 * <p>Per environment and explicit. Never {@code *} on an auth service.
 */
@ConfigurationProperties(prefix = "explorelk.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = (allowedOrigins == null || allowedOrigins.isEmpty())
                ? List.of()
                : List.copyOf(allowedOrigins);
    }
}
