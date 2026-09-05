# ExploreLK — Destination Service

The catalog of **where a traveler can go** in Sri Lanka and **what there is to see**
when they get there. Trip, Itinerary and Experience services all read from it;
none of them keep their own copy.

Full design and build plan: [`docs/destination-service.md`](../../docs/destination-service.md).

Runs on **http://localhost:8082**.

---

## Current state

| Step | What | Status |
| --- | --- | --- |
| 0 | Infrastructure — PostGIS image, second database | done |
| 1 | Spring Boot skeleton, health endpoint | done |
| 2 | Flyway schema + JPA entities, generated `geog` column | done |
| 3 | Seed data + public reads | done |
| 4 | Search, filter, sort | done |
| 5 | Security (JWKS) + admin destination CRUD | done |
| 6 | Attractions | done |
| 7 | Nearby (PostGIS) | done |
| 8 | Redis caching | done |
| 9 | Outbox + Kafka events | done |
| 10 | Tests | done |
| 11 | Package | done |

---

## Endpoints

### Public — no token

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/v1/destinations` | `search`, `category`, `district`, `province`, `sort`, `page`, `size` |
| GET | `/api/v1/destinations/{idOrSlug}` | UUID or slug, same response either way |
| GET | `/api/v1/destinations/{idOrSlug}/attractions` | |
| GET | `/api/v1/destinations/nearby` | `lat`, `lng`, `radiusKm`, `limit` — returns `distanceKm` |
| GET | `/api/v1/attractions/{id}` | Id only; slugs are unique per destination |
| GET | `/api/v1/attractions/nearby` | Same params |
| GET | `/api/v1/categories` | The filter vocabulary |

Only `PUBLISHED` content is ever returned, and an attraction is public only if its
destination is too.

### Admin — `ADMIN` or `SUPER_ADMIN`

| Method | Path |
| --- | --- |
| GET | `/api/v1/admin/destinations` (adds `status`) |
| GET | `/api/v1/admin/destinations/{idOrSlug}` |
| POST | `/api/v1/admin/destinations` |
| PATCH | `/api/v1/admin/destinations/{id}` |
| PATCH | `/api/v1/admin/destinations/{id}/status` |
| DELETE | `/api/v1/admin/destinations/{id}` — archives, never deletes |
| GET/POST | `/api/v1/admin/destinations/{id}/attractions` |
| GET/PATCH/DELETE | `/api/v1/admin/attractions/{id}` |
| PATCH | `/api/v1/admin/attractions/{id}/status` |
| POST | `/api/v1/admin/categories` |

### Platform

`/actuator/health` (plus `/liveness`, `/readiness`), `/actuator/info`,
and `/swagger-ui.html` in the dev profile — springdoc is switched off everywhere
else, because a published schema of every admin endpoint is free reconnaissance.

---

## Running it

```bash
# From the repo root — Postgres (PostGIS), Redis, Kafka, MailHog
docker compose up -d postgres redis kafka

# Then the service
cd services/destination-service
JAVA_HOME=/path/to/jdk-17 ./mvnw spring-boot:run
```

Or the whole platform, containers and all:

```bash
docker compose up -d
```

That builds and starts `destination-service` alongside Postgres, Redis and Kafka.
The **Auth Service is not containerised yet** (its own Step 12), so the container
reaches an auth-service running on your host — `AUTH_JWKS_URI_INTERNAL` in
`.env`. Public catalog reads work regardless; only the admin endpoints need a
token to verify.

> Build with **JDK 17**. If a newer JDK is first on your PATH, set `JAVA_HOME`
> explicitly — the build targets 17 to match `auth-service`.

### Tests

```bash
JAVA_HOME=/path/to/jdk-17 ./mvnw verify
```

`mvn test` runs the unit tests alone and needs no Docker; the `*IT` suites run
under failsafe in `verify`.

Integration tests use Testcontainers, so Docker must be running. They start
their own PostGIS, Redis and — for the one outbox test — Kafka containers, and
do **not** touch your dev database. The Postgres container must be the PostGIS
image or every spatial test fails at `CREATE EXTENSION`. The broker is
`apache/kafka:3.8.0`, the same image `docker-compose.yml` runs, so a developer
who has started the platform once already has it.

> On Windows, Testcontainers must be **1.21 or newer**. Docker Desktop answers
> the legacy `docker_engine` named pipe with an empty `400`, and only 1.21+
> falls back to reading the active docker context.

---

## Things worth knowing before changing anything

**It verifies tokens without ever calling the Auth Service.** The public key is
fetched once from `/.well-known/jwks.json` and cached; every request after that
is local RSA math. Stopping the Auth Service does not stop this one. Do not
"improve" this by calling Auth per request — that throws away the entire design.

The trade is that this service **cannot see a logout**. The `jti` denylist lives
in the Auth Service, so a revoked access token keeps working here until it
expires, at most 15 minutes. Nothing here is destructive enough to need faster
revocation.

The cache tolerance only protects a **warm** cache. Restarting this service while
Auth is down means no key set and no way to get one, so every token is rejected
until Auth returns. Correct, and unavoidable.

**`DELETE` archives; it never deletes a row.** Trip and Itinerary store
destination ids in *their own* databases where no foreign key can protect them.
A hard delete turns every one of those into a silent dangling reference.

**The `geog` column is generated by Postgres and unmapped by JPA.** That single
decision is why there is no `hibernate-spatial`, no JTS types and no dialect
swap. Proximity is one native query per entity; everything else is plain JPA.

**Redis is a cache and only a cache.** Everything in it has a copy in Postgres,
so a Redis outage makes this service slower, never wrong. `/nearby` is
deliberately never cached — its key space is every GPS coordinate a phone emits.

**Kafka is not on the read path.** Events only tell other services the catalog
changed. `ATTRACTION_UPDATED` is the one that matters: an itinerary built around
a 90-minute visit is quietly wrong once that becomes 180.

---

## Configuration

Everything comes from the environment; see [`.env.example`](../../.env.example).

| Variable | Default | Notes |
| --- | --- | --- |
| `DESTINATION_DB_URL` | `jdbc:postgresql://localhost:5433/explorelk_destination` | |
| `DESTINATION_DB_USERNAME` / `_PASSWORD` | `explorelk` | |
| `AUTH_JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` | |
| `JWT_ISSUER` | `explorelk-auth` | Must match auth-service |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `EVENTS_ENABLED` | `true` | `false` runs the catalog with no broker |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Never a wildcard |
| `KAFKA_CATALOG_TOPIC` | `explorelk.destination.events` | One topic, six event types |
| `DESTINATION_PROFILE` | `dev` | The profile the **container** starts in |
| `AUTH_JWKS_URI_INTERNAL` | `http://host.docker.internal:8081/...` | How the container reaches Auth |
