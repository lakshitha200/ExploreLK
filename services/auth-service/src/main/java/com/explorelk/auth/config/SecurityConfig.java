package com.explorelk.auth.config;

import com.explorelk.auth.common.ApiError;
import com.explorelk.auth.common.ErrorCode;
import com.explorelk.auth.token.JwtService;
import com.explorelk.auth.token.TokenDenylistService;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

/**
 * The filter chain.
 *
 * <p>This service both mints and consumes its own tokens. Consuming them through
 * {@code oauth2ResourceServer} rather than a hand-written filter is deliberate:
 * signature checking, {@code exp}, {@code nbf} and issuer validation are exactly the
 * places auth bugs hide, and Spring Security already gets them right. The only custom
 * logic layered on is the denylist check.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
                // No cookies, no sessions, so there is no CSRF vector to protect.
                // (Were tokens ever moved into cookies, this must come back on.)
                .csrf(AbstractHttpConfigurer::disable)
                // Picks up the bean named `corsConfigurationSource` below. Injecting
                // CorsConfigurationSource by type instead would be ambiguous: Spring MVC's
                // mvcHandlerMappingIntrospector implements the same interface.
                .cors(Customizer.withDefaults())

                // Every request carries its own credentials. Spring must not create
                // a session, or one leaked JSESSIONID would outlive token expiry.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Getting a token cannot itself require a token.
                        .requestMatchers("/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/verify-email",
                                "/api/v1/auth/resend-verification",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password").permitAll()

                        // A public key is meant to be public. Requiring a token to
                        // fetch the key that verifies tokens would be circular.
                        .requestMatchers("/.well-known/**").permitAll()

                        // Probes must answer while the app is unhealthy.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()

                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Role checks live on the endpoints themselves via
                        // @PreAuthorize (Step 10); everything else needs a valid token.
                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        // Without these, a rejected token yields an empty 401 body
                        // and the client cannot tell "expired" from "malformed".
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler(this::writeForbidden))

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler(this::writeForbidden));

        return http.build();
    }

    /**
     * Verifies tokens with the public half of our own signing key.
     *
     * <p>Other services do the same job with {@code jwk-set-uri} pointed at this
     * service. Here the key is already in memory, so there is no reason to fetch it
     * over HTTP from ourselves.
     *
     * <p>The denylist validator is what makes logout able to kill an access token that
     * has not expired yet — a plain JWT is otherwise valid until {@code exp} no matter
     * what the server thinks.
     */
    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey,
                                 JwtProperties properties,
                                 TokenDenylistService denylist) throws Exception {
        RSAPublicKey publicKey = rsaKey.toRSAPublicKey();

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();

        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                // Checks exp/nbf with a small clock skew, and that iss is ours.
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new NotDenylistedValidator(denylist)));

        return decoder;
    }

    /** Rejects a token whose {@code jti} was denylisted at logout. */
    @RequiredArgsConstructor
    static class NotDenylistedValidator implements OAuth2TokenValidator<Jwt> {

        private final TokenDenylistService denylist;

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            String jti = token.getId();
            if (jti != null && denylist.isDenied(jti)) {
                return OAuth2TokenValidatorResult.failure(
                        new org.springframework.security.oauth2.core.OAuth2Error(
                                "invalid_token", "Token has been revoked", null));
            }
            return OAuth2TokenValidatorResult.success();
        }
    }

    /**
     * Maps our single {@code role} claim onto one Spring authority.
     *
     * <p>Spring's {@code hasRole('ADMIN')} looks for an authority literally named
     * {@code ROLE_ADMIN}, so the prefix is added here. Forgetting it is the classic
     * cause of "my @PreAuthorize always returns 403".
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("ROLE_");
        authorities.setAuthoritiesClaimName(JwtService.CLAIM_ROLE);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString(JwtService.CLAIM_ROLE);
            if (role == null || role.isBlank()) {
                return List.<GrantedAuthority>of();
            }
            return List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }

    /**
     * CORS.
     *
     * <p>An explicit origin list, never a wildcard — {@code allowCredentials} with
     * {@code *} is rejected by browsers anyway, and a wildcard on an auth service is
     * an invitation.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    // ── Error responses ──────────────────────────────────────────────────────

    private void writeUnauthorized(jakarta.servlet.http.HttpServletRequest request,
                                   HttpServletResponse response,
                                   org.springframework.security.core.AuthenticationException ex) throws IOException {
        write(request, response, ErrorCode.TOKEN_INVALID);
    }

    private void writeForbidden(jakarta.servlet.http.HttpServletRequest request,
                                HttpServletResponse response,
                                org.springframework.security.access.AccessDeniedException ex) throws IOException {
        write(request, response, ErrorCode.FORBIDDEN);
    }

    /** Same {@link ApiError} shape the controllers use, so clients parse one thing. */
    private void write(jakarta.servlet.http.HttpServletRequest request,
                       HttpServletResponse response,
                       ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ApiError body = new ApiError(
                code.name(), code.defaultMessage(), Instant.now(),
                request.getRequestURI(), "sec-" + Long.toHexString(System.nanoTime()), List.of());

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
