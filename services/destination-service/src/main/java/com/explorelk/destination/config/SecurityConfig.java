package com.explorelk.destination.config;

import com.explorelk.destination.common.ApiError;
import com.explorelk.destination.common.ErrorCode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.MalformedURLException;
import java.time.Instant;
import java.util.List;

/**
 * The filter chain, and the first place in the platform where one service trusts
 * another without talking to it.
 *
 * <p>The Auth Service signs access tokens with a private RSA key it never shares,
 * and publishes the matching public key at {@code /.well-known/jwks.json}. This
 * service fetches that key <strong>once</strong>, caches it, and from then on
 * authorizes every request with local signature math:
 *
 * <pre>
 * Request with Bearer token
 *     |- verify RS256 signature with the cached public key   &lt;- no network call
 *     |- check iss / exp / nbf
 *     +- read the `role` claim  --&gt;  hasRole('ADMIN')
 * </pre>
 *
 * <p>There is no shared secret, no per-request call to the Auth Service, and no
 * user table in this database. Stopping the Auth Service does not stop this one.
 *
 * <p><strong>The accepted trade:</strong> this service cannot see a logout. The
 * Redis {@code jti} denylist lives in the Auth Service, so a token revoked at
 * logout keeps working here until it expires — at most fifteen minutes. Nothing
 * in this service is destructive enough to need sub-15-minute revocation, and
 * "fixing" it by calling Auth on every request throws away the whole design.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({AuthServerProperties.class, CorsProperties.class})
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    /** The claim name the Auth Service writes. Named once so the two cannot drift. */
    public static final String CLAIM_ROLE = "role";

    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                          JwtDecoder jwtDecoder,
                                          @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled)
            throws Exception {
        http
                // No cookies, no sessions, so there is no CSRF vector to protect.
                .csrf(AbstractHttpConfigurer::disable)

                // Picks up the corsConfigurationSource bean below by name. Injecting
                // the type instead is ambiguous — Spring MVC's
                // mvcHandlerMappingIntrospector implements the same interface, which
                // is the "required a single bean, but 2 were found" trap from the docs.
                .cors(Customizer.withDefaults())

                // Every request carries its own credentials. A session would outlive
                // token expiry and keep granting access the token no longer does.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> {
                    // Swagger, and only where it exists. Opening these paths
                    // unconditionally would be harmless today — springdoc is not even
                    // on the classpath's active configuration outside dev — but it
                    // would silently become a public schema endpoint the day someone
                    // enables the property in another profile.
                    if (apiDocsEnabled) {
                        auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll();
                    }

                    auth
                        // A CORS preflight carries no Authorization header, by definition.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // The catalog is public: travelers browse without an account.
                        // Only PUBLISHED rows can come back, and that is enforced in the
                        // repository layer rather than here, so a new endpoint cannot
                        // forget it.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/destinations", "/api/v1/destinations/**",
                                "/api/v1/attractions", "/api/v1/attractions/**",
                                "/api/v1/categories").permitAll()

                        // Probes must answer while the app is unhealthy.
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info").permitAll()

                        // Writes. Scoped by path rather than left to @PreAuthorize
                        // alone, so a new admin endpoint is protected the moment it
                        // exists — before its author has annotated anything.
                        .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")

                        .anyRequest().authenticated();
                })

                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        // Without these a rejected token yields an empty 401 body and
                        // the client cannot tell "expired" from "malformed".
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler(this::writeForbidden))

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler(this::writeForbidden));

        return http.build();
    }

    /**
     * Verifies tokens against the Auth Service's published public key.
     *
     * <p>Built by hand rather than through {@code NimbusJwtDecoder.withJwkSetUri},
     * because the cache windows are load-bearing here and the defaults behind that
     * factory are a library implementation detail. Three behaviours are configured
     * explicitly:
     *
     * <ul>
     *   <li><strong>cache</strong> — the key set is fetched once and reused for
     *       {@code jwksCacheTtl}. Every verification in between is local math.</li>
     *   <li><strong>rate limiting</strong> — a flood of tokens carrying an unknown
     *       {@code kid} cannot turn into a flood of HTTP calls to the Auth Service.
     *       Without it, this endpoint is a free amplifier pointed at auth.</li>
     *   <li><strong>outage tolerance</strong> — when a refresh fails because the
     *       Auth Service is down, the stale key set keeps being used for
     *       {@code jwksOutageTtl}. RSA signing keys do not rotate on the hour, and
     *       a catalog that starts rejecting valid tokens because an unrelated
     *       service is restarting is exactly the coupling this design removes.</li>
     * </ul>
     *
     * <p>Claim validation stays with Spring: {@code exp} and {@code nbf} with a
     * small clock skew, plus {@code iss} equal to our issuer.
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
        // RS256 only. Trusting the algorithm named in the token's own header is how
        // the "alg: none" and HMAC-with-the-public-key forgeries work.
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        // Nimbus would otherwise apply its own claim rules on top of Spring's. One
        // place decides what a valid claim set is, and it is the validator below.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));

        log.info("Verifying tokens against {} (issuer {}, key cache {}, outage tolerance {})",
                properties.jwksUri(), properties.issuer(),
                properties.jwksCacheTtl(), properties.jwksOutageTtl());

        return decoder;
    }

    /**
     * Maps the Auth Service's single {@code role} claim onto one Spring authority.
     *
     * <p>{@code hasRole('ADMIN')} looks for an authority named literally
     * {@code ROLE_ADMIN}, so the prefix is added here. Forgetting it is the classic
     * cause of "my rule always returns 403".
     *
     * <p>The claim holds one string, not a list, which is why the stock
     * {@code JwtGrantedAuthoritiesConverter} is not used: it expects a collection
     * and would quietly produce no authorities at all.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString(CLAIM_ROLE);
            if (role == null || role.isBlank()) {
                return List.<GrantedAuthority>of();
            }
            return List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }

    /**
     * CORS, moved here from the MVC-level registration it had before Step 5.
     *
     * <p>Spring Security applies CORS in its own filter, which runs long before MVC
     * — an MVC-only registration lets a preflight be rejected as unauthenticated
     * before any CORS header is written.
     *
     * <p>An explicit origin list, never a wildcard.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        // Tokens travel in the Authorization header, not in a cookie, so credentialed
        // requests are not needed — and disallowing them narrows what an origin can do.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    // ── Error responses ──────────────────────────────────────────────────────

    private void writeUnauthorized(HttpServletRequest request,
                                   HttpServletResponse response,
                                   AuthenticationException ex) throws IOException {
        write(request, response, ErrorCode.UNAUTHORIZED);
    }

    private void writeForbidden(HttpServletRequest request,
                                HttpServletResponse response,
                                AccessDeniedException ex) throws IOException {
        write(request, response, ErrorCode.FORBIDDEN);
    }

    /** The same {@link ApiError} shape the controllers use, so clients parse one thing. */
    private void write(HttpServletRequest request,
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
