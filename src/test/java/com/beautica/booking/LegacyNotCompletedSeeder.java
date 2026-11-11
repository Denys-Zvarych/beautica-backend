package com.beautica.booking;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Phase 475 — seeds a legacy {@code NOT_COMPLETED} (no-show) row directly in the database.
 *
 * <p>No route sets {@code NOT_COMPLETED} any more (BOOK-D12): both {@code PATCH /bookings/{id}/not-complete}
 * and the whole-visit {@code PATCH /appointments/{id}/not-complete} were removed. Rows written before the
 * removal still exist and must stay readable, filterable and displayed (D4), so tests that used the route
 * only as SETUP write the row the way the route used to: status {@code NOT_COMPLETED}, reason
 * {@code CLIENT_NO_SHOW}, and the optional provider note. Nothing else the route did (outbox row, in-app
 * notification, cache eviction) is reproduced — a test that needs one of those was pinning the removed
 * transition itself and is deleted instead.
 */
final class LegacyNotCompletedSeeder {

    private static final String NO_SHOW_REASON = "CLIENT_NO_SHOW";

    private LegacyNotCompletedSeeder() {
    }

    /**
     * Moves one standalone booking (or one visit item) to {@code NOT_COMPLETED} with reason
     * {@code CLIENT_NO_SHOW}. {@code providerComment} may be {@code null}; pass the already-normalized text.
     */
    static void markNotCompleted(JdbcTemplate jdbc, UUID bookingId, String providerComment) {
        int updated = jdbc.update(
                "UPDATE bookings SET status = 'NOT_COMPLETED', cancellation_reason = ?, provider_comment = ? "
                        + "WHERE id = ? AND status = 'CONFIRMED'",
                NO_SHOW_REASON, providerComment, bookingId);
        if (updated != 1) {
            throw new IllegalStateException("Expected exactly one CONFIRMED booking " + bookingId + ", updated " + updated);
        }
    }
}
