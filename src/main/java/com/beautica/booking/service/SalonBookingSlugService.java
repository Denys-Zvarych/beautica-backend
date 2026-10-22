package com.beautica.booking.service;

import com.beautica.booking.dto.SalonBookingSlugInfoResponse;
import com.beautica.booking.dto.SalonBookingSlugMasterItem;
import com.beautica.common.cache.SalonBookingInfoCacheEvictor;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.MasterRepository.SalonBookingPageMasterRow;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonRepository.SalonBookingPageRow;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolves a public SALON booking slug ({@code beautica.app/book/s/{slug}}) to the website's salon page
 * payload (phase 377): the salon's public columns plus its bookable masters, each with the master slug
 * the existing {@code /book/{slug}/*} flow takes.
 *
 * <p><b>Two statements on a cache miss</b>, both projections: the salon row by slug, then every bookable
 * master of the salon in one batch ({@code MasterBookabilitySql.BOOKABLE_MASTER_M}, the predicate the
 * public roster and discovery search use) — never a query per master.
 *
 * <p><b>Read-only, never writes</b> (phase 377 audit): slug-less (legacy, pre-V86) masters are excluded by
 * the batch query rather than issued a slug here, so an anonymous GET performs no transaction and no
 * {@code UPDATE}. Such a master gets its slug via {@code GET /booking-links/mine} (phase 375); every
 * master created since phase 13.1 carries one from creation. Only the salon lookup can 404 the page — a
 * master deactivated between the two statements is simply absent from the batch.
 */
@Service
@RequiredArgsConstructor
public class SalonBookingSlugService {

    /** A salon's headcount bound for the master batch (Anti-Bug §E-3). */
    static final int MAX_MASTERS = 100;

    private final SalonRepository salonRepository;
    private final MasterRepository masterRepository;
    private final ScheduleDateMath scheduleDateMath;

    /**
     * The public salon page for {@code slug}. Unknown slug or inactive salon → {@link NotFoundException}
     * (404, same message as the master page, D2). An active salon without a bookable master → 200 with
     * {@code bookable = false} and no masters (D3).
     *
     * <p>Cached per slug with {@code sync = true} (a {@code permitAll} read, Anti-Bug §F-7); evicted after
     * commit by {@link SalonBookingInfoCacheEvictor}, 60-second TTL backstop ({@code CacheConfig}).
     */
    @Cacheable(cacheNames = SalonBookingInfoCacheEvictor.CACHE, key = "#slug", sync = true)
    public SalonBookingSlugInfoResponse findBySlug(String slug) {
        SalonBookingPageRow salon = salonRepository.findBookingPageRowBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Booking page not found"));
        List<SalonBookingSlugMasterItem> masters = masterRepository
                .findBookingPageMastersBySalonId(salon.getId(), scheduleDateMath.today(), MAX_MASTERS)
                .stream()
                .map(this::toItem)
                .toList();
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
                salon.getUpdatedAt());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private SalonBookingSlugMasterItem toItem(SalonBookingPageMasterRow master) {
        return new SalonBookingSlugMasterItem(
                master.getBookingSlug(),
                BookingSlugService.composeName(master.getFirstName(), master.getLastName()),
                master.getAvatarUrl(),
                master.getProfessionalTitle());
    }
}
