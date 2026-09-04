package com.explorelk.destination.attraction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <p><strong>Public visibility is pinned in the queries, not passed in.</strong>
 * The finders a public endpoint may call have {@code PUBLISHED} written into the
 * JPQL rather than taken as a parameter, so there is no call site that could
 * pass {@code DRAFT} by mistake. The inherited {@code findById} and the
 * {@code findAllOf...} finders are the admin side, which is supposed to see
 * everything.
 *
 * <p>Every public query also requires the <em>destination</em> to be published.
 * An attraction is not independently browsable content — it exists in the
 * context of a place — so archiving Ella has to take Nine Arches Bridge off the
 * public API with it. Without that rule, archived content stays reachable
 * through a nested id and nobody notices for months.
 */
public interface AttractionRepository extends JpaRepository<Attraction, UUID> {

    /**
     * The public attraction list of one destination, best first.
     *
     * <p>The tiebreak on name matters for the same reason it does on the
     * destination list: without a total ordering, equal popularity scores come
     * back in whatever order Postgres chooses, and the order changes between
     * calls.
     */
    @Query("""
            SELECT a FROM Attraction a
            WHERE a.destination.id = :destinationId
              AND a.status = com.explorelk.destination.destination.ContentStatus.PUBLISHED
              AND a.destination.status = com.explorelk.destination.destination.ContentStatus.PUBLISHED
            ORDER BY a.popularityScore DESC, a.name ASC
            """)
    List<Attraction> findPublishedOfDestination(@Param("destinationId") UUID destinationId);

    /** One published attraction, by id. Slugs are unique only within a destination. */
    @Query("""
            SELECT a FROM Attraction a
            WHERE a.id = :id
              AND a.status = com.explorelk.destination.destination.ContentStatus.PUBLISHED
              AND a.destination.status = com.explorelk.destination.destination.ContentStatus.PUBLISHED
            """)
    Optional<Attraction> findPublishedById(@Param("id") UUID id);

    /** The admin list for one destination: drafts and archived rows included. */
    List<Attraction> findByDestinationIdOrderByPopularityScoreDescNameAsc(UUID destinationId);

    /**
     * Slug uniqueness is per destination, matching
     * {@code ux_attractions_destination_slug}. Two destinations may each have a
     * {@code main-beach}.
     */
    boolean existsByDestinationIdAndSlug(UUID destinationId, String slug);

    /**
     * Published attractions within {@code radiusMeters} of a point, nearest first.
     *
     * <p>The same shape as {@code DestinationRepository.findNearby}, and the same
     * three constraints apply: {@code ST_DWithin} so the GiST index can answer
     * it, the origin expression written out rather than lifted into a CTE so the
     * planner still sees a constant, and double-quoted aliases so Postgres does
     * not fold them to lower case before the projection binds them.
     *
     * <p>Joining {@code destinations} is not only for the label — it is what
     * keeps attractions of an unpublished place off the public map.
     */
    @Query(value = """
            SELECT a.id                     AS "id",
                   a.slug                   AS "slug",
                   a.name                   AS "name",
                   a.summary                AS "summary",
                   a.latitude               AS "latitude",
                   a.longitude              AS "longitude",
                   a.visit_duration_minutes AS "visitDurationMinutes",
                   a.is_free                AS "free",
                   a.entrance_fee           AS "entranceFee",
                   a.currency               AS "currency",
                   a.image_url              AS "imageUrl",
                   a.popularity_score       AS "popularityScore",
                   d.id                     AS "destinationId",
                   d.slug                   AS "destinationSlug",
                   d.name                   AS "destinationName",
                   ST_Distance(
                       a.geog,
                       CAST(ST_SetSRID(ST_MakePoint(CAST(:lng AS double precision),
                                                    CAST(:lat AS double precision)), 4326) AS geography)
                   ) / 1000.0               AS "distanceKm"
            FROM attractions a
            JOIN destinations d ON d.id = a.destination_id
            WHERE a.status = 'PUBLISHED'
              AND d.status = 'PUBLISHED'
              AND a.geog IS NOT NULL
              AND ST_DWithin(
                      a.geog,
                      CAST(ST_SetSRID(ST_MakePoint(CAST(:lng AS double precision),
                                                   CAST(:lat AS double precision)), 4326) AS geography),
                      CAST(:radiusMeters AS double precision))
            ORDER BY a.geog <-> CAST(ST_SetSRID(ST_MakePoint(CAST(:lng AS double precision),
                                                             CAST(:lat AS double precision)), 4326) AS geography)
            LIMIT :maxResults
            """, nativeQuery = true)
    List<NearbyAttractionProjection> findNearby(@Param("lat") double latitude,
                                                @Param("lng") double longitude,
                                                @Param("radiusMeters") double radiusMeters,
                                                @Param("maxResults") int maxResults);
}
