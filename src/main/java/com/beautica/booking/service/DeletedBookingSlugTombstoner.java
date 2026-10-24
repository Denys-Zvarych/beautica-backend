package com.beautica.booking.service;

import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.repository.RetiredBookingSlugRepository;
import com.beautica.master.entity.Master;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 379 D7 — tombstones the live booking slug of a master or salon that is being deleted, with reason
 * {@code DELETED}, in the caller's transaction. Keeps 378 D2 ("a retired slug is never re-issued") true for
 * deleted accounts and makes their old links answer 410 (via {@link RetiredBookingSlugGuard}).
 *
 * <p>The delete paths (step 0): {@code StaffAccountDisposalService} — every disposed masters row, deleted or
 * detached (salon staff removal, salon deletion, independent-master self-delete); {@code SalonService
 * #deactivateSalon} — the salon's own slug (a salon row is never hard-deleted; deletion deactivates it for
 * good). Hash-only rows (V205). Idempotent ({@code ON CONFLICT DO NOTHING}), so a slug that is somehow already
 * tombstoned never aborts a deletion.
 */
@Service
@RequiredArgsConstructor
public class DeletedBookingSlugTombstoner {

    private final RetiredBookingSlugRepository retiredBookingSlugRepository;
    private final Clock clock;

    /** One statement for every master in {@code masters} that holds a live slug; nothing when none does. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void tombstoneMasters(List<Master> masters) {
        List<Master> withSlug = masters.stream().filter(m -> m.getBookingSlug() != null).toList();
        if (withSlug.isEmpty()) {
            return;
        }
        String[] slugHashes = withSlug.stream()
                .map(m -> RetiredSlugHasher.hash(BookingLinkKind.MASTER, m.getBookingSlug()))
                .toArray(String[]::new);
        String[] masterIds = withSlug.stream().map(m -> m.getId().toString()).toArray(String[]::new);
        retiredBookingSlugRepository.insertDeletedMasterTombstones(slugHashes, masterIds, clock.instant());
    }

    /** Tombstones the salon's live slug; nothing when it has none (revoked, or never issued). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void tombstoneSalon(UUID salonId, String bookingSlug) {
        Objects.requireNonNull(salonId, "salonId");
        if (bookingSlug == null) {
            return;
        }
        retiredBookingSlugRepository.insertDeletedSalonTombstone(
                RetiredSlugHasher.hash(BookingLinkKind.SALON, bookingSlug), salonId, clock.instant());
    }
}
