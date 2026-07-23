package com.beautica.booking.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the ONE behaviour {@link BookingRepositoryCustomImpl} must never lose: an {@code Unpaged}
 * {@link Pageable} is REFUSED, not quietly executed.
 *
 * <p>Why this deserves its own test rather than a comment. The tempting shape here is
 * {@code if (pageable.isPaged()) { setFirstResult(...); setMaxResults(...); }} — which reads as a
 * defensive guard and is the opposite: it downgrades a loud failure into a silent unbounded
 * {@code SELECT b.id} over a caller's entire booking history, every id materialised into a
 * {@code List} and then hydrated (Anti-Bug §E-3). No HTTP path can produce an {@code Unpaged}
 * ({@code @PageableDefault} + {@code spring.data.web.pageable.max-page-size}), but
 * {@code BookingService#normalizeBookingSort} deliberately PRESERVES {@code Unpaged} for direct
 * in-process callers, so a future service-to-service caller is exactly who this catches.
 *
 * <p>Deliberately a PLAIN JUnit test — no Spring context, no Testcontainers (Anti-Bug §M-1). The
 * guard runs before the first {@code EntityManager} touch, which is itself part of the contract:
 * the request is rejected before any query is built, so a {@code null} {@code EntityManager} is
 * sufficient here and a database would prove nothing extra.
 */
class BookingRepositoryCustomImplUnpagedTest {

    private static final Pageable UNPAGED_WITH_SORT =
            Pageable.unpaged(Sort.by(Sort.Direction.DESC, "startsAt").and(Sort.by(Sort.Direction.ASC, "id")));

    private final BookingRepositoryCustomImpl repository = new BookingRepositoryCustomImpl();

    @Test
    @DisplayName("findIdsByMasterIdFiltered rejects an unpaged Pageable instead of scanning unbounded")
    void should_throwIllegalArgument_when_masterPathIsCalledUnpaged() {
        UUID masterId = UUID.randomUUID();

        assertThatThrownBy(() -> repository.findIdsByMasterIdFiltered(
                masterId, null, null, null, null, UNPAGED_WITH_SORT))
                .as("an unpaged provider id-page must fail loudly, never degrade to an unbounded scan")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(BookingRepositoryCustomImpl.UNPAGED_REJECTED_MESSAGE);
    }

    @Test
    @DisplayName("findIdsBySalonIdsFiltered rejects an unpaged Pageable instead of scanning unbounded")
    void should_throwIllegalArgument_when_salonPathIsCalledUnpaged() {
        List<UUID> salonIds = List.of(UUID.randomUUID());

        assertThatThrownBy(() -> repository.findIdsBySalonIdsFiltered(
                salonIds, null, null, null, null, UNPAGED_WITH_SORT))
                .as("the salon-owner path shares findIdPage, so it shares the refusal")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(BookingRepositoryCustomImpl.UNPAGED_REJECTED_MESSAGE);
    }

    @Test
    @DisplayName("findIdsByClientIdFiltered rejects an unpaged Pageable instead of scanning unbounded")
    void should_throwIllegalArgument_when_clientPathIsCalledUnpaged() {
        UUID clientId = UUID.randomUUID();

        assertThatThrownBy(() -> repository.findIdsByClientIdFiltered(
                clientId, null, null, null, null, UNPAGED_WITH_SORT))
                .as("the client path shares findIdPage, so it shares the refusal")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(BookingRepositoryCustomImpl.UNPAGED_REJECTED_MESSAGE);
    }

    @Test
    @DisplayName("a bare Pageable.unpaged() (no Sort at all) is refused by the same guard")
    void should_throwIllegalArgument_when_unpagedCarriesNoSort() {
        UUID masterId = UUID.randomUUID();

        // Pageable.unpaged() with an UNSORTED sort: the guard must fire on the paging property,
        // never depend on the Sort having been normalized first.
        assertThatThrownBy(() -> repository.findIdsByMasterIdFiltered(
                masterId, null, null, null, null, Pageable.unpaged()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(BookingRepositoryCustomImpl.UNPAGED_REJECTED_MESSAGE);
    }
}
