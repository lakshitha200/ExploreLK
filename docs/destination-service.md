# Destination Service — Design & Build Plan

> Owns the catalog of **where a traveler can go** in Sri Lanka and **what there is to see** when they get there.
> Trip, Itinerary and Experience services all read from it. None of them keep their own copy.

---

## 0. Concepts you need first

You built the Auth Service, so Spring Boot, Flyway, Postgres and Redis are familiar. Three things are new here.

### Why this service can verify a token it never created

The Auth Service signs access tokens with a **private** RSA key and publishes the matching **public** key at `/.well-known/jwks.json`. This service points Spring Security at that URL and gets authorization for free:

```
Destination Service startup
        │
        ├─ fetch http://localhost:8081/.well-known/jwks.json   (once, then cached)
        │
Request with Bearer token
        │
        ├─ verify signature with the public key    ← pure local math
        ├─ check iss / exp
        └─ read the `role` claim  ──►  hasRole('ADMIN')
```

There is **no network call to the Auth Service per request**, no shared secret, and no user table in this database. If the Auth Service is down, already-issued tokens still work here. That property is the entire reason the Auth Service was built the way it was — this is the service that proves it.

> One consequence: **this service cannot see a logout.** The Redis `jti` denylist lives in the Auth Service. A token revoked at logout stays valid here until it expires (15 min). That is an accepted trade for the MVP — nothing in this service is destructive enough to need sub-15-minute revocation. Do not "fix" it by calling Auth on every request; that throws away the whole design.

### What PostGIS is doing here

"Attractions within 5 km of Ella" is a real query in this product. Without PostGIS you have two bad options:

```java
// Option A — load everything, filter in Java. Dies at a few thousand rows.
List<Destination> all = repo.findAll();
all.stream().filter(d -> haversine(lat, lng, d) < 5).toList();

// Option B — hand-written Haversine in SQL. Correct, but no index can help it,
// so every query is a full table scan.
```

PostGIS adds a real spatial type and, crucially, a **spatial index**:

```sql
-- geography(Point, 4326) = a point on the WGS-84 globe. ST_DWithin works in METRES.
SELECT * FROM destinations
WHERE ST_DWithin(geog, ST_MakePoint(:lng, :lat)::geography, :radiusMetres)
ORDER BY geog <-> ST_MakePoint(:lng, :lat)::geography
LIMIT 20;
```

`ST_DWithin` uses a GiST index, so it looks at a handful of candidate rows instead of the whole table. `<->` is the ordered-nearest-neighbour operator — it uses the same index for the sort. Neither has a MySQL-quality equivalent, and neither is something you want to hand-roll.

> **Note the argument order: `ST_MakePoint(longitude, latitude)`.** X before Y, so **lng before lat** — the opposite of how people say it. This is the most common PostGIS bug, and it fails quietly: the results are simply wrong, somewhere in the Indian Ocean.

### Why Redis here is a cache, not security state

In the Auth Service, Redis held counters and the denylist — losing it changed behaviour. Here it holds **copies of rows that rarely change**:

```
GET /api/v1/destinations/ella
        ↓
      Redis
     /     \
   HIT     MISS
    ↓        ↓
 return   PostgreSQL ─► cache with TTL ─► return
```

Destination data is read constantly (every trip plan, every itinerary build) and written maybe a few times a week by an admin. That is the textbook cache profile. If Redis is empty or down, every request goes to Postgres and everything still works — the cache is **never** the source of truth. There is no correctness argument for it, only latency.

The hard part is not caching, it is **eviction**: when an admin edits Ella, the stale copy must go. Rule for this service — any write path evicts the keys it touched, after the transaction commits.

---

## 1. Responsibilities

The Destination Service owns:

- Destinations (place, district, province, coordinates, description, recommended stay)
- Attractions belonging to a destination (visit duration, entrance fee, opening hours, coordinates)
- The category vocabulary (`NATURE`, `BEACH`, `HIKING`, …) and what is tagged with what
- Search, category filtering, and proximity — "what is near here"
- Publication state of catalog content (draft / published / archived)
- Publishing catalog domain events

It does **not** own:

| Not this | Belongs to |
| --- | --- |
| Trips, dates, budgets | Trip Service |
| Day-by-day plans, route ordering | Itinerary Service |
| Bookable activities, prices, capacity | Experience Service |
| Reservations | Booking Service |
| Users, providers, roles | Auth Service |

> The failure mode to guard against is this service slowly absorbing "travel stuff". A test before adding any field: *does it describe the place itself, or does it describe someone's plan or someone's business?* Only the first belongs here.

---

## 2. Domain model

```
Category (NATURE, BEACH, HIKING, WILDLIFE, HISTORY, CULTURE, ADVENTURE, …)
    ▲                                 ▲
    │ many-to-many                    │ many-to-many
    │                                 │
Destination ──── 1 : many ────► Attraction

  Ella                            Nine Arches Bridge
  Kandy                           Ella Rock
  Sigiriya                        Ravana Falls
```

**Destination** — a place you travel *to* and stay near. Has a district, a province, a recommended number of days.

**Attraction** — a specific thing you *do or see*, belonging to exactly one destination. Has a visit duration and an entrance fee. This is the unit the Itinerary Service packs into days, so `visit_duration_minutes` is not decoration — it is the input to route planning.

**Category** — a small fixed vocabulary, shared by both. Multi-valued: Ella is `NATURE` + `HIKING` + `ADVENTURE`.

> **Why a join table and not a `role`-style single column?** The Auth Service gave each user exactly one role, so an enum column was right there. Here a destination genuinely is several things at once, and the primary traveler query is "show me all `BEACH` destinations". A Postgres `text[]` column with a GIN index would also work and is tempting, but a real `categories` table lets an admin screen list categories with display names and icons without hard-coding them in the frontend. Take the join table.

### Example content

```
Destination: Ella                        Attraction: Nine Arches Bridge
  slug              ella                   destination      Ella
  district          Badulla                categories       NATURE
  province          Uva                    visit duration   90 min
  categories        NATURE, HIKING,         entrance fee     free
                    ADVENTURE              opening hours    always accessible
  recommended stay  2 days                 lat / lng        6.8767 / 81.0602
  lat / lng         6.8667 / 81.0466       status           PUBLISHED
  status            PUBLISHED
```

---

## 3. API surface

### Public — travelers, no token required

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/destinations` | Paginated list. Params: `search`, `category`, `district`, `province`, `sort`, `page`, `size` |
| GET | `/api/v1/destinations/{idOrSlug}` | Single destination with its categories |
| GET | `/api/v1/destinations/{idOrSlug}/attractions` | Attractions of one destination |
| GET | `/api/v1/destinations/nearby?lat=&lng=&radiusKm=&limit=` | Destinations within a radius, nearest first |
| GET | `/api/v1/attractions/{id}` | Single attraction |
| GET | `/api/v1/attractions/nearby?lat=&lng=&radiusKm=&limit=` | Attractions within a radius |
| GET | `/api/v1/categories` | The category vocabulary, for filter UIs |

Public endpoints only ever return `PUBLISHED` content. `DRAFT` and `ARCHIVED` rows are invisible without an admin token — enforced in the repository layer, not in the controller, so a new endpoint cannot forget.

### Admin — `ADMIN` or `SUPER_ADMIN` token required

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/admin/destinations` | List **including** drafts and archived |
| POST | `/api/v1/admin/destinations` | Create — starts as `DRAFT` |
| PATCH | `/api/v1/admin/destinations/{id}` | Partial update |
| PATCH | `/api/v1/admin/destinations/{id}/status` | `DRAFT` / `PUBLISHED` / `ARCHIVED` |
| DELETE | `/api/v1/admin/destinations/{id}` | Archives — see §5, this is not a row delete |
| POST | `/api/v1/admin/destinations/{id}/attractions` | Create an attraction |
| PATCH | `/api/v1/admin/attractions/{id}` | Partial update |
| PATCH | `/api/v1/admin/attractions/{id}/status` | Status change |
| DELETE | `/api/v1/admin/attractions/{id}` | Archives |
| POST | `/api/v1/admin/categories` | Add a category to the vocabulary |

### Platform

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/actuator/health/liveness` | Liveness probe |
| GET | `/actuator/health/readiness` | Readiness probe — DB, Redis |
| GET | `/swagger-ui.html` | API docs, dev profile only |

> **`{idOrSlug}` is deliberate.** Machines (Trip, Itinerary) hold UUIDs; humans and URLs want `/destinations/ella`. One resolver: if the path segment parses as a UUID, look up by id, otherwise by slug. It keeps `/destinations/ella` working without a second endpoint. Attractions take id only — their slugs are unique only within a destination.

---

## 4. Search, filtering & pagination

**Search** is a case-insensitive `LIKE '%term%'` over `name` and `district`, accelerated by a **trigram index**:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_destinations_name_lower_trgm ON destinations USING GIN (lower(name) gin_trgm_ops);
```

Without `pg_trgm`, a leading-wildcard match cannot use any index and scans the table. With it, `%ella%` is indexed — and it also gives you fuzzy matching (`similarity()`), so a search for `kandi` can find Kandy later without redesigning anything.

> **Index the expression the query actually uses.** V1 indexed `name gin_trgm_ops`, which answers `name ILIKE '%ella%'`. But the filters are built with the JPA Criteria API, which has no `ILIKE` and emits `lower(name) LIKE '%ella%'` — and Postgres will not use an index on `name` to answer a predicate on `lower(name)`. `V2__search_indexes.sql` moves both trigram indexes onto `lower(...)`. Verified with `SET enable_seqscan = off; EXPLAIN`: the OR across name and district plans as a `BitmapOr` over `ix_destinations_name_lower_trgm` and `ix_destinations_district_lower_trgm`.
>
> The plain btree `ix_destinations_district` stays — it serves the exact `?district=Badulla` filter, which is a different query from the free-text one.

> Full-text search (`tsvector`, ranking, stemming) is the "correct" answer and is **deferred**. For a catalog of a few hundred Sri Lankan destinations, trigram `ILIKE` is faster to build, easier to reason about, and indistinguishable to the user.

**Filtering** — `category`, `district` and `province` are exact matches and compose with search. `category` filters on the join table by code, so answering it needs no join to `categories`.

**Pagination** — Spring Data `Pageable`, wrapped in the platform's own response shape rather than leaking Spring's `Page` JSON, which is verbose and unstable across versions:

```json
{
  "items": [ ... ],
  "page": 0,
  "size": 20,
  "totalItems": 137,
  "totalPages": 7
}
```

Cap `size` at 100 server-side. An uncapped page size is a free denial-of-service on a public endpoint.

---

## 5. Content lifecycle

```
create ──► DRAFT ──publish──► PUBLISHED ──archive──► ARCHIVED
             ▲                     │                     │
             └───── unpublish ─────┘                     │
             └──────────────── restore ──────────────────┘
```

| Status | Publicly visible | Meaning |
| --- | --- | --- |
| `DRAFT` | no | Being written. Incomplete data is fine. |
| `PUBLISHED` | yes | Live in the catalog. |
| `ARCHIVED` | no | Retired, but the row and its id survive. |

> **Why `DELETE` archives instead of deleting.** Trip and Itinerary services will store `destination_id` values pointing here. A hard delete turns every one of those into a dangling reference — and since they live in *different databases*, there is no foreign key to protect you. The corruption is silent and permanent. Archiving keeps the id resolvable forever, which is exactly what a cross-service reference needs. The endpoint stays `DELETE` because that is what an admin UI expects to call; only the implementation differs.
>
> Publishing should also require the fields the traveler-facing UI needs: name, district, province, coordinates, at least one category. Validate that on the **publish transition**, not on create — otherwise an admin cannot save half-finished work.

---

## 6. Database (PostgreSQL + PostGIS)

Own database, `explorelk_destination`, on the shared container. Flyway migrations, `ddl-auto: validate`, same conventions as the Auth Service (§6 there): `UUID` PKs, `VARCHAR` + `CHECK` instead of native enums, `TIMESTAMPTZ` everywhere, `snake_case`.

> **Infrastructure change required.** `postgres:16-alpine` does not contain PostGIS. `docker-compose.yml` reads the image from `${POSTGRES_IMAGE}`, so this is a one-line `.env` change to `postgis/postgis:16-3.4`.
>
> **Done, and the existing volume survived it.** The concern was that swapping the alpine base for debian would upset the cluster's collation and force a volume drop. It did not: Postgres 16.4 (Debian) started cleanly on the data directory initialised by the alpine image — `database system is ready to accept connections`, `explorelk_auth` and its Flyway history intact. No data was destroyed. If a future major-version image ever does refuse the directory, that is the point to drop the volume and let Flyway rebuild both databases.

### `categories`

| Column | Type | Notes |
| --- | --- | --- |
| `code` | `VARCHAR(24)` | **PK** — `NATURE`, `BEACH`, `HIKING`, … |
| `name` | `VARCHAR(60)` | Display name |
| `description` | `VARCHAR(200)` | nullable |
| `icon` | `VARCHAR(40)` | nullable, frontend hint |
| `sort_order` | `SMALLINT` | `NOT NULL DEFAULT 0` |

> A natural primary key, breaking the UUID convention on purpose. `code` **is** the public API contract (`?category=BEACH`), the table is a fixed vocabulary that is never bulk-created, and a natural key means `destination_categories` rows are readable on their own and category filtering never joins.

### `destinations`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | PK, `DEFAULT gen_random_uuid()` |
| `slug` | `VARCHAR(80)` | **UNIQUE**, lowercase — `ella`, `nuwara-eliya` |
| `name` | `VARCHAR(120)` | |
| `district` | `VARCHAR(60)` | indexed |
| `province` | `VARCHAR(40)` | indexed |
| `summary` | `VARCHAR(300)` | one line, for list cards |
| `description` | `TEXT` | nullable, full body |
| `latitude` | `NUMERIC(9,6)` | −90..90, `CHECK` |
| `longitude` | `NUMERIC(9,6)` | −180..180, `CHECK` |
| `geog` | `geography(Point,4326)` | **generated** from lng/lat — see below |
| `recommended_days` | `SMALLINT` | nullable, `CHECK > 0` |
| `cover_image_url` | `VARCHAR(500)` | nullable |
| `popularity_score` | `INTEGER` | `NOT NULL DEFAULT 0` |
| `status` | `VARCHAR(16)` | CHECK IN (DRAFT, PUBLISHED, ARCHIVED) |
| `version` | `INTEGER` | `NOT NULL DEFAULT 0` — JPA `@Version` |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | `DEFAULT now()` |

`NUMERIC(9,6)` not `DOUBLE PRECISION`: six decimal places is about 11 cm, far more than enough, and it round-trips exactly to `BigDecimal`, so the coordinate you save is the coordinate you read back.

`@Version` gives optimistic locking on admin edits. Two admins editing Ella at once stops being hypothetical the moment there is a CMS screen, and the alternative is silent last-write-wins.

### The generated geography column

Store lat/lng as ordinary numbers that JPA understands, and let **Postgres** derive the spatial value:

```sql
ALTER TABLE destinations
  ADD COLUMN geog geography(Point,4326)
  GENERATED ALWAYS AS (
      ST_SetSRID(ST_MakePoint(longitude::float8, latitude::float8), 4326)::geography
  ) STORED;

CREATE INDEX ix_destinations_geog ON destinations USING GIST (geog);
```

This decision is what keeps the service simple: **the entity never maps `geog`**. No `hibernate-spatial` dependency, no JTS types in the domain model, no dialect swap. `ddl-auto: validate` only checks that mapped columns exist, so an unmapped extra column is fine. Proximity is one native query in the repository; everything else stays plain JPA.

> **Gotcha to expect.** `GENERATED ALWAYS AS` requires a strictly `IMMUTABLE` expression. `ST_MakePoint` / `ST_SetSRID` and the geography cast qualify on PostGIS 3.x, but if the migration fails with *"generation expression is not immutable"*, the fallback is a `BEFORE INSERT OR UPDATE` trigger writing a plain (non-generated) `geog` column. Same index, same queries, five more lines of SQL.

### `attractions`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `destination_id` | `UUID` | FK → `destinations(id)`, indexed, `ON DELETE RESTRICT` |
| `slug` | `VARCHAR(80)` | UNIQUE **together with** `destination_id` |
| `name` | `VARCHAR(120)` | |
| `summary` | `VARCHAR(300)` | |
| `description` | `TEXT` | nullable |
| `latitude` / `longitude` | `NUMERIC(9,6)` | same checks |
| `geog` | `geography(Point,4326)` | generated, GiST indexed |
| `visit_duration_minutes` | `SMALLINT` | `CHECK > 0` — the Itinerary Service's input |
| `is_free` | `BOOLEAN` | `NOT NULL DEFAULT false` |
| `entrance_fee` | `NUMERIC(10,2)` | nullable — NULL means *unknown*, not free |
| `currency` | `VARCHAR(3)` | `DEFAULT 'LKR'` |
| `always_open` | `BOOLEAN` | `NOT NULL DEFAULT false` |
| `opening_hours` | `JSONB` | nullable — `{"mon":["06:00","18:00"], …}` |
| `image_url` | `VARCHAR(500)` | nullable |
| `popularity_score` | `INTEGER` | `NOT NULL DEFAULT 0` |
| `status` | `VARCHAR(16)` | DRAFT / PUBLISHED / ARCHIVED |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | |

`ON DELETE RESTRICT` rather than `CASCADE`: destinations are archived, never deleted (§5), so a cascade could only ever fire by accident. Let the database refuse.

Money is `NUMERIC`, never `DOUBLE`. `is_free` exists separately because a NULL fee is genuinely ambiguous — "we don't know" and "it's free" are different facts and the UI shows them differently.

### `destination_categories` / `attraction_categories`

| Column | Type | Notes |
| --- | --- | --- |
| `destination_id` (resp. `attraction_id`) | `UUID` | FK, `ON DELETE CASCADE` |
| `category_code` | `VARCHAR(24)` | FK → `categories(code)` |
| | | PK is the pair. Index the category column too, for `?category=BEACH` |

### Migrations and seed data

```
db/migration/
└── V1__init.sql          # extensions, categories, destinations, attractions, joins, indexes

db/seed/
└── R__seed_catalog.sql   # dev only — repeatable, re-runs when its checksum changes
```

Seed data lives in a **separate Flyway location**, added to `spring.flyway.locations` only in `application-dev.yml`. Real Sri Lankan content — Ella, Kandy, Sigiriya, Nuwara Eliya, Yala, Mirissa, Galle and their attractions — belongs in seeds, not in a versioned migration; otherwise production carries your test fixtures forever. Trip and Itinerary development will both need this data, so make it real, not `test-destination-1`.

---

## 7. Kafka events

Topic: `explorelk.destination.events` — key = the aggregate id, 3 partitions. Same **transactional outbox** pattern as the Auth Service (§0 there), same envelope, same `outbox_events` table shape. Copy it.

| Event | Emitted when | Consumed by |
| --- | --- | --- |
| `DESTINATION_PUBLISHED` | Status → PUBLISHED | Cache warmers, analytics later |
| `DESTINATION_UPDATED` | A published destination is edited | Itinerary — refresh cached copies |
| `DESTINATION_ARCHIVED` | Status → ARCHIVED | Trip, Itinerary — flag affected plans |
| `ATTRACTION_PUBLISHED` | Status → PUBLISHED | Experience Service — link experiences |
| `ATTRACTION_UPDATED` | A published attraction is edited | Itinerary — **duration changes break plans** |
| `ATTRACTION_ARCHIVED` | Status → ARCHIVED | Itinerary |

> **Kafka is not on the read path.** Browsing, searching and nearby queries are plain REST. Events exist only so other services learn that the catalog *changed* — and `ATTRACTION_UPDATED` matters most, because an itinerary built around a 90-minute visit is quietly wrong once that becomes 180.
>
> This is **Step 9** and it is deliberately near the end. Nothing consumes these events until the Itinerary Service exists. Do not block the catalog on Kafka.

---

## 8. Redis usage

Spring's cache abstraction (`@Cacheable` / `@CacheEvict`) over `RedisCacheManager` — no hand-written `RedisTemplate` calls in service code.

| Cache | Key | TTL | Holds |
| --- | --- | --- | --- |
| `destination` | `dest:{idOrSlug}` | 1h | Single destination + categories |
| `destinationList` | `destlist:{filters}:{page}:{size}` | 10m | Only common filter combinations get hits |
| `attractionsOf` | `attr:dest:{destinationId}` | 1h | Full attraction list of one destination |
| `categories` | `cat:all` | 24h | Changes almost never |

**Do not cache `/nearby`.** Its key space is every `(lat, lng, radius)` a phone GPS ever emits, so the hit rate is near zero and Redis fills with garbage. If it ever needs caching, round coordinates to about three decimals first to create real buckets — but measure before bothering, because the GiST index is already fast.

**Eviction rules** — every admin write evicts:

```
update destination X  ──► evict dest:{X.id}, dest:{X.slug}, destlist:*
update attraction  A  ──► evict attr:dest:{A.destinationId}, dest:{…}, destlist:*
category change       ──► evict cat:all, destlist:*
```

Evict **after commit**, not inside the transaction — `@TransactionalEventListener(AFTER_COMMIT)`, or an explicit call at the end of the service method. Evicting inside the transaction opens a window where a concurrent read repopulates the cache from the *old, uncommitted* state, and that stale value then lives for the full TTL.

**Degradation:** a Redis outage must not fail a request. Configure a `CacheErrorHandler` that logs and falls through to the database. The default handler rethrows, which turns a cache outage into a catalog outage.

---

## 9. Security requirements

**Authentication** — `spring-boot-starter-oauth2-resource-server`, pointed at the Auth Service:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${AUTH_JWKS_URI:http://localhost:8081/.well-known/jwks.json}
```

Reuse the Auth Service's `JwtAuthenticationConverter` idea verbatim: the `role` claim is a single value, and Spring's `hasRole('ADMIN')` looks for an authority named literally `ROLE_ADMIN`. Forgetting that prefix is the classic cause of "my `@PreAuthorize` always returns 403".

Validate the `iss` claim against the expected issuer (`explorelk-auth`) as well, so a token minted by a dev Auth Service cannot be replayed against production.

**Authorization**

| Path | Rule |
| --- | --- |
| `GET /api/v1/destinations/**`, `/attractions/**`, `/categories` | `permitAll` |
| `/actuator/health/**`, `/actuator/info` | `permitAll` |
| `/api/v1/admin/**` | `hasAnyRole('ADMIN','SUPER_ADMIN')` |
| everything else | `authenticated()` |

**Input**

- Bean Validation on every DTO; reject unknown JSON fields
- Coordinates range-checked in the DTO **and** by a `CHECK` constraint — a bad coordinate is invisible until someone's itinerary routes through the ocean
- `radiusKm` clamped at 100, `limit` clamped at 50 on the nearby endpoints
- `size` clamped at 100 on every list endpoint
- Slugs generated server-side from the name and normalized to lowercase; never accepted raw from a client
- Admin writes are `@Transactional` at the service layer, never on the controller

**Output**

- Public responses never expose `DRAFT`/`ARCHIVED` content, internal notes, or `version`
- Same `ApiError` shape as the Auth Service — copy `ApiError`, `ErrorCode` and `GlobalExceptionHandler` across so clients parse one error format platform-wide

Codes used here: `VALIDATION_FAILED`, `NOT_FOUND`, `SLUG_ALREADY_EXISTS`, `INVALID_STATUS_TRANSITION`, `INCOMPLETE_FOR_PUBLISH`, `FORBIDDEN`, `CONFLICT` (optimistic lock), `INTERNAL_ERROR`.

**Platform** — CORS allowlist per environment, no stack traces to clients, Actuator limited to `health` and `info`, HTTPS in production.

---

## 10. Project structure

Package by **feature**, not by layer — the same shape as the Auth Service, so moving between the two services costs nothing.

```
com.explorelk.destination
├── DestinationApplication.java
│
├── config/
│   ├── SecurityConfig.java          # resource server, JWKS, RBAC rules, CORS
│   ├── CacheConfig.java             # RedisCacheManager, per-cache TTL, error handler
│   ├── KafkaConfig.java             # Step 9
│   └── OpenApiConfig.java
│
├── category/
│   ├── Category.java
│   ├── CategoryRepository.java
│   ├── CategoryService.java
│   ├── CategoryController.java      # GET /categories
│   └── dto/
│
├── destination/
│   ├── Destination.java  ContentStatus.java
│   ├── DestinationRepository.java   # + DestinationSpatialRepository (native queries)
│   ├── DestinationService.java      # public reads, caching
│   ├── DestinationAdminService.java # writes, status transitions, eviction
│   ├── DestinationController.java   # /api/v1/destinations
│   ├── DestinationAdminController.java
│   └── dto/
│
├── attraction/
│   ├── Attraction.java
│   ├── AttractionRepository.java
│   ├── AttractionService.java
│   ├── AttractionAdminService.java
│   ├── AttractionController.java
│   ├── AttractionAdminController.java
│   └── dto/
│
├── search/
│   └── DestinationSearchSpecs.java  # JPA Specifications: search + category + district
│
├── outbox/                          # Step 9 — copied from auth-service
│   ├── OutboxEvent.java  OutboxRepository.java
│   ├── OutboxWriter.java
│   └── OutboxPublisher.java
│
└── common/
    ├── ApiError.java                # copied from auth-service, unchanged
    ├── ErrorCode.java
    ├── GlobalExceptionHandler.java
    ├── PageResponse.java            # the {items, page, size, totalItems} wrapper
    ├── SlugGenerator.java
    └── exception/
```

```
src/main/resources/
├── application.yml
├── application-dev.yml
├── application-prod.yml
├── db/migration/V1__init.sql
└── db/seed/R__seed_catalog.sql      # dev profile only
```

> **Why `DestinationService` and `DestinationAdminService` are separate classes.** Public reads are cached, filtered to `PUBLISHED`, and never write. Admin operations write, evict, emit events, and see everything. Merging them produces one class where every method has to remember which world it is in — and the day someone forgets, drafts leak onto the public endpoint. Two classes make the boundary structural.

---

## 11. Tech stack

Identical to the Auth Service except for what is genuinely different — PostGIS, and no mail.

| Concern | Choice |
| --- | --- |
| Java | 17 (LTS) — match `auth-service` |
| Framework | Spring Boot 4.1.1 (Spring Framework 7, Hibernate 7) |
| Build | Maven wrapper (`./mvnw`) |
| Security | Spring Security 7 + `spring-boot-starter-oauth2-resource-server` (**verify only**, no signing) |
| Persistence | Spring Data JPA + PostgreSQL 16 + **PostGIS 3.4** |
| Migrations | Flyway (+ `spring-boot-flyway`, see below) |
| Cache | Spring Cache + Spring Data Redis (Lettuce) |
| Messaging | Spring for Apache Kafka — Step 9 |
| Docs | springdoc-openapi |
| Testing | JUnit 5, Mockito, Testcontainers (`postgis/postgis` image), REST Assured |
| Observability | Actuator + Micrometer |
| Port | **8082** (auth is 8081, gateway will take 8080) |

**Boot 4 traps already paid for in the Auth Service** — do not rediscover them:

| Trap | Fix |
| --- | --- |
| Flyway silently never runs; Hibernate then fails `validate` against an empty schema | Add `org.springframework.boot:spring-boot-flyway` **alongside** `flyway-core` |
| `ObjectMapper` not found under `com.fasterxml.jackson.databind` | Jackson 3 — import `tools.jackson.databind.ObjectMapper`; annotations stay at `com.fasterxml.jackson.annotation` |
| `CorsConfigurationSource` — "required a single bean, but 2 were found" | Use `.cors(Customizer.withDefaults())`, do not inject the type |

**Dependencies this service does *not* need:** `spring-boot-starter-mail`, `spring-security-oauth2-jose` (nothing is signed here), and — deliberately — `hibernate-spatial`, thanks to the generated-column decision in §6.

---

## 12. Build plan

Twelve steps. **Each ends in something you can run and see working** — do not move on until the checkpoint passes.

### Step 0 — Infrastructure ✅

Set `POSTGRES_IMAGE=postgis/postgis:16-3.4` in `.env` and recreate the container. **Try it on the existing volume first** — dropping the volume turned out to be unnecessary (see §6). Then create the second database:

```sql
CREATE DATABASE explorelk_destination OWNER explorelk;
CREATE EXTENSION IF NOT EXISTS postgis;   -- in explorelk_destination
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

Added to `.env` / `.env.example`:

```
POSTGRES_IMAGE=postgis/postgis:16-3.4
DESTINATION_DB_URL=jdbc:postgresql://localhost:5433/explorelk_destination
DESTINATION_DB_USERNAME=explorelk
DESTINATION_DB_PASSWORD=explorelk
AUTH_JWKS_URI=http://localhost:8081/.well-known/jwks.json
```

`docker/postgres/init/01-create-databases.sh` is mounted at `/docker-entrypoint-initdb.d`, so a **clean machine** gets `explorelk_destination` and its extensions automatically. It runs only when the data directory is empty, which is why the database above had to be created by hand on this one.

**Checkpoint:** `SELECT postgis_version();` returns `3.4 USE_GEOS=1 USE_PROJ=1 USE_STATS=1` in `explorelk_destination`, and `explorelk_auth` still has its schema and Flyway history after the image swap.

---

### Step 1 — Skeleton ✅

`services/destination-service`: Web, JPA, PostgreSQL Driver, Flyway (all three artifacts), Validation, Actuator, Lombok. Java 17, Spring Boot 4.1.1, port 8082, `ddl-auto: validate`. No security yet. Maven wrapper, `.gitignore` and `.gitattributes` copied from `auth-service`.

**Checkpoint:** app starts, `GET :8082/actuator/health` returns `{"status":"UP"}` with `db: UP`, and `pg_stat_activity` confirms the connection landed on `explorelk_destination`, not the auth database.

> Build with JDK 17, not the JDK 25 that is first on this machine's PATH: `JAVA_HOME="/c/Program Files/Java/jdk-17" ./mvnw clean package`.

---

### Step 2 — Schema ✅

`V1__init.sql`: extensions, `categories` (with the seven-category vocabulary as reference data), `destinations`, `attractions`, both join tables, every index including GiST and trigram, and the `CHECK` constraints. JPA entities to match — **without** mapping `geog`.

**Checkpoint:** Flyway applies V1 (`flyway_schema_history` shows `1 | init | success`), Hibernate `validate` passes at startup. Inserting Ella by hand populates `geog` on its own — `POINT(81.0466 6.8667)`, longitude first — and `ST_Distance` puts Nine Arches Bridge 1.87 km away.

> If `validate` fails, the entity and the SQL disagree. Fix it now — this only gets more painful later.

> **The immutability worry in §6 did not materialise.** `GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude::float8, latitude::float8), 4326)::geography) STORED` is accepted by PostGIS 3.4 without complaint. The trigger fallback stays documented in case a future version tightens this, but it is not needed today.

> **Nullability is looser than §6's table implies, on purpose.** Only `id`, `slug`, `name`, `status`, `version` and the timestamps are `NOT NULL`. A DRAFT is content being written, so an admin must be able to save a half-finished destination; the completeness rules live on the publish transition (`isCompleteForPublishing()`) instead. What the table *does* enforce is that a coordinate is never half-set — `CHECK ((latitude IS NULL) = (longitude IS NULL))`, because a lone latitude is always a bug.

---

### Step 3 — Seed data + public reads ✅

`R__seed_catalog.sql` in the dev-only location: 10 real destinations and 25 attractions, with fixed UUIDs (`d0000000-…`, `a0000000-…`) so ids stay stable across rebuilds and the file can be re-run. Then `GET /destinations`, `GET /destinations/{idOrSlug}`, `GET /categories`, with `PageResponse`, `ApiError`, `ErrorCode` and `GlobalExceptionHandler` carried over from the Auth Service.

**Checkpoint:** `GET /api/v1/destinations` returns real Sri Lankan places with no token. `GET /api/v1/destinations/ella` and the same call with Ella's UUID return **byte-identical** bodies. `GET /api/v1/destinations/atlantis` returns a clean `404 NOT_FOUND` with a `traceId`, not a stack trace.

> **A repeatable migration must be idempotent.** Flyway re-runs `R__` files whenever their checksum changes, so every statement is an upsert against a fixed id. Category tags are delete-then-insert rather than `ON CONFLICT DO NOTHING`, so removing a tag from the file actually removes it on the next run — scoped to the seeded ids, leaving anything an admin created untouched.

> **N+1 avoided by configuration, not by fetch joins.** `categories` is a LAZY many-to-many, so rendering a page of 20 would fire 20 extra queries. A fetch join is the obvious fix and the wrong one — Hibernate cannot paginate a collection join in SQL and falls back to paginating *in memory*. `hibernate.default_batch_fetch_size: 100` instead: verified at exactly **3 statements** for a 10-item page — the page select, the count, and one `destination_id = any (?)` batch for every category collection.

---

### Step 4 — Search, filter, sort ✅

`DestinationSearchSpecs` composes `search` + `category` + `district` + `province` as JPA `Specification`s, always AND-ed with `published()`. `DestinationSort` whitelists the sort names. `size` clamped, `page` floored at 0.

**Checkpoint:** `?search=ell` → Ella; `?search=badulla` → Ella (district matches too); `?category=BEACH` → Galle, Mirissa, Arugam Bay; `?category=BEACH&province=Southern` → Galle, Mirissa; all three filters together → Galle. `?size=1000000` silently becomes 100 and `?size=0` becomes 20.

> **Three details that are easy to skip and expensive later.**
>
> 1. **The sort is a whitelist, not a passthrough.** Spring's `Pageable` resolver will sort by any property named in the query string — on a public endpoint that lets a caller order by an internal column, probe whether it exists, or force an unindexed sort. `?sort=passwordHash` falls back to popularity.
> 2. **Every sort carries `name` as a tiebreak.** Without a total ordering, rows with equal popularity come back in whatever order Postgres chooses, and that differs per page — so an item can appear on both page 1 and page 2, or on neither.
> 3. **`%` and `_` in the search term are escaped.** `?search=%` would otherwise match every row: a one-character request that scans the table.
>
> And an unknown category is a `400` with a field error, not an empty page. `?category=BEECH` returning `[]` reads as "there are no beaches", and the typo survives to production.

---

### Step 5 — Security: the first service to trust the Auth Service

Add the resource server, `SecurityConfig`, the role converter, and the admin CRUD for destinations (`POST`, `PATCH`, `PATCH /status`, `DELETE` → archive). Slug generation, publish-time validation, status transition rules.

**Checkpoint — this is the milestone of the whole service:**

1. `POST /api/v1/admin/destinations` with **no** token → `401`
2. Log in to the **Auth Service** as a TRAVELER, use that token → `403`
3. Log in as the SUPER_ADMIN, use that token → `201`, and the row lands as `DRAFT`
4. The new destination does **not** appear on the public list until you publish it
5. Stop the Auth Service entirely, then reuse the admin token → **still works** (JWKS is cached)

Point 5 is the payoff for RS256 and JWKS. Verify it explicitly.

> This step needs Auth **Step 10** finished, because it needs a real ADMIN account to test with. Do the super-admin bootstrap first.

---

### Step 6 — Attractions

Nested create (`POST /admin/destinations/{id}/attractions`), update, status, archive. Public `GET /destinations/{idOrSlug}/attractions` and `GET /attractions/{id}`. Opening hours as JSONB, fee and `is_free` handling.

**Checkpoint:** Nine Arches Bridge sits under Ella, is returned by the public attraction list once published, and archiving it removes it from that list while `GET /attractions/{id}` still resolves for an admin.

---

### Step 7 — Nearby (PostGIS)

Native queries with `ST_DWithin` + `<->` ordering, exposed as `/destinations/nearby` and `/attractions/nearby`, returning `distanceKm` on each row. Clamp `radiusKm` and `limit`.

**Checkpoint:** `?lat=6.8667&lng=81.0466&radiusKm=10` returns Ella first with `distanceKm` near 0, ordered outward. Then run `EXPLAIN ANALYZE` on the query and confirm it says **Index Scan** using the GiST index, not Seq Scan. Finally swap lat and lng in the request and watch the results become empty — that is the §0 bug, seen once on purpose.

---

### Step 8 — Redis caching

`CacheConfig` with per-cache TTLs, `@Cacheable` on the read services, eviction after commit on every write path, `CacheErrorHandler` that degrades.

**Checkpoint:** enable `logging.level.org.hibernate.SQL: DEBUG`. Request the same destination twice — the second issues **no SQL**. Edit it as an admin, request again — SQL runs and the new value comes back. Stop Redis, request again — still works, just slower.

---

### Step 9 — Outbox + Kafka events

Copy `outbox_events`, `OutboxWriter` and `OutboxPublisher` from `auth-service` as `V2__outbox.sql`. Write event rows in the same transaction as each publish / update / archive. Publish the six event types from §7.

**Checkpoint:** publish a destination, see `DESTINATION_PUBLISHED` in Kafka UI. Stop Kafka, edit an attraction, restart Kafka — the event still arrives. That test is the whole point of the outbox.

> Do this after Auth Step 8, so the pattern is already familiar and there is a working publisher to copy.

---

### Step 10 — Tests

- **Unit (Mockito):** slug generation, status transition rules, publish-completeness validation, cache key building
- **Integration (Testcontainers `postgis/postgis:16-3.4` + Redis):** list/filter/search, slug and UUID lookup, draft invisible publicly, archive removes from list, nearby ordering and radius, optimistic-lock conflict returns `409`
- **Security:** no token → 401, TRAVELER token → 403, ADMIN token → 200. Mint test tokens with a **throwaway RSA keypair** generated in the test and served by a stub JWKS endpoint — do not import the auth service or its keys
- **Kafka:** one test, one event lands on the topic after commit

**Checkpoint:** `mvn verify` green from a clean database.

> The Postgres Testcontainer must be the PostGIS image, or every spatial test fails at `CREATE EXTENSION`. Keep Kafka to a single test — that container costs about 20 s of startup, while Postgres and Redis are cheap.

---

### Step 11 — Package

Multi-stage `Dockerfile`, add `destination-service` to `docker-compose.yml` (depends on postgres + redis, healthcheck on `/actuator/health/readiness`), springdoc at `/swagger-ui.html` in dev, Actuator liveness/readiness, `.env.example` entries, service `README.md`.

**Checkpoint:** `docker compose up` on a clean machine brings up Postgres + Redis + auth + destination, and register → verify → login → admin-create-destination → public-read works end to end across **two** services.

---

### Order rationale

```
Step 0-2    infrastructure & schema        ─┐
Step 3-4    it serves the catalog          ─┤ core — a useful service already
Step 5-6    it is writable and secured     ─┘
Step 7      it answers spatial questions    <- the PostGIS part
Step 8      it is fast
Step 9      it talks to other services      <- needs Auth Step 8 first
Step 10-11  it is verifiable & shippable
```

Steps 3 and 4 come **before** security on purpose. Public reads need no token, so you get a working, demonstrable service without touching Spring Security — and when Step 5 breaks (it will), you already know the data layer is correct and the problem is authentication.

---

## 13. Definition of done

- [ ] A traveler can list, search, filter and open destinations with no token
- [ ] A traveler can see the attractions of a destination, with visit durations
- [ ] `/nearby` returns correct distances in the right order, using the GiST index
- [ ] An ADMIN token from the Auth Service can create, edit, publish and archive; a TRAVELER token gets 403
- [ ] The Destination Service verifies tokens with **no** runtime dependency on the Auth Service being up
- [ ] Drafts and archived content are never visible on a public endpoint
- [ ] Archiving never deletes a row — every id stays resolvable for other services
- [ ] Repeated reads are served from Redis, and a write invalidates them
- [ ] Redis down = slower, not broken
- [ ] All six events reach Kafka, and survive Kafka being restarted
- [ ] `mvn verify` passes from a clean database
- [ ] `docker compose up` works on a clean machine
- [ ] Seed data is real Sri Lankan content, good enough for Trip and Itinerary development

---

## 14. Deferred past MVP

Deliberately out of scope, listed so they are not forgotten:

- Full-text search with `tsvector`, ranking and stemming
- Image upload and storage (S3/MinIO) — MVP stores URLs only
- Reviews and ratings — likely their own service, not this one
- Multi-language content (Sinhala / Tamil)
- Derived popularity — computed from how often a destination appears in real itineraries, which needs the Trip Service to exist and emit events
- Routes and travel times between destinations — arguably belongs to the Itinerary Service
- Admin bulk import (CSV / GeoJSON)
- Opening-hours modelling beyond simple JSONB: seasons, public holidays, last-entry times
