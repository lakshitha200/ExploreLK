package com.explorelk.gateway.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A downstream service, reduced to the only thing these tests need from one:
 * a record of exactly what arrived.
 *
 * <p>Proxying is a claim about what the other end receives, and almost every way
 * of getting it wrong is invisible from the client side. A gateway that quietly
 * rewrites the path, drops the Authorization header, or forwards a forged
 * {@code X-User-Id} still returns 200 to the caller — the request simply means
 * something different by the time it lands. So the assertions have to be made on
 * the receiving end, which is what this exists for.
 *
 * <p>The JDK's own {@link HttpServer} rather than WireMock or a second Spring
 * context: it starts in a millisecond on a random port, has no dependencies, and
 * a stub whose whole job is "remember the request and reply 200" needs nothing
 * more.
 */
public class StubService implements AutoCloseable {

    private final HttpServer server;
    private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();

    private volatile int statusCode = 200;
    private volatile String body = "{\"stub\":true}";
    private volatile long delayMillis = 0;

    public StubService(String name) {
        try {
            // Port 0: the OS picks a free one, so parallel test classes and a
            // developer's own running services never collide.
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the " + name + " stub", e);
        }

        server.createContext("/", exchange -> {
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((key, values) -> {
                if (!values.isEmpty()) {
                    // Lower-cased: HTTP header names are case-insensitive, and a
                    // test asserting on "x-user-id" must not pass or fail based
                    // on how the proxy happened to capitalise it.
                    headers.put(key.toLowerCase(), values.get(0));
                }
            });

            received.add(new ReceivedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(),
                    Map.copyOf(headers)));

            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });

        server.setExecutor(null);
        server.start();
    }

    public String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<ReceivedRequest> received() {
        return List.copyOf(received);
    }

    public Optional<ReceivedRequest> lastRequest() {
        return received.isEmpty() ? Optional.empty() : Optional.of(received.get(received.size() - 1));
    }

    public int requestCount() {
        return received.size();
    }

    public void clear() {
        received.clear();
    }

    /** Makes the next responses fail, for the circuit-breaker tests. */
    public void respondWith(int statusCode) {
        this.statusCode = statusCode;
    }

    /** Makes the stub slow, so a timeout can be tested without waiting for a real one. */
    public void delayBy(long millis) {
        this.delayMillis = millis;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Everything an assertion here could reasonably want to make. */
    public record ReceivedRequest(String method, String path, String query, Map<String, String> headers) {

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }

        public boolean hasHeader(String name) {
            return headers.containsKey(name.toLowerCase());
        }
    }

    /** Convenience for a fresh, empty recorder in a @BeforeEach. */
    public static List<ReceivedRequest> emptyLog() {
        return new ArrayList<>();
    }
}
