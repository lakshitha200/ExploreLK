package com.explorelk.gateway.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code explorelk.gateway.*} — where the services behind this one actually are.
 *
 * <p>Addresses, not service discovery. Two services on one compose network do
 * not need Eureka or Consul to find each other, and adding one would mean a
 * registry that has to be running before anything else can start. Docker's own
 * DNS already resolves {@code auth-service} to the right container.
 *
 * @param authServiceUri        base URI of auth-service, no trailing path
 * @param destinationServiceUri base URI of destination-service
 */
@ConfigurationProperties(prefix = "explorelk.gateway")
public record GatewayProperties(
        String authServiceUri,
        String destinationServiceUri
) {
}
