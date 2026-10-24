package com.beautica.booking.controller;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.BookingSlugInfoResponse;
import com.beautica.booking.dto.CancelTokenInfoResponse;
import com.beautica.booking.dto.GuestBookingRequest;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.booking.dto.SalonBookingSlugInfoResponse;
import com.beautica.common.PageResponse;
import com.beautica.master.dto.MasterWorkingDayResponse;
import com.beautica.review.dto.ReviewResponse;
import com.beautica.review.dto.SalonReviewResponse;
import com.beautica.review.dto.SalonReviewSort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import com.beautica.booking.service.BookingCancellationService;
import com.beautica.booking.service.BookingSlugService;
import com.beautica.booking.service.GuestBookingService;
import com.beautica.booking.service.SalonBookingSlugService;
import com.beautica.booking.service.SlotCalculationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Public, unauthenticated guest-booking endpoints (Phases 13.1 / 13.3).
 *
 * <p>{@code GET} routes are permitted via {@code /api/v1/book/**}. The booking
 * {@code POST} is permitted by a precise per-slug rule in {@code SecurityConfig}
 * (matching the literal {@code /booking} suffix) — the guest JWT is carried in the {@code Authorization}
 * header and validated by {@link GuestBookingService}, not the Spring filter chain
 * (guest tokens have {@code type=GUEST} and no Spring principal).
 *
 * <p>Controller stays HTTP-only: it parses the request and delegates; all business
 * logic (token validation, slot guarding, persistence) lives in the service.
 */
@RestController
@RequestMapping("/api/v1/book")
@RequiredArgsConstructor
@Validated
public class PublicBookingController {

    /**
     * Phase 380 D5 (audit-tightened) — {@code public, max-age=0, s-maxage=30}: a browser always revalidates
     * (cheap 304 via the ETag), a shared cache/CDN may serve for 30 s. On top of the 60 s server cache the
     * worst-case staleness is therefore bounded to ~30 s at the edge and none in the browser, so a
     * cache-evicted write (review, service, schedule) is not hidden for minutes. Set through
     * {@code ResponseEntity} so Spring Security's default {@code no-cache} header writer stays off for
     * these 2xx responses; 404s keep its {@code no-store}. The weak ETag comes from
     * {@code BookingInfoEtagFilterConfig}.
     */
    private static final CacheControl INFO_CACHE_CONTROL =
            CacheControl.maxAge(Duration.ZERO).sMaxAge(Duration.ofSeconds(30)).cachePublic();

    /**
     * Phase 379 D3/D6 — OpenAPI text of the 410 every slug-keyed route can answer. Body:
     * {@code {"success":false,"data":{"code":"BOOKING_LINK_RETIRED"},"message":"Booking link is no longer valid"}}.
     */
    static final String GONE_DESCRIPTION = "Retired booking link (regenerated, revoked or deleted): "
            + "data.code = BOOKING_LINK_RETIRED. An unknown slug stays 404, a malformed one 400.";

    private final BookingSlugService bookingSlugService;
    private final GuestBookingService guestBookingService;
    private final BookingCancellationService bookingCancellationService;
    private final SalonBookingSlugService salonBookingSlugService;

    /**
     * Public booking-page info for {@code slug}. 404 when the slug is unknown.
     *
     * <p>Anti-Bug §A: the {@code slug} is constrained at the boundary to the exact
     * generator charset so a malformed/oversized value is a clean 400, not a DB hit.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Booking page"),
            @ApiResponse(responseCode = "410", description = GONE_DESCRIPTION, content = @Content())
    })
    @GetMapping("/{slug}/info")
    public ResponseEntity<BookingSlugInfoResponse> info(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug) {
        return ResponseEntity.ok().cacheControl(INFO_CACHE_CONTROL).body(bookingSlugService.findBySlug(slug));
    }

    /**
     * Phase 383 D6 — a sortable page of the master's public reviews, for the website's review sheet. Same slug
     * rules, cache headers and per-IP bucket as {@link #info}; {@code size} is clamped to 20, an unknown
     * {@code sort} is a 400 through the enum binding.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of the master's reviews"),
            @ApiResponse(responseCode = "410", description = GONE_DESCRIPTION, content = @Content())
    })
    @GetMapping("/{slug}/reviews")
    public ResponseEntity<PageResponse<ReviewResponse>> reviews(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug,
            @RequestParam(required = false, defaultValue = "NEWEST") SalonReviewSort sort,
            @PageableDefault(size = BookingSlugService.MAX_REVIEWS_PAGE_SIZE) Pageable pageable) {
        Page<ReviewResponse> page = bookingSlugService.reviewsForSlug(slug, sort, pageable);
        return ResponseEntity.ok().cacheControl(INFO_CACHE_CONTROL).body(PageResponse.of(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }

    /**
     * Phase 384 D4 — the salon counterpart of {@link #reviews}: {@code PageResponse<SalonReviewResponse>} (the master
     * name stays, as in the app), same clamp, headers, bucket and error matrix.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of the salon's reviews"),
            @ApiResponse(responseCode = "410", description = GONE_DESCRIPTION, content = @Content())
    })
    @GetMapping("/s/{slug}/reviews")
    public ResponseEntity<PageResponse<SalonReviewResponse>> salonReviews(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug,
            @RequestParam(required = false, defaultValue = "NEWEST") SalonReviewSort sort,
            @PageableDefault(size = BookingSlugService.MAX_REVIEWS_PAGE_SIZE) Pageable pageable) {
        Page<SalonReviewResponse> page = bookingSlugService.salonReviewsForSlug(slug, sort, pageable);
        return ResponseEntity.ok().cacheControl(INFO_CACHE_CONTROL).body(PageResponse.of(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }

    /**
     * Public SALON booking-page info for {@code slug} (phase 377, {@code beautica.app/book/s/{slug}}):
     * salon data plus its bookable masters, each with the master slug the {@code /{slug}/*} routes take.
     * 404 when the slug is unknown or the salon inactive; 200 with {@code bookable=false} when no master
     * is bookable yet. Same slug validation as {@link #info}.
     *
     * <p>The literal {@code s} segment cannot shadow a master slug: master slugs are at least 2 chars
     * (validated above, and generated as {@code base-xxxx}), and Spring ranks this literal-prefixed
     * pattern above {@code /{slug}/info}, so {@code /book/s/info} still reaches {@link #info} and is a 400.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Salon booking page"),
            @ApiResponse(responseCode = "410", description = GONE_DESCRIPTION, content = @Content())
    })
    @GetMapping("/s/{slug}/info")
    public ResponseEntity<SalonBookingSlugInfoResponse> salonInfo(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug) {
        return ResponseEntity.ok().cacheControl(INFO_CACHE_CONTROL).body(salonBookingSlugService.findBySlug(slug));
    }

    /**
     * Available slots for a given service (or several, as one back-to-back visit) on a given date
     * (Phase 13.3 / BE-7). Slot generation is per-service (duration differs), so {@code serviceId} is
     * required. It is a repeatable query param: {@code ?serviceId=a&serviceId=b} sizes each slot to the
     * SUM of the services' effective durations (a multi-service visit); a single {@code ?serviceId=x}
     * binds to a 1-element list and returns the exact legacy single-service (cached) result. No auth
     * required. Past / over-window dates are rejected as 400 in the service.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Available slots"),
            @ApiResponse(responseCode = "410", description = GONE_DESCRIPTION, content = @Content())
    })
    @GetMapping("/{slug}/availability")
    public ResponseEntity<List<AvailableSlotResponse>> availability(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("serviceId")
            @NotEmpty(message = "at least one serviceId is required")
            @Size(max = SlotCalculationService.MAX_SERVICES_PER_VISIT,
                    message = "at most 10 services can be booked in a single visit")
            List<UUID> serviceId) {
        // N=1 routes to the cached single-service overload (byte-for-byte legacy); N>1 to the summed-block
        // multi-service overload (BE-7). Mirrors MasterController's BE-2 slot endpoint.
        List<AvailableSlotResponse> slots = serviceId.size() == 1
                ? guestBookingService.availableSlots(slug, date, serviceId.get(0))
                : guestBookingService.availableSlots(slug, date, serviceId);
        return ResponseEntity.ok(slots);
    }

    /**
     * Phase 387 — per-date bookability over {@code [from, to]} for the public booking page's calendar, so
     * the website greys out dates with no free time exactly like the app calendar
     * ({@code GET /masters/{id}/working-days?serviceId=…}, same {@code SlotCalculationService} core).
     * {@code serviceId} has the {@link #availability} semantics (repeatable; N&gt;1 = one back-to-back visit,
     * summed duration). A date is {@code working = true} iff {@link #availability} returns ≥1 slot for it.
     *
     * <p>Salon links have a twin since phase 392: {@code GET /book/s/{slug}/masters/{masterId}/bookable-days}
     * ({@code PublicSalonBookingController}), keyed by master id because salon masters have no personal link.
     *
     * <p>Same headers, per-IP bucket and error matrix as {@link #availability}: {@code from}/{@code to}
     * outside {@code [today, today + availabilityMaxDays]} (Kyiv) or {@code from > to} → 400; unknown slug,
     * inactive master/salon, or unknown/foreign/inactive service → indistinguishable 404.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Per-date bookability"),
            @ApiResponse(responseCode = "410", description = GONE_DESCRIPTION, content = @Content())
    })
    @GetMapping("/{slug}/bookable-days")
    public ResponseEntity<List<MasterWorkingDayResponse>> bookableDays(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam("serviceId")
            @NotEmpty(message = "at least one serviceId is required")
            @Size(max = SlotCalculationService.MAX_SERVICES_PER_VISIT,
                    message = "at most 10 services can be booked in a single visit")
            List<UUID> serviceId) {
        return ResponseEntity.ok(guestBookingService.bookableDays(slug, from, to, serviceId));
    }

    /**
     * Creates an auto-confirmed guest booking (Phase 13.3). The guest JWT is taken
     * from the {@code Authorization} header and validated by the service; an absent,
     * expired, or non-GUEST token results in 401. Slot conflicts return 409.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Guest booking created"),
            @ApiResponse(responseCode = "410", description = GONE_DESCRIPTION, content = @Content())
    })
    @PostMapping("/{slug}/booking")
    public ResponseEntity<GuestBookingResponse> book(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody GuestBookingRequest request) {
        GuestBookingResponse response = guestBookingService.createGuestBooking(authorization, slug, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Cancel-page info for a one-time cancel token (Phase 13.4). 404 when the token is
     * unknown, already consumed, or the booking is no longer {@code CONFIRMED} — no
     * information is leaked about which condition held. {@code token} is a
     * {@link UUID} {@code @PathVariable}: Spring's converter returns 400 on a malformed
     * value before this method runs, so no manual format validation is needed.
     */
    @GetMapping("/cancel/{token}")
    public ResponseEntity<CancelTokenInfoResponse> cancelInfo(@PathVariable UUID token) {
        return ResponseEntity.ok(bookingCancellationService.getInfo(token));
    }

    /**
     * Cancels a guest booking by its one-time token (Phase 13.4) → 204 No Content. A
     * replayed POST (token already consumed) returns 404; a request inside the
     * cancellation window returns 422. The service consumes the token atomically so
     * concurrent POSTs trigger exactly one cancellation side-effect.
     */
    @PostMapping("/cancel/{token}")
    public ResponseEntity<Void> cancel(@PathVariable UUID token) {
        bookingCancellationService.cancel(token);
        return ResponseEntity.noContent().build();
    }
}
