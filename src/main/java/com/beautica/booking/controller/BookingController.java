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
import com.beautica.common.security.AuthenticationUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.beautica.common.exception.BusinessException;
import io.swagger.v3.oas.annotations.Parameter;

import java.time.LocalDate;
import java.util.List;
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
        BookingResponse response = bookingService.createBooking(AuthenticationUtils.userId(auth), resolvedKey, request);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    @GetMapping("/{bookingId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<BookingDetailResponse> getBooking(
            @PathVariable UUID bookingId,
            Authentication auth
    ) {
        return ApiResponse.ok(bookingService.getBooking(AuthenticationUtils.userId(auth), bookingId));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResponse<BookingDetailResponse>> listMyBookings(
            // Phase 26.1: widened from a single optional BookingStatus to a repeatable list.
            // Spring binds both ?status=A (1-element list — every pre-26.1 caller keeps working
            // unchanged) and ?status=A&status=B (multi-select). null/absent = no status predicate.
            // Finding 3 (LOW, backend-security, Phase 26.1 audit): @Size caps the repeated-param
            // list at the enum's own cardinality (5 values) BEFORE it ever reaches
            // EnumSet.copyOf in the service. Defense-in-depth — Tomcat's request-line length
            // limit already bounds a raw ?status=&status=... query string in practice — but a
            // missing explicit cap here is exactly the bounded-collection pattern Anti-Bug §B1
            // requires. @Validated on the class (see class-level annotation) makes a violation
            // surface as a 400 ConstraintViolationException (GlobalExceptionHandler), not a 500.
            @Parameter(description = "Repeatable status filter, e.g. ?status=CONFIRMED&status=DECLINED. "
                    + "Omit for no status predicate.")
            @RequestParam(required = false) @Size(max = 5) List<BookingStatus> status,
            // Phase 26.2: optional date-range filter on startsAt, independent of each other —
            // `from` alone is an open-ended future window, `to` alone an open-ended past window.
            // `to` is INCLUSIVE of its whole local (Europe/Kyiv) day; the half-open instant
            // conversion happens in BookingService#getMyBookings, never here.
            @Parameter(description = "Bookings starting on/after the start of this local day (Europe/Kyiv). "
                    + "Omit for an open-ended future window.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Bookings starting on/before the end of this local day (Europe/Kyiv), "
                    + "inclusive. Omit for an open-ended past window.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            // Phase 26.4: optional, repeatable serviceId filter — matches b.masterService.id
            // (the master's own catalogue entry a booking was placed against), never
            // masterService.serviceDefinition.id. Spring binds both ?serviceId=<A> (1-element
            // list) and ?serviceId=<A>&serviceId=<B> (multi-select), same repeatable-param
            // pattern as `status`. @Size caps the list at 50 — unlike status (self-bounded at the
            // enum's own cardinality of 5), a UUID list has no natural upper bound, so an explicit
            // cap is needed to stop an unbounded IN list / plan-cache inflation (Anti-Bug §B1).
            // No facet endpoint: the existing GET /independent-masters/me/services catalogue is
            // the option universe (see the phase doc's locked "no facet endpoint" decision).
            @Parameter(description = "Repeatable MasterService id filter, e.g. "
                    + "?serviceId=<A>&serviceId=<B>. Omit for no service predicate.")
            @RequestParam(required = false) @Size(max = 50) List<UUID> serviceId,
            @PageableDefault(size = 20, sort = "startsAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication auth
    ) {
        // Cap page number to prevent giant OFFSET scans (Anti-Bug §J / SEC-MEDIUM-3).
        if (pageable.getPageNumber() > 1000) {
            pageable = PageRequest.of(1000, pageable.getPageSize(), pageable.getSort());
        }
        return ApiResponse.ok(bookingService.getMyBookings(
                AuthenticationUtils.userId(auth), auth, status, from, to, serviceId, pageable));
    }

    // Phase 26.5: three path segments (/me/booked-days) so it cannot collide with the
    // two-segment /{bookingId} above — Spring's PathPattern always prefers the more specific
    // literal match, but this endpoint is pinned by a routing test anyway since /me vs
    // /{bookingId} ambiguity is a live footgun in this controller.
    @GetMapping("/me/booked-days")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<LocalDate>> listMyBookedDays(
            // Both required (unlike /me's optional from/to): an unbounded default would scan
            // the caller's entire booking history. Filter-independent by design — no status/
            // serviceId param here, see BookingService#getMyBookedDays javadoc.
            @Parameter(description = "Range start (inclusive), local Europe/Kyiv day. Required.")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Range end (inclusive), local Europe/Kyiv day. Required.")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication auth
    ) {
        return ApiResponse.ok(bookingService.getMyBookedDays(
                AuthenticationUtils.userId(auth), auth, from, to));
    }

    /**
     * Provider-initiated cancellation of an already-{@code CONFIRMED} booking (Phase 24.2).
     * Distinct from {@code PATCH /cancel} (client-initiated): this yields {@code DECLINED} so
     * the client's booking list can tell "ви скасували" from "салон скасував". There is no
     * {@code /confirm} endpoint any more — every booking is auto-confirmed at creation.
     */
    @PatchMapping("/{bookingId}/decline")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER') and @authz.canCancelBooking(authentication, #bookingId)")
    public ResponseEntity<Void> declineBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody StatusUpdateRequest req,
            Authentication auth
    ) {
        bookingService.declineBooking(AuthenticationUtils.userId(auth), bookingId, req);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{bookingId}/complete")
    @PreAuthorize("hasAnyRole('SALON_OWNER', 'SALON_ADMIN', 'INDEPENDENT_MASTER') and @authz.canCompleteBooking(authentication, #bookingId)")
    public ResponseEntity<Void> completeBooking(
            @PathVariable UUID bookingId,
            Authentication auth
    ) {
        bookingService.completeBooking(AuthenticationUtils.userId(auth), bookingId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{bookingId}/not-complete")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER') and @authz.canCancelBooking(authentication, #bookingId)")
    public ResponseEntity<Void> notCompleteBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody StatusUpdateRequest req,
            Authentication auth
    ) {
        bookingService.notCompleteBooking(AuthenticationUtils.userId(auth), bookingId, req);
        return ResponseEntity.noContent().build();
    }

    /**
     * Moves the authenticated client's own booking to a new future time.
     *
     * <p>Actor is resolved from the security principal — never from the body. Returns the
     * existing {@link BookingDetailResponse} shape (the same view {@code GET /bookings/{id}}
     * returns); Phase 19.3 will enrich this DTO. Errors: {@code 409} on a conflicting slot or
     * a non-CONFIRMED source state, {@code 403} for a non-owner, {@code 400} for a bad time.
     */
    @PatchMapping("/{bookingId}/reschedule")
    @PreAuthorize("hasRole('CLIENT')")
    public ApiResponse<BookingDetailResponse> rescheduleBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody RescheduleBookingRequest req,
            Authentication auth
    ) {
        return ApiResponse.ok(bookingService.rescheduleBooking(AuthenticationUtils.userId(auth), bookingId, req));
    }

    @PatchMapping("/{bookingId}/cancel")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<Void> cancelBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody CancelBookingRequest req,
            Authentication auth
    ) {
        bookingService.cancelBooking(AuthenticationUtils.userId(auth), bookingId, req);
        return ResponseEntity.noContent().build();
    }
}
