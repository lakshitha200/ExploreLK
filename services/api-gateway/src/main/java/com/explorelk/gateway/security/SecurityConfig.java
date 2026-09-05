package com.explorelk.gateway.security;

import com.explorelk.gateway.common.ApiError;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.List;

/**
 * The edge: who gets through, and what the browser is allowed to ask.
 *
 * <p><strong>The gateway authenticates. It does not authorize.</strong> It
 * checks that a token is real — signed by the Auth Service, unexpired, carrying
 * the right issuer — and rejects it at the door if not. It does not decide
 * whether that token may archive a destination or suspend a user. Two reasons:
 *
 * <ul>
 *   <li>A gateway that knows every service's role rules has to be redeployed
 *       whenever any of them changes, and it will eventually disagree with the
 *       service it is protecting. The service that owns the data owns the rule.</li>
 *   <li>The services are reachable inside the compose network without passing
 *       through here. A service that trusted "something in front of me checked"
 *       would be wide open to anything already on that network — the confused
 *       deputy problem, which is why every one of them still verifies the token
 *       itself.</li>
 * </ul>
 *
 * <p>So this is defence in depth rather than duplication: the gateway stops
 * garbage early and cheaply, the services stay correct on their own.
 *
 * <p><strong>CORS lives here and only here.</strong> The browser only ever talks
 * to :8080, so one origin list governs the whole platform. The services keep
 * their own configuration for direct access during development, but in a
 * deployment nothing reaches them from a browser at all.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({AuthServerProperties.class, CorsProperties.class})
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    /**
     * Paths that must work without a token.
     *
     * <p>Logging in cannot require being logged in, and the catalog is public by
     * design — a traveler browses Sri Lanka without an account. Everything else
     * needs a valid token before it is worth proxying.
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/**",
            "/.well-known/**",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            // The fallbacks must answer while a downstream service is down —
            // including to a caller whose request never carried a token.
            "/fallback/**"
    };

    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
                // No cookies, no sessions, no CSRF vector. Every request carries
                // its own credentials or none.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // A preflight carries no Authorization header, by definition.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .requestMatchers(PUBLIC_PATHS).permitAll()

                        // The public catalog: GET only, and no token. A POST to
                        // the same path is an admin write and falls through to
                        // the authenticated rule below.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/destinations/**",
                                "/api/v1/attractions/**",
                                "/api/v1/categories/**").permitAll()

                        // Everything else needs a token that verifies. WHICH
                        // token — which role — is the downstream service's call.
                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler(this::writeForbidden))

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler(this::writeForbidden));

        return http.build();
    }

    /**
     * Verifies tokens the same way every other service does.
     *
     * <p>Fetch the Auth Service's public key once, cache it, and from then on
     * every check is local RSA math. The gateway has no user table, no shared
     * secret and no per-request call to auth — and an auth outage does not stop
     * it verifying the tokens already in flight.
     *
     * <p>Built by hand rather than through {@code withJwkSetUri} because the
     * cache windows are load-bearing: rate limiting the JWKS fetch stops a flood
     * of tokens with an unknown {@code kid} turning into a flood of HTTP calls
     * at the Auth Service, and outage tolerance keeps a warm key usable while
     * auth restarts.
     */
    @Bean
    public JwtDecoder jwtDecoder(AuthServerProperties properties) throws MalformedURLException {
        JWKSource<SecurityContext> jwkSource = JWKSourceBuilder
                .create(properties.jwksUri().toURL())
                .cache(properties.jwksCacheTtl().toMillis(), properties.jwksRefreshTimeout().toMillis())
                .rateLimited(properties.jwksRefreshTimeout().toMillis())
                .outageTolerant(properties.jwksOutageTtl().toMillis())
                .build();

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        // RS256 only. Trusting the algorithm named in the token's own header is
        // how the "alg: none" and HMAC-with-the-public-key forgeries work.
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        processor.setJWTClaimsSetVerifier((claims, context) -> { });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));

        log.info("Verifying tokens against {} (issuer {})", properties.jwksUri(), properties.issuer());
        return decoder;
    }

    /**
     * One origin list for the whole platform.
     *
     * <p>Explicit origins, never a wildcard — and credentials are off, because
     * tokens travel in the Authorization header rather than a cookie, so nothing
     * needs them and disallowing them narrows what any origin can do.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Request-Id"));
        // So a browser client can read the id back and quote it in a bug report.
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    // ── Error responses ──────────────────────────────────────────────────────

    private void writeUnauthorized(HttpServletRequest request,
                                   HttpServletResponse response,
                                   AuthenticationException ex) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "TOKEN_INVALID", "Token is invalid");
    }

    private void writeForbidden(HttpServletRequest request,
                                HttpServletResponse response,
                                AccessDeniedException ex) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to do that");
    }

    /** The same {@link ApiError} shape the services use, so clients parse one thing. */
    private void write(HttpServletRequest request,
                       HttpServletResponse response,
                       HttpStatus status,
                       String code,
                       String message) throws IOException {

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        // The id the RequestIdFilter already put on this request, so a rejected
        // call is as traceable as a successful one.
        String traceId = String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));

        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of(code, message, request.getRequestURI(), traceId));
    }
}
