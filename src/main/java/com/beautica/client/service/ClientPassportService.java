package com.beautica.client.service;

import org.springframework.data.domain.Sort;
import com.beautica.common.web.SortWhitelist;
import com.beautica.client.dto.BudgetBand;
import com.beautica.client.dto.PassportResponse;
import com.beautica.client.dto.TimelineItemResponse;
import com.beautica.client.repository.BudgetAggregate;
import com.beautica.client.repository.CityCount;
import com.beautica.client.repository.ClientAggregationRepository;
import com.beautica.client.repository.ClientStanding;
import com.beautica.client.repository.DistrictCount;
import com.beautica.client.repository.TimelineItemProjection;
import com.beautica.common.PageResponse;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.service.service.PlatformCategoryLabel;
import com.beautica.service.service.PlatformCategoryLabelResolver;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Derives the read-only BEAUTI PASSPORT and BEAUTY TIMELINE for the signed-in client
 * (Phase 19.5). No preferences entity exists and nothing here is user-editable.
 *
 * <p>{@link #getPassport} derives strictly from the client's COMPLETED booking history — every
 * aggregate it reads ({@code findStanding}'s review count aside, {@code aggregateBudget}, {@code
 * findTopDistricts}, {@code findTopCities}) is {@code status = COMPLETED} only. {@link
 * #getTimeline} was widened (2026-08-26) to also include elapsed-but-unclosed {@code CONFIRMED}
 * bookings — see {@link ClientAggregationRepository#findTimeline}'s javadoc — so the timeline can
 * legitimately list more rows than the passport headline counts. That divergence is intentional;
 * do not "fix" it by narrowing the timeline back to COMPLETED-only or by widening the passport
 * aggregates to match.
 */
@Service
@RequiredArgsConstructor
public class ClientPassportService {

    private static final Logger log = LoggerFactory.getLogger(ClientPassportService.class);

    /**
     * Caffeine cache backing {@link #getPassport}. Registered in {@code CacheConfig}; evicted
     * per key by {@link com.beautica.client.event.ClientPassportCacheEvictor}.
     */
    public static final String CLIENT_PASSPORT_CACHE = "client-passport";

    private static final String CURRENCY_UAH = "UAH";
    private static final String UNKNOWN_CATEGORY_KEY = "UNKNOWN";
    private static final int TOP_N = 3;
    private static final Pageable TOP_N_PAGE = PageRequest.of(0, TOP_N);

    private final ClientAggregationRepository aggregationRepository;
    private final DiscoveryLocationResolver discoveryLocationResolver;
    private final PlatformCategoryLabelResolver platformCategoryLabelResolver;
    private final Clock clock;

    /**
     * Builds the passport from the SQL aggregations.
     *
     * <p><b>Two empty-state contracts, deliberately different.</b> The booking-derived values
     * (procedures / districts / cities / budget / considered) short-circuit to empty when the
     * client has no COMPLETED bookings. The identity-standing values
     * ({@code reviewsWritten}, {@code memberSinceYear}) are resolved BEFORE that
     * short-circuit and populated on both branches — a brand-new client still has a real
     * registration year and may already have written reviews, and the «Паспорт — без історії»
     * state is a first-class approved state, not an edge case. Gating them behind
     * {@code considered > 0} would ship a zero and force the client to fabricate a year.
     *
     * <h3>Caching (2026-08 perf audit F3; Phase 250 retired {@code findTopServiceTypes})</h3>
     * A pure derived read costing 4 statements on a miss — {@code findStanding},
     * {@code aggregateBudget}, {@code findTopDistricts}, {@code findTopCities} — 3 of them
     * aggregations over the client's <em>entire</em> COMPLETED booking history. The empty-state
     * short-circuit below costs 2. (Both figures dropped by one when the two identity lookups
     * were folded into a single {@code findStanding}, and again when Phase 250 retired
     * {@code favoriteProcedures}/{@code findTopServiceTypes}; earlier revisions of this javadoc
     * said 7, then 5.) Cached in {@code client-passport} under the caller's own principal id.
     *
     * <p><b>{@code key = "#clientUserId"} is the whole IDOR story.</b> The argument is the
     * authenticated principal's id, extracted by the controller from
     * {@code Authentication.getDetails()} — never a path variable or a body field — so a cache
     * entry is reachable only by the client who owns it. Do not widen this key, and do not add
     * an overload that takes a caller-supplied id.
     *
     * <p>{@code sync = true} because this is a per-client hot key: without it, N concurrent
     * requests for the same client after a TTL expiry each run the full 4-statement derivation
     * (§F-7).
     *
     * <p><b>The {@code NotFoundException} path is never cached.</b> Spring's cache abstraction
     * stores only normal returns; a thrown exception leaves the cache untouched, so a missing
     * user row keeps raising 404 on every call rather than being papered over by a cached
     * fabricated standing.
     *
     * <p>Invalidation is per-key and {@code AFTER_COMMIT} — see
     * {@link com.beautica.client.event.ClientPassportCacheEvictor}.
     */
    @Cacheable(value = CLIENT_PASSPORT_CACHE, key = "#clientUserId", sync = true)
    @Transactional(readOnly = true)
    public PassportResponse getPassport(UUID clientUserId) {
        // ONE statement for both identity-strip values (perf audit F2): they are single-row
        // lookups on the same principal, and were previously two serial round trips issued
        // unconditionally — including on the empty-state path, where they were 2 of 3 statements.
        ClientStanding standing = aggregationRepository.findStanding(clientUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        int reviewsWritten = (int) standing.reviewsWritten();
        int memberSinceYear = memberSinceYear(standing.registeredAt());

        BudgetAggregate budget = aggregationRepository.aggregateBudget(clientUserId);
        int considered = (int) budget.total();
        if (considered == 0) {
            return new PassportResponse(
                    List.of(), List.of(), null, 0, reviewsWritten, memberSinceYear);
        }

        List<DistrictCount> districtRows = aggregationRepository.findTopDistricts(clientUserId, TOP_N_PAGE);
        List<CityCount> cityRows = aggregationRepository.findTopCities(clientUserId, TOP_N_PAGE);
        DiscoveryLabels labels = resolveLocalityLabels(cityRows, districtRows);

        BudgetBand band = new BudgetBand(budget.avg(), budget.min(), budget.max(), CURRENCY_UAH);
        return new PassportResponse(
                toLabels(districtRows, DistrictCount::districtId, labels::districtLabel),
                toLabels(cityRows, CityCount::cityId, labels::cityLabel),
                band,
                considered,
                reviewsWritten,
                memberSinceYear);
    }

    /**
     * Calendar year of {@code users.created_at}, in {@link TimeZones#KYIV}.
     *
     * <p><b>Kyiv is correct here and is not a breach of the partition-phase rule.</b> Phase
     * 216's invariant forbids a Kyiv zone inside <em>instant-ordering predicates</em> (where
     * it silently shifts which rows fall on which side of a boundary). This is the opposite
     * case: a wall-clock calendar-year <em>display</em> value, which is exactly what the
     * user's own civil timezone governs. Do not "fix" this to UTC — a client who registered
     * at {@code 2025-12-31T22:30:00Z} joined in <b>2026</b> as far as they are concerned.
     */
    private static int memberSinceYear(Instant registeredAt) {
        return registeredAt.atZone(TimeZones.KYIV).getYear();
    }

    /**
     * Absolute-instant "now" for {@link #getTimeline}'s elapsed-{@code CONFIRMED} leg — {@link
     * Clock#instant()} as a fixed-offset {@link OffsetDateTime}, the SAME expression {@code
     * BookingService#resolveNow} resolves for the Phase 28.1/29.1 partition boundary
     * ({@code BookingSpecifications#partition} / {@link
     * com.beautica.booking.domain.BookingClosureRule}). Reusing this exact expression — never
     * {@code Instant.now()}/{@code OffsetDateTime.now()} (Anti-Bug §G) — is how the timeline's
     * "has this booking elapsed?" answer is guaranteed to agree with the client's «Минулі» tab:
     * both derive {@code now} from the same injected {@link Clock} bean via the same conversion,
     * so they can never disagree about which side of the boundary a given {@code endsAt} falls on.
     * {@link TimeZones#KYIV} must never appear here — this is an absolute-instant comparison.
     */
    private OffsetDateTime resolveNow() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * The only property {@code GET /clients/me/timeline} may be sorted by — mirrors
     * {@code BookingService.SORTABLE_BOOKING_PROPERTIES}, since this query shares the
     * {@code Booking} root and must not expose a wider sort surface than {@code GET /bookings/me}.
     */
    private static final Set<String> SORTABLE_TIMELINE_PROPERTIES = Set.of("startsAt");

    /**
     * Most-recent-first page of the client's CLOSED-OR-ELAPSED procedures — {@code COMPLETED}, or
     * an unclosed {@code CONFIRMED} booking whose window has already elapsed (see {@link
     * ClientAggregationRepository#findTimeline}'s javadoc for the exact predicate and why {@code
     * NOT_COMPLETED} is deliberately excluded). The {@code categoryKey} slug and the Kyiv {@code
     * LocalDate} are derived in-memory from the scalar projection (no N+1).
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
        Page<TimelineItemProjection> page =
                aggregationRepository.findTimeline(clientUserId, resolveNow(), safePageable);
        ZoneId kyiv = TimeZones.KYIV;
        // Resolved ONCE per request/page, never per row: platformCategoryLabelResolver is
        // itself backed by a 60-min cached list read (see its javadoc), but building the
        // slug->label map inside the .map(...) below would still repeat that stream/collect
        // work once per timeline item for no benefit.
        //
        // Decorative, not load-bearing: categoryName() already falls back to the raw slug via
        // getOrDefault, so a failure here degrades to that same fallback for every row instead
        // of failing the whole timeline read. Caught narrowly at Exception (never Throwable —
        // an OutOfMemoryError must still propagate) and logged at WARN so the degraded state
        // (slugs showing in the UI) is diagnosable as an infra failure, not mistaken for a data
        // problem.
        Map<String, String> categoryLabelsBySlug = resolveCategoryLabelsBySlug();
        List<TimelineItemResponse> content = page.getContent().stream()
                .map(p -> toTimelineResponse(p, kyiv, categoryLabelsBySlug))
                .toList();
        return PageResponse.of(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /**
     * Builds the slug-to-label map for one {@link #getTimeline} call, never failing the
     * timeline read if the lookup itself fails.
     *
     * <p>The label lookup is decorative — the timeline payload (bookings, dates, service
     * names, ids) does not depend on it. A cache or DB failure inside
     * {@link PlatformCategoryLabelResolver#selectableLabels()} therefore degrades to an empty
     * map rather than propagating: {@code categoryName} already falls back to the raw slug via
     * {@code getOrDefault(category, category)} on a map miss, so an empty map here reproduces
     * exactly that pre-existing, documented fallback for every row.
     */
    private Map<String, String> resolveCategoryLabelsBySlug() {
        try {
            return platformCategoryLabelResolver.selectableLabels().stream()
                    .collect(Collectors.toMap(PlatformCategoryLabel::name, PlatformCategoryLabel::displayName));
        } catch (Exception ex) {
            log.warn("Category label resolution failed for timeline read — falling back to raw "
                    + "slugs: {}", ex.getClass().getSimpleName());
            return Map.of();
        }
    }

    /**
     * Resolves the city AND district FK ids of one passport to display labels in a SINGLE
     * pass through the {@code DiscoveryLocationResolver} M2 seam. The seam already takes both
     * id sets and issues at most one query per set, so folding the two rankings into one call
     * keeps the passport at exactly one {@code resolveLabels} invocation per request — never
     * a per-row lookup, and never a second round trip through the seam (§E).
     */
    private DiscoveryLabels resolveLocalityLabels(List<CityCount> cityRows, List<DistrictCount> districtRows) {
        return discoveryLocationResolver.resolveLabels(
                distinctIds(cityRows, CityCount::cityId),
                distinctIds(districtRows, DistrictCount::districtId));
    }

    private static <T> Set<UUID> distinctIds(List<T> rows, Function<T, UUID> idAccessor) {
        return rows.stream()
                .map(idAccessor)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Maps ranked FK rows to display labels, preserving the SQL rank order. Labels come only
     * from the joined, pre-filtered taxonomy — never a literal — so no occupied-territory
     * label can appear; an id whose label fails to resolve (or resolves blank) is
     * <b>dropped</b> rather than surfaced as a raw UUID. That drop is the ban's enforcement
     * point for this endpoint.
     */
    private static <T> List<String> toLabels(
            List<T> rows, Function<T, UUID> idAccessor, Function<UUID, String> labelResolver) {
        return rows.stream()
                .map(idAccessor)
                .map(labelResolver)
                .filter(label -> label != null && !label.isBlank())
                .toList();
    }

    private TimelineItemResponse toTimelineResponse(
            TimelineItemProjection p, ZoneId kyiv, Map<String, String> categoryLabelsBySlug) {
        return new TimelineItemResponse(
                p.bookingId(),
                categoryKey(p.category()),
                categoryName(p.category(), categoryLabelsBySlug),
                p.startsAt().atZoneSameInstant(kyiv).toLocalDate(),
                p.masterId(),
                p.serviceName());
    }

    /**
     * Ukrainian display label for {@code categoryKey}'s slug, resolved against the
     * pre-built {@code categoryLabelsBySlug} map (one {@link PlatformCategoryLabelResolver}
     * read per request, built in {@link #getTimeline}, never per row).
     *
     * <p>Falls back to the raw slug — never {@code null}/blank — whenever the category is
     * {@code null} or the lookup misses (deactivated, rejected, or renamed since the booking
     * was made): the resolver's backing query filters {@code status = APPROVED AND active =
     * true}, so a booking's category can silently drop out of it. The mobile client discards
     * any timeline row where both {@code categoryKey} and {@code categoryName} are empty, so a
     * stale-but-present slug beats a "correct" null that erases the procedure from the client's
     * history.
     */
    private static String categoryName(String category, Map<String, String> categoryLabelsBySlug) {
        if (category == null) {
            return null;
        }
        return categoryLabelsBySlug.getOrDefault(category, category);
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
