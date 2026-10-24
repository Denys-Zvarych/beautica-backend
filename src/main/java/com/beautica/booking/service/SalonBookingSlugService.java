package com.beautica.booking.service;

import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.dto.SalonBookingSlugInfoResponse;
import com.beautica.booking.dto.SalonBookingSlugMasterItem;
import com.beautica.booking.dto.SalonServiceCoverage;
import com.beautica.booking.dto.SalonServiceMasterRef;
import com.beautica.common.cache.SalonBookingInfoCacheEvictor;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.MasterRepository.SalonBookingPageMasterRow;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonRepository.SalonBookingPageRow;
import com.beautica.media.entity.EntityType;
import com.beautica.media.service.MediaService;
import com.beautica.review.dto.SalonReviewSort;
import com.beautica.review.service.ReviewService;
import com.beautica.service.dto.SalonCatalogSnapshot;
import com.beautica.service.dto.SalonServiceCategoryGroup;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.service.ServiceCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves a public SALON booking slug ({@code beautica.app/book/s/{slug}}) to the website's salon page
 * payload (phase 377): the salon's public columns plus its bookable masters, each with the master slug
 * the existing {@code /book/{slug}/*} flow takes.
 *
 * <p><b>Two statements on a cache miss</b>, both projections: the salon row by slug, then every bookable
 * master of the salon in one batch ({@code MasterBookabilitySql.BOOKABLE_MASTER_M}, the predicate the
 * public roster and discovery search use) — never a query per master.
 *
 * <p><b>Read-only, never writes</b> (phase 377 audit): no slug is ever issued here. Since phase 391 a slug-less
 * master (legacy pre-V86 row, or a revoked personal link) is LISTED with {@code masterSlug = null} — the salon
 * flow books by master id — instead of being omitted. Only the salon lookup can 404 the page — a master
 * deactivated between the statements is simply absent from the batch.
 *
 * <p><b>Phase 391 — {@code serviceCoverage}.</b> Per catalogue service, the bookable masters who perform it,
 * restricted to the roster and in roster order, so every id the website gets resolves to a roster card. Both the
 * catalogue tab and the coverage come from ONE {@link ServiceCatalogService#getSalonCatalogSnapshot} (phase 391
 * audit): one candidate load and one free-slot batch — the batched form of the app's per-service master verdict
 * ({@code GET /salons/{id}/services/{defId}/masters}) — instead of running the gate twice on a cold miss.
 */
@Service
@RequiredArgsConstructor
public class SalonBookingSlugService {

    /** A salon's headcount bound for the master batch (Anti-Bug §E-3). */
    static final int MAX_MASTERS = 100;

    private final SalonRepository salonRepository;
    private final MasterRepository masterRepository;
    private final ScheduleDateMath scheduleDateMath;
    // Phase 384 D2: each tab block delegates to the service method behind the app's public salon endpoint.
    private final MediaService mediaService;
    private final ServiceCatalogService serviceCatalogService;
    private final ReviewService reviewService;
    /** Phase 379 — 410 for a retired salon slug on the miss path. */
    private final RetiredBookingSlugGuard retiredBookingSlugGuard;

    /**
     * The public salon page for {@code slug}. Unknown slug or inactive salon → {@link NotFoundException}
     * (404, same message as the master page, D2). An active salon without a bookable master → 200 with
     * {@code bookable = false} and no masters (D3).
     *
     * <p>Cached per slug with {@code sync = true} (a {@code permitAll} read, Anti-Bug §F-7); evicted after
     * commit by {@link SalonBookingInfoCacheEvictor}, 60-second TTL backstop ({@code CacheConfig}).
     */
    // L2 (phase 384 audit, accepted): one read-only transaction for the whole miss; database reads and in-JVM cache
    // lookups only - no external call may be added inside it.
    @Cacheable(cacheNames = SalonBookingInfoCacheEvictor.CACHE, key = "#slug", sync = true)
    @Transactional(readOnly = true)
    public SalonBookingSlugInfoResponse findBySlug(String slug) {
        SalonBookingPageRow salon = salonRepository.findBookingPageRowBySlug(slug)
                .orElseThrow(() -> bookingPageNotFound(slug));
        List<SalonBookingSlugMasterItem> masters = masterRepository
                .findBookingPageMastersBySalonId(salon.getId(), scheduleDateMath.today(), MAX_MASTERS)
                .stream()
                .map(this::toItem)
                .toList();
        // ONE snapshot for both the catalogue tab and serviceCoverage (phase 391 audit, perf MEDIUM-1): the same
        // candidate load and free-slot batch, so a cold miss runs the gate once and the two can never disagree.
        SalonCatalogSnapshot catalogue = serviceCatalogService.getSalonCatalogSnapshot(salon.getId());
        List<SalonServiceCategoryGroup> serviceCategories = catalogue.catalog().categories();
        return new SalonBookingSlugInfoResponse(
                salon.getId(),
                salon.getName(),
                salon.getCity(),
                salon.getAvatarUrl(),
                salon.getCoverImageUrl(),
                // Same rule as PublicSalonResponse: no reviews → null, never a fabricated 0.
                salon.getReviewCount() == 0 ? null : salon.getAvgRating(),
                salon.getReviewCount(),
                !masters.isEmpty(),
                masters,
                salon.getRegion(),
                // Same fallback rule as PublicSalonResponse: blank street -> the legacy address.
                isBlank(salon.getStreet()) ? salon.getAddress() : salon.getStreet(),
                salon.getBuildingNo(),
                salon.getUpdatedAt(),
                salon.getDescription(),
                salon.getLocationNote(),
                // GET /salons/{id}/portfolio's order (createdAt DESC) and the master page's cap — uncached read,
                // the shared `portfolio` cache entry ignores page size (see MediaService#getLatestPortfolio).
                mediaService.getLatestPortfolio(EntityType.SALON, salon.getId(), BookingSlugService.PORTFOLIO_LIMIT),
                // GET /salons/{id}/services' catalogue (same builder): bookable-only grouping, counts, display names.
                serviceCategories,
                // The row already holds avgRating/reviewCount: the overload skips the salon findById.
                reviewService.getSalonReviewSummary(salon.getId(), salon.getAvgRating(), salon.getReviewCount())
                        .ratingDistribution(),
                // One uncached statement, no COUNT (the paged getSalonReviews costs ids + count + hydrate).
                reviewService.getLatestReviewsForSalon(salon.getId(), BookingSlugService.LATEST_REVIEWS_LIMIT),
                serviceCoverage(serviceCategories, catalogue.coverage(), masters));
    }

    /**
     * One {@link SalonServiceCoverage} per catalogue service, in catalogue order. Masters outside the roster
     * (e.g. beyond {@link #MAX_MASTERS}) are dropped so every id maps to a roster card; the rest keep roster order.
     */
    private static List<SalonServiceCoverage> serviceCoverage(List<SalonServiceCategoryGroup> categories,
            Map<UUID, List<SalonServiceMasterRef>> byService, List<SalonBookingSlugMasterItem> roster) {
        if (categories.isEmpty() || roster.isEmpty()) {
            return categories.stream().flatMap(c -> c.services().stream())
                    .map(sd -> new SalonServiceCoverage(sd.id(), List.of())).toList();
        }
        Map<UUID, Integer> rosterOrder = new HashMap<>();
        for (int i = 0; i < roster.size(); i++) {
            rosterOrder.put(roster.get(i).masterId(), i);
        }
        return categories.stream()
                .flatMap(category -> category.services().stream())
                .map(ServiceDefinitionResponse::id)
                .map(serviceId -> new SalonServiceCoverage(serviceId, byService.getOrDefault(serviceId, List.of())
                        .stream()
                        .filter(ref -> rosterOrder.containsKey(ref.masterId()))
                        .sorted(Comparator.comparingInt(ref -> rosterOrder.get(ref.masterId())))
                        .toList()))
                .toList();
    }

    /**
     * Phase 379 D2 — the public-slug miss: 410 when the slug is a retired {@code kind} link
     * ({@link RetiredBookingSlugGuard}), otherwise the unchanged 404. Only ever reached after the live
     * resolver missed, so a valid link pays no extra query.
     */
    private NotFoundException bookingPageNotFound(String slug) {
        retiredBookingSlugGuard.throwGoneIfRetired(BookingLinkKind.SALON, slug);
        return new NotFoundException("Booking page not found");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private SalonBookingSlugMasterItem toItem(SalonBookingPageMasterRow master) {
        return new SalonBookingSlugMasterItem(
                master.getBookingSlug(),
                BookingSlugService.composeName(master.getFirstName(), master.getLastName()),
                master.getAvatarUrl(),
                master.getProfessionalTitle(),
                // The app's salon master card rule (MasterSummaryResponse): no reviews -> null, never the stored 0.00.
                com.beautica.booking.dto.BookingDetailResponse.masterAvgRatingOrNull(
                        master.getReviewCount(), master.getAvgRating()),
                master.getReviewCount(),
                master.getId());
    }
}
