package com.explorelk.destination.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * {@code explorelk.cors.allowed-origins} — an explicit per-environment allowlist.
 *
 * <p>Never a wildcard. Browsers reject {@code *} together with credentials anyway,
 * and this service accepts admin tokens.
 */
@ConfigurationProperties(prefix = "explorelk.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
