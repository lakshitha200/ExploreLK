-- ════════════════════════════════════════════════════════════════════════════
-- Search indexes, corrected.
--
-- V1 put the trigram indexes on the raw columns:
--
--     CREATE INDEX ... USING GIN (name gin_trgm_ops);
--
-- That index answers `name ILIKE '%ella%'`. But the search is built with the JPA
-- Criteria API, which has no ILIKE — it emits case-insensitive matching as
--
--     lower(name) LIKE '%ella%'
--
-- and Postgres will not use an index on `name` to answer a predicate on
-- `lower(name)`. The expression has to match. So: same indexes, on lower().
--
-- The alternative was to keep these indexes and write the search as a native
-- query using ILIKE. Expression indexes are the smaller change and they keep the
-- query in Criteria, where the filters compose (see DestinationSearchSpecs).
-- ════════════════════════════════════════════════════════════════════════════

DROP INDEX IF EXISTS ix_destinations_name_trgm;
DROP INDEX IF EXISTS ix_attractions_name_trgm;

CREATE INDEX ix_destinations_name_lower_trgm
    ON destinations USING GIN (lower(name) gin_trgm_ops);

-- District is searched in the same OR as the name ("ella", "badulla"), so it
-- needs the same treatment. The plain btree ix_destinations_district stays — it
-- serves the exact ?district=Badulla filter, which is a different query.
CREATE INDEX ix_destinations_district_lower_trgm
    ON destinations USING GIN (lower(district) gin_trgm_ops);

CREATE INDEX ix_attractions_name_lower_trgm
    ON attractions USING GIN (lower(name) gin_trgm_ops);
