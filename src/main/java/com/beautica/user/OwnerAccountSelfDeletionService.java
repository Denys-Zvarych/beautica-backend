package com.beautica.user;

import com.beautica.auth.AuthService;
import com.beautica.auth.Role;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.OwnerAccountDeletionBlockedException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.repository.MediaFileKey;
import com.beautica.media.repository.MediaRepository;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.salon.service.StaffAccountDisposalService;
import com.beautica.salon.service.StaffDisposalReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates {@code SALON_OWNER} account self-deletion (Phase 488, SUP-11 / SUP-67):
 * {@code DELETE /api/v1/users/me} for an owner whose salons are all deactivated.
 *
 * <p><b>A thin orchestrator, not a third deletion engine</b> (D3, REUSE-FIRST). Every step composes a seam
 * that already exists: the owner row lock is {@link SalonService#lockOwnerForCreate} (the SAME lock
 * {@code createSalon}/{@code updateSalon}/{@code deactivateSalon} take first — B490 lock order owner → salon),
 * the owner-as-master row and the {@code users} row go
 * through {@link StaffAccountDisposalService#dispose} (detach-to-name-stub when bookings/reviews reference
 * the master, hard delete otherwise — SAL-21/B301), the token denylist and R2 sweep are the ones
 * {@link StaffAccountSelfDeletionService} uses. The only new writes are the queued-invite outbox discard
 * ({@link NotificationOutboxService#discardPendingInvitesForOwnerSalons}) and the salon detach + invite delete
 * ({@link SalonService#detachDeactivatedSalonsForOwnerDeletion}).
 *
 * <p><b>No staff-as-client audit.</b> {@code StaffClientReferenceAuditService} audits {@code SALON_MASTER}
 * / {@code SALON_ADMIN} only, so for a {@code SALON_OWNER} it could never report a violation — running it
 * would only cost three no-op queries per delete.
 *
 * <p><b>Why the salons are detached, not deleted</b> (D2, answered 2026-11-12): SAL-D05 keeps a deleted
 * salon's row, and its clients own the bookings/reviews on it. The person's data (account, avatar, tokens,
 * owner-as-master identity) is gone; what stays is anonymous business history ({@code owner_id = NULL},
 * contacts blank). A re-registration with the same email gets a brand-new id and reaches none of it (AC5):
 * nothing in the system relinks by email or phone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerAccountSelfDeletionService {

    private static final int DELETE_ACCOUNT_TIMEOUT_SECONDS = 30;

    private final SalonService salonService;
    private final SalonRepository salonRepository;
    private final MasterRepository masterRepository;
    private final MediaRepository mediaRepository;
    private final NotificationOutboxService notificationOutboxService;
    private final StaffAccountDisposalService staffAccountDisposalService;
    private final AuthService authService;
    private final AccountBlobPurgeRegistrar accountBlobPurgeRegistrar;

    /**
     * Deletes the calling {@code SALON_OWNER}'s own account. Every refusal happens before any write.
     *
     * @param userId      the caller's own id (from the JWT — never a path/body value)
     * @param accessToken the caller's raw bearer token, for denylisting — may be {@code null}
     * @throws ForbiddenException                   the user row is gone or is not a {@code SALON_OWNER}
     * @throws OwnerAccountDeletionBlockedException ({@code 409}) {@code OWNER_HAS_ACTIVE_SALONS} — at least one
     *                                              salon is still active; {@code OWNER_SALON_HAS_STAFF} —
     *                                              another account is still linked to an owned salon
     */
    @Transactional(timeout = DELETE_ACCOUNT_TIMEOUT_SECONDS)
    public void deleteOwnAccount(UUID userId, String accessToken) {
        // Step 1 — the owner row lock FIRST (B490): serialises with createSalon/updateSalon/deactivateSalon,
        // which all lock owner → salon. A createSalon racing this delete either commits first (step 2 then
        // sees its active salon → 409) or blocks here and finds no owner row once this commits (404). An
        // active salon can never end up ownerless (also pinned by chk_salons_ownerless_inactive, V236).
        User user = salonService.lockOwnerForCreate(userId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        if (user.getRole() != Role.SALON_OWNER) {
            // Defence in depth — UserController routes only SALON_OWNER here.
            throw new ForbiddenException("Access denied");
        }

        // Step 2 — D1 precondition, read under the lock (a fresh READ COMMITTED snapshot per statement).
        if (salonRepository.existsByOwnerIdAndIsActiveTrue(userId)) {
            throw new OwnerAccountDeletionBlockedException(
                    OwnerAccountDeletionBlockedException.Code.OWNER_HAS_ACTIVE_SALONS);
        }

        // Step 3 — mirrors the SQL template's Part B staff guard; unreachable after SAL-21.
        if (salonRepository.existsOtherAccountLinkedToOwnerSalons(userId)) {
            throw new OwnerAccountDeletionBlockedException(
                    OwnerAccountDeletionBlockedException.Code.OWNER_SALON_HAS_STAFF);
        }

        // Step 4 — pre-read the account's own masters rows (the owner-as-master identity; masters.user_id is
        // UNIQUE, so at most one) and its own media keys with the SAME predicates the disposal deletes by,
        // BEFORE anything is deleted. USER rows + the owner-master's MASTER portfolio only — never a SALON row.
        List<Master> ownMasters = masterRepository.findAllByUserIdInWithUser(List.of(userId));
        List<UUID> ownMasterIds = ownMasters.stream().map(Master::getId).toList();
        List<MediaFileKey> mediaRows = mediaRepository.findAccountMediaKeys(List.of(userId), ownMasterIds);

        // Step 5 — still-queued INVITE e-mails for these salons are discarded BEFORE their invite tokens go
        // (the delete resolves them by sub-select), so the drain worker never mails an invite into a salon
        // that no longer has an owner.
        notificationOutboxService.discardPendingInvitesForOwnerSalons(userId);

        // Step 6 — D2 detach: invites deleted, owner_id cleared, contacts + owner-written text blanked;
        // caches evicted after commit. MUST precede the users delete: salons.owner_id is NO ACTION.
        List<UUID> detachedSalonIds = salonService.detachDeactivatedSalonsForOwnerDeletion(userId);

        // Step 7 — owner-as-master row (stub when history references it, else deleted) + the users row,
        // through the one shared disposal seam. salonId = null: an owner was never invited, so there is no
        // staff invite history to clean (the salons' own invites went in step 6). Evicts tokensValidAfter and
        // the user profile after commit; refresh/device tokens, favourites, in-app notifications cascade.
        staffAccountDisposalService.dispose(
                userId, null, List.of(userId), StaffDisposalReason.OWNER_SELF_DELETE, ownMasters);

        // Step 8 — the caller's own access token, exactly as the client/staff self-delete paths.
        authService.denylistAccessToken(accessToken);

        // Step 9 — avatar + own media blobs, purged strictly after commit (§O-8). The disposal skips its own
        // sweep for a self-initiated reason, so this is the only sweeper (no double purge). Salon photos
        // were purged when each salon was deleted (SAL-19); an owner has no INDEPENDENT_MASTER catalogue.
        accountBlobPurgeRegistrar.registerAfterCommit(user, mediaRows);

        // Step 10 — audit trail: ids and counts only, never PII.
        log.info("Owner account self-delete: user {} deleted, {} salon(s) detached, {} master row(s) disposed",
                userId, detachedSalonIds.size(), ownMasters.size());
    }
}
