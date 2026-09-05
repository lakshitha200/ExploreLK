# Auth Service — Design & Build Plan

> Owns identity, authentication, account security, and roles for the whole ExploreLK platform.
> Every other service trusts this service's signature — nothing else.

---

## 0. Concepts you need first

You know Spring Boot. These three are the new pieces. Read this section once, then the rest makes sense.

### Why asymmetric (RSA) JWT and not a shared secret?

With a shared secret (HS256), every service needs the secret — and anything holding the secret can **mint** tokens, not just verify them. One leaked config file and an attacker can forge an ADMIN token.

With RSA (RS256):

```
Auth Service                 Every other service
─────────────                ───────────────────
private key  ──signs──►  JWT  ──verified with──►  public key
(secret, only here)                               (safe to publish)
```

Trip / Booking / Experience services can **verify** but never **create** tokens. They also never call the Auth Service to validate a request — verification is pure local math. That is what makes this scale.

The public key is served at `/.well-known/jwks.json`. Spring Security in the other services points at that URL and caches the key.

### What is Kafka doing here?

When a user registers, an email must be sent. The naive way:

```java
AuthService.register() {
    userRepo.save(user);
    emailService.send(user);   // slow, and if email fails the user is stuck
}
```

Auth Service should not know that email exists. Instead it announces a fact:

```
Auth: "USER_REGISTERED happened"  ──► Kafka topic ──► Notification Service reads it and emails
                                                 └──► (later) Analytics reads the same event
```

Kafka is a durable log. Publishers do not know who reads. Consumers can be down and catch up later. Adding a new consumer requires zero changes to Auth.

### What is the Transactional Outbox and why do I need it?

This looks fine but is broken:

```java
@Transactional
void register(...) {
    userRepo.save(user);         // commits
    kafka.send(USER_REGISTERED); // app crashes here -> user exists, no email, forever
}
```

The DB transaction and the Kafka send are two separate systems; you cannot commit both atomically.

The fix: write the event **into your own database, in the same transaction**, then publish it separately.

```
┌─ ONE PostgreSQL transaction ───────────┐
│  INSERT INTO users ...                 │
│  INSERT INTO outbox_events ...         │
└────────────────────────────────────────┘
              ↓ committed
   OutboxPublisher (@Scheduled, every 1s)
   SELECT * FROM outbox_events WHERE published_at IS NULL
              ↓
        publish to Kafka
              ↓
   UPDATE outbox_events SET published_at = NOW()
```

If the app crashes anywhere, the event is still sitting in the table and gets published on restart. This gives **at-least-once** delivery — a consumer may see the same event twice, so consumers must be idempotent (that is why events carry an `eventId`).

### What is Redis doing here?

Redis is a fast in-memory key-value store with **TTL** (auto-expiring keys). It is used here **only** for short-lived security state:

| Key | Value | TTL | Purpose |
| --- | --- | --- | --- |
| `rl:login:{ip}` | counter | 60s | Login rate limiting |
| `lock:login:{email}` | counter | 15m | Brute-force lockout after N failures |
| `jwt:denylist:{jti}` | `1` | remaining token TTL | Kill an access token on logout |

Redis is **never** the source of truth. If Redis is wiped, users are unaffected — only counters reset.

---

## 1. Responsibilities

The Auth Service owns:

- User identity (email, password, profile basics)
- Authentication (login, token issuance)
- Token lifecycle (access, refresh, rotation, revocation)
- Account lifecycle (verification, suspension, disabling)
- Roles and authorization data
- Publishing identity domain events

It does **not** own: trips, bookings, provider business profiles (only the *account* that owns them), or notification delivery.

---

## 2. Roles

| Role | Created by | Notes |
| --- | --- | --- |
| `TRAVELER` | Public registration | Default consumer role |
| `PROVIDER` | Public registration | Starts unapproved; requires admin approval to sell experiences |
| `ADMIN` | `SUPER_ADMIN` only | Never via public registration |
| `SUPER_ADMIN` | Startup bootstrap only | Exactly one, created from configuration on first boot |

**Design decision:** one role per user, stored as an enum column on `users` — not a `roles`/`user_roles` join. The JWT carries a single role, and no MVP flow needs a user to be two things at once. If that changes later, migrating one column to a join table is a contained change.

### SUPER_ADMIN bootstrap

On startup, an `ApplicationRunner` checks whether a `SUPER_ADMIN` exists. If not, it creates one from environment variables:

```
SUPER_ADMIN_EMAIL=...
SUPER_ADMIN_PASSWORD=...
```

Rules: runs only when zero SUPER_ADMINs exist (idempotent), fails fast if the env vars are missing in a non-dev profile, and the password is force-changed on first login (`must_change_password = true`).

---

## 3. API surface

### Public — authentication

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/register` | — | Register as TRAVELER or PROVIDER |
| POST | `/api/v1/auth/login` | — | Returns access + refresh token |
| POST | `/api/v1/auth/refresh` | refresh token | Rotates: returns new pair, old refresh revoked |
| POST | `/api/v1/auth/logout` | access token | Revokes refresh token + denylists access `jti` |

### Public — account security

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/verify-email` | — | Consumes a verification token |
| POST | `/api/v1/auth/resend-verification` | — | Rate limited; always returns 202 |
| POST | `/api/v1/auth/forgot-password` | — | Always returns 202 regardless of email existence |
| POST | `/api/v1/auth/reset-password` | — | Consumes reset token, revokes all sessions |
| POST | `/api/v1/auth/change-password` | access token | Requires current password; revokes other sessions |

### Authenticated — self

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/v1/users/me` | access token | Current user profile |
| PATCH | `/api/v1/users/me` | access token | Update name, phone, etc. Email change **not** in MVP |

### ADMIN

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/admin/users` | Paginated + filter by role/status |
| GET | `/api/v1/admin/users/{id}` | Single user |
| PATCH | `/api/v1/admin/users/{id}/status` | ACTIVE / SUSPENDED / DISABLED |
| PATCH | `/api/v1/admin/providers/{id}/approval` | **Added** — approve/reject a provider; emits `PROVIDER_APPROVED` |

### SUPER_ADMIN

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/v1/super-admin/admins` | Create an ADMIN account |
| PATCH | `/api/v1/super-admin/admins/{id}/status` | Enable/disable an ADMIN |

### Platform

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/.well-known/jwks.json` | — | **Added** — public key for other services |
| GET | `/actuator/health/liveness` | — | Liveness probe |
| GET | `/actuator/health/readiness` | — | Readiness probe (DB, Kafka, Redis) |
| GET | `/swagger-ui.html` | dev only | API docs |

---

## 4. Token design

### Access token — 15 minutes, RS256, stateless

```json
{
  "iss": "explorelk-auth",
  "sub": "9f1c2b7e-....",
  "role": "TRAVELER",
  "email_verified": true,
  "jti": "b81e...",
  "iat": 1735000000,
  "exp": 1735000900
}
```

No email, no name, no permissions list. Keep it small — it rides on every request.

- `jti` — unique token id, needed for the logout denylist
- `iss` — rejects a dev token replayed against production
- `email_verified` — lets other services block unverified users without a lookup

### Refresh token — 30 days, opaque, stored hashed

A refresh token is a random 256-bit string, **not** a JWT. It is stored in PostgreSQL as a SHA-256 hash (same principle as passwords — a DB leak must not yield usable tokens).

**Rotation with reuse detection:**

```
POST /refresh (token A)
      ↓
   A valid & not revoked?
      ↓ yes
   revoke A, issue B (same family_id)
      ↓
   return B


POST /refresh (token A again)  <- A was already used
      ↓
   A is revoked -> REUSE DETECTED
      ↓
   revoke the ENTIRE family (B and every descendant)
      ↓
   401 — attacker and victim are both logged out
```

That last part is the point: if a refresh token is stolen, the moment either party uses it twice the whole chain dies. Without family revocation, the thief simply keeps rotating forever.

### Logout

```
POST /logout
   ├─► revoke refresh token in PostgreSQL (permanent)
   └─► SET jwt:denylist:{jti} = 1  EX (exp - now)   (Redis, self-expiring)
```

The security filter checks the denylist. The key expires on its own when the token would have expired anyway, so Redis never grows unbounded.

---

## 5. Account lifecycle

```
        register
           ↓
  PENDING_VERIFICATION ──── cannot log in ────►  403 EMAIL_NOT_VERIFIED
           │
      verify-email
           ↓
        ACTIVE  ◄──── reinstate (admin) ────┐
           │                                │
     ┌─────┴─────┐                          │
     ▼           ▼                          │
 SUSPENDED   DISABLED                       │
 (temporary) (permanent) ───────────────────┘
     │
  cannot log in -> 403 ACCOUNT_SUSPENDED
```

**Enforcement rule:** status is checked at **login and at refresh**, not only at login. Otherwise a suspended user keeps refreshing a valid session for 30 days. On suspension or disabling, revoke all of that user's refresh tokens immediately.

Provider approval is a separate axis from status:

```
PROVIDER registers -> status ACTIVE, provider_approved = false
                   -> can log in, cannot publish experiences
ADMIN approves     -> provider_approved = true -> PROVIDER_APPROVED event
```

---

## 6. Database (PostgreSQL)

Managed with **Flyway** migrations (`V1__init.sql`, `V2__...`). Never `ddl-auto: update`.

### PostgreSQL conventions used here

| Decision | Choice | Why |
| --- | --- | --- |
| Primary keys | native `UUID` type | Postgres stores it as 16 bytes and indexes it properly. No `BINARY(16)` tricks needed. |
| Generation | `gen_random_uuid()` (built into PG 13+) | No `pgcrypto` extension required |
| Enums | `VARCHAR(24)` + `CHECK` constraint | Postgres has a real `ENUM` type, but adding a value later requires `ALTER TYPE` and it cannot be removed. A `CHECK` constraint is a one-line migration to change. Maps cleanly to `@Enumerated(EnumType.STRING)`. |
| Timestamps | `TIMESTAMPTZ` | Always store UTC with an offset. `TIMESTAMP` without a zone will bite you. Maps to `java.time.Instant`. |
| JSON payloads | `JSONB` | Binary, indexable, validated on insert |
| Table/column names | `snake_case`, unquoted | Postgres lowercases unquoted identifiers. Never use quoted mixed-case names — you will have to quote them forever after. |

### `users`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | PK, `DEFAULT gen_random_uuid()` |
| `email` | `VARCHAR(255)` | **UNIQUE**, stored lowercase (see note below) |
| `password_hash` | `VARCHAR(72)` | BCrypt |
| `full_name` | `VARCHAR(150)` | |
| `phone` | `VARCHAR(30)` | nullable |
| `role` | `VARCHAR(24)` | CHECK IN (TRAVELER, PROVIDER, ADMIN, SUPER_ADMIN) |
| `status` | `VARCHAR(24)` | CHECK IN (PENDING_VERIFICATION, ACTIVE, SUSPENDED, DISABLED) |
| `email_verified_at` | `TIMESTAMPTZ` | nullable |
| `provider_approved` | `BOOLEAN` | `NOT NULL DEFAULT false`, only meaningful for PROVIDER |
| `must_change_password` | `BOOLEAN` | `NOT NULL DEFAULT false` |
| `failed_login_attempts` | `INTEGER` | `NOT NULL DEFAULT 0` |
| `locked_until` | `TIMESTAMPTZ` | nullable |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | `DEFAULT now()` |

> **Postgres gotcha — case-sensitive uniqueness.** Unlike MySQL, Postgres comparisons are case-sensitive by default, so `A@x.com` and `a@x.com` are two different rows and a plain `UNIQUE(email)` will let both in. Normalize to lowercase in the service **and** enforce it in the database:
>
> ```sql
> CREATE UNIQUE INDEX ux_users_email_lower ON users (LOWER(email));
> ```
>
> (Or use the `CITEXT` extension. The functional index needs no extension, so prefer it.)

### `refresh_tokens`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `user_id` | `UUID` | FK -> `users(id)` `ON DELETE CASCADE`, indexed |
| `token_hash` | `VARCHAR(64)` | SHA-256 hex, **UNIQUE**, indexed |
| `family_id` | `UUID` | shared across a rotation chain, indexed |
| `expires_at` | `TIMESTAMPTZ` | |
| `revoked_at` | `TIMESTAMPTZ` | nullable |
| `replaced_by` | `UUID` | nullable, forms the chain |
| `user_agent` / `ip` | `VARCHAR(255)` / `VARCHAR(45)` | optional, for a "your sessions" screen |

> Postgres has a native `INET` type that validates IPv4/IPv6, but it needs a custom JPA type mapping for no MVP benefit — plain `VARCHAR(45)` is used instead. Likewise `VARCHAR(64)` rather than `CHAR(64)` for hashes: Hibernate `ddl-auto: validate` is fussy about `bpchar`, and blank-padding semantics are one more thing to think about for zero gain.

### `email_verification_tokens` / `password_reset_tokens`

Same shape for both:

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `user_id` | `UUID` | FK -> `users(id)` `ON DELETE CASCADE` |
| `token_hash` | `VARCHAR(64)` | UNIQUE — store the hash, email the raw value |
| `expires_at` | `TIMESTAMPTZ` | verification 24h, reset **15 min** |
| `used_at` | `TIMESTAMPTZ` | nullable — single use |

### `outbox_events`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | PK — becomes the Kafka `eventId` |
| `aggregate_type` | `VARCHAR(32)` | `USER` |
| `aggregate_id` | `UUID` | user id — used as the Kafka **partition key** so one user's events stay ordered |
| `event_type` | `VARCHAR(48)` | `USER_REGISTERED`, ... |
| `payload` | `JSONB` | event body |
| `created_at` | `TIMESTAMPTZ` | `DEFAULT now()` |
| `published_at` | `TIMESTAMPTZ` | NULL until published |
| `attempts` | `INTEGER` | retry counter |

The publisher only ever scans unpublished rows, so index exactly that:

```sql
CREATE INDEX ix_outbox_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;
```

That is a **partial index** — a Postgres feature with no MySQL equivalent. It only contains the handful of pending rows, so it stays tiny no matter how large the table grows, and published rows drop out of it automatically.

> **Dropped from the original spec:** the `roles` and `user_roles` tables (replaced by the `role` column, see §2).

---

## 7. Kafka events

Topic: `explorelk.auth.events` — key = `user_id`, 3 partitions.

| Event | Emitted when | Consumed by |
| --- | --- | --- |
| `USER_REGISTERED` | Registration commits | Notification (verification email) |
| `USER_EMAIL_VERIFIED` | Verification succeeds | Notification (welcome) |
| `USER_SUSPENDED` | Admin suspends | Booking, Trip (block activity) |
| `USER_DISABLED` | Admin disables | Booking, Trip |
| `PROVIDER_REGISTERED` | PROVIDER registers | Notification (admin alert) |
| `PROVIDER_APPROVED` | Admin approves | Experience (allow publishing), Notification |
| `ADMIN_CREATED` | SUPER_ADMIN creates an admin | Audit |

**Envelope** — every event has the same shape so consumers can be written generically:

```json
{
  "eventId": "uuid",
  "eventType": "USER_REGISTERED",
  "occurredAt": "2026-08-27T10:15:30Z",
  "version": 1,
  "aggregateId": "user-uuid",
  "data": { "userId": "...", "email": "...", "role": "TRAVELER" }
}
```

**Rule: never put a password hash in an event.** Kafka retains events for days and every consumer sees them. The Notification Service receives the verification *token* only because it must build the link — that is standard and acceptable for the MVP.

### Registration flow end to end

```
POST /register
     │
     ├─ validate, check email uniqueness
     ├─ hash password (BCrypt)
     │
     ├─ BEGIN TX ─────────────────────────────┐
     │    INSERT users (PENDING_VERIFICATION) │
     │    INSERT email_verification_tokens    │
     │    INSERT outbox_events USER_REGISTERED│
     ├─ COMMIT ───────────────────────────────┘
     │
     └─► 201 Created (no tokens — must verify first)

   OutboxPublisher @Scheduled(1s)
     └─► Kafka: explorelk.auth.events
              └─► Notification Service -> verification email
```

The HTTP response does not wait for Kafka or email. If Kafka is down, the user is still registered and the email goes out when Kafka recovers.

---

## 8. Redis usage

| Purpose | Key | TTL |
| --- | --- | --- |
| Login rate limit (per IP) | `rl:login:{ip}` | 60s — 10 attempts |
| Brute force (per account) | `lock:login:{email}` | 15m — lock after 5 failures |
| Forgot-password throttle | `rl:pwreset:{email}` | 15m — 3 requests |
| Resend-verification throttle | `rl:verify:{email}` | 15m — 3 requests |
| Access token denylist | `jwt:denylist:{jti}` | remaining token lifetime |

Use **Bucket4j** with the Redis backend, or a plain `INCR` + `EXPIRE`. `INCR`/`EXPIRE` is fewer dependencies and easier to understand while learning — start there.

**Degradation policy:** if Redis is down, rate limiting fails **open** (log a warning, allow the request) but the denylist fails **closed** on logout-sensitive paths. Never let a Redis outage take down login entirely.

> **Built differently, on purpose: the account lockout lives in Postgres, not Redis.** `users.failed_login_attempts` and `users.locked_until` already exist in `V1__init.sql` and `User.isLocked()` was already checked on every login, so keeping the count in Redis would have left two answers to the same question with the schema's answer permanently stale. More importantly, the rate limiter above *must* fail open — and if the lockout failed open with it, a single unreachable cache would remove both defences at once and leave the password endpoint completely unprotected. What stays in Redis is `rl:login:{ip}`, `rl:pwreset:{email}` and `rl:verify:{email}`, which genuinely are throwaway counters. See `LoginAttemptService`.

---

## 9. Security requirements

**Input & password**

- Bean Validation on every request DTO; reject unknown JSON fields
- Email normalized to lowercase, unique constraint at the DB level (not just a service check — concurrent registrations will race)
- Password policy: min 10 chars, at least one letter and one digit; reject a common-password list
- BCrypt strength 12

**Tokens**

- Access 15 min, refresh 30 days, reset token 15 min, verification token 24h
- All non-JWT tokens stored hashed, single-use, with `used_at`
- Refresh rotation with family reuse detection (§4)
- Revoke all refresh tokens on: password change, password reset, suspension, disabling

**Enumeration resistance** — the same response whether or not an email exists:

| Endpoint | Response |
| --- | --- |
| `/login` with wrong email **or** wrong password | `401 INVALID_CREDENTIALS` — identical body, identical timing |
| `/forgot-password` | `202` always |
| `/resend-verification` | `202` always |
| `/register` with an existing email | `202` + "check your email" (and email the existing owner a "someone tried to register" notice) |

> Timing matters: if the email does not exist, still run a dummy BCrypt hash so the response time matches.

**Transport & platform**

- HTTPS only in production; `Strict-Transport-Security`
- CORS allowlist per environment — never `*` with credentials
- Global `@RestControllerAdvice`; no stack traces to the client
- Logs: never log passwords, raw tokens, `Authorization` headers, or reset links. Mask email as `j***@example.com`
- Lock down Actuator: only `health` and `info` public

### Error format

```json
{
  "code": "INVALID_CREDENTIALS",
  "message": "Invalid email or password",
  "timestamp": "2026-08-27T10:15:30Z",
  "path": "/api/v1/auth/login",
  "traceId": "a1b2c3"
}
```

Codes: `VALIDATION_FAILED`, `INVALID_CREDENTIALS`, `EMAIL_NOT_VERIFIED`, `ACCOUNT_SUSPENDED`, `ACCOUNT_DISABLED`, `ACCOUNT_LOCKED`, `TOKEN_EXPIRED`, `TOKEN_INVALID`, `TOKEN_REUSED`, `RATE_LIMITED`, `FORBIDDEN`, `NOT_FOUND`, `INTERNAL_ERROR`.

`message` is safe to show a user; `code` is what clients branch on. Never put an exception message into `message`.

---

## 10. Project structure

Package by **feature**, not by layer — each folder is a slice you can read top to bottom.

```
com.explorelk.auth
├── AuthApplication.java
├── config/
│   ├── SecurityConfig.java          # filter chain, RBAC rules, CORS
│   ├── JwtKeyConfig.java            # loads RSA keypair
│   ├── RedisConfig.java
│   ├── KafkaConfig.java
│   ├── OpenApiConfig.java
│   └── SuperAdminBootstrap.java     # ApplicationRunner
│
├── user/
│   ├── User.java  UserRole.java  UserStatus.java
│   ├── UserRepository.java
│   ├── UserService.java
│   ├── UserController.java          # /users/me
│   └── dto/
│
├── auth/
│   ├── AuthController.java          # register, login, refresh, logout
│   ├── AuthService.java
│   ├── RegistrationService.java
│   └── dto/
│
├── token/
│   ├── JwtService.java              # sign + parse
│   ├── JwksController.java          # /.well-known/jwks.json
│   ├── RefreshToken.java  RefreshTokenRepository.java
│   ├── RefreshTokenService.java     # rotation + family revocation
│   └── TokenDenylistService.java    # Redis
│
├── verification/                    # email verification + password reset
├── admin/                           # admin + super-admin controllers
│
├── outbox/
│   ├── OutboxEvent.java
│   ├── OutboxRepository.java
│   ├── OutboxWriter.java            # called inside @Transactional
│   └── OutboxPublisher.java         # @Scheduled -> Kafka
│
├── ratelimit/
│   ├── RateLimitService.java
│   └── RateLimitFilter.java
│
└── common/
    ├── ApiError.java
    ├── GlobalExceptionHandler.java
    └── exception/
```

```
src/main/resources/
├── application.yml            # shared
├── application-dev.yml
├── application-prod.yml
└── db/migration/V1__init.sql

keys/                          # dev only — NEVER commit; prod uses secrets
├── private.pem
└── public.pem
```

---

## 11. Tech stack

| Concern | Choice |
| --- | --- |
| Java | 17 (LTS) — see note below |
| Framework | Spring Boot 4.1.1 (Spring Framework 7, Hibernate 7) |
| Build | Maven (wrapper: `./mvnw`) |
| Security | Spring Security 7 + `spring-boot-starter-oauth2-resource-server` |
| Persistence | Spring Data JPA + PostgreSQL 16 |
| Migrations | Flyway |
| Cache | Spring Data Redis (Lettuce) |
| Messaging | Spring for Apache Kafka |
| Docs | springdoc-openapi |
| Testing | JUnit 5, Mockito, Testcontainers, REST Assured |
| Observability | Actuator + Micrometer |

> **On the Java version.** This design was written against Java 21, but the machine has JDK 17 and JDK 25 installed and no 21. Java 17 is the right pick: it is an LTS, it is Spring Boot 4's baseline, and nothing in this service needs a Java 21 feature. Moving up later is a one-line change to `<java.version>` in the pom.

### Spring Boot 4 differences to expect

The scaffold was generated at Spring Boot 4.1.1, so this is Spring Framework 7 / Security 7 / Hibernate 7 — newer than most tutorials you will find. Differences hit so far:

| Difference | Symptom | Fix |
| --- | --- | --- |
| **Autoconfiguration is split into per-technology modules.** In Boot 3, `spring-boot-autoconfigure` wired up everything on the classpath. In Boot 4 each integration ships its own module. | `flyway-core` on the classpath but migrations never run, and Hibernate then fails `validate` against an empty schema. No warning, no error — Flyway simply is not invoked. | Add `org.springframework.boot:spring-boot-flyway` alongside `flyway-core`. |
| **Nimbus JOSE is not directly version-managed.** Boot 4 pins it through the Spring Security BOM instead. | `'dependencies.dependency.version' for com.nimbusds:nimbus-jose-jwt is missing`. | Depend on `spring-security-oauth2-jose` rather than the Nimbus artifact. |
| **Jackson 3.** `ObjectMapper` moved from `com.fasterxml.jackson.databind` to `tools.jackson.databind`. Annotations stayed at `com.fasterxml.jackson.annotation`, so imports split across two roots. | `package com.fasterxml.jackson.databind does not exist`, while `@JsonInclude` keeps compiling. | Import `tools.jackson.databind.ObjectMapper`. |
| **`CorsConfigurationSource` is ambiguous by type.** Spring MVC's `mvcHandlerMappingIntrospector` implements the same interface. | `required a single bean, but 2 were found` at startup. | Use `.cors(Customizer.withDefaults())`, which resolves the bean named `corsConfigurationSource`, instead of injecting the type. |

When a Boot 3 tutorial's dependency list does not work, check first whether the autoconfiguration now lives in its own `spring-boot-*` module.

> Use `oauth2-resource-server` rather than hand-writing a JWT filter. Give it the JWKS URL (or the public key) and Spring Security handles parsing, signature verification, and expiry — you only add the denylist check. Hand-rolled JWT filters are where most auth bugs live.

---

## 12. Build plan

Twelve steps. **Each one ends in something you can run and see working** — do not move on until the checkpoint passes.

### Step 0 — Local infrastructure

Write `docker-compose.yml` at the repo root with: PostgreSQL 16, Redis 7, Kafka (KRaft mode, no ZooKeeper), Kafka UI (`localhost:8085`), MailHog (fake SMTP, `localhost:8025`).

> **This machine.** Ports 5432 and 5434 were already taken by other Postgres servers, so the compose Postgres publishes on **5433**. Docker Hub pulls are extremely slow here (layers sit at 0 bytes for many minutes before moving) but they do complete — budget 20+ minutes for the first `docker compose up -d` and let it finish rather than killing it. All five images are pulled and running.

**Checkpoint:** `docker compose up -d`, all containers healthy, Kafka UI loads in a browser.

> Do this first. Nothing else works without it, and Kafka in KRaft mode is far simpler than the old ZooKeeper setup.

---

### Step 1 — Skeleton

`services/auth-service` via Spring Initializr: Web, JPA, PostgreSQL Driver, Flyway, Validation, Actuator, Lombok. Config in `application-dev.yml`, `ddl-auto: validate`.

**Checkpoint:** app starts, `GET /actuator/health` returns `{"status":"UP"}`.

---

### Step 2 — Schema

Write `V1__init.sql` with all six tables from §6. Create the JPA entities to match.

**Checkpoint:** app boots, Flyway applies V1, `flyway_schema_history` shows success, `ddl-auto: validate` does not complain.

> If validate fails, your entity and your SQL disagree. Fix it now — this only gets more painful later.

---

### Step 3 — Registration (no email, no events yet)

`POST /register`: validate -> check email -> BCrypt -> save as `PENDING_VERIFICATION`. Global exception handler + `ApiError`. Return 201.

**Checkpoint:** register via Postman, row appears in PostgreSQL with a hashed password. Duplicate email returns a clean `409`, not a stack trace.

> **409 here is temporary.** §9 requires registration to be enumeration-resistant, which means returning 202 whether or not the email exists and emailing the existing owner instead. That is impossible until the Notification Service can send mail, so Step 3 returns 409 and **Step 7 changes it to 202**. `EmailAlreadyRegisteredException` carries a note to the same effect.

> **Watch the logs, not just the response.** `org.hibernate.orm.jdbc.bind: TRACE` writes every JDBC bind parameter to the log — in this service that means email addresses and BCrypt hashes in plain text. It is off in `application-dev.yml` for exactly that reason.

---

### Step 4 — RSA keys + JWKS

Generate a keypair:

```bash
openssl genrsa -out keys/private.pem 2048
openssl rsa -in keys/private.pem -pubout -out keys/public.pem
```

Add `keys/` to `.gitignore` now. Load them in `JwtKeyConfig`, write `JwtService.generateAccessToken()`, expose `/.well-known/jwks.json`.

**Checkpoint:** hit the JWKS endpoint, get a JSON key whose `kid`, `kty`, `n` and `e` are present and which contains **no** `d`, `p` or `q` — those are private parameters and publishing them would hand out the signing key.

> Rather than eyeballing a token on jwt.io, `JwtServiceTest` asserts the round trip: sign with the private key, then verify using only what the JWKS document exposes. That is the property every other service depends on, and it is worth having a test hold it. It also avoids shipping a token-minting endpoint just to see a token — real ones arrive with login in Step 5.

---

### Step 5 — Login + security filter chain

`POST /login`: verify password, check status, issue access + refresh. Configure `SecurityConfig` — permit `/auth/**` and `/.well-known/**`, authenticate everything else. Add `GET /users/me`.

**Checkpoint:** login returns a token pair. `/users/me` with the token returns your user; without the token, 401. Suspended user gets 403.

> This is the milestone where the service becomes real. Take your time on `SecurityConfig`.

---

### Step 6 — Refresh rotation + logout

`RefreshTokenService` with hashing, `family_id`, rotation, and reuse detection. Logout revokes the refresh token and denylists the `jti` in Redis. Add a small filter that rejects denylisted `jti`s.

**Checkpoint:** refresh returns a new pair; **reusing the old refresh token returns 401 and kills the family**; after logout the old access token is rejected before it expires.

> Test the reuse case explicitly. It is the part people get wrong — and the 401 alone does **not** prove it works. Build a chain A -> B -> C, replay A, then try **C**. If C still refreshes, the family was never revoked.

> **The trap, hit for real while building this.** Reuse detection has to revoke the family *and* reject the request. But rejecting means throwing, and a throw out of a `@Transactional` method rolls the transaction back — taking the revocation with it. The result is the worst possible outcome: a convincing `401 TOKEN_REUSED` while every token in the family stays alive, so the attacker just moves to the next one. The revocation must commit in its own transaction (`REQUIRES_NEW`, on a **separate bean** — self-invocation bypasses the proxy and the propagation is silently ignored). See `RefreshTokenFamilyRevoker`.

---

### Step 7 — Email verification + password reset

Token generation (raw emailed, hash stored), `verify-email`, `resend-verification`, `forgot-password`, `reset-password`, `change-password`. Reset revokes all refresh tokens. Enumeration-safe responses throughout.

**Checkpoint:** full cycle works against MailHog. `forgot-password` for a non-existent email still returns 202. A reset token cannot be used twice.

> **Registration flips from 201/409 to a flat 202 here.** Enumeration resistance is not only about the status code — the *body* must match too, so registration stops returning the new user's id and stops setting a `Location` header. Both would answer the question the 202 refuses to. The duplicate branch also re-hashes the password it is about to discard, because skipping that work makes the duplicate path measurably faster and the timing becomes the tell.
>
> Cost of this: someone who typos their address gets no "you already have an account", just a quiet inbox. Put "check your email" and a password-reset link on the signup screen.

> **Reading tokens out of MailHog:** bodies are quoted-printable, so `token=3DXApu…` is really `token=` + `XApu…` (`=3D` is an escaped `=`). Decode with `quopri` before regexing, or the token will be wrong in a way that looks like a backend bug.

---

### Step 8 — Outbox + Kafka ✅

`outbox_events` writes inside the existing transactions. `OutboxPublisher` on `@Scheduled(fixedDelay = 1000)` with `FOR UPDATE SKIP LOCKED` (native in Postgres — lets several instances drain the outbox without stepping on each other). Publish all seven event types.

**Checkpoint:** register a user, see the event in Kafka UI. Then **stop Kafka, register again, restart Kafka** — the event still arrives. That test is the whole point of the outbox.

---

### Step 9 — Rate limiting + brute force ✅

Redis counters per §8. Lock the account after 5 failures for 15 minutes; reset the counter on success. Return `429` with a `Retry-After` header.

**Checkpoint:** 6 bad logins produce `ACCOUNT_LOCKED`. Rapid requests produce `429`. Stop Redis and login still works (fails open).

---

### Step 10 — Admin, super-admin, bootstrap ✅

`SuperAdminBootstrap` runner. Admin user list/detail/status, provider approval. Super-admin creates admins. RBAC via `@PreAuthorize("hasRole('ADMIN')")`. Status changes revoke that user's refresh tokens and emit events.

**Checkpoint:** super-admin exists on a fresh DB. A TRAVELER hitting `/admin/**` gets 403. Suspending a user invalidates their session immediately.

---

### Step 11 — Tests ✅

- **Unit (Mockito):** password policy, rotation logic, denylist, outbox writer
- **Integration (Testcontainers PostgreSQL + Redis):** registration, duplicate email, login success/failure, refresh + rotation, **reuse detection**, logout, email verification, password reset, suspended login blocked, role-protected endpoints
- **Kafka (Testcontainers):** one test — event lands on the topic after commit

**Checkpoint:** `mvn verify` green from a clean DB.

> Keep Kafka to a single test. Each Kafka container costs roughly 20s of startup; PostgreSQL and Redis containers are cheap by comparison.

---

### Step 12 — Package ✅

Multi-stage `Dockerfile`, add the service to `docker-compose.yml`, springdoc at `/swagger-ui.html`, Actuator liveness/readiness, `.env.example`, and a service `README.md`.

**Checkpoint:** `docker compose up` from scratch on a clean machine brings up the whole thing and register -> verify -> login works end to end.

---

### Order rationale

```
Step 0-2   infrastructure & data      ─┐
Step 3-5   it authenticates           ─┤ core — nothing works without these
Step 6-7   it is secure               ─┘
Step 8     it talks to other services  <- the microservices part
Step 9-10  it is production-shaped
Step 11-12 it is verifiable & shippable
```

You could reorder 9 and 10, but **do not** move Kafka earlier. Get authentication correct against a database first; adding messaging while login is still half-built means debugging two unfamiliar systems at once.

---

## 13. Definition of done

- [x] A traveler can register, verify, log in, refresh, and log out — `AuthFlowIT`
- [x] A provider can register and be approved by an admin — `AdminIT`
- [x] Suspended and unverified users cannot authenticate — `AuthFlowIT`, `AdminIT`
- [x] Refresh reuse revokes the entire token family — `AuthFlowIT.reuseRevokesTheFamily`
- [x] All seven events reach Kafka, and survive Kafka being restarted — `OutboxEventsIT` covers the seven; `OutboxKafkaIT` pauses the broker mid-registration and the event still arrives
- [x] Another service can verify a token using only the JWKS endpoint — proven by the Destination Service, which has no user table and never calls this one
- [x] Rate limiting and account lockout work — `RateLimitIT` (account, Postgres) and `IpRateLimitIT` (per IP, Redis)
- [x] `mvn verify` passes from a clean database
- [x] `docker compose up` works on a clean machine — `Dockerfile` plus an `auth-service` entry with a readiness healthcheck
- [x] No secrets, tokens, or passwords in logs or in git — `keys/` is git-ignored and excluded by `.dockerignore`; `AuthEventTest` asserts no event type can carry the hash

---

## 14. Deferred past MVP

Deliberately out of scope, listed so they are not forgotten:

- OAuth2 social login (Google/Facebook)
- Two-factor authentication
- Email change flow (needs dual verification)
- "Your active sessions" management screen
- OpenTelemetry distributed tracing — add once 3+ services exist and there is something to trace
- Kubernetes manifests
