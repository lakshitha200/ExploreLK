package com.explorelk.destination.search;

import com.explorelk.destination.category.Category;
import com.explorelk.destination.destination.ContentStatus;
import com.explorelk.destination.destination.Destination;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/**
 * The composable pieces of the destination search.
 *
 * <p>Specifications rather than a pile of {@code findByAOrBAndC} repository
 * methods: four optional filters is sixteen combinations, and Spring Data method
 * names do not scale past about two. Each predicate below is independent, and
 * {@link #publicSearch} ANDs whichever ones are actually present.
 */
public final class DestinationSearchSpecs {

    private DestinationSearchSpecs() {
    }

    /**
     * The one filter that is never optional on a public endpoint.
     *
     * <p>Kept as its own Specification and applied inside {@link #publicSearch} so
     * a caller cannot build a public query that forgets it.
     */
    public static Specification<Destination> published() {
        return (root, query, cb) -> cb.equal(root.get("status"), ContentStatus.PUBLISHED);
    }

    /**
     * Free-text match on name or district.
     *
     * <p>Emitted as {@code lower(name) LIKE '%term%'}. The leading wildcard defeats
     * a btree index, which is why V2 puts GIN trigram indexes on {@code lower(name)}
     * and {@code lower(district)} — the expression has to match the index exactly
     * or Postgres falls back to a sequential scan.
     *
     * <p>{@code %} and {@code _} in the input are escaped: without that, a search
     * for {@code %} matches every row, which is a cheap way to make the database
     * work hard.
     */
    public static Specification<Destination> matchesText(String term) {
        String pattern = "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, '\\'),
                cb.like(cb.lower(root.get("district")), pattern, '\\'));
    }

    /**
     * Tagged with a given category.
     *
     * <p>An inner join onto the join table. No {@code distinct} is needed: the
     * primary key of {@code destination_categories} is (destination_id,
     * category_code), so a destination can match a single code at most once.
     */
    public static Specification<Destination> hasCategory(String categoryCode) {
        return (root, query, cb) -> {
            Join<Destination, Category> categories = root.join("categories");
            return cb.equal(categories.get("code"), categoryCode);
        };
    }

    /** Exact district match, case-insensitive — served by ix_destinations_district. */
    public static Specification<Destination> inDistrict(String district) {
        return (root, query, cb) ->
                cb.equal(cb.lower(root.get("district")), district.toLowerCase(Locale.ROOT));
    }

    /** Exact province match, case-insensitive. */
    public static Specification<Destination> inProvince(String province) {
        return (root, query, cb) ->
                cb.equal(cb.lower(root.get("province")), province.toLowerCase(Locale.ROOT));
    }

    /**
     * Everything a public list request asks for, always AND-ed with
     * {@link #published()}.
     */
    public static Specification<Destination> publicSearch(DestinationQuery query) {
        Specification<Destination> spec = published();

        if (query.search() != null) {
            spec = spec.and(matchesText(query.search()));
        }
        if (query.category() != null) {
            spec = spec.and(hasCategory(query.category()));
        }
        if (query.district() != null) {
            spec = spec.and(inDistrict(query.district()));
        }
        if (query.province() != null) {
            spec = spec.and(inProvince(query.province()));
        }
        return spec;
    }

    /** Treats the LIKE metacharacters as literals. Escape char is backslash. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
