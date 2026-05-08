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

    @Cacheable(value = "service-type-by-id", key = "#id")
    @Transactional(readOnly = true)
    ServiceType getById(UUID id) {
        return serviceTypeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ServiceType not found: " + id));
    }

    @Cacheable(value = "service-types", key = "#categoryId != null ? #categoryId.toString() : 'ALL'")
    @Transactional(readOnly = true)
    List<ServiceType> getByCategory(@Nullable UUID categoryId) {
        if (categoryId == null) {
            return serviceTypeRepository.findAllActiveWithCategory();
        }
        return serviceTypeRepository.findByCategoryWithCategory(categoryId);
    }
}
