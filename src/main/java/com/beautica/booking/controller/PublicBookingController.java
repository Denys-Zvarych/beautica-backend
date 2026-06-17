package com.beautica.booking.controller;

import com.beautica.booking.dto.BookingSlugInfoResponse;
import com.beautica.booking.service.BookingSlugService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated guest-booking endpoints (Phase 13.1).
 *
 * <p>Permitted in {@code SecurityConfig} via {@code /api/v1/book/**}. No JWT
 * required — clients open {@code beautica.app/book/{slug}} from a shared link.
 */
@RestController
@RequestMapping("/api/v1/book")
@RequiredArgsConstructor
@Validated
public class PublicBookingController {

    private final BookingSlugService bookingSlugService;

    /**
     * Returns the public booking-page info for {@code slug}: master display name,
     * avatar, bio, and bookable services. 404 when the slug is unknown.
     *
     * <p>Anti-Bug §A: the {@code slug} path variable is constrained at the boundary
     * to the exact generator charset so a malformed or oversized value is rejected
     * as a clean 400 on this {@code permitAll} endpoint instead of reaching the DB.
     */
    @GetMapping("/{slug}/info")
    public ResponseEntity<BookingSlugInfoResponse> info(
            @PathVariable
            @Size(min = 2, max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$")
            String slug) {
        return ResponseEntity.ok(bookingSlugService.findBySlug(slug));
    }
}
