package com.beautica.client.service;

import com.beautica.client.dto.PassportResponse;
import com.beautica.client.dto.TimelineItemResponse;
import com.beautica.client.repository.BudgetAggregate;
import com.beautica.client.repository.ClientAggregationRepository;
import com.beautica.client.repository.DistrictCount;
import com.beautica.client.repository.TimelineItemProjection;
import com.beautica.common.PageResponse;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    private ClientPassportService service() {
        if (service == null) {
            service = new ClientPassportService(aggregationRepository, discoveryLocationResolver, clock);
        }
        return service;
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
            + "the ranking + district queries are never run")
    void should_returnEmptyState_when_noCompletedBookings() {
        // aggregateBudget over an empty set returns total=0 and null amounts.
        when(aggregationRepository.aggregateBudget(clientId)).thenReturn(budget(null, null, null, 0));

        PassportResponse result = service().getPassport(clientId);

        assertThat(result.favoriteProcedures()).as("empty-state procedures").isEmpty();
        assertThat(result.favoriteDistricts()).as("empty-state districts").isEmpty();
        assertThat(result.budget()).as("empty-state budget is null, not a band of nulls").isNull();
        assertThat(result.bookingsConsidered()).as("considered count").isZero();

        // Short-circuit: no ranking query, no district query, no label resolution.
        verify(aggregationRepository, never()).findTopServiceTypes(any(), any());
        verify(aggregationRepository, never()).findTopDistricts(any(), any());
        verifyNoInteractions(discoveryLocationResolver);
    }

    // ── passport: happy path (top-N + budget math + UAH + district labels) ──────

    @Test
    @DisplayName("getPassport — maps top-N procedures, batched district labels, and the UAH budget "
            + "band straight from the aggregates")
    void should_buildPassport_when_completedBookingsExist() {
        UUID districtA = UUID.randomUUID();
        UUID districtB = UUID.randomUUID();

        when(aggregationRepository.aggregateBudget(clientId))
                .thenReturn(budget("325.50", "150.00", "600.00", 7));
        when(aggregationRepository.findTopServiceTypes(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of("MANICURE", "PEDICURE", "HAIRCUT"));
        when(aggregationRepository.findTopDistricts(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of(new DistrictCount(districtA, 5), new DistrictCount(districtB, 2)));
        when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                .thenReturn(new DiscoveryLabels(
                        Map.of(),
                        Map.of(districtA, "Pechersk", districtB, "Shevchenkivskyi")));

        PassportResponse result = service().getPassport(clientId);

        assertThat(result.favoriteProcedures())
                .as("top-N service types pass through in repo order")
                .containsExactly("MANICURE", "PEDICURE", "HAIRCUT");
        assertThat(result.favoriteDistricts())
                .as("district ids resolved to labels in count order")
                .containsExactly("Pechersk", "Shevchenkivskyi");
        assertThat(result.bookingsConsidered()).as("considered = budget total").isEqualTo(7);

        assertThat(result.budget()).isNotNull();
        assertThat(result.budget().avg()).as("avg").isEqualByComparingTo("325.50");
        assertThat(result.budget().min()).as("min").isEqualByComparingTo("150.00");
        assertThat(result.budget().max()).as("max").isEqualByComparingTo("600.00");
        assertThat(result.budget().currency()).as("currency constant").isEqualTo("UAH");
    }

    @Test
    @DisplayName("getPassport — top-N queries are bounded to a size-3 page (top-3 contract)")
    void should_requestTopThreePage_when_buildingPassport() {
        when(aggregationRepository.aggregateBudget(clientId)).thenReturn(budget("100", "100", "100", 1));
        when(aggregationRepository.findTopServiceTypes(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of("MANICURE"));
        when(aggregationRepository.findTopDistricts(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of());
        when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                .thenReturn(new DiscoveryLabels(Map.of(), Map.of()));

        service().getPassport(clientId);

        ArgumentCaptor<Pageable> procPage = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> distPage = ArgumentCaptor.forClass(Pageable.class);
        verify(aggregationRepository).findTopServiceTypes(eq(clientId), procPage.capture());
        verify(aggregationRepository).findTopDistricts(eq(clientId), distPage.capture());

        assertThat(procPage.getValue().getPageSize()).as("procedures page size").isEqualTo(3);
        assertThat(procPage.getValue().getPageNumber()).isZero();
        assertThat(distPage.getValue().getPageSize()).as("districts page size").isEqualTo(3);
    }

    @Test
    @DisplayName("getPassport — district ids whose label fails to resolve are dropped, never surfaced as raw ids")
    void should_dropUnresolvableDistrict_when_labelMissing() {
        UUID resolvable = UUID.randomUUID();
        UUID unresolvable = UUID.randomUUID();

        when(aggregationRepository.aggregateBudget(clientId)).thenReturn(budget("200", "200", "200", 3));
        when(aggregationRepository.findTopServiceTypes(eq(clientId), any(Pageable.class)))
                .thenReturn(List.of("MANICURE"));
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
