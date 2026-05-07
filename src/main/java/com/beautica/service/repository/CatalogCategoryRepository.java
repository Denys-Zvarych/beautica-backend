package com.beautica.service.repository;

import com.beautica.service.entity.CatalogCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CatalogCategoryRepository extends JpaRepository<CatalogCategory, UUID> {
    List<CatalogCategory> findAllByOrderBySortOrderAsc();
}
