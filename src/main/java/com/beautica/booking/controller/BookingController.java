package com.beautica.booking.controller;

import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.service.BookingService;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.common.exception.ForbiddenException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Validated
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<ApiResponse<BookingResponse>> createBooking(
            @Valid @RequestBody CreateBookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            Authentication auth
    ) {
        String resolvedKey = idempotencyKeyHeader != null ? idempotencyKeyHeader : request.idempotencyKey();
        if (resolvedKey != null && resolvedKey.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must not exceed 64 characters");
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
            @PageableDefault(size = 20) Pageable pageable,
            Authentication auth
    ) {
        Page<BookingResponse> page = bookingService.listBookings(principalId(auth), status, pageable);
        return ApiResponse.ok(PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        ));
    }

    @PatchMapping("/{bookingId}/confirm")
    @PreAuthorize("hasAnyRole('SALON_OWNER', 'SALON_MASTER', 'INDEPENDENT_MASTER')")
    public ResponseEntity<Void> confirmBooking(
            @PathVariable UUID bookingId,
            Authentication auth
    ) {
        bookingService.confirmBooking(principalId(auth), bookingId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{bookingId}/decline")
    @PreAuthorize("hasAnyRole('SALON_OWNER', 'SALON_MASTER', 'INDEPENDENT_MASTER')")
    public ResponseEntity<Void> declineBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody StatusUpdateRequest req,
            Authentication auth
    ) {
        bookingService.declineBooking(principalId(auth), bookingId, req);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{bookingId}/complete")
    @PreAuthorize("hasAnyRole('SALON_OWNER', 'SALON_MASTER', 'INDEPENDENT_MASTER')")
    public ResponseEntity<Void> completeBooking(
            @PathVariable UUID bookingId,
            Authentication auth
    ) {
        bookingService.completeBooking(principalId(auth), bookingId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{bookingId}/not-complete")
    @PreAuthorize("hasAnyRole('SALON_OWNER', 'SALON_MASTER', 'INDEPENDENT_MASTER')")
    public ResponseEntity<Void> notCompleteBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody StatusUpdateRequest req,
            Authentication auth
    ) {
        bookingService.notCompleteBooking(principalId(auth), bookingId, req);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{bookingId}/cancel")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<Void> cancelBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody StatusUpdateRequest req,
            Authentication auth
    ) {
        bookingService.cancelBooking(principalId(auth), bookingId, req);
        return ResponseEntity.noContent().build();
    }

    private UUID principalId(Authentication auth) {
        if (auth instanceof UsernamePasswordAuthenticationToken token) {
            return (UUID) token.getDetails();
        }
        throw new ForbiddenException("Not authenticated");
    }
}
