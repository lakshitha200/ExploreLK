package com.explorelk.destination.category;

import com.explorelk.destination.category.dto.CategoryResponse;
import com.explorelk.destination.category.dto.CreateCategoryRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Extending the category vocabulary. {@code ADMIN} or {@code SUPER_ADMIN} only.
 *
 * <p>Reading categories stays on the public {@link CategoryController} — a filter
 * UI must render without a token.
 */
@RestController
@RequestMapping("/api/v1/admin/categories")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@RequiredArgsConstructor
public class CategoryAdminController {

    private final CategoryService categoryService;

    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
        CategoryResponse created = categoryService.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/categories#" + created.code()))
                .body(created);
    }
}
