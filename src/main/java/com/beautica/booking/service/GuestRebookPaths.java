package com.beautica.booking.service;

import com.beautica.booking.entity.Booking;
import com.beautica.config.BookingLinksProperties;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.salon.entity.Salon;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phase 435 — the website-relative «записатись знову» path the public guest booking page offers next to a booking
 * ({@code rebookPath} of {@code GET /api/v1/book/cancel/{token}}). Same rule the phase-390 rebook SMS link used:
 * an ACTIVE INDEPENDENT master's own page ({@code /book/{slug}}), else the ACTIVE salon's page
 * ({@code /book/s/{slug}}). A salon master or owner never yields a master path (locked booking-link access rule).
 * Empty when booking-link sharing is off, when neither has a well-formed slug, or when both are inactive.
 *
 * <p>Slugs are already public (they are the booking pages' own URLs), so the path exposes no internal id.
 */
@Component
@RequiredArgsConstructor
public class GuestRebookPaths {

    private static final String MASTER_PATH = "/book/";
    private static final String SALON_PATH = "/book/s/";
    /** The V86/V200 slug shape; anything else is never emitted. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]*[a-z0-9]$");

    private final BookingLinksProperties bookingLinksProperties;

    /** The rebook path for {@code booking}'s master / salon; reads both (callers fetch them). */
    public Optional<String> forBooking(Booking booking) {
        if (!bookingLinksProperties.isShareEnabled()) {
            return Optional.empty();
        }
        return masterPath(booking.getMaster()).or(() -> salonPath(booking.getSalon()));
    }

    private static Optional<String> masterPath(Master master) {
        if (master == null || master.getMasterType() != MasterType.INDEPENDENT_MASTER || !master.isActive()) {
            return Optional.empty();
        }
        return path(MASTER_PATH, master.getBookingSlug());
    }

    private static Optional<String> salonPath(Salon salon) {
        if (salon == null || !salon.isActive()) {
            return Optional.empty();
        }
        return path(SALON_PATH, salon.getBookingSlug());
    }

    private static Optional<String> path(String prefix, String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            return Optional.empty();
        }
        return Optional.of(prefix + slug);
    }
}
