package com.beautica.booking.controller;

import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.CreateStaffBookingRequest;
import com.beautica.booking.dto.StaffBookingScope;
import com.beautica.booking.service.StaffBookingScopeResolver;
import com.beautica.booking.service.StaffBookingService;
import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * HTTP surface for provider-created (walk-in / phone-in) bookings — Phase 22.4.
 *
 * <h2>Route: no {@code &#123;salonId&#125;}</h2>
 * {@code POST /api/v1/masters/&#123;masterId&#125;/bookings} (amendment A2). An
 * {@code INDEPENDENT_MASTER} has no salon to put in such a segment, and a {@code Master} belongs to
 * at most one salon anyway — so carrying the salon in the path bought nothing except a
 * salonId/masterId <b>mismatch</b> state that a guard then had to police. Deriving the salon removes
 * the failure mode instead of guarding it.
 *
 * <h2>Where authorization happens, and why in two places</h2>
 * <ol>
 *   <li><b>Role gate + {@code @authz.canBookForMaster}</b> on the method. One predicate, keyed on
 *       the TARGET's {@code masterType} (amendment A3). It answers 403 — never 404 — for an unknown,
 *       inactive or closed-salon master, so {@code masterId} cannot be probed for existence. Because
 *       method security runs before the handler, this also fixes the ordering against 22.2's own
 *       indistinct 404 for the same condition: over HTTP an unauthorized caller can never reach it.</li>
 *   <li><b>{@link StaffBookingScopeResolver}</b> then derives the {@link StaffBookingScope} FROM THE
 *       CALLER. This is not the gate repeated (Anti-Bug §D): SpEL cannot return a value, and the
 *       scope is the datum 22.2's {@code assertMasterInScope} needs. Deriving
 *       {@code InSalon.salonId} from the target master instead would make that check compare the
 *       master's salon against itself — see the resolver's Javadoc.</li>
 * </ol>
 *
 * <h2>The actor never comes from the body</h2>
 * {@code actorId} is read here from the security context and handed to
 * {@code StaffBookingService#createStaffBooking} as its own parameter.
 * {@code bookings.created_by_user_id} is the ONLY attribution a staff booking carries — there is
 * deliberately no fourth {@code BookingSource} value for an independent master's self-booking
 * (amendment A7), the discriminator being
 * {@code created_by_user_id = (SELECT user_id FROM masters WHERE id = booking.master_id)} — so a
 * spoofable actor would destroy the audit trail. See {@link CreateStaffBookingRequest} for the
 * fields deliberately absent from the wire shape.
 *
 * <h2>What this endpoint dispatches</h2>
 * <b>ONE SMS to the walk-in client per visit</b> — regardless of how many services the visit
 * chains — after the transaction commits: the confirmation added in Phase 22.7 and corrected to be
 * visit-scoped (not per-booking) in Phase 22.13, sent to the phone number the caller typed in.
 * Whether it actually leaves the building depends on {@code app.booking.sms.enabled} ({@code false}
 * in every committed profile, flipped at release), which selects the {@code SmsService} bean in
 * {@code SmsConfig} — no code here branches on it. Delivery is best-effort and never affects the
 * response: the 201 is identical either way and carries no field reporting the outcome.
 *
 * <p>This is a consent-relevant fact, so it is stated in the OpenAPI {@code description} too — the
 * provider keying in someone else's number is the party who needs to know exactly how many messages
 * will be sent. Both statements said "No SMS or notification is sent by this endpoint" until
 * 2026-08-18, which 22.7 had made false; the description was then briefly wrong a SECOND way,
 * implying one SMS per chained SERVICE rather than one per VISIT, until Phase 22.13 fixed the
 * dispatch and this Javadoc/description were corrected to match.
 *
 * <p><b>Still no notification.</b> No outbox enqueue is specified for a staff booking, in any
 * configuration. <b>Open product question, flagged not decided:</b> a master currently gets a
 * calendar row and no message when someone books on their behalf.
 */
@RestController
@RequestMapping("/api/v1/masters/{masterId}/bookings")
@RequiredArgsConstructor
@Tag(name = "Staff bookings", description = "Provider-created walk-in / phone-in bookings")
public class StaffBookingController {

    private final StaffBookingService staffBookingService;
    private final StaffBookingScopeResolver staffBookingScopeResolver;

    /**
     * Creates one {@code CONFIRMED}, {@code STAFF}-sourced VISIT — one {@code appointments} header
     * plus one {@code bookings} row per selected service — for an account-less walk-in.
     *
     * @param masterId the master whose calendar the booking lands on — authorised by
     *                 {@code canBookForMaster} before this method runs
     * @param request  the walk-in identity, ordered services and start; validated at the boundary
     * @param auth     the authenticated caller — the ONLY source of both the actor id and the scope
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER') "
            + "and @authz.canBookForMaster(authentication, #masterId)")
    @Operation(
            summary = "Create a walk-in visit on a master's calendar",
            description = """
                    Salon owners and admins may book any master of the salon they manage; an
                    independent master may book only themselves. The salon the booking is scoped to
                    is derived from the caller, never from the request. The visit is created as ONE
                    appointment header plus ONE booking per selected service, all CONFIRMED, source
                    STAFF, no cancel token, and created_by_user_id set to the caller on the header AND
                    every booking. Each service is cancelled, rescheduled, declined and reviewed
                    INDEPENDENTLY of its siblings — creating a visit never implies a whole-visit
                    cascade for any later transition. The guest phone is normalised to E.164
                    server-side; non-Ukrainian numbers are rejected.

                    Exactly ONE confirmation SMS is dispatched to that phone number after the visit is
                    committed, regardless of how many services it contains, subject to the
                    platform-wide app.booking.sms.enabled switch. Delivery is best-effort: it never
                    changes the response, and no field here reports whether a message was sent. No
                    push or email notification is sent by this endpoint.""",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "Visit created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Invalid payload, phone, or start time",
                    content = @io.swagger.v3.oas.annotations.media.Content()),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Not authorised for this master — also returned for an unknown "
                            + "or inactive master, so the id cannot be probed for existence",
                    content = @io.swagger.v3.oas.annotations.media.Content()),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "A selected service is not performed by this master",
                    content = @io.swagger.v3.oas.annotations.media.Content()),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Start is off-schedule or the window is already taken",
                    content = @io.swagger.v3.oas.annotations.media.Content()),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429",
                    description = "SMS-spend throttle: too many walk-in creates from this account, "
                            + "or too many recently for this phone number",
                    content = @io.swagger.v3.oas.annotations.media.Content())
    })
    public ResponseEntity<ApiResponse<AppointmentDetailResponse>> createStaffBooking(
            @PathVariable UUID masterId,
            @Valid @RequestBody CreateStaffBookingRequest request,
            Authentication auth
    ) {
        UUID actorId = AuthenticationUtils.userId(auth);
        StaffBookingScope scope = staffBookingScopeResolver.resolve(auth, masterId);

        AppointmentDetailResponse response =
                staffBookingService.createStaffBooking(request.toCommand(masterId, scope), actorId);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }
}
