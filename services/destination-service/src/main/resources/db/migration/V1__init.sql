-- ════════════════════════════════════════════════════════════════════════════
-- Destination Service — initial schema
--
-- The catalog of where a traveler can go and what there is to see there.
-- Owns: destinations, attractions, the category vocabulary.
-- Owns nothing about trips, itineraries, experiences, bookings or users.
--
-- Conventions (same as auth-service):
--   * UUID primary keys, gen_random_uuid() — built in since PG 13, no pgcrypto
--   * enums as VARCHAR + CHECK, not a native ENUM type: adding a value later is
--     a one-line migration instead of ALTER TYPE, and it maps straight onto
--     @Enumerated(EnumType.STRING)
--   * TIMESTAMPTZ everywhere, never bare TIMESTAMP
--   * snake_case, unquoted
-- ════════════════════════════════════════════════════════════════════════════

-- postgis  — geography type + GiST index, for "what is near here"
-- pg_trgm  — trigram index, so a leading-wildcard ILIKE search can use an index
--            instead of scanning the table
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;


-- ────────────────────────────────────────────────────────────────────────────
-- categories
--
-- A small, shared, slow-moving vocabulary. `code` is the primary key rather
-- than a UUID, deliberately breaking the convention above: the code IS the
-- public API contract (?category=BEACH), the table is never bulk-created, and
-- a natural key means the join tables below are readable on their own and
-- filtering by category never has to join back to here.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE categories
(
    code        VARCHAR(24) PRIMARY KEY,
    name        VARCHAR(60)  NOT NULL,
    description VARCHAR(200),
    icon        VARCHAR(40),
    sort_order  SMALLINT     NOT NULL DEFAULT 0,

    CONSTRAINT ck_categories_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);

-- The starting vocabulary. This is reference data, not seed/test data — the
-- foreign keys below depend on it and production needs it too, so it belongs in
-- the migration rather than in db/seed. Admins can add more through the API.
INSERT INTO categories (code, name, description, icon, sort_order) VALUES
    ('NATURE',    'Nature',    'Landscapes, forests, waterfalls and scenery',      'leaf',      10),
    ('BEACH',     'Beach',     'Coastline, swimming, surfing and diving',          'waves',     20),
    ('WILDLIFE',  'Wildlife',  'Safaris, national parks, whale and bird watching', 'paw',       30),
    ('HIKING',    'Hiking',    'Trails, peaks and walking routes',                 'mountain',  40),
    ('HISTORY',   'History',   'Ancient sites, forts, ruins and monuments',        'landmark',  50),
    ('CULTURE',   'Culture',   'Temples, festivals, crafts and local life',        'temple',    60),
    ('ADVENTURE', 'Adventure', 'Rafting, climbing, zip-lining and the like',       'compass',   70);


-- ────────────────────────────────────────────────────────────────────────────
-- destinations
--
-- A place you travel TO and stay near — Ella, Kandy, Sigiriya.
--
-- Only id / slug / name / status / version / timestamps are NOT NULL. Everything
-- else is nullable on purpose: a DRAFT is content being written, and an admin
-- must be able to save a half-finished destination. The completeness rules
-- (district, province, coordinates, at least one category) are enforced by the
-- service on the DRAFT -> PUBLISHED transition, not by the table.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE destinations
(
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Lowercase, hyphenated, generated server-side from the name: 'nuwara-eliya'.
    -- Public URLs use it, so it is unique and immutable in practice.
    slug             VARCHAR(80)  NOT NULL,
    name             VARCHAR(120) NOT NULL,

    district         VARCHAR(60),
    province         VARCHAR(40),

    summary          VARCHAR(300),
    description      TEXT,

    -- NUMERIC, not DOUBLE PRECISION: 6 decimal places is ~11 cm of resolution,
    -- and it round-trips exactly to BigDecimal, so the coordinate you save is the
    -- coordinate you read back.
    latitude         NUMERIC(9,6),
    longitude        NUMERIC(9,6),

    recommended_days SMALLINT,
    cover_image_url  VARCHAR(500),
    popularity_score INTEGER      NOT NULL DEFAULT 0,

    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',

    -- JPA @Version. Two admins editing Ella at once stops being hypothetical the
    -- moment there is a CMS screen; without this the loser wins silently.
    version          INTEGER      NOT NULL DEFAULT 0,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_destinations_status    CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_destinations_slug      CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_destinations_lat       CHECK (latitude  IS NULL OR latitude  BETWEEN -90  AND 90),
    CONSTRAINT ck_destinations_lng       CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    -- A half-set coordinate is always a bug. Reject it here rather than putting
    -- the place in the ocean.
    CONSTRAINT ck_destinations_latlng    CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT ck_destinations_days      CHECK (recommended_days IS NULL OR recommended_days > 0),
    CONSTRAINT ck_destinations_popularity CHECK (popularity_score >= 0)
);

-- The spatial column is DERIVED, never written by the application. The entity
-- maps latitude/longitude only, which is why this service needs no
-- hibernate-spatial, no JTS types and no spatial dialect: ddl-auto=validate only
-- checks that mapped columns exist, and is happy to ignore this one.
--
-- ST_MakePoint takes X then Y — longitude BEFORE latitude. Getting that backwards
-- is the classic PostGIS bug and it fails silently, not loudly.
ALTER TABLE destinations
    ADD COLUMN geog geography(Point, 4326)
    GENERATED ALWAYS AS (
        ST_SetSRID(ST_MakePoint(longitude::float8, latitude::float8), 4326)::geography
    ) STORED;

CREATE UNIQUE INDEX ux_destinations_slug     ON destinations (slug);
CREATE        INDEX ix_destinations_district ON destinations (district);
CREATE        INDEX ix_destinations_province ON destinations (province);

-- Serves the default public listing: published, most popular first.
CREATE INDEX ix_destinations_status_popularity ON destinations (status, popularity_score DESC);

-- Makes ILIKE '%ella%' indexable. Without pg_trgm a leading wildcard defeats
-- every btree index and the query degrades to a sequential scan.
CREATE INDEX ix_destinations_name_trgm ON destinations USING GIN (name gin_trgm_ops);

-- ST_DWithin and the <-> nearest-neighbour operator both use this.
CREATE INDEX ix_destinations_geog ON destinations USING GIST (geog);


-- ────────────────────────────────────────────────────────────────────────────
-- attractions
--
-- A specific thing you DO or SEE, belonging to exactly one destination.
-- visit_duration_minutes is not decoration: it is the input the Itinerary
-- Service packs into days.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE attractions
(
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- RESTRICT, not CASCADE. Destinations are archived, never deleted, so a
    -- cascade here could only ever fire by accident. Let the database refuse.
    destination_id         UUID         NOT NULL REFERENCES destinations (id) ON DELETE RESTRICT,

    -- Unique within its destination, not globally: two destinations may both
    -- have a 'main-beach'.
    slug                   VARCHAR(80)  NOT NULL,
    name                   VARCHAR(120) NOT NULL,

    summary                VARCHAR(300),
    description            TEXT,

    latitude               NUMERIC(9,6),
    longitude              NUMERIC(9,6),

    visit_duration_minutes SMALLINT,

    -- is_free is separate from entrance_fee because a NULL fee is genuinely
    -- ambiguous: "we don't know yet" and "it costs nothing" are different facts
    -- and the UI shows them differently.
    is_free                BOOLEAN      NOT NULL DEFAULT false,
    entrance_fee           NUMERIC(10,2),
    currency               VARCHAR(3)   NOT NULL DEFAULT 'LKR',

    always_open            BOOLEAN      NOT NULL DEFAULT false,
    -- {"mon": ["06:00","18:00"], "tue": [...], ...}. JSONB, so it is validated on
    -- insert and can be indexed later if it ever needs querying.
    opening_hours          JSONB,

    image_url              VARCHAR(500),
    popularity_score       INTEGER      NOT NULL DEFAULT 0,

    status                 VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',

    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_attractions_status     CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_attractions_slug       CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_attractions_lat        CHECK (latitude  IS NULL OR latitude  BETWEEN -90  AND 90),
    CONSTRAINT ck_attractions_lng        CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_attractions_latlng     CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT ck_attractions_duration   CHECK (visit_duration_minutes IS NULL OR visit_duration_minutes > 0),
    CONSTRAINT ck_attractions_fee        CHECK (entrance_fee IS NULL OR entrance_fee >= 0),
    CONSTRAINT ck_attractions_free_fee   CHECK (NOT (is_free AND entrance_fee IS NOT NULL AND entrance_fee > 0)),
    CONSTRAINT ck_attractions_currency   CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_attractions_popularity CHECK (popularity_score >= 0)
);

ALTER TABLE attractions
    ADD COLUMN geog geography(Point, 4326)
    GENERATED ALWAYS AS (
        ST_SetSRID(ST_MakePoint(longitude::float8, latitude::float8), 4326)::geography
    ) STORED;

CREATE UNIQUE INDEX ux_attractions_destination_slug ON attractions (destination_id, slug);
CREATE        INDEX ix_attractions_destination      ON attractions (destination_id, status);
CREATE        INDEX ix_attractions_name_trgm        ON attractions USING GIN (name gin_trgm_ops);
CREATE        INDEX ix_attractions_geog             ON attractions USING GIST (geog);


-- ────────────────────────────────────────────────────────────────────────────
-- destination_categories / attraction_categories
--
-- Many-to-many, unlike the single `role` column in auth-service: Ella genuinely
-- is NATURE and HIKING and ADVENTURE at the same time, and "show me all BEACH
-- destinations" is the primary traveler query.
--
-- CASCADE from the owning row here is correct — a tag has no meaning without the
-- thing it tags. RESTRICT from categories: removing a category from the
-- vocabulary while content still uses it should fail loudly.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE destination_categories
(
    destination_id UUID        NOT NULL REFERENCES destinations (id) ON DELETE CASCADE,
    category_code  VARCHAR(24) NOT NULL REFERENCES categories (code) ON DELETE RESTRICT,

    PRIMARY KEY (destination_id, category_code)
);

-- The PK covers destination -> categories. This one covers the other direction,
-- which is what ?category=BEACH needs.
CREATE INDEX ix_destination_categories_category ON destination_categories (category_code);

CREATE TABLE attraction_categories
(
    attraction_id UUID        NOT NULL REFERENCES attractions (id) ON DELETE CASCADE,
    category_code VARCHAR(24) NOT NULL REFERENCES categories (code) ON DELETE RESTRICT,

    PRIMARY KEY (attraction_id, category_code)
);

CREATE INDEX ix_attraction_categories_category ON attraction_categories (category_code);
