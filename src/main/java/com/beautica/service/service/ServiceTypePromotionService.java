package com.beautica.service.service;

import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.repository.CatalogCategoryRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Materializes an APPROVED {@link ServiceTypeSuggestion} into a real, displayed service
 * for the requesting master (Phase 16.9, Option B). The suggestion service owns the
 * token/decision lifecycle; this collaborator performs ONLY the catalog + draft-service
 * writes, idempotently.
 *
 * <p>On approval this creates:
 * <ol>
 *   <li>a {@link ServiceType} under the approved platform category (reusing an existing
 *       one with the same category + case-insensitive name), UNLESS the category has no
 *       System-A bucket (V78 {@code service_category_id} is null) — then the type is
 *       skipped and the draft uses the suggested name directly;</li>
 *   <li>a master-owned <em>draft</em> {@link ServiceDefinition}
 *       ({@code isDraft = true}, {@code isActive = false}, sentinel placeholder pricing)
 *       so the requested NAME is attached to the master; and</li>
 *   <li>an <em>inactive</em> {@link MasterServiceAssignment} linking the two.</li>
 * </ol>
 *
 * <p><strong>Read-path note (deviation, documented).</strong> The draft is
 * {@code isActive = false}, so it is intentionally excluded from
 * {@code GET /masters/{id}/services} — that endpoint is {@code permitAll()} (the public
 * client-browse path) and must never expose ₴0 placeholder pricing to anonymous callers
 * (anti-bug §I) nor enter search/booking. Surfacing the draft to the OWNING master
 * requires a separate authenticated "my drafts" read path + a mobile completion UX,
 * which is out of scope for this backend phase (matches the Phase 16.9.0 caveat). The
 * row is durably created and the NAME is materialized; rendering it is the mobile follow-up.
 *
 * <p><strong>Transaction.</strong> MUST run inside the caller's transaction
 * ({@code ServiceTypeSuggestionService.approve}) so a promotion failure rolls back the
 * APPROVED flip — no "approved but not created" split-brain.
 *
 * <p><strong>Requester resolution.</strong> A null / non-master / deleted requester is
 * NOT an error: the {@link ServiceType} is still created (catalog-only) and the method
 * returns normally — an approval must never 500 because the requester vanished.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ServiceTypePromotionService {

    /** Sentinel placeholder duration for a draft (minutes). Gated out by isActive=false. */
    private static final int DRAFT_DURATION_MINUTES = 0;
    /** Sentinel placeholder buffer for a draft (minutes). */
    private static final int DRAFT_BUFFER_MINUTES = 0;
    private static final String MASTER_SERVICES_CACHE = "masterServices";

    private final ServiceTypeRepository serviceTypeRepository;
    private final ServiceRepository serviceRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final MasterRepository masterRepository;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final CatalogCategoryRepository catalogCategoryRepository;
    private final SlugGenerator slugGenerator;
    private final CacheManager cacheManager;

    /**
     * Materializes the approved suggestion. Idempotent: safe even if invoked twice for the
     * same suggestion (the secondary draft-dedup guard returns early on a re-entry).
     *
     * @param suggestion an already-APPROVED suggestion (caller guarantees the flip happened)
     */
    public void promote(ServiceTypeSuggestion suggestion) {
        Optional<Master> master = resolveRequesterMaster(suggestion.getRequestedByUserId());

        // Resolve (or create) the catalog ServiceType under the approved category. May be
        // null when the platform category has no System-A bucket (unmapped self-service).
        ServiceType serviceType = resolveOrCreateServiceType(suggestion);

        if (master.isEmpty()) {
            // Null / non-master / deleted requester → catalog-only, never throw.
            log.info("promotion: no master for requester of suggestion '{}' under '{}', catalog-only",
                    suggestion.getSuggestedName(), suggestion.getCategoryName());
            return;
        }

        createDraftServiceForMaster(master.get(), suggestion, serviceType);
    }

    private Optional<Master> resolveRequesterMaster(@Nullable UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return masterRepository.findByUserId(userId);
    }

    /**
     * Returns an existing active type with the same category + name, or creates a new one.
     * Returns {@code null} when the platform category has no mapped System-A bucket — the
     * draft then displays the suggested name directly with {@code serviceType = null}.
     */
    @Nullable
    private ServiceType resolveOrCreateServiceType(ServiceTypeSuggestion suggestion) {
        String categoryName = suggestion.getCategoryName();
        String suggestedName = suggestion.getSuggestedName();

        Optional<ServiceType> existing = serviceTypeRepository
                .findFirstActiveByPlatformCategoryNameAndNameUkIgnoreCase(categoryName, suggestedName);
        if (existing.isPresent()) {
            return existing.get();
        }

        CatalogCategory bucket = resolveBucket(categoryName);
        if (bucket == null) {
            log.info("promotion: platform category '{}' has no System-A bucket — "
                    + "skipping ServiceType creation, draft will use suggested name directly", categoryName);
            return null;
        }

        String slug = slugGenerator.uniqueSlug(suggestedName, categoryName, serviceTypeRepository::existsBySlug);
        ServiceType type = ServiceType.builder()
                .category(bucket)
                .nameUk(suggestedName)
                // service_types.name_en is NOT NULL (V17). A self-service suggestion only
                // carries a Ukrainian name, so mirror it as the English fallback (matches the
                // V75 seed convention where both name_uk and name_en are always populated).
                .nameEn(suggestedName)
                .slug(slug)
                .active(true)
                .platformCategoryName(categoryName)
                .build();
        return serviceTypeRepository.save(type);
    }

    /**
     * Resolves the System-A {@link CatalogCategory} bucket for a platform category via the
     * V78 {@code service_category_id} mapping. Returns {@code null} when the platform
     * category is unknown or unmapped.
     */
    @Nullable
    private CatalogCategory resolveBucket(String categoryName) {
        UUID bucketId = platformCategoryRepository.findByName(categoryName)
                .map(PlatformCategory::getServiceCategoryId)
                .orElse(null);
        if (bucketId == null) {
            return null;
        }
        return catalogCategoryRepository.findById(bucketId).orElse(null);
    }

    private void createDraftServiceForMaster(
            Master master, ServiceTypeSuggestion suggestion, @Nullable ServiceType serviceType) {

        String name = suggestion.getSuggestedName();
        String category = suggestion.getCategoryName();

        // Salon-null guard (audit LOW): a salon-typed master with a null salon cannot own a
        // SALON-scoped definition. Treat it like a vanished requester → catalog-only, never
        // NPE on approve. In practice every salon-typed master row has a salon FK; this is
        // defense in depth mirroring the empty-master branch in promote().
        if (master.getMasterType() != MasterType.INDEPENDENT_MASTER && master.getSalon() == null) {
            log.info("promotion: salon-typed master '{}' has null salon — catalog-only, no draft", master.getId());
            return;
        }

        // Secondary idempotency guard: a draft for this (master, name, category) already
        // exists → treat promotion as already done (defense in depth; the primary guard is
        // the single-use token cleared in the same transaction).
        if (masterServiceRepository.existsDraftAssignment(master.getId(), name, category)) {
            log.info("promotion: draft already exists for master '{}' name '{}' — skipping", master.getId(), name);
            return;
        }

        ServiceDefinition draft = buildDraftDefinition(master, name, category, serviceType);
        ServiceDefinition savedDraft = serviceRepository.save(draft);

        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(savedDraft)
                .isActive(false)
                .build();
        masterServiceRepository.save(assignment);

        // Do NOT refresh masters.min_effective_price — a draft (isActive=false) must not
        // enter the search price index; the refresh would either no-op or pull a ₴0 floor.
        evictMasterServicesCacheAfterCommit(master.getId());
    }

    private ServiceDefinition buildDraftDefinition(
            Master master, String name, String category, @Nullable ServiceType serviceType) {

        OwnerType ownerType;
        UUID ownerId;
        if (master.getMasterType() == MasterType.INDEPENDENT_MASTER) {
            ownerType = OwnerType.INDEPENDENT_MASTER;
            ownerId = master.getId();
        } else {
            // SALON_MASTER / SALON_OWNER → the owning salon owns the definition.
            ownerType = OwnerType.SALON;
            ownerId = master.getSalon().getId();
        }

        return ServiceDefinition.builder()
                .ownerType(ownerType)
                .ownerId(ownerId)
                .name(name)
                .category(category)
                .baseDurationMinutes(DRAFT_DURATION_MINUTES)
                .bufferMinutesAfter(DRAFT_BUFFER_MINUTES)
                .priceType(PriceType.FIXED)
                .basePrice(BigDecimal.ZERO)
                .priceMax(null)
                .isActive(false)
                .isDraft(true)
                .serviceType(serviceType)
                .build();
    }

    /**
     * Evicts the master's {@code masterServices} cache entry after commit so a parallel
     * reader cannot repopulate it with the pre-insert snapshot (anti-bug §F). When no
     * transaction is active (e.g. direct unit-test invocation) the eviction runs inline.
     */
    private void evictMasterServicesCacheAfterCommit(UUID masterId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doEvict(masterId);
                }
            });
        } else {
            doEvict(masterId);
        }
    }

    private void doEvict(UUID masterId) {
        var cache = cacheManager.getCache(MASTER_SERVICES_CACHE);
        if (cache != null) {
            cache.evict(masterId);
        }
    }
}
