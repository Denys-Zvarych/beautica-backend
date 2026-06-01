package com.beautica.service.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.service.dto.CreatePlatformCategoryRequest;
import com.beautica.service.dto.PlatformCategoryResponse;
import com.beautica.service.dto.PlatformCategoryUsageResponse;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.repository.PlatformCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Business logic for platform-managed service categories.
 *
 * <p>Used exclusively by the internal API (protected by {@code X-Internal-Key}).
 * No JWT authentication context is required — callers are identified by the API key.
 */
@Service
@RequiredArgsConstructor
public class InternalCategoryService {

    private final PlatformCategoryRepository platformCategoryRepository;

    /**
     * Returns all platform categories with their current usage counts from
     * {@code service_definitions.category}.
     */
    @Transactional(readOnly = true)
    public List<PlatformCategoryUsageResponse> listWithUsageCounts() {
        return platformCategoryRepository.findAllWithUsageCounts()
                .stream()
                .map(PlatformCategoryUsageResponse::from)
                .toList();
    }

    /**
     * Creates a new platform category.
     *
     * @throws BusinessException (409) if a category with the same name already exists
     *                           (case-insensitive comparison)
     */
    @Transactional
    public PlatformCategoryResponse create(CreatePlatformCategoryRequest request) {
        if (platformCategoryRepository.existsByNameIgnoreCase(request.name())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Category already exists: " + request.name());
        }

        // Ops-created categories are immediately usable: APPROVED + active. The
        // internal CRUD DTO carries no display name, so the wire name doubles as the
        // display label (same fallback as the V65 backfill for legacy ops rows).
        PlatformCategory category = PlatformCategory.ofApproved(request.name(), request.name());
        PlatformCategory saved = platformCategoryRepository.save(category);
        return PlatformCategoryResponse.from(saved);
    }
}
