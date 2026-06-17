package com.beautica.booking.controller;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.BookingSlugInfoResponse;
import com.beautica.booking.dto.GuestBookingRequest;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.booking.service.BookingSlugService;
import com.beautica.booking.service.GuestBookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

    private final BookingSlugService bookingSlugService;
    private final GuestBookingService guestBookingService;

    /**
     * Public booking-page info for {@code slug}. 404 when the slug is unknown.
     *
     * <p>Anti-Bug §A: the {@code slug} is constrained at the boundary to the exact
     * generator charset so a malformed/oversized value is a clean 400, not a DB hit.
     */
    @GetMapping("/{slug}/info")
    public ResponseEntity<BookingSlugInfoResponse> info(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug) {
        return ResponseEntity.ok(bookingSlugService.findBySlug(slug));
    }

    /**
     * Available slots for a given service on a given date (Phase 13.3). Slot
     * generation is per-service (duration differs), so {@code serviceId} is required.
     * No auth required. Past / over-window dates are rejected as 400 in the service.
     */
    @GetMapping("/{slug}/availability")
    public ResponseEntity<List<AvailableSlotResponse>> availability(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam @NotNull UUID serviceId) {
        return ResponseEntity.ok(guestBookingService.availableSlots(slug, date, serviceId));
    }

    /**
     * Creates an auto-confirmed guest booking (Phase 13.3). The guest JWT is taken
     * from the {@code Authorization} header and validated by the service; an absent,
     * expired, or non-GUEST token results in 401. Slot conflicts return 409.
     */
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
}
