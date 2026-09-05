package com.explorelk.destination.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The shared setup for every test that needs the real stack.
 *
 * <p>The containers themselves live in {@link TestContainers} as static
 * singletons, so all of these tests share one PostGIS and one Redis for the
 * whole build rather than starting a pair each. That is the difference between
 * a suite that runs in under a minute and one nobody waits for.
 *
 * <p>{@code MockMvc} rather than a real port: it still runs the complete Spring
 * Security filter chain, which is what these tests are mostly about, without the
 * cost and flakiness of a socket.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
public @interface IntegrationTest {
}
