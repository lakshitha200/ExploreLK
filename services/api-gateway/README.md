# ExploreLK — API Gateway

The single address the outside world uses: **http://localhost:8080**. Browsers,
apps and curl talk to this; the services behind it are reachable only inside the
compose network.

Full design and build plan: [`docs/api-gateway.md`](../../docs/api-gateway.md).

---

## What it does

| Concern | Why it lives here |
| --- | --- |
| **Routing** | One public URL space fanned out to the service that owns each path |
| **CORS** | One origin list for the platform; without a gateway every service keeps its own copy and they drift |
| **Token verification** | A forged or expired token is rejected at the door, before it costs a service anything |
| **Rate limiting** | Shared counters in Redis, so a burst is stopped before it reaches a database connection |
| **Identity header hygiene** | Inbound `X-User-*` headers are stripped, so nobody can claim to be somebody by asking |
| **Request ids** | One id per request, forwarded inward and returned, so three log files tell one story |
| **Circuit breaking** | One dead service does not become every request hanging |

**What it deliberately does not do: authorization.** The gateway checks that a
token is *real*, not what it may *do*. Roles stay with the service that owns the
data — see below.

---

## Routes

Both services already publish endpoints under `/api/v1/admin`, so routing is by
specific prefix rather than a service-prefix split. Nothing is rewritten: the
path a client sends is the path the service receives.

| Path | Service |
| --- | --- |
| `/api/v1/auth/**` | auth-service :8081 |
| `/api/v1/users/**` | auth-service |
| `/api/v1/super-admin/**` | auth-service |
| `/api/v1/admin/users/**` | auth-service |
| `/api/v1/admin/providers/**` | auth-service |
| `/.well-known/**` | auth-service |
| `/api/v1/destinations/**` | destination-service :8082 |
| `/api/v1/attractions/**` | destination-service |
| `/api/v1/categories/**` | destination-service |
| `/api/v1/admin/destinations/**` | destination-service |
| `/api/v1/admin/attractions/**` | destination-service |
| `/api/v1/admin/categories/**` | destination-service |

Anything else is a 404 — there is no catch-all.

### Who may cross

| | |
| --- | --- |
| No token needed | `/api/v1/auth/**`, `/.well-known/**`, and **GET** on destinations, attractions, categories |
| Valid token needed | everything else |
| Role checked | **never here** — the service that owns the data decides |

---

## Running it

```bash
# From the repo root — the whole platform
docker compose up -d
```

Or locally, with the three services on the host:

```bash
docker compose up -d postgres redis kafka mailhog
# then, in three terminals
cd services/auth-service        && JAVA_HOME=/path/to/jdk-17 ./mvnw spring-boot:run
cd services/destination-service && JAVA_HOME=/path/to/jdk-17 ./mvnw spring-boot:run
cd services/api-gateway         && JAVA_HOME=/path/to/jdk-17 ./mvnw spring-boot:run
```

```bash
curl http://localhost:8080/api/v1/destinations
curl http://localhost:8080/api/v1/categories
curl -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{...}'
```

`/actuator/gateway/routes` lists every route in the dev profile — and is
deliberately not exposed in prod, where it would publish the platform's topology.

### Tests

```bash
JAVA_HOME=/path/to/jdk-17 ./mvnw verify
```

The integration tests start a real port and stub both downstream services, so
they assert what the *other end* received. Docker is needed for Redis.

---

## Things worth knowing before changing anything

**It runs Spring Boot 4.0.8, not the 4.1.1 the other services use.** Spring
Cloud ships as a train pinned to one Boot minor, and 2025.1.x targets 4.0.x.
Forcing 4.1.1 under it is the combination nobody tests, and it fails at runtime
rather than at compile time. Nothing is shared between these services at build
time, so the difference costs nothing. Raise it when a train targets 4.1.

**The gateway authenticates; it does not authorize.** A TRAVELER token on an
admin path is forwarded, and the service answers 403. That is deliberate: a
gateway that knew every service's role map would need redeploying whenever any
of them changed, and would eventually disagree with the service it protects.
More importantly, the services are reachable inside the network without passing
through here, so none of them may assume something in front did the checking.
They all still verify the token themselves.

**Identity headers are stripped unconditionally.** `X-User-Id` and friends are
removed on the way in whether or not the request is authenticated. If a client
could send `X-User-Id: <someone else>` and have it survive, the whole
authentication system would be bypassable by typing.

**A downstream 500 is not a circuit-breaker failure; 502/503/504 are.** A 500 is
a bug in one handler. Tripping on it would make one broken endpoint return 503
for every endpoint of a mostly-healthy service — one bug becoming an outage.

**Rate limiting fails open.** A gateway that refuses everything when Redis is
down is a single point of failure for the platform, which is the one thing a
gateway must never be.

**Readiness reports only the gateway itself.** Not Redis (the limiter fails
open), and not the downstream services — a gateway that called itself unhealthy
because the catalog was down would be restarted by an orchestrator that cannot
fix the catalog, taking auth offline with it.

---

## Configuration

| Variable | Default | Notes |
| --- | --- | --- |
| `AUTH_SERVICE_URI` | `http://localhost:8081` | Service name inside compose |
| `DESTINATION_SERVICE_URI` | `http://localhost:8082` | |
| `AUTH_JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` | The auth service directly, never back through this gateway |
| `JWT_ISSUER` | `explorelk-auth` | Must match auth-service |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | |
| `RATE_LIMIT_ENABLED` | `true` | |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Never a wildcard |
| `GATEWAY_PROFILE` | `dev` | The profile the container starts in |
