package com.beautica.booking.enums;

/**
 * Booking lifecycle status (track 24.x — auto-confirm; {@code PENDING} retired).
 *
 * <pre>
 * POST /bookings (APP, client)     ─┐
 * POST /book/{slug}/booking (LINK) ─┴─► CONFIRMED   (created here, ALWAYS — auto-approved)
 *                                         ├─ COMPLETED      /complete      (provider)  → reviewable
 *                                         ├─ NOT_COMPLETED  /not-complete  (provider, no-show)
 *                                         ├─ CANCELLED      /cancel        (CLIENT backs out)
 *                                         └─ DECLINED       /decline       (PROVIDER backs out)
 *
 * /reschedule (CLIENT): CONFIRMED ──► CONFIRMED (no revert; overlap checks still run)
 * </pre>
 *
 * <p>{@code CANCELLED} = client backed out; {@code DECLINED} = provider backed out. The
 * distinction is load-bearing: the client's "Мої записи" must distinguish "ви скасували" from
 * "салон скасував".
 */
public enum BookingStatus {
    CONFIRMED,
    DECLINED,
    COMPLETED,
    NOT_COMPLETED,
    CANCELLED
}
