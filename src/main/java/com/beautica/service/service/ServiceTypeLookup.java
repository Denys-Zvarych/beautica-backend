package com.beautica.service.service;

import com.beautica.common.exception.NotFoundException;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.ServiceTypeRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
class ServiceTypeLookup {

    private final ServiceTypeRepository serviceTypeRepository;

    ServiceTypeLookup(ServiceTypeRepository serviceTypeRepository) {
        this.serviceTypeRepository = serviceTypeRepository;
    }

    // sync = true: single-key reference-data read; on TTL expiry of a hot service-type id only
    // ONE thread reloads it while concurrent callers wait, instead of a herd of identical
    // findById round-trips (Anti-Bug §F-7).
    @Cacheable(value = "service-type-by-id", key = "#id", sync = true)
    @Transactional(readOnly = true)
    ServiceType getById(UUID id) {
        return serviceTypeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ServiceType not found: " + id));
    }

    // sync = true: hot public catalog read (service types per category); collapses the herd on
    // a popular category's TTL expiry to a single reload. No unless/condition, single cache name,
    // so sync is supported and safe (Anti-Bug §F-7).
    @Cacheable(value = "service-types", key = "#categoryId != null ? #categoryId.toString() : 'ALL'", sync = true)
    @Transactional(readOnly = true)
    List<ServiceType> getByCategory(@Nullable UUID categoryId) {
        if (categoryId == null) {
            return serviceTypeRepository.findAllActiveWithCategory();
        }
        return serviceTypeRepository.findByCategoryWithCategory(categoryId);
    }
}
