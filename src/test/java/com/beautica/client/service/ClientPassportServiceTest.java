package com.beautica.client.service;

import com.beautica.client.dto.PassportResponse;
import com.beautica.client.dto.TimelineItemResponse;
import com.beautica.client.repository.BudgetAggregate;
import com.beautica.client.repository.CityCount;
import com.beautica.client.repository.ClientAggregationRepository;
import com.beautica.client.repository.ClientStanding;
import com.beautica.client.repository.DistrictCount;
import com.beautica.client.repository.TimelineItemProjection;
import com.beautica.common.PageResponse;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Mockito unit test for {@link ClientPassportService} (Phase 19.5 BEAUTI PASSPORT +
 * BEAUTY TIMELINE). The aggregation repository and the discovery label resolver are
 * mocked; this test pins the pure mapping/aggregation contract of the service in
 * isolation — empty-state short-circuit, top-N pass-through, budget band assembly with
 * the fixed UAH currency, the empty-set ban guard (budget query runs first), the batched
 * district-label resolution, the in-memory Kyiv {@code LocalDate} conversion, and the
 * category-key slugging. ASCII-only fixture data throughout.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClientPassportService — unit")
class ClientPassportServiceTest {

    @Mock
    private ClientAggregationRepository aggregationRepository;

    @Mock
    private DiscoveryLocationResolver discoveryLocationResolver;

    // Fixed clock — the service does not branch on time, but the constructor requires one.
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T12:00:00Z"), ZoneOffset.UTC);

    private ClientPassportService service;

    private final UUID clientId = UUID.randomUUID();

    /** Registration instant used by every passport test unless the test pins its own. */
    private static final Instant REGISTERED_AT = Instant.parse("2024-03-04T10:00:00Z");

    private ClientPassportService service() {
        if (service == null) {
            service = new ClientPassportService(
                    aggregationRepository, discoveryLocationResolver, clock);
        }
        return service;
    }

    /**
     * Stubs the SINGLE identity-standing read {@code getPassport} always performs. Two separate
     * repository round trips were merged into one query by the 2026-08 perf audit (F2), so this
     * helper now stubs one call — that collapse is itself part of the contract under test.
     */
    private void stubIdentity(long reviewsWritten, Instant createdAt) {
        when(aggregationRepository.findStanding(clientId))
                .thenReturn(Optional.of(new ClientStanding(createdAt, reviewsWritten)));
    }

    private static BudgetAggregate budget(String avg, String min, String max, long total) {
        return new BudgetAggregate(
                avg == null ? null : new BigDecimal(avg),
                min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max),
                total);
    }

    // ── passport: empty-state short-circuit ────────────────────────────────────

    @Test
    @DisplayName("getPassport — empty state (no COMPLETED): empty lists, null budget, considered 0; "
            + "the ranking + locality queries are never run")
    void should_returnEmptyState_when_noCompletedBookings() {
        stubIdentity(0L, REGISTERED_AT);
        // aggregateBudget over an empty set returns total=0 and null amounts.
        when(aggregationRepository.aggregateBudget(clientId)).thenReturn(budget(null, null, null, 0));

        PassportResponse result = service().getPassport(clientId);

        assertThat(result.favoriteDistricts()).as("empty-state districts").isEmpty();
        assertThat(result.favoriteCities()).as("empty-state cities").isEmpty();
        assertThat(result.budget()).as("empty-state budget is null, not a band of nulls").isNull();
        assertThat(result.bookingsConsidered()).as("considered count").isZero();

        // Short-circuit: no ranking query, no locality queries, no label resolution.
        verify(aggregationRepository, never()).findTopDistricts(any(), any());
        verify(aggregationRepository, never()).findTopCities(any(), any());
        verifyNoInteractions(discoveryLocationResolver);
    }

    // ── passport: identity standing (Phase 245) ────────────────────────────────

    @Test
    @DisplayName("getPassport — the identity strip is populated in the EMPTY state too: a brand-new "
            + "client still has a real registration year and may already have written reviews")
    void should_populateIdentityFields_when_noCompletedBookings() {
        stubIdentity(4L, Instant.parse("2023-08-09T07:15:00Z"));
        when(aggregationRepository.aggregateBudget(clientId)).thenReturn(budget(null, null, null, 0));

        PassportResponse result = service().getPassport(clientId);

        assertThat(result.bookingsConsidered()).as("precondition: this IS the empty state").isZero();
        assertThat(result.reviewsWritten())
                .as("reviews written are NOT gated behind having COMPLETED bookings")
                .isEqualTo(4);
        assertThat(result.memberSinceYear())
                .as("real registration year, never the current year")
                .isEqualTo(2023);
    }

    @Test
    @DisplayName("getPassport — reviewsWritten counts only the principal's own reviews (the repository "
            + "is queried with the principal id, never a wider scope)")
    void should_countOnlyThisClientsReviews_when_otherClientsHaveReviews() {
        // The mock answers 9 ONLY for this client id; any other id would fall through to 0.
        stubIdentity(9L, REGISTERED_AT);
        when(aggregationRepository.aggregateBudget(clientId)).thenReturn(budget(null, null, null, 0));

        PassportResponse result = service().getPassport(clientId);

        assertThat(result.reviewsWritten()).isEqualTo(9);
        // Scoped to the principal: the mock answers only for this client id.
        verify(aggregationRepository).findStanding(clientId);
    }

    @Test
    @DisplayName("getPassport — memberSinceYear is the Kyiv calendar year: a 2025-12-31T22:30Z "
            + "registration is already 2026 in Kyiv (UTC+2 winter)")
    void should_deriveMemberSinceYearInKyiv_when_createdAtIsNewYearUtcBoundary() {
        stubIdentity(0L, Instant.parse("2025-12-31T22:30:00Z"));
        when(aggregationRepository.aggregateBudget(clientId)).thenReturn(budget(null, null, null, 0));

        PassportResponse result = service().getPassport(clientId);

        assertThat(result.memberSinceYear())
                .as("Kyiv wall-clock year (2026), not the UTC year (2025) — and independent of the "
                        + "JVM default zone, so this holds under TZ=UTC and TZ=Europe/Kyiv alike")
                .isEqualTo(2026);
    }

    @Test
    @DisplayName("getPassport — a client row that cannot be resolved raises NotFoundException rather "
            + "than fabricating a year")
    void should_throwNotFound_when_clientRowMissing() {
        when(aggregationRepository.findStanding(clientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getPassport(clientId))
                .isInstanceOf(NotFoundException.class);

        // No fabricated year, and no booking aggregation attempted for a user that does not exist.
        verify(aggregationRepository, never()).aggregateBudget(any());
    }

    // ── passport: happy path (top-N + budget math + UAH + district labels) ──────

    @Test
    @DisplayName("getPassport — maps batched district labels and the UAH budget "
            + "band straight from the aggregates")
    void should_buildPassport_when_completedBookingsExist() {
        UUID districtA = UUID.randomUUID();
        UUID districtB = UUID.randomUUID();
        UUID cityA = UUID.randomUUID();
        UUID cityB = UUID.randomUUID();

        stubIdentity(12L, REGISTERED_AT);
        when(aggregationRepository.aggregateBudget(clientId))
                .thenReturn(budget("325.50", "150.00", "600.00", 7));
        when(aggregationRepository.findTopDistricts(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of(new DistrictCount(districtA, 5), new DistrictCount(districtB, 2)));
        when(aggregationRepository.findTopCities(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of(new CityCount(cityA, 6), new CityCount(cityB, 1)));
        when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                .thenReturn(new DiscoveryLabels(
                        Map.of(cityA, "Kyiv", cityB, "Lviv"),
                        Map.of(districtA, "Pechersk", districtB, "Shevchenkivskyi")));

        PassportResponse result = service().getPassport(clientId);

        assertThat(result.favoriteDistricts())
                .as("district ids resolved to labels in count order")
                .containsExactly("Pechersk", "Shevchenkivskyi");
        assertThat(result.favoriteCities())
                .as("city ids resolved to labels in count order")
                .containsExactly("Kyiv", "Lviv");
        assertThat(result.bookingsConsidered()).as("considered = budget total").isEqualTo(7);
        assertThat(result.reviewsWritten()).as("identity standing on the non-empty branch too").isEqualTo(12);
        assertThat(result.memberSinceYear()).isEqualTo(2024);

        assertThat(result.budget()).isNotNull();
        assertThat(result.budget().avg()).as("avg").isEqualByComparingTo("325.50");
        assertThat(result.budget().min()).as("min").isEqualByComparingTo("150.00");
        assertThat(result.budget().max()).as("max").isEqualByComparingTo("600.00");
        assertThat(result.budget().currency()).as("currency constant").isEqualTo("UAH");
    }

    @Test
    @DisplayName("getPassport — top-N queries are bounded to a size-3 page (top-3 contract)")
    void should_requestTopThreePage_when_buildingPassport() {
        stubIdentity(0L, REGISTERED_AT);
        when(aggregationRepository.aggregateBudget(clientId)).thenReturn(budget("100", "100", "100", 1));
        when(aggregationRepository.findTopDistricts(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of());
        when(aggregationRepository.findTopCities(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of());
        when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                .thenReturn(new DiscoveryLabels(Map.of(), Map.of()));

        service().getPassport(clientId);

        ArgumentCaptor<Pageable> distPage = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> cityPage = ArgumentCaptor.forClass(Pageable.class);
        verify(aggregationRepository).findTopDistricts(eq(clientId), distPage.capture());
        verify(aggregationRepository).findTopCities(eq(clientId), cityPage.capture());

        assertThat(distPage.getValue().getPageSize()).as("districts page size").isEqualTo(3);
        assertThat(cityPage.getValue().getPageSize()).as("cities page size").isEqualTo(3);
        assertThat(cityPage.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("getPassport — district ids whose label fails to resolve are dropped, never surfaced as raw ids")
    void should_dropUnresolvableDistrict_when_labelMissing() {
        UUID resolvable = UUID.randomUUID();
        UUID unresolvable = UUID.randomUUID();

        stubIdentity(0L, REGISTERED_AT);
        when(aggregationRepository.aggregateBudget(clientId)).thenReturn(budget("200", "200", "200", 3));
        when(aggregationRepository.findTopDistricts(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of(new DistrictCount(resolvable, 2), new DistrictCount(unresolvable, 1)));
        // Only the first id resolves; the second yields no label.
        when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                .thenReturn(new DiscoveryLabels(Map.of(), Map.of(resolvable, "Obolonskyi")));

        PassportResponse result = service().getPassport(clientId);

        assertThat(result.favoriteDistricts())
                .as("unresolved district dropped — no raw UUID leaks")
                .containsExactly("Obolonskyi");
    }

    @Test
    @DisplayName("getPassport — city ids whose label fails to resolve are dropped, never surfaced as raw ids "
            + "(occupied-territory ban enforcement point)")
    void should_dropUnresolvableCity_when_labelMissing() {
        UUID resolvable = UUID.randomUUID();
        UUID unresolvable = UUID.randomUUID();

        stubIdentity(0L, REGISTERED_AT);
        when(aggregationRepository.aggregateBudget(clientId)).thenReturn(budget("200", "200", "200", 3));
        when(aggregationRepository.findTopCities(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of(new CityCount(resolvable, 2), new CityCount(unresolvable, 1)));
        // Only the first id resolves; the second yields no label (e.g. filtered out of the taxonomy).
        when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                .thenReturn(new DiscoveryLabels(Map.of(resolvable, "Kyiv"), Map.of()));

        PassportResponse result = service().getPassport(clientId);

        assertThat(result.favoriteCities())
                .as("unresolved city dropped — no raw UUID leaks")
                .containsExactly("Kyiv");
    }

    @Test
    @DisplayName("getPassport — label resolution is ONE batched call carrying both id sets (no N+1, "
            + "no second trip through the M2 seam)")
    void should_batchLabelResolution_when_buildingPassport() {
        UUID districtA = UUID.randomUUID();
        UUID cityA = UUID.randomUUID();
        UUID cityB = UUID.randomUUID();

        stubIdentity(0L, REGISTERED_AT);
        when(aggregationRepository.aggregateBudget(clientId)).thenReturn(budget("200", "200", "200", 3));
        when(aggregationRepository.findTopDistricts(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of(new DistrictCount(districtA, 2)));
        when(aggregationRepository.findTopCities(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of(new CityCount(cityA, 2), new CityCount(cityB, 1)));
        when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                .thenReturn(new DiscoveryLabels(Map.of(cityA, "Kyiv", cityB, "Lviv"), Map.of(districtA, "Pechersk")));

        service().getPassport(clientId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> cityIds = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> districtIds = ArgumentCaptor.forClass(Collection.class);
        // times(1) is the assertion: two separate resolveDistrictLabels/resolveCityLabels calls would fail here.
        verify(discoveryLocationResolver, times(1)).resolveLabels(cityIds.capture(), districtIds.capture());
        verifyNoMoreInteractions(discoveryLocationResolver);

        assertThat(cityIds.getValue()).containsExactly(cityA, cityB);
        assertThat(districtIds.getValue()).containsExactly(districtA);
    }

    // ── timeline: ordering preserved, Kyiv date, category key slug ──────────────

    @Test
    @DisplayName("getTimeline — maps the projection page to responses, converting startsAt to a Kyiv "
            + "LocalDate and slugging the category key")
    void should_mapTimeline_when_completedBookingsExist() {
        UUID bookingId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        // 2026-06-18T23:30 UTC is already 2026-06-19 in Kyiv (UTC+3 summer) — proves Kyiv conversion.
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 6, 18, 23, 30, 0, 0, ZoneOffset.UTC);
        TimelineItemProjection projection =
                new TimelineItemProjection(bookingId, "MANICURE", startsAt, masterId, "Classic Manicure");
        Page<TimelineItemProjection> page =
                new PageImpl<>(List.of(projection), PageRequest.of(0, 20), 1);
        when(aggregationRepository.findTimeline(eq(clientId), any(Pageable.class))).thenReturn(page);

        PageResponse<TimelineItemResponse> result = service().getTimeline(clientId, PageRequest.of(0, 20));

        assertThat(result.data()).hasSize(1);
        TimelineItemResponse item = result.data().get(0);
        assertThat(item.bookingId()).isEqualTo(bookingId);
        assertThat(item.masterId()).isEqualTo(masterId);
        assertThat(item.serviceName()).isEqualTo("Classic Manicure");
        assertThat(item.categoryKey()).as("uppercase slug of category").isEqualTo("MANICURE");
        assertThat(item.categoryName()).isEqualTo("MANICURE");
        assertThat(item.date()).as("Kyiv local date rolls past midnight from a late-UTC instant")
                .isEqualTo(LocalDate.of(2026, 6, 19));
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("getTimeline — winter (EET, UTC+2) near-midnight instant maps to the correct Kyiv date")
    void should_useKyivWinterOffset_when_startsAtNearMidnightUtcInJanuary() {
        UUID bookingId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        // 2026-01-18T22:30 UTC is already 2026-01-19T00:30 in Kyiv (UTC+2 winter/EET) —
        // proves the conversion picks the Kyiv calendar date, not the UTC date, at the +2 offset.
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 1, 18, 22, 30, 0, 0, ZoneOffset.UTC);
        TimelineItemProjection projection =
                new TimelineItemProjection(bookingId, "HAIR", startsAt, masterId, "Winter Haircut");
        Page<TimelineItemProjection> page =
                new PageImpl<>(List.of(projection), PageRequest.of(0, 20), 1);
        when(aggregationRepository.findTimeline(eq(clientId), any(Pageable.class))).thenReturn(page);

        PageResponse<TimelineItemResponse> result = service().getTimeline(clientId, PageRequest.of(0, 20));

        TimelineItemResponse item = result.data().get(0);
        assertThat(item.date())
                .as("Kyiv local date rolls past midnight at the +2 winter (EET) offset, not the UTC date")
                .isEqualTo(LocalDate.of(2026, 1, 19));
    }

    @Test
    @DisplayName("getTimeline — a null service category slugs to the UNKNOWN key")
    void should_useUnknownKey_when_categoryNull() {
        OffsetDateTime startsAt = OffsetDateTime.of(2026, 1, 10, 9, 0, 0, 0, ZoneOffset.UTC);
        TimelineItemProjection projection = new TimelineItemProjection(
                UUID.randomUUID(), null, startsAt, UUID.randomUUID(), "Mystery Service");
        when(aggregationRepository.findTimeline(eq(clientId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(projection), PageRequest.of(0, 20), 1));

        PageResponse<TimelineItemResponse> result = service().getTimeline(clientId, PageRequest.of(0, 20));

        assertThat(result.data().get(0).categoryKey()).isEqualTo("UNKNOWN");
        assertThat(result.data().get(0).categoryName()).as("null category surfaced as-is").isNull();
    }

    @Test
    @DisplayName("getTimeline — empty page maps to an empty PageResponse")
    void should_returnEmptyPage_when_noTimelineItems() {
        when(aggregationRepository.findTimeline(eq(clientId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PageResponse<TimelineItemResponse> result = service().getTimeline(clientId, PageRequest.of(0, 20));

        assertThat(result.data()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }
}
