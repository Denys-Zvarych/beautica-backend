package com.beautica.booking.service;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.PostLockRescheduleCheck;
import com.beautica.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Post-master-advisory-lock re-check for every RESCHEDULE path — the reschedule analogue of
 * {@link PostLockSlotGuard}. Closes the reschedule-vs-cascade race: the pre-lock
 * {@code isStillConfirmed} probe passes, the reschedule then queues on the master advisory lock while
 * a master self-delete / salon closure holding it DECLINES the booking, and on grant the reschedule
 * would move the declined row and write a spurious feed + outbox row. The freshness re-read runs in
 * the SAME statement as the overlap check (a native scalar, never the possibly-stale managed entity),
 * so the guard adds no round trip. Freshness is checked FIRST (409 "changed concurrently"), then
 * overlap (409 "Slot not available"). Static utility, same shape as its package siblings.
 */
final class PostLockRescheduleGuard {

    private PostLockRescheduleGuard() {
    }

    /** Standalone booking / single visit item: the moving row must still be CONFIRMED and its window free. */
    static void assertBookingStillConfirmedAndFree(
            BookingRepository repo, UUID masterId, OffsetDateTime startsAt, OffsetDateTime endsAt,
            UUID bookingId, String staleMessage) {
        evaluate(repo.findPostLockConfirmedAndOverlapExcluding(masterId, startsAt, endsAt, bookingId), staleMessage);
    }

    /** Whole visit: EVERY moved item must still be CONFIRMED and the new block free. */
    static void assertVisitItemsStillConfirmedAndFree(
            BookingRepository repo, UUID masterId, OffsetDateTime startsAt, OffsetDateTime endsAt,
            UUID appointmentId, List<UUID> targetIds, String staleMessage) {
        evaluate(repo.findPostLockAllConfirmedAndOverlapExcludingAppointment(
                masterId, startsAt, endsAt, appointmentId, targetIds, targetIds.size()), staleMessage);
    }

    private static void evaluate(PostLockRescheduleCheck check, String staleMessage) {
        if (check == null || !Boolean.TRUE.equals(check.getStillConfirmed())) {
            throw new BusinessException(HttpStatus.CONFLICT, staleMessage);
        }
        if (!Boolean.FALSE.equals(check.getOverlapExists())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }
    }
}
