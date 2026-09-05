package com.explorelk.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ExploreLK API Gateway.
 *
 * <p>The single address the outside world knows: <strong>:8080</strong>. Browsers,
 * apps and curl talk to this; the services behind it are reachable only inside
 * the compose network.
 *
 * <p>What lives here, and nowhere else:
 * <ul>
 *   <li><b>Routing.</b> One public URL space fanned out to the service that owns
 *       each path.</li>
 *   <li><b>CORS.</b> One origin list, in one place. Without a gateway every
 *       service needs its own copy and they drift.</li>
 *   <li><b>Edge rate limiting.</b> Shared counters in Redis, so a burst is
 *       stopped before it costs a downstream service anything.</li>
 *   <li><b>Identity header hygiene.</b> Inbound {@code X-User-*} headers are
 *       stripped so a client cannot claim to be somebody by asking.</li>
 *   <li><b>Timeouts and circuit breaking</b>, so one dead service does not become
 *       every request hanging.</li>
 * </ul>
 *
 * <p>What deliberately does <em>not</em> live here: authorization decisions. The
 * gateway checks that a token is valid, not what it may do. Roles stay with the
 * service that owns the data, because a gateway that knows which roles may
 * archive a destination has to be redeployed every time that rule changes — and
 * because a service reachable inside the network must never assume something in
 * front of it did the checking.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
