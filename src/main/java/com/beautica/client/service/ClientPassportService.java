package com.beautica.client.service;

import org.springframework.data.domain.Sort;
import com.beautica.common.web.SortWhitelist;
import com.beautica.client.dto.BudgetBand;
import com.beautica.client.dto.PassportResponse;
import com.beautica.client.dto.TimelineItemResponse;
import com.beautica.client.repository.BudgetAggregate;
import com.beautica.client.repository.ClientAggregationRepository;
import com.beautica.client.repository.DistrictCount;
import com.beautica.client.repository.TimelineItemProjection;
import com.beautica.common.PageResponse;
import com.beautica.common.TimeZones;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Derives the read-only BEAUTI PASSPORT and BEAUTY TIMELINE for the signed-in client
 * (Phase 19.5) from their COMPLETED booking history. No preferences entity exists and
 * nothing here is user-editable.
 */
@Service
@RequiredArgsConstructor
public class ClientPassportService {

    private static final String CURRENCY_UAH = "UAH";
    private static final String UNKNOWN_CATEGORY_KEY = "UNKNOWN";
    private static final int TOP_N = 3;
    private static final Pageable TOP_N_PAGE = PageRequest.of(0, TOP_N);

    private final ClientAggregationRepository aggregationRepository;
    private final DiscoveryLocationResolver discoveryLocationResolver;
    private final Clock clock;

    /**
     * Builds the passport from the three SQL aggregations. Empty-state contract: when no
     * COMPLETED bookings exist ({@code bookingsConsidered == 0}) the lists are empty and
     * the budget is {@code null}.
     */
    @Transactional(readOnly = true)
    public PassportResponse getPassport(UUID clientUserId) {
        BudgetAggregate budget = aggregationRepository.aggregateBudget(clientUserId);
        int considered = (int) budget.total();
        if (considered == 0) {
            return new PassportResponse(List.of(), List.of(), null, 0);
        }

        List<String> favoriteProcedures =
                aggregationRepository.findTopServiceTypes(clientUserId, TOP_N_PAGE);
        List<String> favoriteDistricts = resolveDistrictLabels(
                aggregationRepository.findTopDistricts(clientUserId, TOP_N_PAGE));

        BudgetBand band = new BudgetBand(budget.avg(), budget.min(), budget.max(), CURRENCY_UAH);
        return new PassportResponse(favoriteProcedures, favoriteDistricts, band, considered);
    }

    /**
     * The only property {@code GET /clients/me/timeline} may be sorted by — mirrors
     * {@code BookingService.SORTABLE_BOOKING_PROPERTIES}, since this query shares the
     * {@code Booking} root and must not expose a wider sort surface than {@code GET /bookings/me}.
     */
    private static final Set<String> SORTABLE_TIMELINE_PROPERTIES = Set.of("startsAt");

    /**
     * Most-recent-first page of COMPLETED procedures. The {@code categoryKey} slug and the
     * Kyiv {@code LocalDate} are derived in-memory from the scalar projection (no N+1).
     */
    @Transactional(readOnly = true)
    public PageResponse<TimelineItemResponse> getTimeline(UUID clientUserId, Pageable pageable) {
        // findTimeline's root is Booking, whose `master`/`client` associations reach User — an
        // unguarded sort resolves `client.passwordHash` as valid JPQL and orders rows by it.
        // Whitelisted to the property @PageableDefault already supplies, matching
        // BookingService.SORTABLE_BOOKING_PROPERTIES. No tiebreaker: the JPQL hardcodes
        // `ORDER BY b.startsAt DESC` and Spring appends the caller's sort after it.
        Pageable safePageable = SortWhitelist.apply(
                pageable, SORTABLE_TIMELINE_PROPERTIES, Sort.unsorted(), null);
        Page<TimelineItemProjection> page = aggregationRepository.findTimeline(clientUserId, safePageable);
        ZoneId kyiv = TimeZones.KYIV;
        List<TimelineItemResponse> content = page.getContent().stream()
                .map(p -> toTimelineResponse(p, kyiv))
                .toList();
        return PageResponse.of(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /**
     * Resolves district FK ids to display labels through the {@code DiscoveryLocationResolver}
     * M2 seam (one batched query, no per-row lookup). Labels come only from the joined,
     * pre-filtered taxonomy — never a literal — so no occupied-territory label can appear.
     * Ids whose label fails to resolve are dropped rather than surfaced as a raw id.
     */
    private List<String> resolveDistrictLabels(List<DistrictCount> rows) {
        Set<UUID> districtIds = rows.stream()
                .map(DistrictCount::districtId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        DiscoveryLabels labels = discoveryLocationResolver.resolveLabels(Set.of(), districtIds);
        return rows.stream()
                .map(r -> labels.districtLabel(r.districtId()))
                .filter(label -> label != null && !label.isBlank())
                .toList();
    }

    private TimelineItemResponse toTimelineResponse(TimelineItemProjection p, ZoneId kyiv) {
        return new TimelineItemResponse(
                p.bookingId(),
                categoryKey(p.category()),
                p.category(),
                p.startsAt().atZoneSameInstant(kyiv).toLocalDate(),
                p.masterId(),
                p.serviceName());
    }

    /**
     * Stable machine key for the client-side icon map: a service has only a single String
     * {@code category} (validated against {@code platform_categories.name}), so the key is
     * its uppercase slug — already-uppercase values like {@code "MANICURE"} pass through
     * unchanged; a {@code null} category yields {@code "UNKNOWN"}.
     */
    private String categoryKey(String category) {
        if (category == null || category.isBlank()) {
            return UNKNOWN_CATEGORY_KEY;
        }
        return category.trim()
                .toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
