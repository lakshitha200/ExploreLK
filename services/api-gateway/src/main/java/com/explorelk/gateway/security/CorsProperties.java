package com.explorelk.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * {@code explorelk.cors.allowed-origins} — the one origin list for the platform.
 *
 * <p>Explicit per environment, never a wildcard. A wildcard on the front door of
 * a service that proxies to an admin API means any page on the internet can make
 * a traveler's browser call it.
 */
@ConfigurationProperties(prefix = "explorelk.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
