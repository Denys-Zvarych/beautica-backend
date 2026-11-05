package com.beautica.booking.service;

import com.beautica.booking.dto.CancelTokenItemResponse;
import com.beautica.booking.dto.GuestCancelRequest;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 436 — the rules shared by both guest cancel-by-link paths ({@link BookingCancellationService} for a single
 * booking, {@link GuestVisitCancellationService} for a visit): request-id validation, per-line cancellability, the
 * page's line view and the error messages. Stateless; the cancel cutoff ({@code now + cancel window}) is passed in by
 * the caller, which owns the {@code Clock}.
 */
final class GuestCancelSelection {

    static final String TOKEN_NOT_FOUND = "Cancel token not found";

    private GuestCancelSelection() {
    }

    /**
     * The requested ids, or an empty list for «cancel everything cancellable» — only when {@code bookingIds} is
     * {@code null} (no body, or a body without the {@code bookingIds} key: the pre-436 client).
     *
     * @throws BusinessException 400 on an EMPTY selection (a guest must never lose the whole visit through an empty
     *                           selection), more than {@value GuestCancelRequest#MAX_BOOKING_IDS} ids, a null or a
     *                           duplicate id
     */
    static List<UUID> requestedIds(List<UUID> bookingIds) {
        if (bookingIds == null) {
            return List.of();
        }
        if (bookingIds.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "bookingIds must not be empty");
        }
        if (bookingIds.size() > GuestCancelRequest.MAX_BOOKING_IDS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "at most 10 services can be cancelled at once");
        }
        // stream null check: List.of(...).contains(null) throws NPE
        if (bookingIds.stream().anyMatch(Objects::isNull) || new HashSet<>(bookingIds).size() != bookingIds.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "bookingIds must be distinct");
        }
        return List.copyOf(bookingIds);
    }

    /** A line is cancellable while it is {@code CONFIRMED} and starts after {@code cutoff}. */
    static boolean cancellable(Booking booking, BookingStatus status, OffsetDateTime cutoff) {
        return status == BookingStatus.CONFIRMED && booking.getStartsAt().isAfter(cutoff);
    }

    static CancelTokenItemResponse item(Booking booking, String serviceName, BookingStatus status, boolean live,
                                        OffsetDateTime cutoff) {
        return new CancelTokenItemResponse(
                booking.getId(),
                serviceName,
                booking.getStartsAt(),
                booking.getDurationMinutesAtBooking(),
                status,
                live && cancellable(booking, status, cutoff));
    }

    static NotFoundException tokenNotFound() {
        return new NotFoundException(TOKEN_NOT_FOUND);
    }

    /** 422 — the pre-436 message when the window has closed; a dedicated one for a line no longer CONFIRMED. */
    static BusinessException notCancellable(Booking booking, int cancelWindowHours) {
        if (booking != null && booking.getStatus() != BookingStatus.CONFIRMED) {
            return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "Скасування недоступне — послугу вже скасовано або завершено");
        }
        return windowClosed(cancelWindowHours);
    }

    static BusinessException windowClosed(int cancelWindowHours) {
        return new BusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Скасування недоступне — менше ніж %d год до запису".formatted(cancelWindowHours));
    }
}
