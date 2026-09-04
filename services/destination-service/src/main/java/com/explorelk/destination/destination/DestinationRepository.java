package com.explorelk.destination.destination;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <p><strong>Public reads are status-qualified here, not in the controller.</strong>
 * Every finder a public endpoint may use takes a {@link ContentStatus} or pins it
 * in SQL, so the filter cannot be forgotten when someone adds an endpoint later.
 * The unqualified {@code findById} inherited from {@link JpaRepository} and
 * {@link #findBySlug} exist for the admin side, which is supposed to see drafts.
 *
 * <p>{@link JpaSpecificationExecutor} backs the composable search — see
 * {@link com.explorelk.destination.search.DestinationSearchSpecs}.
 */
public interface DestinationRepository
        extends JpaRepository<Destination, UUID>, JpaSpecificationExecutor<Destination> {

    Optional<Destination> findByIdAndStatus(UUID id, ContentStatus status);

    Optional<Destination> findBySlugAndStatus(String slug, ContentStatus status);

    /** Admin lookup: any status. */
    Optional<Destination> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * Published destinations within {@code radiusMeters} of a point, nearest first.
     *
     * <p>This is the one query in the service that touches PostGIS, and the only
     * reason the {@code geog} column exists. {@code ST_DWithin} is what makes it
     * index-friendly: it is rewritten by the planner into a bounding-box search
     * the GiST index can answer, and only the survivors get an exact distance.
     * Computing {@code ST_Distance} in a {@code WHERE} clause instead would work
     * and would scan every row in the table.
     *
     * <p><strong>Why the origin expression is written out three times instead of
     * being lifted into a CTE.</strong> A CTE referenced more than once is not
     * inlined by Postgres — it is materialised as a separate node, and the
     * {@code ST_DWithin} argument then stops being a constant the planner can
     * push into the index. The result is a correct answer delivered by a
     * sequential scan. Repetition here buys an index scan.
     *
     * <p>Aliases are double-quoted so Postgres preserves their case exactly.
     * Unquoted identifiers come back folded to lower case, and the projection
     * below is matched by property name — {@code distancekm} would not bind to
     * {@code getDistanceKm()}.
     *
     * <p>The parameters are explicitly cast: an untyped bind parameter inside
     * {@code ST_MakePoint} leaves Postgres unable to infer a type and the query
     * fails at prepare time, not at development time.
     */
    @Query(value = """
            SELECT d.id                AS "id",
                   d.slug              AS "slug",
                   d.name              AS "name",
                   d.district          AS "district",
                   d.province          AS "province",
                   d.summary           AS "summary",
                   d.latitude          AS "latitude",
                   d.longitude         AS "longitude",
                   d.recommended_days  AS "recommendedDays",
                   d.cover_image_url   AS "coverImageUrl",
                   d.popularity_score  AS "popularityScore",
                   ST_Distance(
                       d.geog,
                       CAST(ST_SetSRID(ST_MakePoint(CAST(:lng AS double precision),
                                                    CAST(:lat AS double precision)), 4326) AS geography)
                   ) / 1000.0          AS "distanceKm"
            FROM destinations d
            WHERE d.status = 'PUBLISHED'
              AND d.geog IS NOT NULL
              AND ST_DWithin(
                      d.geog,
                      CAST(ST_SetSRID(ST_MakePoint(CAST(:lng AS double precision),
                                                   CAST(:lat AS double precision)), 4326) AS geography),
                      CAST(:radiusMeters AS double precision))
            ORDER BY d.geog <-> CAST(ST_SetSRID(ST_MakePoint(CAST(:lng AS double precision),
                                                             CAST(:lat AS double precision)), 4326) AS geography)
            LIMIT :maxResults
            """, nativeQuery = true)
    List<NearbyDestinationProjection> findNearby(@Param("lat") double latitude,
                                                 @Param("lng") double longitude,
                                                 @Param("radiusMeters") double radiusMeters,
                                                 @Param("maxResults") int maxResults);
}
