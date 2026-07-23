package com.beautica.booking.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for the client-initiated visit-cancel endpoint
 * {@code PATCH /api/v1/appointments/{id}/cancel} (BE-4).
 *
 * <p>{@code clientCancellationNote} is OPTIONAL for all clients (CLAUDE.md booking-notes contract —
 * notes are optional everywhere); {@code null}/blank normalizes to {@code null} via
 * {@code BookingComments.normalize()} and persists cleanly against the V124
 * {@code chk_appointment_client_cancellation_note_status} CHECK (which allows NULL regardless of
 * status). The note lives on the appointment HEADER only — the child booking items carry no note.
 *
 * <p>Unlike the single-service {@link CancelBookingRequest}, this body carries NO
 * {@code cancellationReason}: a client-initiated visit cancel always resolves to
 * {@code CLIENT_CANCELLED}, set by the service — never chosen by the caller.
 */
public record AppointmentCancelRequest(
        // max=1000 mirrors appointments.client_cancellation_note VARCHAR(1000) (§A) — a raw-length
        // ceiling guarding against an oversized payload. Control-char ban prevents an embedded NUL
        // reaching the DB as a 500 instead of a 400; line breaks (\n, \r) and tabs (\t) are permitted
        // — this is a long-form free-text field. Mirrors CancelBookingRequest#comment exactly.
        @Size(max = 1000, message = "Note must be at most 1000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Note must not contain control characters other than line breaks and tabs")
        String clientCancellationNote
) {}
