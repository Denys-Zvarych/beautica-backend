package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.dto.BookingLinkResponse;
import com.beautica.common.exception.ConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.config.BookingLinksProperties;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonRepository.SalonBookingLinkStateRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Phase 378 — regenerate / revoke of the caller's shareable booking link (MASTER: the independent master's
 * own; SALON: the owner's or bound admin's salon). Every call runs, in order: kill-switch (D9, 404 before
 * any database work) → authorization on the stored rows (D6) → an early stale check against the row just
 * read (D7) → the write in {@link BookingLinkRetirementWriter}'s own short transaction → the response in
 * the exact {@code GET /booking-links/mine} shape ({@link BookingLinkService}).
 *
 * <p>Deliberately NOT {@code @Transactional} (the {@link BookingLinkService} pattern): the reads and the
 * response's bookability read run auto-commit, and a unique-index violation on the new slug — a lazy issue
 * elsewhere drew the same candidate between probe and write — is retried ONCE in a fresh transaction with a
 * fresh candidate (D11d); a second violation propagates.
 *
 * <p>Audit (D14): the tombstone row, plus one INFO line with ids only — never a name, phone or slug owner's
 * personal data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingLinkRetirementService {

    private final BookingLinksProperties bookingLinksProperties;
    private final MasterRepository masterRepository;
    private final SalonRepository salonRepository;
    private final AuthorizationService authorizationService;
    private final BookingLinkRetirementWriter writer;
    private final BookingLinkService bookingLinkService;

    /** {@code POST /booking-links/master/regenerate}. */
    public BookingLinkResponse regenerateMaster(UUID userId, String currentSlug) {
        Master master = ownMasterRow(userId, currentSlug);
        String newSlug = retryOnCollision(() -> writer.regenerateMaster(master, currentSlug, userId));
        logRetired(BookingLinkKind.MASTER, master.getId(), userId, "REGENERATED", currentSlug);
        return bookingLinkService.masterLinkWith(master, newSlug);
    }

    /** {@code DELETE /booking-links/master}. */
    public BookingLinkResponse revokeMaster(UUID userId, String currentSlug) {
        Master master = ownMasterRow(userId, currentSlug);
        writer.revokeMaster(master.getId(), currentSlug, userId);
        logRetired(BookingLinkKind.MASTER, master.getId(), userId, "REVOKED", currentSlug);
        return bookingLinkService.masterLinkWith(master, null);
    }

    /** {@code POST /booking-links/salons/{salonId}/regenerate}. */
    public BookingLinkResponse regenerateSalon(UUID userId, Role role, UUID salonId, String currentSlug) {
        SalonBookingLinkStateRow salon = memberSalonRow(userId, role, salonId, currentSlug);
        String newSlug = retryOnCollision(() -> writer.regenerateSalon(salonId, salon.getName(), currentSlug, userId));
        logRetired(BookingLinkKind.SALON, salonId, userId, "REGENERATED", currentSlug);
        return bookingLinkService.salonLinkWith(
                salonId, salon.getName(), newSlug, bookingLinkService.isSalonBookable(salonId));
    }

    /** {@code DELETE /booking-links/salons/{salonId}}. */
    public BookingLinkResponse revokeSalon(UUID userId, Role role, UUID salonId, String currentSlug) {
        SalonBookingLinkStateRow salon = memberSalonRow(userId, role, salonId, currentSlug);
        writer.revokeSalon(salonId, currentSlug, userId);
        logRetired(BookingLinkKind.SALON, salonId, userId, "REVOKED", currentSlug);
        return BookingLinkResponse.revoked(BookingLinkKind.SALON, salon.getName(), salonId);
    }

    /**
     * The caller's own ACTIVE independent-master row. The controller already admits only the
     * {@code INDEPENDENT_MASTER} token role; the stored {@code master_type} is re-checked so a stale token
     * cannot act on a salon master's slug.
     */
    private Master ownMasterRow(UUID userId, String currentSlug) {
        requireShareEnabled();
        Master master = masterRepository.findActiveByUserIdWithUserAndSalon(userId)
                .orElseThrow(() -> new NotFoundException("Booking link not found"));
        if (master.getMasterType() != MasterType.INDEPENDENT_MASTER) {
            throw new ForbiddenException("Only an independent master manages a master booking link");
        }
        requireCurrent(master.getBookingSlug(), currentSlug);
        return master;
    }

    /**
     * The salon, if the caller is its owner or a {@code SALON_ADMIN} bound to it (D6). Membership is
     * decided BEFORE existence / activity, so a foreign or unknown salon is always 403 — never an existence
     * oracle; only the caller's own inactive salon is 404.
     */
    private SalonBookingLinkStateRow memberSalonRow(UUID userId, Role role, UUID salonId, String currentSlug) {
        requireShareEnabled();
        SalonBookingLinkStateRow salon = salonRepository.findBookingLinkStateById(salonId).orElse(null);
        if (!isMember(userId, role, salonId, salon)) {
            throw new ForbiddenException("Not a member of this salon");
        }
        if (salon == null || !Boolean.TRUE.equals(salon.getActive())) {
            throw new NotFoundException("Booking link not found");
        }
        requireCurrent(salon.getBookingSlug(), currentSlug);
        return salon;
    }

    private boolean isMember(UUID userId, Role role, UUID salonId, SalonBookingLinkStateRow salon) {
        if (role == Role.SALON_OWNER) {
            return salon != null && userId.equals(salon.getOwnerId());
        }
        return role == Role.SALON_ADMIN && authorizationService.adminBelongsToSalon(userId, salonId);
    }

    /** D9 — sharing switched off server-side: the mutations do not exist. */
    private void requireShareEnabled() {
        if (!bookingLinksProperties.isShareEnabled()) {
            throw new NotFoundException("Booking link not found");
        }
    }

    /** D7 early check (the writer's conditional UPDATE is the authoritative one). */
    private static void requireCurrent(String storedSlug, String currentSlug) {
        if (!Objects.equals(storedSlug, currentSlug)) {
            throw new ConflictException(BookingLinkRetirementWriter.STALE_MESSAGE);
        }
    }

    /**
     * D11d — one retry in a fresh transaction when the new slug hit a unique index; a second propagates.
     *
     * <p>378 audit perf INFO-4: a regenerate locks the current slug, then each candidate — unsorted, so two
     * regenerates can in theory wait on each other's held slug. Postgres breaks that (deadlock, or the 3s
     * {@code lock_timeout}) with a {@link PessimisticLockingFailureException}; it is answered here as the same
     * "changed, refresh and try again" 409 as a stale CAS, never retried (the other side wins).
     */
    private static String retryOnCollision(Supplier<String> write) {
        try {
            try {
                return write.get();
            } catch (DataIntegrityViolationException firstCollision) {
                return write.get();
            }
        } catch (PessimisticLockingFailureException lockConflict) {
            throw new ConflictException(BookingLinkRetirementWriter.STALE_MESSAGE);
        }
    }

    /** D14 — ids only; logged once the write committed, and only when a live slug was actually retired. */
    private static void logRetired(BookingLinkKind kind, UUID ownerId, UUID actorId, String reason, String oldSlug) {
        if (oldSlug != null) {
            log.info("bookingLink.retired kind={} ownerId={} actorId={} reason={}", kind, ownerId, actorId, reason);
        }
    }
}
