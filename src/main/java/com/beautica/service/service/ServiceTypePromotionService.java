package com.beautica.service.service;

import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.repository.CatalogCategoryRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Materializes an APPROVED {@link ServiceTypeSuggestion} into a real, catalog-level
 * {@link ServiceType} under the approved platform category (Phase 16.9, Option A —
 * <strong>catalog-only</strong>; this reverses the earlier Option B "auto-create draft
 * master service" behaviour). The suggestion service owns the token/decision lifecycle;
 * this collaborator performs ONLY the catalog write, idempotently.
 *
 * <p>On approval this creates (or reuses) exactly one {@link ServiceType} under the approved
 * platform category — reusing an existing active type with the same category +
 * case-insensitive name when present. The new type then surfaces in the mobile
 * "Тип послуги" dropdown via {@code GET /service-types?categoryName=} (Phase 16.2). The
 * master adds their own service later by picking the now-available type; <strong>this
 * service never creates a {@code ServiceDefinition} (draft) or a
 * {@code MasterServiceAssignment}.</strong>
 *
 * <p><strong>Bucket resolution.</strong> {@link ServiceType#getCategory()} is NOT NULL, so
 * every type must be bound to a System-A {@link CatalogCategory}. A platform category mapped
 * to a System-A bucket (V78 {@code service_category_id}) binds the type to that bucket; a
 * brand-new self-service category with no mapping falls back to the permanent "Other / Інше"
 * System-A bucket (seeded V13) so the NOT NULL is satisfied without any migration.
 *
 * <p><strong>Transaction.</strong> MUST run inside the caller's transaction
 * ({@code ServiceTypeSuggestionService.approve}) so a promotion failure rolls back the
 * APPROVED flip — no "approved but not created" split-brain.
 *
 * <p><strong>Requester resolution.</strong> The requester identity is irrelevant under
 * Option A: the {@link ServiceType} is created regardless of who suggested it (or whether
 * the requester still exists). An approval never 500s because the requester vanished.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ServiceTypePromotionService {

    /**
     * Permanent System-A "Other / Інше" {@link CatalogCategory} bucket, seeded in V13 and
     * always present. Used as the fallback parent for a {@link ServiceType} promoted from a
     * platform category that has no mapped System-A bucket (V78 {@code service_category_id}
     * is null) — it satisfies the {@code service_types.category_id} NOT NULL with no
     * migration.
     */
    private static final UUID OTHER_BUCKET_ID =
            UUID.fromString("11111111-0008-0000-0000-000000000000");

    private final ServiceTypeRepository serviceTypeRepository;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final CatalogCategoryRepository catalogCategoryRepository;
    private final SlugGenerator slugGenerator;

    /**
     * Materializes the approved suggestion as a catalog {@link ServiceType}. Idempotent:
     * safe even if invoked twice for the same suggestion (the second call reuses the type
     * created by the first).
     *
     * @param suggestion an already-APPROVED suggestion (caller guarantees the flip happened)
     */
    public void promote(ServiceTypeSuggestion suggestion) {
        resolveOrCreateServiceType(suggestion);
    }

    /**
     * Returns an existing active type with the same category + name, or creates a new one.
     * Always returns a non-null {@link ServiceType}: the parent bucket is the platform
     * category's mapped System-A bucket when present, otherwise the permanent "Other"
     * fallback bucket.
     */
    private ServiceType resolveOrCreateServiceType(ServiceTypeSuggestion suggestion) {
        String categoryName = suggestion.getCategoryName();
        String suggestedName = suggestion.getSuggestedName();

        Optional<ServiceType> existing = serviceTypeRepository
                .findFirstActiveByPlatformCategoryNameAndNameUkIgnoreCase(categoryName, suggestedName);
        if (existing.isPresent()) {
            return existing.get();
        }

        CatalogCategory bucket = resolveBucket(categoryName);
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
     * V78 {@code service_category_id} mapping. When the platform category is unknown or
     * unmapped (a brand-new self-service category), falls back to the permanent "Other"
     * System-A bucket (seeded V13) so the NOT NULL {@code service_types.category_id} is
     * always satisfied without a migration.
     */
    private CatalogCategory resolveBucket(String categoryName) {
        UUID mappedBucketId = platformCategoryRepository.findByName(categoryName)
                .map(PlatformCategory::getServiceCategoryId)
                .orElse(null);
        if (mappedBucketId == null) {
            log.info("promotion: platform category '{}' has no System-A bucket — "
                    + "binding new ServiceType to the permanent 'Other' bucket", categoryName);
        }
        UUID bucketId = mappedBucketId != null ? mappedBucketId : OTHER_BUCKET_ID;
        return catalogCategoryRepository.findById(bucketId)
                .orElseThrow(() -> new IllegalStateException(
                        "System-A bucket '" + bucketId + "' not found for platform category '"
                                + categoryName + "'"));
    }
}
