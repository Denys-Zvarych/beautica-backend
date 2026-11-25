package com.beautica.service.service;

import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.common.exception.CatalogUsage;
import com.beautica.common.exception.NotFoundException;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.PlatformCategoryStatus;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import com.beautica.service.repository.ServiceTypeSuggestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * B505 (SVC-57) — the domain half of the catalog-admin delete of an UNUSED category or service type. The caller
 * ({@code CatalogEntryAdminService}) joins this transaction, writes the audit row and evicts the catalog caches.
 *
 * <p>The row is locked with a real {@code FOR UPDATE} (the native {@code find…ForDelete} finders — Hibernate's
 * {@code PESSIMISTIC_WRITE} is {@code FOR NO KEY UPDATE} on PostgreSQL and would NOT block an FK check), every
 * reference is counted, and only an all-zero count deletes. Any other writer that would add a reference serialises
 * behind that lock: a new type or a re-parent takes {@code FOR KEY SHARE} on the category (V73 FK), a new service
 * definition on its type (V111 FK), a new PENDING suggestion takes {@code FOR SHARE} on the category
 * ({@code ServiceTypeSuggestionService#submitSuggestion}), and a definition's {@code category} string always equals its
 * type's category (create / PATCH guard). So the writer either commits first and is counted, or fails after the
 * delete. The FK RESTRICT is the backstop: a violation at the flush is the same {@code CATALOG_IN_USE} (without
 * counts).
 */
@Service
@RequiredArgsConstructor
public class PlatformCatalogDeleteService {

    private final PlatformCategoryRepository platformCategoryRepository;
    private final ServiceTypeRepository serviceTypeRepository;
    private final ServiceRepository serviceRepository;
    private final ServiceTypeSuggestionRepository suggestionRepository;

    /** The deleted category's catalog fields (for the caller's audit row); no requester data. */
    public record CategoryDeletion(String name, String displayName, PlatformCategoryStatus status, boolean active,
                                   @Nullable String initialServiceName, @Nullable UUID serviceCategoryId) {}

    /** The deleted type's catalog fields (for the caller's audit row); {@code nameEn} may be {@code ""}. */
    public record ServiceTypeDeletion(UUID id, String platformCategoryName, String slug, String nameUk,
                                      @Nullable String nameEn, boolean active) {}

    /**
     * Deletes the APPROVED category with this wire name when no service type (hidden included), no service definition
     * naming it (inactive included) and no PENDING suggestion references it. Decided suggestions are history and stay;
     * the requester's in-app decision notification goes with the row (V196 {@code ON DELETE CASCADE}).
     *
     * <p>B539 hook: once categories carry an R2 icon, its object key must be removed AFTER COMMIT by the caller (never
     * inside this transaction — a rollback would orphan the row's icon reference).
     *
     * @throws NotFoundException               no category with this wire name
     * @throws CatalogRequestConflictException {@code CATEGORY_NOT_APPROVED} for a pending / rejected request (decide it
     *                                         in the request queue); {@code CATALOG_IN_USE} with the counts
     */
    @Transactional
    public CategoryDeletion deleteCategory(String name) {
        PlatformCategory category = platformCategoryRepository.findByNameForDelete(name)
                .orElseThrow(() -> new NotFoundException("Category not found"));
        if (!category.isApproved()) {
            throw new CatalogRequestConflictException(CatalogRequestConflictException.Code.CATEGORY_NOT_APPROVED);
        }
        requireUnused(CatalogUsage.ofCategory(
                serviceTypeRepository.countAllByPlatformCategoryName(name),
                serviceRepository.countAllByCategory(name),
                suggestionRepository.countPendingByCategoryName(name)));
        CategoryDeletion deletion = new CategoryDeletion(category.getName(), category.getDisplayName(),
                category.getStatus(), category.isActive(), category.getInitialServiceName(),
                category.getServiceCategoryId());
        deleteAndFlush(() -> {
            platformCategoryRepository.delete(category);
            platformCategoryRepository.flush();
        });
        return deletion;
    }

    /**
     * Deletes the service type when no service definition (active or inactive) references it.
     *
     * @throws NotFoundException               no type with this id
     * @throws CatalogRequestConflictException {@code CATALOG_IN_USE} with {@code definitions}
     */
    @Transactional
    public ServiceTypeDeletion deleteServiceType(UUID id) {
        ServiceType type = serviceTypeRepository.findByIdForDelete(id)
                .orElseThrow(() -> new NotFoundException("Service type not found"));
        requireUnused(CatalogUsage.ofServiceType(serviceRepository.countAllByServiceTypeId(id)));
        ServiceTypeDeletion deletion = new ServiceTypeDeletion(type.getId(), type.getPlatformCategoryName(),
                type.getSlug(), type.getNameUk(), type.getNameEn(), type.isActive());
        deleteAndFlush(() -> {
            serviceTypeRepository.delete(type);
            serviceTypeRepository.flush();
        });
        return deletion;
    }

    private static void requireUnused(CatalogUsage usage) {
        if (usage.inUse()) {
            throw new CatalogRequestConflictException(CatalogRequestConflictException.Code.CATALOG_IN_USE, usage);
        }
    }

    /**
     * Flushes inside the method so an FK RESTRICT violation (the backstop behind the locked count) surfaces here as
     * {@code CATALOG_IN_USE}, not as a commit-time 500. No counts: the aborted transaction cannot re-query.
     */
    private static void deleteAndFlush(Runnable delete) {
        try {
            delete.run();
        } catch (DataIntegrityViolationException ex) {
            throw new CatalogRequestConflictException(CatalogRequestConflictException.Code.CATALOG_IN_USE);
        }
    }
}
