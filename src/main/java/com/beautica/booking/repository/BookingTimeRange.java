package com.beautica.booking.repository;

import java.time.OffsetDateTime;

/**
 * Read-only projection of a booking's occupied interval — the ONLY two columns the availability
 * computation ever reads (Perf MEDIUM-1, backlog P1 pattern).
 *
 * <p>{@code SlotCalculationService#loadOccupiedByDay} previously consumed
 * {@code List<Booking>} from a {@code SELECT *} native query: 20+ columns hydrated into managed
 * entities (guest name/phone, cancel token, comments, idempotency key, the whole audit block) added to
 * the persistence context, for a consumer that only ever calls {@code getStartsAt()} /
 * {@code getEndsAt()}. On a fully-booked master's 180-day horizon that is hundreds of pointless entity
 * hydrations — and it needlessly pulled guest PII into memory on a read path that has no business
 * seeing it.
 *
 * <p>Implemented as a JPQL constructor expression rather than a native-query interface projection:
 * Spring Data maps native-query interface projections by {@code Tuple} alias, which forces
 * case-preserving quoted aliases and silently returns {@code null} on a mismatch, whereas
 * {@code SELECT new ...BookingTimeRange(b.startsAt, b.endsAt)} is compile-time-checked, emits exactly
 * the same two-column SQL, and hydrates no managed state. The generated predicate is unchanged
 * ({@code master_id}, {@code status IN (...)}, {@code starts_at &lt; ?}, {@code ends_at &gt; ?}), so the
 * query still rides the existing {@code idx_bookings_master_slot_overlap} index — no migration.
 */
public record BookingTimeRange(OffsetDateTime startsAt, OffsetDateTime endsAt) {
}
