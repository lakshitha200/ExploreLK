package com.explorelk.destination.category.dto;

import com.explorelk.destination.category.Category;

/**
 * A category as the API exposes it.
 *
 * <p>{@code code} is the value clients pass back as {@code ?category=BEACH};
 * {@code name} is what a person reads. Frontends must never display the code.
 */
public record CategoryResponse(
        String code,
        String name,
        String description,
        String icon,
        short sortOrder
) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getCode(),
                category.getName(),
                category.getDescription(),
                category.getIcon(),
                category.getSortOrder());
    }
}
