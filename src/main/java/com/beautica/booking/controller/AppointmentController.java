package com.beautica.booking.controller;

import com.beautica.booking.dto.AppointmentCancelRequest;
import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.AppointmentProviderNoteRequest;
import com.beautica.booking.dto.CreateAppointmentRequest;
import com.beautica.booking.service.AppointmentService;
import com.beautica.booking.service.AppointmentTransitionService;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.security.AuthenticationUtils;
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
     * Client-initiated visit cancel (BE-4) — moves the whole visit (header + every chained item) to
     * {@code CANCELLED} in lockstep. Mirrors {@code PATCH /bookings/{id}/cancel}: only the visit's own
     * client may cancel (role-only {@code hasRole('CLIENT')} gate here + ownership enforced in the
     * service, §D). The optional {@code clientCancellationNote} is written to the header only.
     */
    @PatchMapping("/{appointmentId}/cancel")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<Void> cancelAppointment(
            @PathVariable UUID appointmentId,
            @Valid @RequestBody(required = false) AppointmentCancelRequest req,
            Authentication auth
    ) {
        appointmentTransitionService.cancelAppointment(AuthenticationUtils.userId(auth), appointmentId, req);
        return ResponseEntity.noContent().build();
    }

    /**
     * Provider-initiated visit decline (BE-4) — moves the whole visit to {@code DECLINED} in lockstep.
     * Mirrors {@code PATCH /bookings/{id}/decline}: role-only provider gate here + the
     * {@code enforceCanCancelBooking} ownership guard in the service (§D). The optional
     * {@code providerComment} is written to the header only.
     */
    @PatchMapping("/{appointmentId}/decline")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER')")
    public ResponseEntity<Void> declineAppointment(
            @PathVariable UUID appointmentId,
            @Valid @RequestBody(required = false) AppointmentProviderNoteRequest req,
            Authentication auth
    ) {
        appointmentTransitionService.declineAppointment(AuthenticationUtils.userId(auth), appointmentId, req);
        return ResponseEntity.noContent().build();
    }

    /**
     * Provider-initiated visit completion (BE-4) — moves the whole visit to {@code COMPLETED} in
     * lockstep. Mirrors {@code PATCH /bookings/{id}/complete}: role-only provider gate here + the
     * {@code enforceCanCompleteBooking} ownership guard in the service (§D). No request body.
     */
    @PatchMapping("/{appointmentId}/complete")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER')")
    public ResponseEntity<Void> completeAppointment(
            @PathVariable UUID appointmentId,
            Authentication auth
    ) {
        appointmentTransitionService.completeAppointment(AuthenticationUtils.userId(auth), appointmentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Provider-initiated visit no-show (BE-4) — moves the whole visit to {@code NOT_COMPLETED} in
     * lockstep. Mirrors {@code PATCH /bookings/{id}/not-complete}: role-only provider gate here + the
     * {@code enforceCanCancelBooking} ownership guard in the service (§D). The optional
     * {@code providerComment} is written to the header only.
     */
    @PatchMapping("/{appointmentId}/not-complete")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER')")
    public ResponseEntity<Void> notCompleteAppointment(
            @PathVariable UUID appointmentId,
            @Valid @RequestBody(required = false) AppointmentProviderNoteRequest req,
            Authentication auth
    ) {
        appointmentTransitionService.notCompleteAppointment(AuthenticationUtils.userId(auth), appointmentId, req);
        return ResponseEntity.noContent().build();
    }
}
