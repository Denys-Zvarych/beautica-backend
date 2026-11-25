package com.beautica.service.service;

import com.beautica.service.dto.CatalogCategoryResponse;
import com.beautica.service.repository.CatalogCategoryRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
class CatalogCategoryLookup {

    private final CatalogCategoryRepository catalogCategoryRepository;

    CatalogCategoryLookup(CatalogCategoryRepository catalogCategoryRepository) {
        this.catalogCategoryRepository = catalogCategoryRepository;
    }

    // Any write path that creates, modifies, or deletes a CatalogCategory must @CacheEvict(value="service-categories", allEntries=true).
    // sync = true: one platform-wide key read by every picker; a TTL expiry must load it once, not N times (B503).
    @Cacheable(value = "service-categories", sync = true)
    @Transactional(readOnly = true)
    public List<CatalogCategoryResponse> getAll() {
        return catalogCategoryRepository.findAllByOrderBySortOrderAsc()
                .stream()
                .map(CatalogCategoryResponse::from)
                .toList();
    }
}
