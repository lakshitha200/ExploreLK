package com.explorelk.destination.category;

import com.explorelk.destination.category.dto.CategoryResponse;
import com.explorelk.destination.category.dto.CreateCategoryRequest;
import com.explorelk.destination.common.ErrorCode;
import com.explorelk.destination.common.exception.AppException;
import com.explorelk.destination.common.CatalogCache;
import com.explorelk.destination.common.exception.ValidationException;
import com.explorelk.destination.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The category vocabulary: read by everyone, extended by admins.
 *
 * <p>Step 8 puts a 24-hour Redis cache in front of {@link #listAll()} — the table
 * changes about never and is read on every filter UI render.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CatalogCache catalogCache;

    @Cacheable(cacheNames = CacheConfig.CATEGORIES, key = "'all'")
    @Transactional(readOnly = true)
    public List<CategoryResponse> listAll() {
        return categoryRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    /**
     * Used to reject {@code ?category=BEECH} with a 400 rather than silently
     * returning an empty page, which reads to a client as "there are no beaches".
     */
    @Transactional(readOnly = true)
    public boolean exists(String code) {
        return code != null && categoryRepository.existsByCode(code);
    }

    /**
     * Turns the codes on a write request into managed entities, or fails.
     *
     * <p>Shared by the destination and attraction admin services so tagging
     * behaves identically on both — including the part that matters, which is
     * that an unknown code is rejected as a whole rather than dropped. Saving
     * {@code ["BEACH", "BEECH"]} as a single BEACH tag is the kind of partial
     * success that is discovered months later by a traveler who cannot find the
     * place.
     *
     * <p>Codes are upper-cased first, so {@code beach} works from a hand-written
     * request, and a {@link LinkedHashSet} keeps the caller's order for the rare
     * case where it reads as a priority.
     *
     * @param field the request field to blame in the error
     */
    @Transactional(readOnly = true)
    public Set<Category> resolve(Collection<String> codes, String field) {
        if (codes == null || codes.isEmpty()) {
            return new LinkedHashSet<>();
        }

        Set<String> wanted = codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<Category> found = new LinkedHashSet<>(categoryRepository.findAllById(wanted));

        if (found.size() != wanted.size()) {
            Set<String> known = found.stream().map(Category::getCode).collect(Collectors.toSet());
            List<String> unknown = wanted.stream().filter(code -> !known.contains(code)).toList();
            throw new ValidationException(field,
                    "contains unknown category codes: " + String.join(", ", unknown));
        }
        return found;
    }

    /**
     * Adds a term to the vocabulary.
     *
     * <p>Deliberately create-only. Renaming a code would break every client that
     * has {@code ?category=BEACH} in a saved filter, and deleting one is blocked
     * by the {@code ON DELETE RESTRICT} foreign keys from the join tables anyway
     * — which is the right answer: a category still in use is not garbage.
     */
    @Transactional
    public CategoryResponse create(CreateCategoryRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);

        if (categoryRepository.existsByCode(code)) {
            throw new AppException(ErrorCode.CONFLICT, "Category already exists: " + code);
        }

        Category category = Category.builder()
                .code(code)
                .name(request.name().trim())
                .description(request.description())
                .icon(request.icon())
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                .build();

        Category saved = categoryRepository.save(category);
        catalogCache.evictCategories();
        log.info("Category created: {}", saved.getCode());

        return CategoryResponse.from(saved);
    }
}
