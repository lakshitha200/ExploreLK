package com.explorelk.destination.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code explorelk.cache.*} — how long each cache holds a value.
 *
 * <p>Configuration rather than constants because the right number is an
 * operational judgement, not a code decision: it trades staleness against
 * database load, and the acceptable staleness differs between a demo, a staging
 * environment and production.
 *
 * <p>The defaults follow the shape of the data. Categories almost never change,
 * so a day is fine. A single destination changes when an admin edits it, and
 * every edit evicts it anyway, so an hour is a backstop rather than the
 * mechanism. Lists are the shortest, because a list can go stale for a reason
 * that never touches its own key — publishing a <em>new</em> destination changes
 * what every list should contain.
 */
@ConfigurationProperties(prefix = "explorelk.cache")
public record CatalogCacheProperties(
        Duration destination,
        Duration destinationList,
        Duration attractionsOf,
        Duration categories
) {
}
