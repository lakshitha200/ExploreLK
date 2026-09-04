package com.explorelk.destination.category;

import com.explorelk.destination.category.dto.CategoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read access to the category vocabulary.
 *
 * <p>Step 8 puts a 24-hour Redis cache in front of {@link #listAll()} — the table
 * changes about never and is read on every filter UI render.
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

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
}
