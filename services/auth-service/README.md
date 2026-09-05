# ExploreLK — Auth Service

Identity for the whole platform: who a user is, what they may do, and the tokens
that prove it. Every other service verifies those tokens without ever calling
this one.

Full design and build plan: [`docs/auth-service.md`](../../docs/auth-service.md).

Runs on **http://localhost:8081**.

---

## Current state

| Step | What | Status |
| --- | --- | --- |
| 0 | Infrastructure — Postgres, Redis, Kafka, MailHog | done |
| 1 | Spring Boot skeleton, health endpoint | done |
| 2 | Flyway schema + JPA entities | done |
| 3 | Registration | done |
| 4 | RSA keys + JWKS | done |
| 5 | Login + security filter chain | done |
| 6 | Refresh rotation + logout | done |
| 7 | Email verification + password reset | done |
| 8 | Outbox + Kafka events | done |
| 9 | Rate limiting + brute force | done |
| 10 | Admin, super-admin, bootstrap | done |
| 11 | Tests | done |
| 12 | Package | done |

---

## Endpoints

### Public — authentication

| Method | Path | Notes |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | 202 whether or not the address was free |
| POST | `/api/v1/auth/login` | 200 with an access + refresh token |
| POST | `/api/v1/auth/refresh` | Rotates the refresh token |
| POST | `/api/v1/auth/verify-email` | Single-use token |
| POST | `/api/v1/auth/resend-verification` | Silent for unknown addresses |
| POST | `/api/v1/auth/forgot-password` | Silent for unknown addresses |
| POST | `/api/v1/auth/reset-password` | Revokes every session |

### Authenticated — self

| Method | Path |
| --- | --- |
| POST | `/api/v1/auth/logout` |
| POST | `/api/v1/auth/change-password` |
| GET/PATCH | `/api/v1/users/me` |

### ADMIN or SUPER_ADMIN

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/admin/users` | Paginated; filter by `role` and `status` |
| GET | `/api/v1/admin/users/{id}` | |
| PATCH | `/api/v1/admin/users/{id}/status` | ACTIVE / SUSPENDED / DISABLED |
| PATCH | `/api/v1/admin/providers/{id}/approval` | Emits `PROVIDER_APPROVED` |

### SUPER_ADMIN only

| Method | Path |
| --- | --- |
| POST | `/api/v1/super-admin/admins` |
| PATCH | `/api/v1/super-admin/admins/{id}/status` |

### Platform

`/.well-known/jwks.json`, `/actuator/health` (plus `/liveness`, `/readiness`),
`/actuator/info`, and `/swagger-ui.html` in the dev profile.

---

## Running it

```bash
# From the repo root — Postgres, Redis, Kafka, MailHog
docker compose up -d postgres redis kafka mailhog

# Generate the dev signing keypair once (keys/ is git-ignored)
cd services/auth-service
mkdir -p keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/private.pem
openssl rsa -pubout -in keys/private.pem -out keys/public.pem

JAVA_HOME=/path/to/jdk-17 ./mvnw spring-boot:run
```

Or the whole platform, containers and all:

```bash
docker compose up -d
```

> Build with **JDK 17**. If a newer JDK is first on your PATH, set `JAVA_HOME`
> explicitly — the build targets 17.

### Tests

```bash
JAVA_HOME=/path/to/jdk-17 ./mvnw verify
```

`mvn test` runs the unit tests alone and needs no Docker; the `*IT` suites run
under failsafe in `verify` and start their own Postgres, Redis and — for the one
outbox test — Kafka containers.

The suite generates its **own throwaway RSA keypair** at startup and never reads
`keys/`, so it passes on a fresh clone and in CI.

> On Windows, Testcontainers must be **1.21 or newer**. Docker Desktop answers
> the legacy `docker_engine` named pipe with an empty `400`, and only 1.21+ falls
> back to reading the active docker context.

---

## Things worth knowing before changing anything

**This is the only service that signs anything.** It holds the RSA private key;
every other service fetches the public key from `/.well-known/jwks.json` once and
then verifies locally. That is why stopping this service does not stop the
others — and why the key must never be baked into an image. `.dockerignore`
excludes `keys/`, and compose mounts it read-only.

**A logout cannot be seen by other services.** The `jti` denylist is Redis here,
and nothing else reads it, so a revoked access token keeps working elsewhere
until it expires — at most 15 minutes. The same applies to suspending a user:
their refresh tokens die immediately, their current access token does not.

**Registration tells the caller nothing.** Free address or taken, the response is
the same 202 with the same wording, and the owner is emailed either way. A
201/409 split would let anyone feed in a list of addresses and learn which ones
have accounts. `forgot-password` and `resend-verification` are silent for the
same reason.

**The account lockout lives in Postgres; the request limit lives in Redis.**
That is a deliberate departure from §8 of the design, which puts both in Redis.
The rate limiter must fail *open* — a cache outage cannot be allowed to stop
everyone logging in — so if the lockout were also in Redis, one unreachable cache
would remove both defences at once. The columns for it already existed in
`V1__init.sql`. See `LoginAttemptService`.

**`SuperAdminBootstrap` refuses to start outside dev without credentials.** The
alternative is a platform nobody can administer, or one with a default password.
In dev it logs a warning and carries on.

**Kafka is not on the request path.** Events only tell other services that
something happened to a user. A broker outage costs nothing: the outbox keeps
accepting rows and drains when Kafka returns.

---

## Configuration

Everything comes from the environment; see [`.env.example`](../../.env.example).

| Variable | Default | Notes |
| --- | --- | --- |
| `AUTH_DB_URL` | `jdbc:postgresql://localhost:5433/explorelk_auth` | |
| `AUTH_DB_USERNAME` / `_PASSWORD` | `explorelk` | |
| `JWT_ISSUER` | `explorelk-auth` | Must match every other service |
| `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` | `file:./keys/*.pem` | Never in the image |
| `JWT_ACCESS_TTL` / `JWT_REFRESH_TTL` | `15m` / `30d` | |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `EVENTS_ENABLED` | `true` | `false` runs with no broker |
| `RATE_LIMIT_ENABLED` | `true` | |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `1025` | MailHog |
| `SUPER_ADMIN_EMAIL` / `_PASSWORD` | — | Required outside dev on an empty DB |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Never a wildcard |
