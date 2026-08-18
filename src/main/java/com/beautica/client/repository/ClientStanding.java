package com.beautica.client.repository;

import java.time.Instant;

/**
 * The BEAUTY PASSPORT's identity-strip values for one client, read in a SINGLE statement
 * (Phase 245 / 31.2; merged by the 2026-08 perf audit F2).
 *
 * <p>These two values used to be two unconditional, SERIAL round trips —
 * {@code ReviewRepository.countByClientId} followed by {@code UserRepository.findCreatedAtById}
 * — issued on <em>every</em> passport load including the empty state, where they were 2 of the
 * only 3 statements. Both are single-row lookups keyed on the same principal, so they are now
 * one query: the registration instant off {@code users}, with the authored-review count as a
 * correlated scalar subquery. See {@link ClientAggregationRepository#findStanding}.
 *
 * <p>An absent row means the principal's user record does not exist; the service turns that
 * into a {@code NotFoundException} rather than fabricating a registration year. It is never
 * a zero-valued default.
 *
 * @param registeredAt   {@code users.created_at} — NOT NULL, so never null for a live row.
 *                       The service renders its calendar year in {@code Europe/Kyiv}.
 * @param reviewsWritten number of reviews this client has <em>authored</em> (client → provider).
 *                       Distinct from reviews <em>received</em>, which live on
 *                       {@code client_reviews}.
 */
public record ClientStanding(
        Instant registeredAt,
        long reviewsWritten
) {
}
