package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.domain.MasterBookability;
import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.dto.BookingLinkResponse;
import com.beautica.booking.dto.BookingLinksResponse;
import com.beautica.config.BookingLinksProperties;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.ScheduleDateMath;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 375 — the caller's shareable public booking links ({@code GET /api/v1/booking-links/mine}).
 *
 * <p><b>Who gets what (D2).</b> {@code INDEPENDENT_MASTER} and {@code SALON_OWNER} (the owner is
 * always a master) get their own MASTER link; {@code SALON_ADMIN} and {@code SALON_MASTER} get none
 * here (salon links arrive in phase 376; salon masters are booked through their salon);
 * {@code CLIENT} is refused by the controller's {@code @PreAuthorize} — the single role gate. This
 * service only allow-lists who OWNS a master link ({@link #ownsMasterLink}), so any other role gets
 * no link. An inactive master row yields no link.
 *
 * <p><b>No method-level transaction (375 audit F1/F2).</b> {@code getMyLinks} is deliberately NOT
 * {@code @Transactional}: the kill-switch and role checks run before any connection is checked out,
 * the two reads ({@link MasterRepository#findActiveByUserIdWithUserAndSalon} — which fetches
 * {@code user} and {@code salon}, everything {@code displayFirstName/LastName} and
 * {@link MasterBookability#isBookable} touch — and {@link MasterRepository#findSearchBookability})
 * run auto-commit, and the slug write is confined to {@link BookingSlugService#getOrIssueSlugRaceSafe}'s
 * own short transaction, so the row lock taken by its {@code UPDATE} is released at its commit instead
 * of being held across the bookability read.
 *
 * <p><b>Kill-switch (D6).</b> With {@code app.booking-links.share-enabled=false} the method returns
 * {@link BookingLinksResponse#disabled()} before touching the database — no connection, no slug.
 *
 * <p><b>Lazy slug (D3/D4).</b> A legacy master whose {@code booking_slug} is still {@code NULL} gets
 * one here, via {@link BookingSlugService#getOrIssueSlugRaceSafe} (the existing generator, guarded
 * against two concurrent first calls). Once issued it never changes.
 *
 * <p><b>{@code bookable} (D7/D8).</b> The conjunction of the two existing rules, never a new copy:
 * {@link MasterBookability#isBookable} (active master, open salon — what {@code /book/{slug}} pages
 * accept bookings for) AND {@link MasterRepository#findSearchBookability}, the single
 * {@code MasterBookabilitySql.BOOKABLE_MASTER_M} predicate discovery search lists masters by
 * (≥1 active, correctly-owned service AND working hours within the booking horizon).
 */
@Service
@RequiredArgsConstructor
public class BookingLinkService {

    private static final String BOOK_PATH = "/book/";

    private final MasterRepository masterRepository;
    private final BookingSlugService bookingSlugService;
    private final ScheduleDateMath scheduleDateMath;
    private final BookingLinksProperties bookingLinksProperties;

    /**
     * Returns the caller's links. Not transactional (see class Javadoc): the first call for a legacy
     * master issues its slug in {@link BookingSlugService#getOrIssueSlugRaceSafe}'s own transaction.
     */
    public BookingLinksResponse getMyLinks(UUID userId, Role role) {
        if (!bookingLinksProperties.isShareEnabled()) {
            return BookingLinksResponse.disabled();
        }
        List<BookingLinkResponse> links = new ArrayList<>();
        if (ownsMasterLink(role)) {
            masterLink(userId).ifPresent(links::add);
        }
        return new BookingLinksResponse(true, links);
    }

    private static boolean ownsMasterLink(Role role) {
        return role == Role.INDEPENDENT_MASTER || role == Role.SALON_OWNER;
    }

    /** The caller's own active master row as a MASTER link; empty when there is no active row. */
    private Optional<BookingLinkResponse> masterLink(UUID userId) {
        return masterRepository.findActiveByUserIdWithUserAndSalon(userId).map(this::toMasterLink);
    }

    private BookingLinkResponse toMasterLink(Master master) {
        String slug = issueSlug(master);
        return new BookingLinkResponse(
                BookingLinkKind.MASTER,
                slug,
                urlFor(slug),
                BookingSlugService.composeName(master.displayFirstName(), master.displayLastName()),
                null,
                isBookable(master));
    }

    /**
     * 375 audit F3 — one retry when the conditional {@code UPDATE} hits the {@code booking_slug} unique
     * index (another master took the same candidate between the existence check and the write). The
     * failed attempt's transaction has already rolled back at the proxy boundary, and this method runs
     * outside any transaction, so the retry is a fresh transaction with a fresh candidate (the detached
     * {@code master} still carries {@code booking_slug = null}). A second violation propagates.
     */
    private String issueSlug(Master master) {
        if (master.getBookingSlug() != null) {
            // Steady state: the slug came with the read — no transaction, no connection checkout.
            return master.getBookingSlug();
        }
        try {
            return bookingSlugService.getOrIssueSlugRaceSafe(master);
        } catch (DataIntegrityViolationException firstCollision) {
            return bookingSlugService.getOrIssueSlugRaceSafe(master);
        }
    }

    private String urlFor(String slug) {
        return bookingLinksProperties.getWebBaseUrl() + BOOK_PATH + slug;
    }

    private boolean isBookable(Master master) {
        return MasterBookability.isBookable(master)
                && masterRepository.findSearchBookability(List.of(master.getId()), scheduleDateMath.today())
                        .stream()
                        .anyMatch(row -> Boolean.TRUE.equals(row.getBookable()));
    }
}
