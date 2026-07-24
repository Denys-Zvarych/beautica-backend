package com.beautica.service.repository;

import com.beautica.service.entity.CatalogCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

/**
 * Read-only repository for service catalog categories.
 *
 * <p>Auth policy: {@code GET /api/v1/service-categories} is declared {@code permitAll()} in
 * {@code SecurityConfig} — callers of {@link #findAllByOrderBySortOrderAsc()} must not assume
 * an authenticated principal is present in the security context.
 */
public interface CatalogCategoryRepository extends JpaRepository<CatalogCategory, UUID> {
    List<CatalogCategory> findAllByOrderBySortOrderAsc();
}
