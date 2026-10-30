package com.beautica.booking.repository;

import java.util.UUID;

/**
 * Phase 402 D3 — one anonymised guest booking and the visit it belongs to ({@code appointmentId} {@code null} for a
 * single booking with no visit header). The review-invite skip keys a visit item's invite by its visit and a single
 * booking's invite by the booking itself (phase 400 D7).
 */
public interface GuestBookingSubject {

    UUID getBookingId();

    UUID getAppointmentId();
}
