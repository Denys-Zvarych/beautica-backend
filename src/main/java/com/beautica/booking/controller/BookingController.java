package com.beautica.booking.controller;

import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.service.BookingService;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;

import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Validated
public class BookingController {

    /**
     * Whitelist pattern for {@code Idempotency-Key} values regardless of whether the key
     * arrives via the HTTP header or the request body field. The {@code @Pattern} constraint
     * on {@link com.beautica.booking.dto.CreateBookingRequest#idempotencyKey()} only covers
     * the body field; header-supplied values must be validated here explicitly (Finding 4).
     */
    private static final Pattern IDEMPOTENCY_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9\\-_]{1,64}$");

    private final BookingService bookingService;

    @PostMapping
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<ApiResponse<BookingResponse>> createBooking(
            @Valid @RequestBody CreateBookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            Authentication auth
    ) {
        String resolvedKey = idempotencyKeyHeader != null ? idempotencyKeyHeader : request.idempotencyKey();
        if (resolvedKey != null && !IDEMPOTENCY_KEY_PATTERN.matcher(resolvedKey).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1-64 alphanumeric, dash, or underscore characters");
        }
        BookingResponse response = bookingService.createBooking(principalId(auth), resolvedKey, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @GetMapping("/{bookingId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<BookingDetailResponse> getBooking(
            @PathVariable UUID bookingId,
            Authentication auth
    ) {
        return ApiResponse.ok(bookingService.getBooking(principalId(auth), bookingId));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResponse<BookingResponse>> listMyBookings(
            @RequestParam(required = false) BookingStatus status,
            @PageableDefault(size = 20, sort = "startsAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication auth
    ) {
        // Cap page number to prevent giant OFFSET scans (Anti-Bug §J / SEC-MEDIUM-3).
        if (pageable.getPageNumber() > 1000) {
            pageable = PageRequest.of(1000, pageable.getPageSize(), pageable.getSort());
        }
        Page<BookingResponse> page = bookingService.listBookings(principalId(auth), auth, status, pageable);
        return ApiResponse.ok(PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        ));
    }

    @PatchMapping("/{bookingId}/confirm")
    @PreAuthorize("hasAnyRole('SALON_OWNER', 'INDEPENDENT_MASTER')")
    public ResponseEntity<Void> confirmBooking(
            @PathVariable UUID bookingId,
            Authentication auth
    ) {
        bookingService.confirmBooking(principalId(auth), bookingId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{bookingId}/decline")
    @PreAuthorize("hasAnyRole('SALON_OWNER', 'INDEPENDENT_MASTER')")
    public ResponseEntity<Void> declineBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody StatusUpdateRequest req,
            Authentication auth
    ) {
        bookingService.declineBooking(principalId(auth), bookingId, req);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{bookingId}/complete")
    @PreAuthorize("hasAnyRole('SALON_OWNER', 'INDEPENDENT_MASTER')")
    public ResponseEntity<Void> completeBooking(
            @PathVariable UUID bookingId,
            Authentication auth
    ) {
        bookingService.completeBooking(principalId(auth), bookingId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{bookingId}/not-complete")
    @PreAuthorize("hasAnyRole('SALON_OWNER', 'INDEPENDENT_MASTER')")
    public ResponseEntity<Void> notCompleteBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody StatusUpdateRequest req,
            Authentication auth
    ) {
        bookingService.notCompleteBooking(principalId(auth), bookingId, req);
        return ResponseEntity.noContent().build();
    }

    /**
     * Moves the authenticated client's own booking to a new future time.
     *
     * <p>Actor is resolved from the security principal — never from the body. Returns the
     * existing {@link BookingDetailResponse} shape (the same view {@code GET /bookings/{id}}
     * returns); Phase 19.3 will enrich this DTO. Errors: {@code 409} on a conflicting slot or
     * a non-PENDING/CONFIRMED source state, {@code 403} for a non-owner, {@code 400} for a bad time.
     */
    @PatchMapping("/{bookingId}/reschedule")
    @PreAuthorize("hasRole('CLIENT')")
    public ApiResponse<BookingDetailResponse> rescheduleBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody RescheduleBookingRequest req,
            Authentication auth
    ) {
        return ApiResponse.ok(bookingService.rescheduleBooking(principalId(auth), bookingId, req));
    }

    @PatchMapping("/{bookingId}/cancel")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<Void> cancelBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody CancelBookingRequest req,
            Authentication auth
    ) {
        bookingService.cancelBooking(principalId(auth), bookingId, req);
        return ResponseEntity.noContent().build();
    }

    private UUID principalId(Authentication auth) {
        if (auth instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof UUID id) {
            return id;
        }
        throw new ForbiddenException("Not authenticated");
    }
}
