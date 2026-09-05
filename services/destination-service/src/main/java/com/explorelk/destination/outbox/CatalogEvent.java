package com.explorelk.destination.outbox;

import com.explorelk.destination.attraction.Attraction;
import com.explorelk.destination.category.Category;
import com.explorelk.destination.destination.Destination;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The JSON envelope that reaches Kafka.
 *
 * <p><strong>The payload is deliberately fat enough to act on.</strong> A
 * consumer that receives {@code ATTRACTION_UPDATED} and then has to call back to
 * this service to discover what changed has gained nothing from being told — and
 * has added a synchronous dependency on the catalog to its own write path, which
 * is precisely what events are meant to remove. So the fields another service
 * would immediately fetch travel with the event.
 *
 * <p>It is equally deliberate about what it leaves out. There is no
 * {@code description}, no {@code openingHours}, no long body text: those are
 * read by people looking at a page, never by a service making a decision, and
 * putting them here would push messages toward the broker's size limit for no
 * gain.
 *
 * @param eventId    matches the outbox row id. Delivery is at-least-once, so
 *                   this is what consumers dedupe on
 * @param eventType  one of {@link CatalogEventType}
 * @param occurredAt when the change committed here — not when it was published,
 *                   which can be much later if the broker was down
 */
public record CatalogEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        DestinationPayload destination,
        AttractionPayload attraction
) {

    /**
     * @param recommendedDays  a trip-length input for the Trip Service
     * @param status           always included: {@code ARCHIVED} is the event that
     *                         matters most, because a plan pointing at archived
     *                         content needs flagging
     */
    public record DestinationPayload(
            UUID id,
            String slug,
            String name,
            String district,
            String province,
            String summary,
            BigDecimal latitude,
            BigDecimal longitude,
            Short recommendedDays,
            int popularityScore,
            String status,
            List<String> categories
    ) {
        public static DestinationPayload from(Destination destination) {
            return new DestinationPayload(
                    destination.getId(),
                    destination.getSlug(),
                    destination.getName(),
                    destination.getDistrict(),
                    destination.getProvince(),
                    destination.getSummary(),
                    destination.getLatitude(),
                    destination.getLongitude(),
                    destination.getRecommendedDays(),
                    destination.getPopularityScore(),
                    destination.getStatus().name(),
                    destination.getCategories().stream()
                            .sorted(Comparator.comparing(Category::getSortOrder))
                            .map(Category::getCode)
                            .toList());
        }
    }

    /**
     * @param visitDurationMinutes the single most consequential field in this
     *                             whole envelope. The Itinerary Service packs
     *                             days with it, so a change here invalidates
     *                             every plan containing this attraction
     */
    public record AttractionPayload(
            UUID id,
            UUID destinationId,
            String destinationSlug,
            String slug,
            String name,
            String summary,
            BigDecimal latitude,
            BigDecimal longitude,
            Short visitDurationMinutes,
            boolean free,
            BigDecimal entranceFee,
            String currency,
            int popularityScore,
            String status,
            List<String> categories
    ) {
        public static AttractionPayload from(Attraction attraction) {
            return new AttractionPayload(
                    attraction.getId(),
                    attraction.getDestination().getId(),
                    attraction.getDestination().getSlug(),
                    attraction.getSlug(),
                    attraction.getName(),
                    attraction.getSummary(),
                    attraction.getLatitude(),
                    attraction.getLongitude(),
                    attraction.getVisitDurationMinutes(),
                    attraction.isFree(),
                    attraction.getEntranceFee(),
                    attraction.getCurrency(),
                    attraction.getPopularityScore(),
                    attraction.getStatus().name(),
                    attraction.getCategories().stream()
                            .sorted(Comparator.comparing(Category::getSortOrder))
                            .map(Category::getCode)
                            .toList());
        }
    }

    public static CatalogEvent of(CatalogEventType type, Destination destination) {
        return new CatalogEvent(null, type.name(), Instant.now(),
                DestinationPayload.from(destination), null);
    }

    public static CatalogEvent of(CatalogEventType type, Attraction attraction) {
        return new CatalogEvent(null, type.name(), Instant.now(),
                null, AttractionPayload.from(attraction));
    }

    /**
     * The outbox assigns the id when the row is written, so it is stamped in
     * afterwards rather than guessed at construction time. Keeping the event id
     * and the row id identical is what lets an operator trace a message in Kafka
     * UI straight back to a row in {@code outbox_events}.
     */
    public CatalogEvent withEventId(UUID id) {
        return new CatalogEvent(id, eventType, occurredAt, destination, attraction);
    }
}
