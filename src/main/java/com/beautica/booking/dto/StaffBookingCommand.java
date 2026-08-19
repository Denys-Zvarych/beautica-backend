package com.beautica.booking.dto;

import com.beautica.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The internal command {@code StaffBookingService.createStaffBooking} consumes — one staff-created
 * booking, fully resolved down to ids.
 *
 * <p><b>Not an HTTP request DTO.</b> Phase 22.4 owns the wire shape, its Bean Validation and its
 * authorization; this record is the domain-facing boundary between the two.
 *
 * <h2>Every field here may legitimately come from the request body — and that is the invariant</h2>
 * The staff user who keyed the booking in is deliberately <b>NOT</b> a field
 * (security MEDIUM, 2026-08-18). It used to be: {@code createdByUserId} sat on this record beside
 * every client-supplied value, so 22.4's natural {@code request.toCommand()} mapping was one added
 * field away from letting a caller attribute a walk-in — third-party PII, no OTP, no consent — to a
 * different staff user. {@code bookings.created_by_user_id} (V137) is the ONLY attribution a staff
 * booking carries. It is now an explicit second parameter of
 * {@code createStaffBooking(StaffBookingCommand, UUID actorId)}, so the controller MUST source it
 * from the security context and no request-body field can ever reach it.
 *
 * <p>{@link #scope} is the same argument applied to authority: it is derived by 22.4 from the
 * authenticated caller, not from the body, and it is a sealed type precisely so that "unscoped"
 * cannot be expressed — see {@link StaffBookingScope}.
 *
 * <h2>Why {@code BusinessException} and not {@code Objects.requireNonNull}</h2>
 * A {@code requireNonNull} NPE escaping a service hits {@code GlobalExceptionHandler}'s catch-all and
 * surfaces as a <b>500 with a full ERROR stack trace</b> — which is what a 22.4 request record
 * missing one {@code @NotNull} would produce (security LOW, 2026-08-18). A missing required value is
 * a 400 whether the omission was the client's or the wiring's, so these mirror
 * {@link StaffClientRef.Guest}'s own choice rather than contradicting it five lines away.
 *
 * @param scope           the authority the booking is created under — {@link StaffBookingScope.InSalon}
 *                        for a salon caller, {@link StaffBookingScope.Self} for an independent master
 *                        booking themselves. Asserted against the loaded master by the service; NO
 *                        variant skips that assertion. The persisted {@code bookings.salon_id} is
 *                        still derived from {@code master.getSalon()}, never from this value.
 * @param masterId        the master whose calendar the booking lands on
 * @param masterServiceId a {@code MasterServiceAssignment} id — the same grain every other create
 *                        path uses. Loading it master-scoped IS the service-eligibility proof
 *                        ("does this master actually perform this service?").
 *                        <b>Scalar, not a list, deliberately</b>: staff bookings are single-service
 *                        in this track, because a multi-service visit would insert an
 *                        {@code appointments} row and {@code chk_appointment_source} (V124:54) still
 *                        admits only {@code 'APP','LINK'}. See {@code StaffBookingService}'s class
 *                        Javadoc.
 * @param startsAt        the requested start; validated against the STAFF lead-time rule (no past,
 *                        "now" allowed) and against the master's real working hours
 * @param client          who the booking is for — only {@link StaffClientRef.Guest} ships today
 */
public record StaffBookingCommand(
        StaffBookingScope scope,
        UUID masterId,
        UUID masterServiceId,
        OffsetDateTime startsAt,
        StaffClientRef client
) {
    public StaffBookingCommand {
        scope = required(scope, "Booking scope");
        masterId = required(masterId, "masterId");
        masterServiceId = required(masterServiceId, "masterServiceId");
        startsAt = required(startsAt, "startsAt");
        client = required(client, "client");
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value;
    }
}
