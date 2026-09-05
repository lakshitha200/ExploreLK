package com.explorelk.auth.support;

import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * One Postgres and one Redis, started once and shared by the whole suite.
 *
 * <p><strong>Started manually and never stopped, on purpose.</strong> The
 * {@code @Testcontainers}/{@code @Container} lifecycle would start and stop a
 * pair per test class; Ryuk reaps these when the JVM exits instead. They are
 * cheap to start but not free, and a suite that pays for them a dozen times is
 * a suite people stop running.
 *
 * <p><strong>Every test leaves the database empty.</strong> One database shared
 * by every class means every class can dirty it, and these tests count rows —
 * "six failed logins lock the account" is meaningless if a previous class left
 * three. Truncating after each test is cheaper than reasoning about which class
 * ran first, and makes running one test give the same answer as running all of
 * them.
 *
 * <p>Redis is flushed for the same reason: the rate limiter counts per IP, and
 * every MockMvc request arrives from the same one.
 */
public abstract class TestContainers {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("explorelk_auth_test")
                    .withUsername("explorelk")
                    .withPassword("explorelk")
                    .withReuse(true);

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * A throwaway RSA keypair, generated once per JVM and written to temp files.
     *
     * <p>The alternative is pointing the test profile at {@code keys/}, which is
     * git-ignored and therefore absent on a fresh clone and in CI — so the suite
     * would fail for everyone who had not run the key-generation step by hand.
     * Worse, it would make the developer's real signing key a build input.
     *
     * <p>Generating a 2048-bit key costs a few hundred milliseconds once. Tokens
     * signed with it verify against the JWKS this same context serves, which is
     * all any test here needs.
     */
    private static final Path KEY_DIRECTORY = generateSigningKeys();

    private static Path generateSigningKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();

            Path directory = Files.createTempDirectory("explorelk-auth-test-keys");
            directory.toFile().deleteOnExit();

            writePem(directory.resolve("private.pem"), "PRIVATE KEY", pair.getPrivate().getEncoded());
            writePem(directory.resolve("public.pem"), "PUBLIC KEY", pair.getPublic().getEncoded());

            return directory;

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No RSA on this JVM", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the test signing keys", e);
        }
    }

    private static void writePem(Path file, String label, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, System.lineSeparator().getBytes()).encodeToString(der);
        Files.writeString(file, "-----BEGIN " + label + "-----" + System.lineSeparator()
                + body + System.lineSeparator()
                + "-----END " + label + "-----" + System.lineSeparator());
        file.toFile().deleteOnExit();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("explorelk.jwt.private-key", () -> "file:" + KEY_DIRECTORY.resolve("private.pem"));
        registry.add("explorelk.jwt.public-key", () -> "file:" + KEY_DIRECTORY.resolve("public.pem"));
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    @AfterEach
    void resetState() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // One statement, CASCADE, and users last: refresh_tokens and both token
        // tables reference it. RESTART IDENTITY is not needed — every id here is
        // a UUID — but TRUNCATE is still far faster than DELETE and does not
        // leave the tables needing a vacuum after a few hundred tests.
        jdbc.execute("""
                TRUNCATE TABLE outbox_events,
                               refresh_tokens,
                               email_verification_tokens,
                               password_reset_tokens,
                               users
                CASCADE""");

        try {
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        } catch (RuntimeException e) {
            // A test that deliberately breaks Redis must still be able to finish.
            LoggerFactory.getLogger(TestContainers.class)
                    .debug("Could not flush Redis after the test: {}", e.getMessage());
        }
    }
}
