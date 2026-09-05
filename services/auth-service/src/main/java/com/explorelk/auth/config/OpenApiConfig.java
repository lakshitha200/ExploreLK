package com.explorelk.auth.config;

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
 * <p>No global security requirement is declared. Most of this API is
 * necessarily public — registering and logging in cannot require a token — so
 * marking every operation as secured would describe a service that does not
 * exist. The {@code bearerAuth} scheme is registered so the <em>Authorize</em>
 * button exists; the endpoints that need it are the ones under
 * {@code /api/v1/admin} and {@code /api/v1/super-admin}.
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfig {

    @Bean
    public OpenAPI authServiceOpenApi(@Value("${server.port:8081}") int port) {
        return new OpenAPI()
                .info(new Info()
                        .title("ExploreLK — Auth Service")
                        .version("v1")
                        .description("""
                                Identity for the whole platform: registration, login,                                 refresh-token rotation, email verification, password                                 reset, and the administrative surface.

                                Everything under `/api/v1/auth` is public — you cannot                                 need a token to get a token. `/api/v1/admin` needs an                                 `ADMIN` or `SUPER_ADMIN` token and `/api/v1/super-admin`                                 needs a `SUPER_ADMIN` one; paste it into **Authorize**                                 above without the `Bearer ` prefix.

                                This service is the only one that SIGNS tokens. Every                                 other service verifies them against                                 `/.well-known/jwks.json` and never calls this one per                                 request.""")
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
                                .description("An access token from POST /api/v1/auth/login.")));
    }
}
