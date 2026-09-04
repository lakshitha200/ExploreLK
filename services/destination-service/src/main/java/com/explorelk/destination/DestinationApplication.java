package com.explorelk.destination;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * ExploreLK Destination Service.
 *
 * <p>The catalog of <em>where</em> a traveler can go in Sri Lanka and <em>what</em>
 * there is to see there. Trip, Itinerary and Experience services read from it; none
 * of them keep their own copy.
 *
 * <p>What this service deliberately does not do:
 * <ul>
 *   <li>It has no user table. Identity lives in the Auth Service, and this service
 *       authorizes requests by verifying the token signature against the public key
 *       at {@code /.well-known/jwks.json} — local math, no call per request.</li>
 *   <li>It never signs a token. It is a resource server only.</li>
 *   <li>It does not own trips, itineraries, experiences or bookings.</li>
 * </ul>
 *
 * <p>Runs on port 8082 (auth-service is 8081, the API gateway will take 8080).
 */
@SpringBootApplication
@EnableJpaAuditing // populates @CreatedDate / @LastModifiedDate on the entities
public class DestinationApplication {

    public static void main(String[] args) {
        SpringApplication.run(DestinationApplication.class, args);
    }
}
