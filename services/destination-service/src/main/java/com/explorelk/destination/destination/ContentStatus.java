package com.explorelk.destination.destination;

/**
 * Publication state of a piece of catalog content. Shared by destinations and
 * attractions.
 *
 * <pre>
 *   create -> DRAFT ──publish──► PUBLISHED ──archive──► ARCHIVED
 *               ▲                    │                      │
 *               └──── unpublish ─────┘                      │
 *               └──────────────── restore ──────────────────┘
 * </pre>
 *
 * <p>There is no DELETED state and no row deletion. Trip and Itinerary services
 * store destination ids in <em>their own</em> databases, where no foreign key can
 * protect them — a hard delete would turn every one of those references into a
 * silent dangling pointer. Archiving keeps the id resolvable forever.
 */
public enum ContentStatus {

    /** Being written. Incomplete data is fine; never visible publicly. */
    DRAFT,

    /** Live in the catalog. The only status a public endpoint may return. */
    PUBLISHED,

    /** Retired. Invisible publicly, but the row and its id survive. */
    ARCHIVED;

    /** Only PUBLISHED content may be returned by a public endpoint. */
    public boolean isPubliclyVisible() {
        return this == PUBLISHED;
    }

    /** Legal transitions, checked by the admin service before any status change. */
    public boolean canTransitionTo(ContentStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case DRAFT -> target == PUBLISHED || target == ARCHIVED;
            case PUBLISHED -> target == DRAFT || target == ARCHIVED;
            case ARCHIVED -> target == DRAFT;   // restore, then publish again
        };
    }
}
