package com.beautica.service.service;

import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.ServiceTypeRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Isolated Spring component for trigram-search caching of service types.
 *
 * <p>Extracted from {@link ServiceCatalogService} to avoid the Spring AOP
 * self-invocation proxy bypass: a direct {@code this.method()} call never passes
 * through the proxy, so {@code @Cacheable} annotations on self-calls are inert.
 * By delegating to this component, the cache interceptor is always active.
 *
 * <p>Pattern mirrors {@link ServiceTypeLookup} — same package, package-private
 * class, injected via constructor into the owning service.
 */
@Component
class ServiceTypeSearchService {

    private final ServiceTypeRepository serviceTypeRepository;

    ServiceTypeSearchService(ServiceTypeRepository serviceTypeRepository) {
        this.serviceTypeRepository = serviceTypeRepository;
    }

    /**
     * Searches service types by trigram similarity, optionally scoped to a category.
     *
     * <p>Cache key: {@code q + ':' + categoryId} — both parameters form a composite
     * key so that searches with and without a category are cached independently.
     *
     * @param q          search term (caller must ensure length >= 3, stripped)
     * @param categoryId optional category filter; {@code null} searches across all categories
     */
    @Cacheable(value = "service-type-search", key = "#q + ':' + #categoryId")
    @Transactional(readOnly = true)
    List<ServiceTypeResponse> searchByName(String q, @Nullable UUID categoryId) {
        List<ServiceType> types = categoryId != null
                ? serviceTypeRepository.searchByNameAndCategory(q, categoryId, PageRequest.of(0, 20))
                : serviceTypeRepository.searchByName(q, PageRequest.of(0, 20));
        return types.stream()
                .map(ServiceTypeResponse::from)
                .toList();
    }
}
