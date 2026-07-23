package com.beautica.booking.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Shared request body for the two provider-terminated visit transitions (BE-4):
 * {@code PATCH /api/v1/appointments/{id}/decline} and
 * {@code PATCH /api/v1/appointments/{id}/not-complete}.
 *
 * <p>{@code providerComment} is OPTIONAL for all roles (CLAUDE.md — Phase 25.9 reversed the earlier
 * "required, ≥10 chars" rule; a provider may decline or record a no-show without a note);
 * {@code null}/blank normalizes to {@code null} via {@code BookingComments.normalize()} and persists
 * cleanly against the V124 {@code chk_appointment_provider_comment_status} CHECK (which allows NULL
 * regardless of status). The note lives on the appointment HEADER only. Per the locked booking-notes
 * decision it is mutually visible — shown to the client on both DECLINED and NOT_COMPLETED — which is
 * intentional, not a leak.
 *
 * <p>Unlike the single-service {@link StatusUpdateRequest}, this body carries NO
 * {@code cancellationReason}: a visit decline always resolves to {@code PROVIDER_UNAVAILABLE} and a
 * visit no-show to {@code CLIENT_NO_SHOW}, both set by the service — never chosen by the caller.
 */
public record AppointmentProviderNoteRequest(
        // max=1000 mirrors appointments.provider_comment VARCHAR(1000) (§A). Control-char ban
        // prevents an embedded NUL reaching the DB as a 500 instead of a 400; line breaks (\n, \r)
        // and tabs (\t) are permitted. Mirrors StatusUpdateRequest#comment exactly.
        @Size(max = 1000, message = "Comment must be at most 1000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Comment must not contain control characters other than line breaks and tabs")
        String providerComment
) {}
