package com.beautica.booking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Phase 436 — OPTIONAL body of {@code POST /api/v1/book/cancel/{token}}.
 *
 * <p>No body, {@code {}} or {@code "bookingIds": null} cancels every cancellable line (the pre-436 behaviour). An
 * EMPTY {@code bookingIds} is 400 (a guest must never lose the whole visit through an empty selection). Otherwise only
 * the named lines are cancelled, all or nothing. Duplicates are rejected with 400 by the service. No note is accepted (locked decision 2026-11-05).
 *
 * @param bookingIds the service lines to cancel, at most {@value #MAX_BOOKING_IDS}
 */
public record GuestCancelRequest(
        @Size(max = GuestCancelRequest.MAX_BOOKING_IDS, message = "at most 10 services can be cancelled at once")
        List<@NotNull UUID> bookingIds
) {

    /** Same cap as the services of one guest visit. */
    public static final int MAX_BOOKING_IDS = 10;
}
