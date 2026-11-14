package com.beautica.booking.service;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ClientBookingConflictException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The ONE builder of {@link ClientBookingConflictException} from a conflicting booking id, shared by
 * {@code BookingService}, {@code AppointmentService} and {@code AppointmentTransitionService} (which
 * each carried an identical private copy before).
 *
 * <p><b>Two shapes (mobile-security HIGH, 2026-11-14).</b> Client-initiated: the full graph is
 * loaded so the body can name the client's own booking (id, service, master, window).
 * Provider-initiated: the overlap scan spans every salon, so the row may be the client's booking at
 * a competitor — only its window is read ({@link BookingRepository#findTimeRangeById}, two columns,
 * no joins), and id/service/master never leave the database.
 *
 * <p><b>Why a static utility, not a bean.</b> Same shape and reason as
 * {@link BookingSlotAvailabilityGuard}: each caller passes its OWN injected repository, so no new
 * constructor dependency (and no risk of a {@code BookingService} ↔
 * {@code AppointmentTransitionService} bean cycle) is introduced.
 *
 * <p>Callers run this on the conflict path only, inside the transaction where the id was just
 * found, so the row is guaranteed to still exist; a miss is an internal error.
 */
final class ClientBookingConflicts {

    private static final String NOT_LOADED = "Conflicting booking could not be loaded";

    private ClientBookingConflicts() {
    }

    static ClientBookingConflictException build(
            BookingRepository bookingRepository, UUID conflictingBookingId, boolean initiatedByProvider) {
        if (initiatedByProvider) {
            return bookingRepository.findTimeRangeById(conflictingBookingId)
                    .map(range -> ClientBookingConflictException.windowOnly(range.startsAt(), range.endsAt()))
                    .orElseThrow(ClientBookingConflicts::notLoaded);
        }
        return bookingRepository.findByIdWithFullGraph(conflictingBookingId)
                .map(ClientBookingConflictException::new)
                .orElseThrow(ClientBookingConflicts::notLoaded);
    }

    private static BusinessException notLoaded() {
        return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, NOT_LOADED);
    }
}
