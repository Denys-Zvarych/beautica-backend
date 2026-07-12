package com.beautica.common.exception;

import com.beautica.booking.entity.Booking;
import com.beautica.user.User;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@code BookingService} when an authenticated client attempts to create, or
 * reschedule into, a time window that overlaps a {@code PENDING}/{@code CONFIRMED} booking
 * the SAME client already holds — regardless of master or salon.
 *
 * <p>Distinct from the pre-existing generic {@code 409 "Slot not available"}
 * {@link BusinessException}, which reports that the <em>master</em> is busy. This exception
 * carries structured details about the conflicting booking (id, service name, master display
 * name, start/end) so the mobile client can render a specific message, e.g.
 * «У вас вже є запис: Манікюр з Оленою, 14:00–15:30», instead of the generic slot-busy copy.
 *
 * <p>Fields are captured eagerly from the (still-attached) {@link Booking} at construction
 * time — the exception never holds a live entity reference, so it stays safe to inspect after
 * the loading transaction completes.
 *
 * <p>Stack-trace capture is suppressed — like {@link EmailAlreadyRegisteredException}, this is
 * a flow-control exception translated directly to an HTTP response; the trace is never logged.
 */
public class ClientBookingConflictException extends BusinessException {

    /**
     * Stable error code echoed in the response body under {@code data.code}. The mobile
     * client uses this to distinguish "you are already booked" from the generic
     * "Slot not available" (master-busy) 409 and route to a dedicated conflict message.
     */
    public static final String ERROR_CODE = "CLIENT_BOOKING_CONFLICT";

    private final UUID conflictingBookingId;
    private final String serviceName;
    private final String masterName;
    private final OffsetDateTime startsAt;
    private final OffsetDateTime endsAt;

    public ClientBookingConflictException(Booking conflictingBooking) {
        super(HttpStatus.CONFLICT, "Client already has an overlapping booking");
        this.conflictingBookingId = conflictingBooking.getId();
        this.serviceName = conflictingBooking.getMasterService().getServiceDefinition().getName();
        this.masterName = displayName(conflictingBooking.getMaster().getUser());
        this.startsAt = conflictingBooking.getStartsAt();
        this.endsAt = conflictingBooking.getEndsAt();
    }

    private static String displayName(User user) {
        String firstName = user.getFirstName() != null ? user.getFirstName() : "";
        String lastName = user.getLastName() != null ? user.getLastName() : "";
        String combined = (firstName + " " + lastName).trim();
        return combined.isEmpty() ? "" : combined;
    }

    public UUID getConflictingBookingId() {
        return conflictingBookingId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getMasterName() {
        return masterName;
    }

    public OffsetDateTime getStartsAt() {
        return startsAt;
    }

    public OffsetDateTime getEndsAt() {
        return endsAt;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
