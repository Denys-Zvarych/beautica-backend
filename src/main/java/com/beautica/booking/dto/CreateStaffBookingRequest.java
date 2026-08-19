package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire shape of {@code POST /api/v1/masters/{masterId}/bookings} — the walk-in / phone-in booking a
 * {@code SALON_OWNER}, {@code SALON_ADMIN} or {@code INDEPENDENT_MASTER} keys in on a master's
 * calendar (Phase 22.4, amendment A4).
 *
 * <h2>What is deliberately NOT here — and that is the security property</h2>
 * There is no {@code createdByUserId}, no {@code salonId}, no {@code masterUserId} and no
 * {@code existingClientId}:
 * <ul>
 *   <li><b>The actor</b> is a separate parameter of
 *       {@code StaffBookingService#createStaffBooking(StaffBookingCommand, UUID)} precisely so that
 *       no request field can reach it. {@code bookings.created_by_user_id} (V137) is the ONLY
 *       attribution a staff booking carries — there is deliberately no fourth {@code BookingSource}
 *       value for self-booking (amendment A7) — so a spoofable actor would destroy the audit trail
 *       outright.</li>
 *   <li><b>The authority</b> ({@link StaffBookingScope}) is resolved from the authenticated caller
 *       by {@code StaffBookingScopeResolver}, never from the body or the path. Amendment A2 removed
 *       {@code {salonId}} from the route for the same reason.</li>
 *   <li><b>{@code existingClientId}</b> is Phase 22.3, deferred — {@link StaffClientRef.ExistingClient}
 *       answers {@code 501} today, so exposing it would only publish a dead field. Adding it later
 *       is additive and non-breaking; the "exactly one identity mode" {@code @AssertTrue} arrives
 *       with it.</li>
 * </ul>
 * {@link #toCommand(UUID, StaffBookingScope)} takes both untrusted-by-construction values as
 * <em>parameters</em> rather than reading them off {@code this}, so the mapping cannot be widened
 * into the hazard by accident.
 *
 * <p><b>{@code clientComment} is not a field</b> even though amendment A4 lists it: 22.2's
 * {@link StaffBookingCommand} carries no comment, and {@code Booking#staffBooking} has nowhere to
 * put one. Flagged rather than invented.
 *
 * @param masterServiceId the {@code MasterServiceAssignment} the booking is placed against — the
 *                        same grain every other create path uses. Scalar, not a list: a staff
 *                        booking is single-service while {@code chk_appointment_source} excludes
 *                        {@code STAFF} (see {@code StaffBookingService}'s class Javadoc).
 * @param startsAt        the requested start. {@code @Future} is the first-pass 400 against the
 *                        default Bean Validation (system) clock, exactly as
 *                        {@link CreateBookingRequest} and {@code GuestBookingRequest} do;
 *                        {@code BookingStartsAtValidator#validateStaff} remains authoritative and
 *                        uses the injected {@code Clock} bean. The two are intentionally
 *                        independent. Note the STAFF rule has ZERO lead time (unlike APP/LINK's 15
 *                        minutes), so the only start this annotation rejects that the service would
 *                        have accepted is one equal to "now" to the millisecond — unreachable in
 *                        practice, since a start must also land on the 30-minute slot grid.
 * @param guest           the walk-in identity; {@code @Valid} so the nested field errors surface
 *                        individually rather than as one opaque "guest is invalid"
 */
@Schema(description = "Creates a CONFIRMED, STAFF-sourced booking for a walk-in client on a master's calendar.")
public record CreateStaffBookingRequest(

        @NotNull(message = "Service ID is required")
        @Schema(description = "MasterService (assignment) id the master performs.")
        UUID masterServiceId,

        @NotNull(message = "Start time is required")
        @Future(message = "Start time must be in the future")
        @Schema(description = "ISO-8601 start instant; must land on the master's real slot grid.")
        OffsetDateTime startsAt,

        @NotNull(message = "Client details are required")
        @Valid
        GuestClientDto guest
) {

    /**
     * Maps the wire shape onto 22.2's internal command.
     *
     * <p>{@code masterId} comes from the PATH and {@code scope} from the SECURITY CONTEXT — both are
     * parameters, not fields, so this method structurally cannot be the place a body field becomes
     * an authority claim. The actor id is not accepted here at all: it is the service's own second
     * parameter.
     *
     * @param masterId the path variable the {@code @PreAuthorize} gate already authorised
     * @param scope    the authority resolved from the authenticated caller
     */
    public StaffBookingCommand toCommand(UUID masterId, StaffBookingScope scope) {
        return new StaffBookingCommand(
                scope,
                masterId,
                masterServiceId,
                startsAt,
                // Raw, un-normalised: UkrainianPhoneNormalizer runs inside StaffBookingService so
                // exactly one component owns phone format (see GuestClientDto#phone).
                new StaffClientRef.Guest(guest.name(), guest.surname(), guest.phone()));
    }
}
