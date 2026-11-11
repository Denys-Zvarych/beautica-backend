package com.beautica.booking.controller;

import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.AppointmentItemRescheduleRequest;
import com.beautica.booking.dto.AppointmentProviderNoteRequest;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.CreateAppointmentRequest;
import com.beautica.booking.service.AppointmentService;
import com.beautica.booking.service.AppointmentTransitionService;
import com.beautica.booking.service.BookingService;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.security.AuthenticationUtils;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Multi-service single-visit create endpoint (BE-3): {@code POST /api/v1/appointments} atomically
 * creates ONE appointment plus N chained CONFIRMED bookings for a single master.
 *
 * <p>Authorization mirrors the single-service {@code POST /bookings}: gated purely by
 * {@code @PreAuthorize("hasRole('CLIENT')")} on top of {@code SecurityConfig}'s
 * {@code anyRequest().authenticated()} default — {@code POST /bookings} carries no explicit
 * {@code requestMatcher} either, so no {@code SecurityConfig} change is needed. Per-user throttling is
 * added to {@code BookingRateLimitFilter} (same {@code bookingWriteBuckets} as {@code POST /bookings}).
 */
@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
@Validated
public class AppointmentController {

    /**
     * Whitelist pattern for {@code Idempotency-Key} values arriving via the HTTP header (the body
     * field is already {@code @Pattern}-validated on {@link CreateAppointmentRequest}). Mirrors
     * {@code BookingController}'s header validation (§L).
     */
    private static final Pattern IDEMPOTENCY_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9\\-_]{1,64}$");

    private final AppointmentService appointmentService;
    private final AppointmentTransitionService appointmentTransitionService;
    // Phase 30.6 — needed ONLY for cancelAppointmentItem's per-item cancel, which deliberately
    // reuses BookingService#cancelBooking verbatim rather than forking its logic (see that
    // method's own Javadoc, phase 30.6 D1, for why it cannot live on AppointmentTransitionService).
    private final BookingService bookingService;

    @PostMapping
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<ApiResponse<AppointmentDetailResponse>> createAppointment(
            @Valid @RequestBody CreateAppointmentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            Authentication auth
    ) {
        String resolvedKey = idempotencyKeyHeader != null ? idempotencyKeyHeader : request.idempotencyKey();
        if (resolvedKey != null && !IDEMPOTENCY_KEY_PATTERN.matcher(resolvedKey).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1-64 alphanumeric, dash, or underscore characters");
        }
        AppointmentDetailResponse response =
                appointmentService.createAppointment(AuthenticationUtils.userId(auth), resolvedKey, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    /**
     * Reads the full enriched detail of one multi-service visit (BE-5) —
     * {@code GET /api/v1/appointments/{appointmentId}}.
     *
     * <p>Authorization mirrors {@code GET /bookings/{id}} exactly: a role-agnostic
     * {@code isAuthenticated()} gate here, then {@code AuthorizationService.enforceCanViewBooking}
     * inside the service after the visit is loaded once (the owning client OR the provider with
     * view-access to the visit's single master). A missing/foreign visit is a uniform 403 — no
     * existence oracle.
     */
    @GetMapping("/{appointmentId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<AppointmentDetailResponse> getAppointment(
            @PathVariable UUID appointmentId,
            Authentication auth
    ) {
        return ApiResponse.ok(
                appointmentService.getAppointment(AuthenticationUtils.userId(auth), appointmentId));
    }

    /**
     * Provider-initiated PER-SERVICE decline — declines
     * exactly ONE service line of a multi-service visit, leaving its siblings CONFIRMED:
     * {@code PATCH /api/v1/appointments/{appointmentId}/services/{bookingId}/decline}.
     *
     * <p>Provider authority (role-only gate here +
     * {@code enforceCanCancelBooking} ownership guard in the service, §D). The optional
     * {@code providerComment} is written to the declined CHILD row (its own status becomes
     * {@code DECLINED}), never the header. The header collapses to {@code DECLINED} only once the
     * declined child was the last CONFIRMED service. A {@code bookingId} not belonging to the
     * appointment is a 404; a non-CONFIRMED (already terminal) child is a 409.
     */
    @PatchMapping("/{appointmentId}/services/{bookingId}/decline")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER')")
    public ResponseEntity<Void> declineAppointmentItem(
            @PathVariable UUID appointmentId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody(required = false) AppointmentProviderNoteRequest req,
            Authentication auth
    ) {
        appointmentTransitionService.declineAppointmentItem(
                AuthenticationUtils.userId(auth), appointmentId, bookingId, req);
        return ResponseEntity.noContent().build();
    }

    /**
     * Provider-initiated PER-SERVICE completion —
     * completes exactly ONE service line of a multi-service visit, leaving its siblings CONFIRMED:
     * {@code PATCH /api/v1/appointments/{appointmentId}/services/{bookingId}/complete}. Fixes the
     * reported bug where tapping «Завершити» on one card of a multi-service visit completed every
     * service in the visit, because the only complete route was a whole-visit one (removed in
     * phase 475).
     *
     * <p>Provider authority (role-only gate here +
     * {@code enforceCanManageAppointment} ownership guard in the service, §D). No request body — mirrors
     * {@code PATCH /bookings/{id}/complete}, which takes no note either (unlike decline,
     * {@code providerComment} is never collected for a completion).
     * The header collapses to {@code COMPLETED} only once the completed child was the last CONFIRMED
     * service, and the visit's single review-requested notification (never one per item) fires exactly
     * then. A {@code bookingId} not belonging to the appointment is a 404; a non-CONFIRMED (already
     * terminal) child is a 409.
     */
    @Operation(summary = "Complete one service line of a visit",
            description = "Provider-initiated completion of ONE service line of a multi-service visit. "
                    + "Siblings stay CONFIRMED. The header collapses to COMPLETED, and the visit's single "
                    + "review-requested notification fires, only once the last CONFIRMED sibling completes.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Completed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Missing, foreign, or non-authorized visit (uniform — no existence oracle)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "bookingId is not a child of appointmentId"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Service already terminal, or changed concurrently (retryable)")
    })
    @PatchMapping("/{appointmentId}/services/{bookingId}/complete")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER')")
    public ResponseEntity<Void> completeAppointmentItem(
            @PathVariable UUID appointmentId,
            @PathVariable UUID bookingId,
            Authentication auth
    ) {
        appointmentTransitionService.completeAppointmentItem(
                AuthenticationUtils.userId(auth), appointmentId, bookingId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Moves ONE service line of a multi-service visit to a new time (phase 30.5): {@code PATCH
     * /api/v1/appointments/{appointmentId}/services/{bookingId}/reschedule}. Siblings keep their
     * windows byte-for-byte; the visit's items may end up non-contiguous afterwards (LOCKED
     * per-item semantics, phase 30.1 — gaps are legal, overlaps are not). The whole-visit reschedule
     * route was removed in phase 475; every leg moves on its own.
     *
     * <p>Same URL shape as {@link #declineAppointmentItem} and {@link #cancelAppointmentItem}, so
     * all three per-item transitions live at {@code /{appointmentId}/services/{bookingId}/<verb>}.
     *
     * <p><b>{@code @PreAuthorize} is role-only</b> (perf audit F1, cross-batch — REVERSES phase
     * 30.5 D1's "copy the whole-visit expression verbatim"). That earlier version additionally
     * gated the provider arm on {@code @authz.canRescheduleAppointment(authentication,
     * #appointmentId)}, which runs the exact same {@code findAllCompletionAccessByAppointmentId}
     * joined projection that {@code AuthorizationService#enforceCanManageAppointment} runs moments
     * later inside {@code AppointmentTransitionService#rescheduleAppointmentItem} — every provider
     * request paid for that query twice. Dropping the SpEL authority check here is safe precisely
     * because {@code enforceCanManageAppointment} is the SOLE authoritative provider gate now (no
     * layer skips it): a non-owner provider still gets exactly the same 403, just from one query
     * instead of two — mirrors the sibling {@link #declineAppointmentItem}/
     * {@link #cancelAppointmentItem} routes, which never duplicated this check in the first place.
     * {@code SALON_MASTER} is excluded exactly as before: absent from {@code hasAnyRole} here, AND
     * {@code enforceCanManageAppointment} would fast-reject it too if it were ever reached. For a
     * CLIENT principal, the provider arm is never evaluated (SpEL {@code or} short-circuits) —
     * CLIENT ownership is enforced in the service layer instead, via the projection-only
     * {@code AppointmentRepository#findClientIdById} comparison (phase 30.4 step 2), identical to
     * how the (since removed) whole-visit reschedule/cancel routes worked. {@code Role} is resolved from the JWT,
     * never the body — a body-supplied role would be a privilege-escalation vector, since the
     * service branches on it for both the temporal guard and the outbox addressing.
     *
     * <p>Returns {@code 200} + the WHOLE enriched visit (not just the moved line, phase 30.1 D11):
     * the header window and totals may both have changed, and mobile reuses the same deserializer
     * as {@code GET /appointments/{id}}.
     */
    @Operation(summary = "Reschedule one service line of a visit",
            description = "Moves ONE service line of a multi-service visit to a new time. Siblings "
                    + "keep their windows; the visit may become non-contiguous afterwards (gaps are "
                    + "legal, overlaps are not). Returns the whole enriched visit.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Bad newStartsAt (null/past/too soon/too far ahead)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Missing, foreign, or guest visit (uniform — no existence oracle)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "bookingId is not a child of appointmentId"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Header/item not CONFIRMED, elapsed, sibling overlap, or "
                            + "slot unavailable (including CLIENT_BOOKING_CONFLICT)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = "Rate limited (Retry-After: 10)")
    })
    @PatchMapping("/{appointmentId}/services/{bookingId}/reschedule")
    @PreAuthorize("hasRole('CLIENT') or hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER')")
    public ApiResponse<AppointmentDetailResponse> rescheduleAppointmentItem(
            @PathVariable UUID appointmentId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody AppointmentItemRescheduleRequest req,
            Authentication auth
    ) {
        return ApiResponse.ok(appointmentTransitionService.rescheduleAppointmentItem(
                AuthenticationUtils.userId(auth), AuthenticationUtils.role(auth),
                appointmentId, bookingId, req));
    }

    /**
     * Client-initiated cancel of ONE service line of a multi-service visit — the appointment-scoped
     * twin of {@code PATCH /bookings/{bookingId}/cancel}, whose service logic
     * ({@link BookingService#cancelBooking}) it reuses VERBATIM so the two routes can never diverge
     * (phase 30.6). The provider's per-item counterpart is {@link #declineAppointmentItem}.
     *
     * <p>Request body is the EXISTING {@link CancelBookingRequest} — deliberately NOT
     * the old whole-visit cancel body (removed with that route in phase 475), which carried no
     * {@code cancellationReason} (phase 30.6 D4). Body-compatible
     * with {@code PATCH /bookings/{bookingId}/cancel}, so the two routes stay interchangeable at the
     * wire level too. Returns {@code 204} (the appointment-family convention), unlike the old
     * booking-scoped route's {@code 200} + {@code BookingResponse} — both routes stay, unchanged for
     * their existing consumers.
     */
    @Operation(summary = "Cancel one service line of a visit",
            description = "Client-initiated cancel of ONE service line of a multi-service visit. "
                    + "Delegates verbatim to the same logic as PATCH /bookings/{bookingId}/cancel, "
                    + "so the two routes can never diverge.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Cancelled"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Missing cancellationReason or oversized/invalid comment, "
                            + "or the item is not CONFIRMED (cancelBooking's status guard throws a bare "
                            + "BusinessException, mapped to 400 — NOT 409 — same as the legacy "
                            + "/bookings/{bookingId}/cancel route)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Missing, foreign, or guest visit (uniform — no existence oracle)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "bookingId is not a child of appointmentId"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Item already elapsed (BOOKING_ALREADY_ELAPSED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = "Rate limited (Retry-After: 10)")
    })
    @PatchMapping("/{appointmentId}/services/{bookingId}/cancel")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<Void> cancelAppointmentItem(
            @PathVariable UUID appointmentId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CancelBookingRequest req,
            Authentication auth
    ) {
        bookingService.cancelAppointmentItem(
                AuthenticationUtils.userId(auth), appointmentId, bookingId, req);
        return ResponseEntity.noContent().build();
    }
}
