package com.explorelk.destination.destination;

import com.explorelk.destination.category.Category;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A place a traveler goes TO and stays near — Ella, Kandy, Sigiriya.
 *
 * <p>Almost every field is nullable, which is deliberate: a {@link ContentStatus#DRAFT}
 * is content still being written, and an admin has to be able to save a
 * half-finished destination. The completeness rules — district, province,
 * coordinates and at least one category — are enforced on the transition to
 * {@link ContentStatus#PUBLISHED}, not by the table.
 *
 * <p><strong>There is no {@code geog} field here on purpose.</strong> The PostGIS
 * {@code geography(Point,4326)} column is {@code GENERATED ALWAYS} by the database
 * from {@link #latitude} and {@link #longitude}. Leaving it unmapped is what lets
 * this service skip hibernate-spatial, JTS types and a spatial dialect entirely;
 * {@code ddl-auto=validate} ignores columns no entity claims. Proximity search is
 * a single native query in the repository.
 */
@Entity
@Table(name = "destinations")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(of = {"id", "slug", "name", "status"})
public class Destination {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Lowercase and hyphenated — {@code ella}, {@code nuwara-eliya}. Generated
     * server-side from the name and never accepted raw from a client. Public URLs
     * resolve on it, so treat it as immutable once published.
     */
    @Column(name = "slug", nullable = false, length = 80)
    private String slug;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "district", length = 60)
    private String district;

    @Column(name = "province", length = 40)
    private String province;

    /** One line, for list cards. */
    @Column(name = "summary", length = 300)
    private String summary;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /**
     * NUMERIC(9,6), mapped to BigDecimal rather than double: six decimals is about
     * 11 cm of resolution and the value round-trips exactly.
     */
    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    /** How many days to spend here. Feeds trip planning. */
    @Column(name = "recommended_days")
    private Short recommendedDays;

    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    /** Admin-set for the MVP; derived from real itinerary usage later. */
    @Column(name = "popularity_score", nullable = false)
    @Builder.Default
    private int popularityScore = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private ContentStatus status = ContentStatus.DRAFT;

    /**
     * Optimistic locking. Two admins editing the same destination is not
     * hypothetical once there is a CMS screen, and the alternative is a silent
     * last-write-wins. A clash surfaces as {@code CONFLICT} (409), not corruption.
     */
    @Version
    @Column(name = "version", nullable = false)
    private int version;

    /**
     * Ella is NATURE and HIKING and ADVENTURE at once, so this is a real
     * many-to-many — unlike the single {@code role} column in auth-service.
     * LAZY: list endpoints that do not render tags should not pay for the join.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "destination_categories",
            joinColumns = @JoinColumn(name = "destination_id"),
            inverseJoinColumns = @JoinColumn(name = "category_code")
    )
    @Builder.Default
    private Set<Category> categories = new LinkedHashSet<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── Behaviour ────────────────────────────────────────────────────────────

    /** True when a public endpoint is allowed to return this row. */
    public boolean isPubliclyVisible() {
        return status != null && status.isPubliclyVisible();
    }

    /** Everything the traveler-facing UI needs before this may be published. */
    public boolean isCompleteForPublishing() {
        return name != null && !name.isBlank()
                && district != null && !district.isBlank()
                && province != null && !province.isBlank()
                && summary != null && !summary.isBlank()
                && latitude != null && longitude != null
                && !categories.isEmpty();
    }

    public void addCategory(Category category) {
        categories.add(category);
    }

    public void removeCategory(Category category) {
        categories.remove(category);
    }
}
