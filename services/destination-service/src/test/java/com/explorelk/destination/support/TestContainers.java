package com.explorelk.destination.support;

import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/**
 * One PostGIS and one Redis, started once and shared by the whole suite.
 *
 * <p><strong>Started manually and never stopped, on purpose.</strong> The
 * {@code @Testcontainers}/{@code @Container} lifecycle would start and stop a
 * pair per test class; Ryuk reaps these when the JVM exits instead. Postgres and
 * Redis are cheap to start but not free, and a suite that pays for them a dozen
 * times is a suite people stop running.
 *
 * <p>The Postgres image <strong>must</strong> be PostGIS. The stock
 * {@code postgres} image fails on the very first line of {@code V1__init.sql}
 * with {@code CREATE EXTENSION postgis}, and the error names the extension
 * rather than the image, which is a confusing five minutes for whoever hits it.
 *
 * <p><strong>One database shared by every class also means one database every
 * class can dirty</strong>, and the read tests assert exact counts against the
 * seeded catalog — ten published destinations, seven categories. Left alone,
 * the two published places {@code AdminCatalogIT} creates make
 * {@code PublicCatalogIT} fail, and only when it happens to run second. The
 * snapshot/restore below removes the whole class of failure: each test records
 * what exists, and afterwards anything it added is deleted again. Running one
 * test, one class or the whole suite then gives the same answer.
 */
public abstract class TestContainers {

    /** Pinned to the same version the application runs against in docker-compose. */
    private static final DockerImageName POSTGIS_IMAGE = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("explorelk_destination_test")
            .withUsername("explorelk")
            .withPassword("explorelk")
            .withReuse(true);

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // The throwaway auth server, not the real one. Nothing in the test suite
        // reads auth-service's keys or requires it to be running.
        registry.add("explorelk.auth.jwks-uri", StubAuthServer::jwksUri);
        registry.add("explorelk.auth.issuer", () -> StubAuthServer.ISSUER);
    }

    // ── Leaving the catalog as it was found ──────────────────────────────────

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CacheManager cacheManager;

    private List<UUID> destinationBaseline;
    private List<UUID> attractionBaseline;
    private List<String> categoryBaseline;

    @BeforeEach
    void recordCatalogBaseline() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        destinationBaseline = jdbc.queryForList("SELECT id FROM destinations", UUID.class);
        attractionBaseline = jdbc.queryForList("SELECT id FROM attractions", UUID.class);
        categoryBaseline = jdbc.queryForList("SELECT code FROM categories", String.class);
    }

    @AfterEach
    void restoreCatalogBaseline() {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);

        // Attractions first: their destination_id is ON DELETE RESTRICT, which is
        // the schema refusing to orphan them and would refuse this too.
        jdbc.update("DELETE FROM attractions WHERE id NOT IN (:ids)",
                new MapSqlParameterSource("ids", attractionBaseline));
        jdbc.update("DELETE FROM destinations WHERE id NOT IN (:ids)",
                new MapSqlParameterSource("ids", destinationBaseline));
        jdbc.update("DELETE FROM categories WHERE code NOT IN (:codes)",
                new MapSqlParameterSource("codes", categoryBaseline));

        // Events describe rows that no longer exist, and one test asserting on a
        // topic must not see another test's backlog.
        jdbc.getJdbcTemplate().update("DELETE FROM outbox_events");

        // The deletes above went around JPA and therefore around every eviction
        // rule, so anything cached is now a description of rows that are gone.
        //
        // Swallowing the failure is not laziness here: CacheOutageIT deliberately
        // runs against an unreachable Redis, and a clear() called directly like
        // this bypasses the CacheErrorHandler that protects the application code.
        // Without the catch, the class that proves the cache is optional would be
        // the one class that cannot clean up after itself.
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache == null) {
                return;
            }
            try {
                cache.clear();
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(TestContainers.class)
                        .debug("Cache {} could not be cleared after the test: {}", name, e.getMessage());
            }
        });
    }
}
