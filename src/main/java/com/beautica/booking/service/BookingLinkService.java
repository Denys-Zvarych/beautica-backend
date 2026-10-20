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
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonRepository.SalonBookingLinkRow;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 375 — the caller's shareable public booking links ({@code GET /api/v1/booking-links/mine}).
 *
 * <p><b>Who gets what (375 D2 + 376 D6, amended 2026-10-20).</b> {@code INDEPENDENT_MASTER} gets its own
 * MASTER link; {@code SALON_OWNER} gets ONLY one SALON link per ACTIVE owned salon — the same link the
 * salon's admin shares — and NO personal MASTER link (product rule 2026-10-20: the owner's own master
 * row is neither read nor slug-issued on this call); {@code SALON_ADMIN} gets the SALON link of the
 * salon it is bound to; {@code SALON_MASTER} gets none (booked through their salon). {@code CLIENT} is refused by the
 * controller's {@code @PreAuthorize} — the single role gate. This service only allow-lists who OWNS a
 * link ({@link #ownsMasterLink}, {@link #salonLinkRows}), so any other role gets no link and touches no
 * data. An inactive master row or an inactive salon yields no link (376 D8 — its page would 404).
 *
 * <p><b>SALON links (phase 376).</b> The caller's salons load in ONE statement
 * ({@link SalonRepository#findBookingLinkRowsByOwnerId} / {@link SalonRepository#findBookingLinkRowsByAdminUserId})
 * and their {@code bookable} flag in ONE batched statement ({@link SalonRepository#findBookableSalonIds},
 * the shared {@code MasterBookabilitySql.BOOKABLE_SALON_S} rule) — never one query per salon. ALL of the
 * caller's legacy salons ({@code booking_slug NULL}) are issued their slugs together in ONE short
 * transaction ({@link BookingSlugService#issueMissingSalonSlugs}, 376 audit), with the same single retry
 * as masters.
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
    /** Phase 376 — salon page namespace, separate from masters' {@code /book/{slug}} (D2). */
    private static final String SALON_BOOK_PATH = "/book/s/";

    private final MasterRepository masterRepository;
    private final BookingSlugService bookingSlugService;
    private final ScheduleDateMath scheduleDateMath;
    private final BookingLinksProperties bookingLinksProperties;
    private final SalonRepository salonRepository;

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
        links.addAll(salonLinks(salonLinkRows(userId, role)));
        return new BookingLinksResponse(true, links);
    }

    /**
     * Only the independent master owns a personal MASTER link here. The salon owner is a master too, but
     * shares its salon(s) instead (product rule 2026-10-20) — so its master row is never read on this call.
     */
    private static boolean ownsMasterLink(Role role) {
        return role == Role.INDEPENDENT_MASTER;
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

    /**
     * The caller's active salons, by name (376 D6): every owned salon for {@code SALON_OWNER}, the bound
     * salon for {@code SALON_ADMIN}; any other role gets none WITHOUT a query.
     */
    private List<SalonBookingLinkRow> salonLinkRows(UUID userId, Role role) {
        if (role == Role.SALON_OWNER) {
            return salonRepository.findBookingLinkRowsByOwnerId(userId);
        }
        if (role == Role.SALON_ADMIN) {
            return salonRepository.findBookingLinkRowsByAdminUserId(userId);
        }
        return List.of();
    }

    private List<BookingLinkResponse> salonLinks(List<SalonBookingLinkRow> salons) {
        if (salons.isEmpty()) {
            return List.of();
        }
        Set<UUID> bookable = new HashSet<>(salonRepository.findBookableSalonIds(
                salons.stream().map(SalonBookingLinkRow::getId).toList(), scheduleDateMath.today()));
        Map<UUID, String> slugs = salonSlugs(salons);
        // 376 audit cycle 2: a salon deleted between the read above and the lazy issue has no slug — its
        // link is omitted instead of failing the whole response with 404.
        return salons.stream()
                .filter(salon -> slugs.containsKey(salon.getId()))
                .map(salon -> toSalonLink(salon, slugs.get(salon.getId()), bookable.contains(salon.getId())))
                .toList();
    }

    private BookingLinkResponse toSalonLink(SalonBookingLinkRow salon, String slug, boolean bookable) {
        return new BookingLinkResponse(
                BookingLinkKind.SALON, slug, salonUrlFor(slug), salon.getName(), salon.getId(), bookable);
    }

    /** Stored slugs as read (steady state: nothing further); the legacy ones issued together. */
    private Map<UUID, String> salonSlugs(List<SalonBookingLinkRow> salons) {
        Map<UUID, String> slugs = new HashMap<>();
        List<SalonBookingLinkRow> missing = new ArrayList<>();
        for (SalonBookingLinkRow salon : salons) {
            if (salon.getBookingSlug() != null) {
                slugs.put(salon.getId(), salon.getBookingSlug());
            } else {
                missing.add(salon);
            }
        }
        if (!missing.isEmpty()) {
            slugs.putAll(issueMissingSalonSlugs(missing));
        }
        return slugs;
    }

    /**
     * Salon twin of {@link #issueSlug}: one retry when the batched issue hits the unique index. The failed
     * attempt's transaction rolled back at the proxy boundary and this method runs outside any
     * transaction, so the retry is a fresh transaction with fresh candidates. A second violation propagates.
     */
    private Map<UUID, String> issueMissingSalonSlugs(List<SalonBookingLinkRow> missing) {
        try {
            return bookingSlugService.issueMissingSalonSlugs(missing);
        } catch (DataIntegrityViolationException firstCollision) {
            return bookingSlugService.issueMissingSalonSlugs(missing);
        }
    }

    private String salonUrlFor(String slug) {
        return bookingLinksProperties.getWebBaseUrl() + SALON_BOOK_PATH + slug;
    }

    private boolean isBookable(Master master) {
        return MasterBookability.isBookable(master)
                && masterRepository.findSearchBookability(List.of(master.getId()), scheduleDateMath.today())
                        .stream()
                        .anyMatch(row -> Boolean.TRUE.equals(row.getBookable()));
    }
}
