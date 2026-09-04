package com.explorelk.destination.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One entry in the shared category vocabulary — {@code NATURE}, {@code BEACH},
 * {@code HIKING} and so on. Tagged onto both destinations and attractions.
 *
 * <p>The primary key is the {@code code}, not a UUID, deliberately breaking the
 * convention used everywhere else in the platform. The code <em>is</em> the public
 * API contract ({@code ?category=BEACH}), the table is a small fixed vocabulary
 * that is never bulk-created, and a natural key means filtering content by
 * category never has to join back to this table.
 */
@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "code")
@ToString(of = {"code", "name"})
public class Category {

    /** Upper snake case: {@code NATURE}, {@code TEA_COUNTRY}. Enforced by a CHECK. */
    @Id
    @Column(name = "code", nullable = false, length = 24)
    private String code;

    /** Human-readable label. Frontends show this, never the code. */
    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "description", length = 200)
    private String description;

    /** Icon hint for the frontend — a name, not a URL. */
    @Column(name = "icon", length = 40)
    private String icon;

    /** Display order in filter UIs. Lower comes first. */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private short sortOrder = 0;
}
