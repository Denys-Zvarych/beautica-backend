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
 *
 * <p><b>Reviewability is not status-only.</b> A {@code CONFIRMED} booking whose {@code endsAt}
 * has already elapsed is ALSO reviewable — no {@code @Scheduled} job ever auto-transitions an
 * unclosed booking out of {@code CONFIRMED} (see {@code BookingClosureRule}'s header javadoc), so
 * gating review eligibility on {@code COMPLETED} alone left every unclosed-but-elapsed booking
 * permanently unreviewable once it aged into the client's "Past" tab. See {@code
 * BookingClosureRule#isReviewEligible} for the full rule; {@code NOT_COMPLETED}/{@code
 * CANCELLED}/{@code DECLINED} stay unreviewable regardless of time.
 */
public enum BookingStatus {
    CONFIRMED,
    DECLINED,
    COMPLETED,
    NOT_COMPLETED,
    CANCELLED
}
