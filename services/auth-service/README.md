# ExploreLK — Auth Service

Identity, authentication, account security and roles for the whole platform.
Full design and build plan: [`docs/auth-service.md`](../../docs/auth-service.md).

Runs on **http://localhost:8081**.

---

## Current state

| Step | What | Status |
| --- | --- | --- |
| 0 | Local infrastructure (Postgres, Redis, Kafka, Kafka UI, MailHog) | done |
| 1 | Spring Boot skeleton, health endpoint | done |
| 2 | Flyway schema + JPA entities | done |
| 3 | Registration | done |
| 4 | RSA keys, JWT signing, JWKS | done |
| 5 | Login + security filter chain | done |
| 6 | Refresh rotation + logout denylist | done |
| 7 | Email verification + password reset | done |
| 8 | Outbox + Kafka | next |
| 9–12 | See the design doc | pending |

### Endpoints

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/register` | — | Always **202**, identical body whether or not the address is taken |
| POST | `/api/v1/auth/login` | — | Access + refresh token |
| POST | `/api/v1/auth/refresh` | — | Rotates; replaying a consumed token kills the whole family |
| POST | `/api/v1/auth/logout` | access | Revokes refresh + denylists the access `jti` |
| POST | `/api/v1/auth/verify-email` | — | Single use |
| POST | `/api/v1/auth/resend-verification` | — | Always 202 |
| POST | `/api/v1/auth/forgot-password` | — | Always 202 |
| POST | `/api/v1/auth/reset-password` | — | Single use; revokes every session |
| POST | `/api/v1/auth/change-password` | access | Needs the current password; revokes every session |
| GET/PATCH | `/api/v1/users/me` | access | Own profile |
| GET | `/.well-known/jwks.json` | — | Public signing key |
| GET | `/actuator/health` | — | Plus `/liveness` and `/readiness` |

### Full flow

```bash
B=http://localhost:8081/api/v1

# 1. Register — always 202, tells you nothing about whether the address existed
curl -X POST $B/auth/register -H "Content-Type: application/json" \
  -d '{"email":"nimal@explorelk.lk","password":"Sigiriya2026","fullName":"Nimal Perera","role":"TRAVELER"}'

# 2. Grab the verification link from http://localhost:8025 and post the token
curl -X POST $B/auth/verify-email -H "Content-Type: application/json" -d '{"token":"<token>"}'

# 3. Log in
curl -X POST $B/auth/login -H "Content-Type: application/json" \
  -d '{"email":"nimal@explorelk.lk","password":"Sigiriya2026"}'

# 4. Use it
curl $B/users/me -H "Authorization: Bearer <accessToken>"
```

> Reading a token out of MailHog by script: the body is quoted-printable, so
> `token=3DXApu...` is really `token=` + `XApu...`. Decode with `quopri` first, or the
> token will be subtly wrong in a way that looks like a backend bug.

**Stack:** Java 17 · Spring Boot 4.1.1 · Spring Framework 7 · Hibernate 7 · Flyway 12 · PostgreSQL 16

---

## Prerequisites

- **JDK 17** — `C:\Program Files\Java\jdk-17`
- **Docker Desktop** — must be running before the app starts
- **Maven** — not needed; use the bundled wrapper `./mvnw`

---

## Running it

### 1. Start the infrastructure

From the repository root:

```bash
cp .env.example .env          # first time only
docker compose up -d
docker compose ps             # all five should read healthy
```

| Service | Address |
| --- | --- |
| PostgreSQL | `localhost:`**`5433`**, db `explorelk_auth`, user/pass `explorelk` |
| Redis | `localhost:6379` |
| Kafka | `localhost:9092` — used from Step 8 |
| Kafka UI | http://localhost:8085 — used from Step 8 |
| MailHog inbox | http://localhost:8025 — used from Step 7 |

### 2. Start the service

**IntelliJ** — open `services/auth-service/pom.xml` as a project, set the SDK to JDK 17,
run `AuthApplication`. The `dev` profile is the default and its datasource settings
already match `docker-compose.yml`, so no configuration is needed.

**Command line:**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-17"
./mvnw spring-boot:run
```

### 3. Check it

```bash
curl http://localhost:8081/actuator/health
# {"status":"UP", "components":{"db":{"status":"UP"}, ...}}
```

---

## Layout

```
src/main/java/com/explorelk/auth/
├── AuthApplication.java
├── config/        SecurityConfig, JwtKeyConfig, PasswordConfig, AsyncConfig, *Properties
├── security/      CurrentUser (reads identity from the verified JWT)
├── auth/          AuthController, LoginService, RegistrationService, dto/
├── user/          User, UserService, UserController, roles + status
├── token/         JwtService, JwksController, RefreshTokenService,
│                  RefreshTokenFamilyRevoker, TokenDenylistService, TokenHasher
├── verification/  VerificationService, EmailSender + SMTP impl, token entities
├── outbox/        OutboxEvent (wired up in Step 8)
└── common/        ApiError, ErrorCode, GlobalExceptionHandler, LogSafe, validation/

src/main/resources/
├── application.yml            shared config
├── application-dev.yml        local defaults, matches docker-compose
├── application-prod.yml       everything from the environment, no defaults
└── db/migration/V1__init.sql  Flyway schema
```

---

## Schema rules

Flyway owns the schema. Hibernate runs `ddl-auto: validate` and only checks that
the entities match it — it never creates or alters anything.

**Never edit a migration that has already run.** Flyway stores a checksum and will
refuse to start. Add `V2__…`, `V3__…` instead.

Start over locally:

```bash
docker compose down -v postgres && docker compose up -d postgres
```

Verify a migration applied:

```bash
docker exec explorelk-postgres psql -U explorelk -d explorelk_auth \
  -c "SELECT version, description, success FROM flyway_schema_history;"
```

---

## JWT signing keys

`keys/private.pem` and `keys/public.pem` are **not** in git (`**/keys/` is ignored at
the repo root). Generate a pair on any new machine:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/private.pem
openssl rsa -in keys/private.pem -pubout -out keys/public.pem
```

Use `genpkey`, not `genrsa`: it emits PKCS#8 (`BEGIN PRIVATE KEY`), which Java reads
directly. Older `genrsa` emits PKCS#1 (`BEGIN RSA PRIVATE KEY`), which it cannot.

The private key signs and never leaves this service. The public key is served at
`/.well-known/jwks.json`, and that URL is how every other ExploreLK service verifies a
token without calling back here:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://auth-service:8081/.well-known/jwks.json
```

In production the keys come from a secret store, not from disk — override
`JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY`.

---

## Machine-specific notes

Three local quirks, all worked around in committed config:

### 1. TLS interception breaks Maven

`.mvn/jvm.config` contains `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`.

Something on this machine (antivirus or a corporate proxy) intercepts TLS, so Maven
cannot verify Maven Central against the JDK truststore and fails with
`PKIX path building failed`. That flag tells Java to trust the Windows certificate
store, which already holds the intercepting CA.

It is **Windows-only**. When the Dockerfile arrives in Step 12, exclude
`.mvn/jvm.config` via `.dockerignore` — inside a Linux build image it breaks TLS
rather than fixing it.

### 2. Postgres is on 5433, not 5432

Ports 5432 and 5434 are both already in use on this machine by other Postgres
servers, so the compose container publishes **5433**.

### 3. Docker Hub pulls are very slow

Image layers sit at 0 bytes for many minutes before moving, but they do complete.
Budget 20+ minutes for a first `docker compose up -d` on a cold cache and let it run
rather than killing it. All five images are pulled and working now.

`docker-compose.yml` keeps the Postgres image behind `${POSTGRES_IMAGE:-postgres:16-alpine}`
so a local substitute can be swapped in if pulls ever fail again. If you do that, run
`docker compose down -v` first: mounting a volume created by a Debian-based Postgres
image under an Alpine one (or the reverse) mixes glibc and musl collations, which
Postgres warns about and which can corrupt text indexes.

---

## Spring Boot 4 gotcha, already hit

Boot 4 split autoconfiguration into per-technology modules. Having `flyway-core` on
the classpath is no longer enough — without `org.springframework.boot:spring-boot-flyway`,
migrations silently never run and Hibernate then fails `validate` against an empty
schema, with no mention of Flyway anywhere in the log.

Expect the same pattern for Kafka and Redis in Steps 8–9. When a Boot 3 tutorial's
dependency list does not work, check whether that autoconfiguration now lives in its
own `spring-boot-*` module.
