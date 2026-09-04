package com.explorelk.destination.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, String> {

    /** The order filter UIs render in. */
    List<Category> findAllByOrderBySortOrderAscNameAsc();

    boolean existsByCode(String code);
}
