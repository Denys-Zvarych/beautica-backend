package com.beautica.booking.controller;

import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.CreateAppointmentRequest;
import com.beautica.booking.service.AppointmentService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
