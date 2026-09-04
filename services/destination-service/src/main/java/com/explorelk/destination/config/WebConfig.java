package com.explorelk.destination.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC-level CORS, so a browser app can call the public catalog during Steps 3–4.
 *
 * <p>Spring Security is not on the classpath yet. When it arrives in Step 5 this
 * registration keeps working for the paths the filter chain permits, but the
 * chain itself needs a {@code CorsConfigurationSource} bean — Security applies
 * CORS in its own filter, before MVC ever runs. Expect to move this configuration
 * into {@code SecurityConfig} then rather than to duplicate it.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final CorsProperties corsProperties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
