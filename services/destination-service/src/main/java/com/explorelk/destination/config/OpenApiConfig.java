package com.explorelk.destination.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The OpenAPI document behind {@code /swagger-ui.html}.
 *
 * <p><strong>Dev only.</strong> The whole of springdoc is disabled in
 * {@code application.yml} and re-enabled by {@code application-dev.yml}, and
 * this bean is conditional on the same switch — a published schema of every
 * admin endpoint, its request bodies and its validation rules is free
 * reconnaissance for anyone probing the service, and nothing in production
 * needs it.
 *
 * <p>No global security requirement is declared. Most of this API is genuinely
 * public — a traveler browses the catalog with no token at all — so marking
 * every operation as secured would describe a service that does not exist. The
 * {@code bearerAuth} scheme is registered so the <em>Authorize</em> button
 * exists and admin calls can be tried from the browser; the endpoints that
 * actually require it are the ones under {@code /api/v1/admin}, and their
 * controllers say so.
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfig {

    @Bean
    public OpenAPI destinationServiceOpenApi(@Value("${server.port:8082}") int port) {
        return new OpenAPI()
                .info(new Info()
                        .title("ExploreLK — Destination Service")
                        .version("v1")
                        .description("""
                                The catalog of where a traveler can go in Sri Lanka and what \
                                there is to see when they get there.

                                Everything under `/api/v1/destinations`, `/api/v1/attractions` \
                                and `/api/v1/categories` is public and returns `PUBLISHED` \
                                content only. Everything under `/api/v1/admin` needs an \
                                `ADMIN` or `SUPER_ADMIN` access token issued by the Auth \
                                Service — paste it into **Authorize** above, without the \
                                `Bearer ` prefix.

                                This service verifies that token against the Auth Service's \
                                published JWKS and never calls it per request.""")
                        .contact(new Contact().name("ExploreLK").email("dev@explorelk.local"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server()
                        .url("http://localhost:" + port)
                        .description("Local development")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("An access token from POST /api/v1/auth/login on "
                                        + "the Auth Service (http://localhost:8081).")));
    }
}
