# API Gateway — Design & Build Notes

> The single entry point to the platform. One address, one origin list, one
> place where a bad token stops being the services' problem.

---

## 0. Why a gateway at all

Two services already work without one. What a client has to know today:

```
http://localhost:8081   auth      register, login, refresh, users, admin/users
http://localhost:8082   catalog   destinations, attractions, categories, admin/destinations
```

Every browser app then hard-codes two hosts, every service maintains its own CORS
allowlist, and there is nowhere to put a limit that covers the platform rather
than one endpoint. With a third service that becomes three of everything.

The gateway collapses it to one address and gives four cross-cutting concerns a
single home: routing, CORS, edge authentication and rate limiting.

**What it is not.** It is not a place to put business logic, not a place to
aggregate responses from several services into one, and not an authorization
engine. Each of those makes the gateway a thing that must be redeployed whenever
any service changes, which is how a gateway becomes the bottleneck it was meant
to remove.

---

## 1. The routing problem this platform actually has

The textbook design gives each service a prefix:

```
/auth/**     -> auth-service
/catalog/**  -> destination-service
```

That does not work here, and the reason is worth stating because it shapes
everything else. **Both services already own paths under `/api/v1/admin`:**

| Path | Owner |
| --- | --- |
| `/api/v1/admin/users`, `/api/v1/admin/providers` | auth-service |
| `/api/v1/admin/destinations`, `/api/v1/admin/attractions`, `/api/v1/admin/categories` | destination-service |

A prefix split would mean rewriting every URL both services publish — breaking
their own tests, their READMEs, and the direct-access URLs a developer uses when
debugging one service without the gateway running.

So routing is by **specific prefix**, with no rewriting at all:

```
/api/v1/auth/**              ─┐
/api/v1/users/**              │
/api/v1/super-admin/**        ├─►  auth-service         :8081
/api/v1/admin/users/**        │
/api/v1/admin/providers/**    │
/.well-known/**              ─┘

/api/v1/destinations/**      ─┐
/api/v1/attractions/**        │
/api/v1/categories/**         ├─►  destination-service  :8082
/api/v1/admin/destinations/** │
/api/v1/admin/attractions/**  │
/api/v1/admin/categories/**  ─┘

anything else                ──►  404
```

**No catch-all**, on purpose. A default route is how a typo in one service's path
silently starts reaching another, and how a service that was never meant to be
public becomes public the day somebody adds it to the network.

**No path rewriting**, so a URL means the same thing on both sides of the
gateway. A log line is comparable, and bypassing the gateway while debugging
gives identical behaviour.

---

## 2. Authentication, not authorization

The gateway verifies that a token is real — RS256 signature against the Auth
Service's published JWKS, unexpired, correct issuer — and rejects it at the door
if not. It does **not** decide whether that token may archive a destination.

Two reasons, and the second is the one that matters:

1. A gateway that knows every service's role rules must be redeployed whenever
   any of them changes, and will eventually disagree with the service it is
   protecting.
2. **The services are reachable inside the network without passing through the
   gateway.** A service that trusted "something in front of me checked" would be
   open to anything already on that network. That is the confused deputy problem,
   and it is why every service still verifies the token itself.

So a TRAVELER token on an admin path *is forwarded*, and the service answers 403.
That looks wrong in a test until you see the reasoning; it is asserted explicitly
in `GatewayRoutingIT.roleChecksAreLeftToTheService`.

This is defence in depth rather than duplication: the gateway stops garbage
early and cheaply, the services stay correct on their own.

### Public paths

| | |
| --- | --- |
| No token | `/api/v1/auth/**`, `/.well-known/**`, `/fallback/**`, and **GET** on destinations / attractions / categories |
| Valid token | everything else |

`GET` only for the catalog: a `POST` to the same path is an admin write and falls
through to the authenticated rule.

---

## 3. Header hygiene

**The security property that makes identity headers safe to use at all.**

The pattern is common: the gateway verifies a token and passes `X-User-Id`
inward so services need not re-parse it. The trap is that a header is just a
header — if a client can send `X-User-Id: <someone else>` and have it survive,
authentication has been bypassed by typing.

`IdentityHeaderFilter` strips every one of these on the way in, at
`HIGHEST_PRECEDENCE`, **unconditionally**:

```
x-user-id  x-user-email  x-user-role  x-authenticated
x-forwarded-for  x-forwarded-host  x-forwarded-proto  x-real-ip
```

Stripping only when a token is present is the subtle version of the same bug: an
unauthenticated request to a public endpoint would keep its forged header.

The `x-forwarded-*` entries matter for a second reason — the rate limiter buckets
by client address, and a caller who can set that header picks their own bucket,
or poisons somebody else's.

> This gateway does not currently *add* `X-User-Id`. The services read the
> subject from the token they verify themselves, which is stronger than trusting
> a header from a neighbour. The stripping exists anyway, so the protection is
> already in place the day someone starts setting it.

---

## 4. Request ids

One id per request, generated at the edge, forwarded as `X-Request-Id`, returned
to the caller, and pushed into the SLF4J MDC so every gateway log line carries it.

With more than one service, "what happened to this request" stops being
answerable from one log file. One id turns three unrelated files into one story.

Generated here rather than accepted from the client, so nobody can collide with —
or forge — somebody else's trace. This is the cheap end of distributed tracing;
OpenTelemetry is the real answer and stays deferred until there is more to trace
across.

---

## 5. Rate limiting

Fixed window, `INCR` + `EXPIRE`, in Redis so two gateway instances share one
count and a deploy does not reset it.

This is **not** a second copy of the Auth Service's limiter. That one protects
accounts — five wrong passwords locks one login. This one protects the platform:
it counts every request from an address, whatever it is for. A caller that never
touches login can still flatten the catalog without it.

| | |
| --- | --- |
| Key | `gw:rl:{ip}` |
| Default | 300 requests / 60s per address |
| Exempt | `/actuator/health/**`, `/fallback/**` |

**It fails open.** If Redis is unreachable the request is allowed and a warning
logged. A gateway that refuses everything when its cache is down is a single
point of failure for the whole platform — the one thing a gateway must not be.

---

## 6. Resilience

Without a breaker, every request to a dead service occupies a thread until it
times out, the pool fills with calls waiting on the same host, and endpoints with
nothing to do with it stop answering. **One broken service becomes a broken
platform.**

| Setting | Value | Why |
| --- | --- | --- |
| Breakers | one per service | A broken catalog must not stop anyone logging in |
| Trips on | connection failure, timeout, `502`, `503`, `504` | All of these mean "not serving" |
| Does **not** trip on | `500` | A bug in one handler is not evidence the service is down — tripping would make one broken endpoint return 503 for every endpoint |
| Connect / read timeout | 2s / 15s | Shorter than a user's patience |
| Time limiter | 10s | Below the read timeout, so a slow call is cut by the breaker — and *counted* — rather than by the socket, which would count as nothing |
| Fallback | `503` + `Retry-After` | 500 says "do not repeat this"; 503 says "try again shortly" |

The fallback body reports the **original** path, not `/fallback/{service}` —
both because the client asked about a destination, and because echoing the
fallback path would put the platform's internal topology in a response body.

### Readiness

Reports the gateway alone. Not Redis (the limiter fails open), and **not** the
downstream services: a gateway that called itself unhealthy because the catalog
was down would be restarted by an orchestrator that cannot fix the catalog,
taking auth offline with it. The circuit breaker is the right tool for that case.

---

## 7. Tech stack

| Concern | Choice |
| --- | --- |
| Java | 17 (LTS) — matches every other service |
| Framework | Spring Boot **4.0.8** + Spring Cloud **2025.1.3** |
| Gateway | Spring Cloud Gateway **server-webmvc** 5.0.3 |
| Security | Spring Security + oauth2-resource-server (verify only) |
| Rate limiting | Spring Data Redis (Lettuce) |
| Resilience | Resilience4j via spring-cloud-circuitbreaker |
| Testing | JUnit 5, Testcontainers (Redis), JDK `HttpServer` stubs |
| Port | **8080** (auth 8081, destination 8082) |

**Two version decisions worth not rediscovering:**

| Trap | Fix |
| --- | --- |
| Spring Cloud is pinned to one Boot minor. 2025.1.x targets Boot 4.0.x, not the 4.1.1 the other services run | Pin *this module* to 4.0.8. The services share nothing at build time and meet only over HTTP, so the difference costs nothing |
| `ObjectMapper` not found as a bean | Boot 4 is Jackson 3 — inject `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson.databind`. Same trap as the other two services |

**WebMVC, not WebFlux.** The reactive gateway handles more connections per
thread and is right at large scale. The servlet one runs on the same stack as the
other two services, so the security config, error handling and tests look exactly
like theirs — worth more, for a platform of two services, than throughput nobody
is close to needing. Switching later changes this module only.

---

## 8. Definition of done

- [x] One address serves the whole platform; both services reachable through it
- [x] `/api/v1/admin` is split correctly between the two services
- [x] An unrouted path is a 404, and a 401 to an anonymous caller — no endpoint map leaks
- [x] Public catalog reads work with no token; admin paths need a valid one
- [x] A forged, expired or foreign-issuer token is rejected at the edge and never reaches a service
- [x] Role checks are left to the owning service, and that is asserted, not assumed
- [x] Client-supplied `X-User-*` and `X-Forwarded-*` headers never reach a service
- [x] Every request gets an id, forwarded inward and returned to the caller
- [x] A burst is stopped at the edge with a `Retry-After`, and health probes are exempt
- [x] Redis down = slower/unlimited, not broken
- [x] A failing service becomes a 503, an open circuit sheds load, and the other service keeps working
- [x] The circuit closes again on its own once the service recovers
- [x] `mvn verify` passes — 43 integration tests
- [x] `docker compose up` brings up the whole platform behind :8080

---

## 9. Deferred past MVP

- OpenTelemetry tracing — worth it once there are more services to trace across
- Response aggregation / BFF endpoints — deliberately not a gateway concern
- Service discovery (Eureka, Consul) — Docker DNS already resolves two names
- Per-route rate limits, and per-user rather than per-IP buckets
- Request/response body logging for audit — needs a retention policy first
- TLS termination — belongs to the load balancer in front, not here
