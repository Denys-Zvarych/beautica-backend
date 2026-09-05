package com.beautica.booking.controller;

import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.dto.UnclosedCountResponse;
import com.beautica.booking.enums.BookingPartition;
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
import io.swagger.v3.oas.annotations.Operation;
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

    /**
     * Giant-OFFSET clamp ceiling shared by every paginated booking list route (Anti-Bug §J /
     * SEC-MEDIUM-3): a caller-supplied page number beyond this is silently clamped down to it
     * rather than executed as-is, so an attacker cannot force an ever-deeper {@code OFFSET} scan
     * by walking {@code page} up. Phase 23.4 audit fix, Finding 4 (LOW, backend-qa) — previously
     * duplicated verbatim in both {@link #listMyBookings} and {@link #getSalonBookings}; extracted
     * to {@link #clampGiantOffset(Pageable)} so there is exactly one implementation to test and
     * keep in sync.
     */
    private static final int MAX_CLAMPED_PAGE_NUMBER = 1000;

    private final BookingService bookingService;

    /**
     * Creates a booking and returns the enriched detail view.
     *
     * <p><b>Additive contract change.</b> This returned the lean {@link BookingResponse} until the
     * enrichment change; it now returns {@link BookingDetailResponse}, whose first twelve
     * components are identical to {@code BookingResponse}'s in name, type, order and semantics.
     * The response body is therefore a strict superset — no field was removed, renamed or
     * re-typed — so a client that ignores the added fields is unaffected, while a client that
     * reads them no longer needs the follow-up {@code GET /bookings/{id}} that previously existed
     * only to fetch master name, avatar, salon name and address for the confirmation screen.
     */
    @PostMapping
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> createBooking(
            @Valid @RequestBody CreateBookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            Authentication auth
    ) {
        String resolvedKey = idempotencyKeyHeader != null ? idempotencyKeyHeader : request.idempotencyKey();
        if (resolvedKey != null && !IDEMPOTENCY_KEY_PATTERN.matcher(resolvedKey).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1-64 alphanumeric, dash, or underscore characters");
        }
        BookingDetailResponse response =
                bookingService.createBooking(AuthenticationUtils.userId(auth), resolvedKey, request);
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
                    + "Omit for no status predicate. IGNORED whenever `partition` is present — see "
                    + "that parameter's doc for the precedence rule.")
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
            // Phase 28.2: additive-optional time-based partition, ANDs with from/to/serviceId
            // exactly like `status` does. Absent (the default) => SQL and response byte-identical
            // to pre-28.1 behaviour — see BookingService#getMyBookings(..., BookingPartition, ...)
            // for the full contract.
            @Parameter(description = "Time-based partition: UPCOMING (status=CONFIRMED and not yet "
                    + "elapsed), PAST (COMPLETED/NOT_COMPLETED, or an elapsed unclosed CONFIRMED), "
                    + "or CANCELLED (CANCELLED/DECLINED) — a total, disjoint cover of every "
                    + "booking status. AWAITING_CLOSURE is a named subset of PAST (an elapsed "
                    + "unclosed CONFIRMED booking only). HISTORY is a union view spanning PAST and "
                    + "CANCELLED, i.e. every booking EXCEPT UPCOMING, in one correctly-paginated "
                    + "request — use it for an \"archive\"/history list that must include cancelled "
                    + "and declined bookings alongside finished ones. When present, `status` is "
                    + "IGNORED — NOT a 400 — this is the additive rollout safety valve: a client "
                    + "sending both params degrades cleanly to the pre-partition `status`-only "
                    + "behaviour against a backend that does not yet know `partition`. Omit for "
                    + "byte-identical pre-Phase-28 behaviour.")
            @RequestParam(required = false) BookingPartition partition,
            @PageableDefault(size = 20, sort = "startsAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication auth
    ) {
        return ApiResponse.ok(bookingService.getMyBookings(
                AuthenticationUtils.userId(auth), auth, status, from, to, serviceId, partition,
                clampGiantOffset(pageable)));
    }

    // Phase 29.4: three path segments (/me/unclosed-count), same collision-avoidance rationale as
    // /me/booked-days below — Spring's PathPattern always prefers the more specific literal match
    // over /{bookingId}, but is kept explicit rather than relied upon, per that endpoint's comment.
    @GetMapping("/me/unclosed-count")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<UnclosedCountResponse> getUnclosedCount(Authentication auth) {
        return ApiResponse.ok(bookingService.getUnclosedCount(AuthenticationUtils.userId(auth), auth));
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
     * Phase 23.4 — {@code GET /bookings/salon/{salonId}}: a single-salon, paginated booking list
     * for {@code SALON_OWNER}/{@code SALON_ADMIN}, backing the mobile salon "Розклад" tab and the
     * salon-wide booking list. Distinct from {@code GET /bookings/me} (see {@code
     * BookingService#getSalonBookings}'s javadoc for the full rationale): that endpoint aggregates
     * a {@code SALON_OWNER} across every owned salon with no per-salon filter and rejects {@code
     * SALON_ADMIN} outright — neither guard is touched by this endpoint.
     *
     * <p><b>Authorization.</b> {@code hasAnyRole('SALON_OWNER','SALON_ADMIN')} alone would admit
     * any owner/admin for ANY salon id — {@code @authz.canManageSalon(authentication, #salonId)}
     * is the per-salon ownership/assignment assertion (Anti-Bug §D: a GET is a read, so the SpEL
     * {@code can*} form is the canonical placement — the same expression {@code SalonController}
     * uses for its own owner-or-admin-of-this-salon endpoints). An owner of a DIFFERENT salon, or
     * an admin assigned elsewhere, gets 403.
     *
     * <p>Route is {@code /api/v1/bookings/salon/{salonId}} — three path segments, so it cannot
     * collide with the two-segment {@code /{bookingId}} above (same collision-avoidance rationale
     * {@code /me/booked-days} documents; Spring's {@code PathPattern} prefers the literal {@code
     * salon} segment over the {@code {bookingId}} variable regardless, but the two-segment shape
     * keeps that unambiguous).
     */
    @Operation(summary = "List salon bookings (owner/admin)")
    @GetMapping("/salon/{salonId}")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN') and @authz.canManageSalon(authentication, #salonId)")
    public ApiResponse<PageResponse<BookingDetailResponse>> getSalonBookings(
            @PathVariable UUID salonId,
            @Parameter(description = "Filter to one master's bookings within the salon. Omit for every master.")
            @RequestParam(required = false) UUID masterId,
            @Parameter(description = "Filter by a single status. Omit for no status predicate.")
            @RequestParam(required = false) BookingStatus status,
            @Parameter(description = "Bookings starting on/after the start of this local day (Europe/Kyiv). "
                    + "Omit for an open-ended future window.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Bookings starting on/before the end of this local day (Europe/Kyiv), "
                    + "inclusive. Omit for an open-ended past window.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20, sort = "startsAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication auth
    ) {
        return ApiResponse.ok(bookingService.getSalonBookings(
                AuthenticationUtils.userId(auth), salonId, masterId, status, from, to,
                clampGiantOffset(pageable)));
    }

    /**
     * Clamps a caller-supplied page number down to {@link #MAX_CLAMPED_PAGE_NUMBER} — see that
     * constant's javadoc. Page size and sort pass through unchanged; only the page index is capped.
     */
    private static Pageable clampGiantOffset(Pageable pageable) {
        if (pageable.getPageNumber() > MAX_CLAMPED_PAGE_NUMBER) {
            return PageRequest.of(MAX_CLAMPED_PAGE_NUMBER, pageable.getPageSize(), pageable.getSort());
        }
        return pageable;
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

    // Role-only gate here + service-layer @authz.enforceCanCancelBooking ownership guard in
    // BookingService#notCompleteBooking (§D — ownership enforced once, in the service). Mirrors
    // the sibling AppointmentController /not-complete, which is likewise role-only.
    @PatchMapping("/{bookingId}/not-complete")
    @PreAuthorize("hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER')")
    public ResponseEntity<Void> notCompleteBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody StatusUpdateRequest req,
            Authentication auth
    ) {
        bookingService.notCompleteBooking(AuthenticationUtils.userId(auth), bookingId, req);
        return ResponseEntity.noContent().build();
    }

    /**
     * Moves a booking to a new future time — the client's own booking, OR (Phase 27.2 — REVERSES
     * the previously-locked "reschedule is client-only" decision) the provider (salon owner /
     * assigned salon admin / independent master) with authority over it.
     *
     * <p>Actor is resolved from the security principal — never from the body. Returns the
     * existing {@link BookingDetailResponse} shape (the same view {@code GET /bookings/{id}}
     * returns). Errors: {@code 409} on a conflicting slot, a non-CONFIRMED source state, or
     * (provider path) an already-elapsed current booking; {@code 403} for a non-owner/non-
     * authorized provider; {@code 400} for a bad new time.
     */
    @PatchMapping("/{bookingId}/reschedule")
    @PreAuthorize("hasRole('CLIENT') or (hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER') "
            + "and @authz.canRescheduleBooking(authentication, #bookingId))")
    public ApiResponse<BookingDetailResponse> rescheduleBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody RescheduleBookingRequest req,
            Authentication auth
    ) {
        return ApiResponse.ok(bookingService.rescheduleBooking(
                AuthenticationUtils.userId(auth), AuthenticationUtils.role(auth), bookingId, req));
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
