package com.beautica.common.exception;

import com.beautica.booking.entity.Booking;
import com.beautica.master.entity.Master;
import com.beautica.user.User;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@code BookingService} when an authenticated client attempts to create, or
 * reschedule into, a time window that overlaps a {@code CONFIRMED} booking
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
 * <p><b>Provider redaction (mobile-security HIGH, 2026-11-14):</b> the overlap scan matches the
 * client's bookings at ANY salon/master, so when a PROVIDER reschedules a client the conflicting
 * row may belong to a competing salon. {@link #windowOnly(OffsetDateTime, OffsetDateTime)} captures ONLY the
 * time window — id, service name and master name are never read, so they cannot reach the
 * response body or any log. The client-initiated constructor keeps the full detail (the client
 * owns both bookings).
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
    private final boolean redacted;

    /** Client-initiated request: full detail of the client's own conflicting booking. */
    public ClientBookingConflictException(Booking conflictingBooking) {
        // V157 / phase 294 D3 — the conflicting row is a HISTORICAL booking, so its master may be
        // detached (staff account hard-deleted) and carry only the name snapshot.
        this(conflictingBooking.getId(),
                conflictingBooking.getMasterService().getServiceDefinition().getName(),
                displayName(conflictingBooking.getMaster()),
                conflictingBooking.getStartsAt(),
                conflictingBooking.getEndsAt(),
                false);
    }

    private ClientBookingConflictException(UUID conflictingBookingId, String serviceName, String masterName,
            OffsetDateTime startsAt, OffsetDateTime endsAt, boolean redacted) {
        super(HttpStatus.CONFLICT, "Client already has an overlapping booking");
        this.conflictingBookingId = conflictingBookingId;
        this.serviceName = serviceName;
        this.masterName = masterName;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.redacted = redacted;
    }

    /**
     * Provider-initiated request (reschedule by SALON_OWNER/SALON_ADMIN/INDEPENDENT_MASTER):
     * carries ONLY the conflicting window — no booking id, service name or master name, which
     * could describe the client's booking at another salon.
     */
    public static ClientBookingConflictException windowOnly(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return new ClientBookingConflictException(null, null, null, startsAt, endsAt, true);
    }

    /** {@code true} when id/service/master were withheld (provider-initiated request). */
    public boolean isRedacted() {
        return redacted;
    }

    private static String displayName(Master master) {
        String firstName = master.displayFirstName() != null ? master.displayFirstName() : "";
        String lastName = master.displayLastName() != null ? master.displayLastName() : "";
        String combined = (firstName + " " + lastName).trim();
        return combined.isEmpty() ? "" : combined;
    }

    /** {@code null} when {@link #isRedacted()}. */
    public UUID getConflictingBookingId() {
        return conflictingBookingId;
    }

    /** {@code null} when {@link #isRedacted()}. */
    public String getServiceName() {
        return serviceName;
    }

    /** {@code null} when {@link #isRedacted()}. */
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
