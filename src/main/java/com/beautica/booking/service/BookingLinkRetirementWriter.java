package com.beautica.booking.service;

import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.entity.RetiredSlugReason;
import com.beautica.booking.repository.RetiredBookingSlugRepository;
import com.beautica.common.cache.MasterProfileCacheEvictor;
import com.beautica.common.cache.SalonBookingInfoCacheEvictor;
import com.beautica.common.exception.ConflictException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Phase 378 — the transactional half of a booking-link regenerate / revoke: one short transaction per
 * call, in the D11 order — advisory lock on the current slug text → (regenerate) new candidate locked and
 * probed against live slugs AND tombstones → conditional compare-and-swap {@code UPDATE} (0 rows → 409) →
 * tombstone {@code INSERT} → post-commit cache eviction (D13). Authorization, the kill-switch and the
 * single collision retry live in {@link BookingLinkRetirementService}, which calls in here so a retry
 * starts a fresh transaction.
 *
 * <p>The slug-text lock is {@link SalonRepository#lockBookingSlugCandidates} — the one advisory lock every
 * salon-slug writer and every regenerate takes before probing, so a slug being retired can never be handed
 * out concurrently (D11c). Its transaction-local 3s {@code lock_timeout} also bounds the CAS row-lock wait.
 * A candidate equal to the slug already held is not locked twice; a genuine lock-order deadlock between two
 * regenerates surfaces as a {@code PessimisticLockingFailureException}, which
 * {@link BookingLinkRetirementService} maps to 409 (378 audit perf INFO-4).
 *
 * <p>Tombstones store only {@link RetiredSlugHasher#hash} of the retired slug (V205, 378 audit security MEDIUM).
 */
@Service
@RequiredArgsConstructor
public class BookingLinkRetirementWriter {

    static final String STALE_MESSAGE = "Booking link has changed; refresh and try again";
    /** The CAS token for "no live slug" — a real slug is never empty (V86 / V200 CHECK). */
    private static final String NO_SLUG = "";

    private final MasterRepository masterRepository;
    private final SalonRepository salonRepository;
    private final RetiredBookingSlugRepository retiredBookingSlugRepository;
    private final BookingSlugService bookingSlugService;
    private final MasterProfileCacheEvictor masterProfileCacheEvictor;
    private final SalonBookingInfoCacheEvictor salonBookingInfoCacheEvictor;
    private final Clock clock;

    /** Retires {@code currentSlug} (if any) and issues a new slug; returns it. 409 when stale. */
    @Transactional
    public String regenerateMaster(Master master, String currentSlug, UUID actorId) {
        lockSlugText(currentSlug);
        String newSlug = bookingSlugService.generateMasterSlugUnderLock(
                master.displayFirstName(), master.displayLastName(), currentSlug);
        if (masterRepository.replaceBookingSlug(master.getId(), casToken(currentSlug), newSlug) == 0) {
            throw new ConflictException(STALE_MESSAGE);
        }
        retireMasterSlug(master.getId(), currentSlug, actorId, RetiredSlugReason.REGENERATED);
        return newSlug;
    }

    /**
     * Retires {@code currentSlug} and leaves no live slug. Already revoked + {@code currentSlug == null} is a
     * no-op (D8: no write, no tombstone). 409 when stale.
     */
    @Transactional
    public void revokeMaster(UUID masterId, String currentSlug, UUID actorId) {
        if (currentSlug == null && isMasterRevoked(masterId)) {
            return;
        }
        lockSlugText(currentSlug);
        if (masterRepository.revokeBookingSlug(masterId, casToken(currentSlug), clock.instant()) == 0) {
            throw new ConflictException(STALE_MESSAGE);
        }
        retireMasterSlug(masterId, currentSlug, actorId, RetiredSlugReason.REVOKED);
    }

    /** SALON twin of {@link #regenerateMaster}; the new slug comes from the salon generator. */
    @Transactional
    public String regenerateSalon(UUID salonId, String salonName, String currentSlug, UUID actorId) {
        List<String> memberSlugs = activeMemberSlugs(salonId);
        lockSlugText(currentSlug);
        String newSlug = bookingSlugService.generateSalonSlug(salonName, currentSlug);
        if (salonRepository.replaceBookingSlug(salonId, casToken(currentSlug), newSlug) == 0) {
            throw new ConflictException(STALE_MESSAGE);
        }
        retireSalonSlug(salonId, currentSlug, actorId, RetiredSlugReason.REGENERATED);
        evictSalonPagesAfterCommit(salonId, currentSlug, memberSlugs);
        return newSlug;
    }

    /** SALON twin of {@link #revokeMaster}. */
    @Transactional
    public void revokeSalon(UUID salonId, String currentSlug, UUID actorId) {
        if (currentSlug == null && isSalonRevoked(salonId)) {
            return;
        }
        List<String> memberSlugs = activeMemberSlugs(salonId);
        lockSlugText(currentSlug);
        if (salonRepository.revokeBookingSlug(salonId, casToken(currentSlug), clock.instant()) == 0) {
            throw new ConflictException(STALE_MESSAGE);
        }
        retireSalonSlug(salonId, currentSlug, actorId, RetiredSlugReason.REVOKED);
        evictSalonPagesAfterCommit(salonId, currentSlug, memberSlugs);
    }

    private boolean isMasterRevoked(UUID masterId) {
        MasterRepository.BookingLinkState state = masterRepository.findBookingLinkStateById(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));
        return state.getBookingSlug() == null && state.revoked();
    }

    private boolean isSalonRevoked(UUID salonId) {
        SalonRepository.SalonBookingLinkStateRow state = salonRepository.findBookingLinkStateById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found"));
        return state.getBookingSlug() == null && state.revoked();
    }

    private void lockSlugText(String currentSlug) {
        if (currentSlug != null) {
            salonRepository.lockBookingSlugCandidates(new String[] {currentSlug});
        }
    }

    private static String casToken(String currentSlug) {
        return currentSlug == null ? NO_SLUG : currentSlug;
    }

    /** Tombstone + per-key {@code booking-slug-info} eviction of the old slug; nothing when none was live. */
    private void retireMasterSlug(UUID masterId, String oldSlug, UUID actorId, RetiredSlugReason reason) {
        if (oldSlug == null) {
            return;
        }
        retiredBookingSlugRepository.insertMasterTombstone(
                RetiredSlugHasher.hash(BookingLinkKind.MASTER, oldSlug), masterId, actorId, reason.name(), clock.instant());
        masterProfileCacheEvictor.evictBookingSlugInfoBySlugAfterCommit(oldSlug);
    }

    private void retireSalonSlug(UUID salonId, String oldSlug, UUID actorId, RetiredSlugReason reason) {
        if (oldSlug != null) {
            retiredBookingSlugRepository.insertSalonTombstone(
                    RetiredSlugHasher.hash(BookingLinkKind.SALON, oldSlug), salonId, actorId, reason.name(), clock.instant());
        }
    }

    /**
     * 378 audit perf LOW-2 — the member masters' page keys in ONE statement, read BEFORE the slug lock and the
     * CAS row lock so the read never extends the time those locks are held.
     */
    private List<String> activeMemberSlugs(UUID salonId) {
        return masterRepository.findActiveMemberBookingSlugsBySalonId(salonId);
    }

    /**
     * D13 — the salon page ({@code salon-booking-slug-info}: by value AND per key on the retired slug, which
     * also catches a load in flight — 378 audit security LOW) and every member master's page
     * ({@code booking-slug-info}, per key: it renders the salon's slug in its salon reference), after commit.
     */
    private void evictSalonPagesAfterCommit(UUID salonId, String oldSlug, List<String> memberSlugs) {
        salonBookingInfoCacheEvictor.evictSalonAfterCommit(salonId);
        salonBookingInfoCacheEvictor.evictSlugAfterCommit(oldSlug);
        masterProfileCacheEvictor.evictBookingSlugInfoBySlugsAfterCommit(memberSlugs);
    }
}
