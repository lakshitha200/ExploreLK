package com.explorelk.destination.outbox;

/**
 * The six events this service publishes, and nothing else.
 *
 * <p>An enum rather than string literals at the call sites: these names are a
 * contract other services subscribe to, a typo in one would compile fine and
 * silently deliver an event nobody is listening for, and the
 * {@code ck_outbox_event_type} constraint would then reject the row at flush
 * time — failing the write that the event was only meant to describe.
 *
 * <table>
 *   <caption>Who cares about what</caption>
 *   <tr><th>Event</th><th>Emitted when</th><th>Consumed by</th></tr>
 *   <tr><td>{@code DESTINATION_PUBLISHED}</td><td>status becomes PUBLISHED</td>
 *       <td>cache warmers, analytics</td></tr>
 *   <tr><td>{@code DESTINATION_UPDATED}</td><td>a published destination is edited</td>
 *       <td>Itinerary — refresh cached copies</td></tr>
 *   <tr><td>{@code DESTINATION_ARCHIVED}</td><td>status becomes ARCHIVED</td>
 *       <td>Trip, Itinerary — flag affected plans</td></tr>
 *   <tr><td>{@code ATTRACTION_PUBLISHED}</td><td>status becomes PUBLISHED</td>
 *       <td>Experience — link experiences</td></tr>
 *   <tr><td>{@code ATTRACTION_UPDATED}</td><td>a published attraction is edited</td>
 *       <td>Itinerary — <strong>duration changes break plans</strong></td></tr>
 *   <tr><td>{@code ATTRACTION_ARCHIVED}</td><td>status becomes ARCHIVED</td>
 *       <td>Itinerary</td></tr>
 * </table>
 *
 * <p>{@code ATTRACTION_UPDATED} is the one that matters most in practice. An
 * itinerary built around a 90-minute visit is quietly wrong the moment that
 * becomes 180, and there is no way for the Itinerary Service to notice on its
 * own.
 *
 * <p><strong>Only changes to published content are announced.</strong> Editing a
 * draft emits nothing — no consumer has ever seen the content, so there is
 * nothing to correct, and a stream of events for work in progress is noise that
 * teaches consumers to ignore the stream.
 */
public enum CatalogEventType {

    DESTINATION_PUBLISHED(AggregateType.DESTINATION),
    DESTINATION_UPDATED(AggregateType.DESTINATION),
    DESTINATION_ARCHIVED(AggregateType.DESTINATION),

    ATTRACTION_PUBLISHED(AggregateType.ATTRACTION),
    ATTRACTION_UPDATED(AggregateType.ATTRACTION),
    ATTRACTION_ARCHIVED(AggregateType.ATTRACTION);

    /** Matches the {@code ck_outbox_aggregate_type} constraint. */
    public static final class AggregateType {
        public static final String DESTINATION = "DESTINATION";
        public static final String ATTRACTION = "ATTRACTION";

        private AggregateType() {
        }
    }

    private final String aggregateType;

    CatalogEventType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    /** Derived from the event rather than passed in, so the two cannot disagree. */
    public String aggregateType() {
        return aggregateType;
    }
}
