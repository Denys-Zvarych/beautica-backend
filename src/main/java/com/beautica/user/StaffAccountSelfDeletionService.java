package com.beautica.user;

import com.beautica.auth.AuthService;
import com.beautica.auth.Role;
import com.beautica.booking.service.BookingService;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.repository.MediaRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.StaffAccountDisposalService;
import com.beautica.salon.audit.AuditOutcome;
import com.beautica.salon.audit.StaffClientReferenceAuditResult;
import com.beautica.salon.service.StaffClientReferenceAuditService;
import com.beautica.salon.service.StaffDisposalReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates {@code SALON_ADMIN}/{@code SALON_MASTER}/{@code INDEPENDENT_MASTER} account
 * self-deletion (Phase 301): {@code DELETE /api/v1/users/me}, widened from {@code CLIENT}-only.
 * {@code SALON_OWNER} is refused before reaching any service — the controller's {@code
 * @PreAuthorize} SpEL gate does not admit that role at all.
 *
 * <p><b>Not a fork of {@link ClientAccountDeletionService}</b> (REUSE-FIRST). The two services
 * share every seam that is actually shareable — the pessimistic account lock ({@code
 * userRepository.findByIdForUpdate}), the 422 cap-exception shape, the outbox-then-booking delete
 * ordering, the after-commit token/profile-cache evictions, the token denylist, and the R2 sweep
 * registration (via the promoted {@link AccountBlobPurgeRegistrar}) — but the DISPOSAL itself is
 * different by design: a CLIENT's future bookings are cancelled one at a time through the
 * ordinary client-cancel path, while a departing PROVIDER's are bulk-declined through {@link
 * BookingService#disposeFutureConfirmedForMasterSelfDelete}, and the account/{@code masters}-row
 * hard delete itself is delegated to {@link StaffAccountDisposalService#dispose} — the SAME
 * promoted seam the owner-initiated {@code SalonService#removeAdmin}/{@code #removeMaster}/{@code
 * #deleteSalonStaff} paths already use, never a re-implementation.
 *
 * <p><b>The structural surprise driving the booking-disposal design</b> (see the phase 301 plan
 * §1): a {@code SALON_MASTER} is a read-only role and can authorize NO existing booking mutation
 * whatsoever ({@link com.beautica.common.security.AuthorizationService#canCancelBooking} rejects
 * {@code ROLE_SALON_MASTER} at its fast path; every provider-cancellation seam does the same).
 * {@link BookingService#disposeFutureConfirmedForMasterSelfDelete} therefore authorizes on
 * ownership of the {@code masters} row rather than on role authority — see that method's own
 * javadoc for the full mechanism.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffAccountSelfDeletionService {

    private static final int DELETE_ACCOUNT_TIMEOUT_SECONDS = 30;

    /**
     * Deliberately its OWN constant, distinct from {@code
     * ClientAccountDeletionService#MAX_FUTURE_BOOKINGS_PER_SELF_DELETE} (50, unchanged). A working
     * master is the COUNTERPARTY to every client's booking and can legitimately hold hundreds
     * across a booked-out month; 50 would refuse ordinary accounts. 500 rows of bulk {@code
     * DECLINE} + {@code DELETE} is comfortably inside the same 30s transaction timeout below —
     * the cap exists to bound one transaction, not to express a policy (Phase 301 Q6).
     */
    static final int MAX_FUTURE_BOOKINGS_PER_STAFF_SELF_DELETE = 500;

    private final UserRepository userRepository;
    private final MasterRepository masterRepository;
    private final SalonRepository salonRepository;
    private final MediaRepository mediaRepository;
    private final BookingService bookingService;
    private final StaffAccountDisposalService staffAccountDisposalService;
    private final StaffClientReferenceAuditService staffClientReferenceAuditService;
    private final AuthService authService;
    private final AccountBlobPurgeRegistrar accountBlobPurgeRegistrar;

    private static final Set<Role> SELF_DELETABLE_ROLES =
            Set.of(Role.SALON_ADMIN, Role.SALON_MASTER, Role.INDEPENDENT_MASTER);

    /**
     * Deletes the calling {@code SALON_ADMIN}/{@code SALON_MASTER}/{@code INDEPENDENT_MASTER}'s
     * own account. See the class javadoc and the phase doc's {@code ## Decisions} block for the
     * full design; this method's body is the numbered disposal order those documents describe.
     *
     * @param userId      the deleting caller's own id (from the JWT — never a path/body value)
     * @param accessToken the caller's raw bearer token, for denylisting — may be {@code null} if
     *                    it could not be extracted (defensive only, mirroring {@code
     *                    AuthService#logout}'s contract)
     * @throws ForbiddenException the user row does not exist (should be unreachable — the caller
     *                            just authenticated as this id), or the reloaded user is not one
     *                            of the three self-deletable roles (defence in depth — the
     *                            controller's {@code @PreAuthorize} is the primary guard)
     * @throws BusinessException  ({@code 409}) the caller owns a salon ({@code
     *                            salons.owner_id} is {@code NOT NULL NO ACTION} — Phase 301 R9,
     *                            structurally unreachable today but enforced rather than assumed),
     *                            or ({@code 409}) the caller's user id is also referenced as a
     *                            CLIENT elsewhere (Phase 301 Q7 — the fail-closed audit), or
     *                            ({@code 422}) the caller holds too many future {@code CONFIRMED}
     *                            bookings to dispose of in one transaction
     */
    @Transactional(timeout = DELETE_ACCOUNT_TIMEOUT_SECONDS)
    public void deleteOwnAccount(UUID userId, String accessToken) {
        // Step 1 — load and guard, mirroring ClientAccountDeletionService#deleteOwnAccount's
        // identical PESSIMISTIC_WRITE row lock rationale: the actor deletes their OWN account, so
        // the lock closes a concurrent-request race on the same account (e.g. a double-submit).
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        if (!SELF_DELETABLE_ROLES.contains(user.getRole())) {
            // Defence in depth only — the controller's @PreAuthorize("hasAnyRole(...)") is the
            // primary guard and already excludes CLIENT (routed to ClientAccountDeletionService
            // instead) and SALON_OWNER (refused before reaching any service).
            throw new ForbiddenException("Access denied");
        }

        // Step 2 — R9 precondition. salons.owner_id is NOT NULL / NO ACTION (V3:3): if this
        // account somehow also owned a salon, a bare hard delete below would surface as a raw
        // DataIntegrityViolationException (500), not a clean refusal. Structurally unreachable
        // today (only hasRole('SALON_OWNER') may create a salon), enforced anyway (Phase 301 R9)
        // — placed before any write, immediately after the role guard.
        if (salonRepository.existsByOwnerId(userId)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Власник салону не може видалити акаунт через цей запит");
        }

        // Step 3 — fail-closed client-residue precondition (Phase 301 Q7), scoped to the ONE user
        // being hard-deleted — mirrors SalonService#removeMaster/#removeAdmin's identical guard.
        // Structurally unreachable (roles are immutable — InviteService never upgrades an existing
        // CLIENT in place), enforced anyway rather than assumed.
        StaffClientReferenceAuditResult audit =
                staffClientReferenceAuditService.runAuditForStaffUserIds(List.of(userId));
        if (audit.outcome() == AuditOutcome.VIOLATIONS_FOUND) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "This account is also referenced as a client and cannot be deleted");
        }

        // Step 4 — pre-read external-storage pointers BEFORE anything cascades them away.
        List<MediaFile> mediaRows = mediaRepository.findByUploaderId(userId);
        String avatarR2Key = user.getAvatarR2Key();
        UUID salonId = user.getSalonId();

        // Step 5 — resolve the caller's OWN masters row, if any. SALON_ADMIN has none (D3 — no
        // calendar of their own); SALON_MASTER/INDEPENDENT_MASTER always have exactly one live,
        // ATTACHED row while the account itself is still live (the only path that detaches a
        // masters row also hard-deletes the account in the SAME statement — see Master#detach's
        // javadoc), so absence here for either of those two roles is unreachable defensively.
        Master master = null;
        if (user.getRole() == Role.SALON_MASTER || user.getRole() == Role.INDEPENDENT_MASTER) {
            master = masterRepository.findByUserId(userId)
                    .orElseThrow(() -> new ForbiddenException("Access denied"));
        }

        // Step 6 — the master-role booking cascade (Q3). SALON_ADMIN skips this entirely — no
        // masters row, no provider bookings (Q3/Q6).
        //
        // Residual-race fix (2026-09 re-audit): the per-master advisory lock is acquired FIRST,
        // via BookingService#acquireMasterLockForSelfDelete, BEFORE the read below builds the
        // fixed futureBookingIds list — not merely before the write at the bottom of this block.
        // A booking that committed for this master between an unlocked read and a later-acquired
        // lock would never appear in futureBookingIds; disposeFutureConfirmedForMasterSelfDelete's
        // own internal re-scan only maps appointment ids for the already-fixed list, it never
        // grows the list itself, so that booking would survive the cascade. Holding the lock
        // across the read, the cap check below, and the eventual decline+delete call closes that
        // gap. See BookingService#acquireMasterLockForSelfDelete's javadoc for the full mechanism.
        List<UUID> futureBookingIds = List.of();
        if (master != null) {
            UUID masterId = master.getId();
            bookingService.acquireMasterLockForSelfDelete(masterId);
            futureBookingIds = bookingService.findFutureConfirmedBookingIdsForMaster(masterId);
            if (futureBookingIds.size() > MAX_FUTURE_BOOKINGS_PER_STAFF_SELF_DELETE) {
                // Fails BEFORE any write — mirrors ClientAccountDeletionService's identical
                // ordering. Two distinct messages (D5, Phase 301 Q3f/R4): a SALON_MASTER cannot
                // cancel their own bookings (every existing decline seam 403s them), so they are
                // directed to the salon owner; an INDEPENDENT_MASTER CAN, so they are directed to
                // self-remedy first.
                String message = user.getRole() == Role.SALON_MASTER
                        ? ("Забагато майбутніх записів (%d). Зверніться до власника салону, щоб він "
                                + "видалив вас у розділі «Команда».").formatted(futureBookingIds.size())
                        : ("Забагато майбутніх записів (%d). Спочатку скасуйте або завершіть майбутні "
                                + "записи (максимум %d) і спробуйте ще раз.")
                                .formatted(futureBookingIds.size(), MAX_FUTURE_BOOKINGS_PER_STAFF_SELF_DELETE);
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, message);
            }

            // Bulk-declines then hard-deletes every future CONFIRMED booking, collapses childless
            // appointment headers, and evicts the slot/calendar caches — see that method's own
            // javadoc for why this cannot reuse the shared salon/master-removal decline cascade.
            bookingService.disposeFutureConfirmedForMasterSelfDelete(userId, masterId, salonId, futureBookingIds);
        }

        // Step 7 — the account + masters-row hard delete itself (D1/D2), delegated to the SAME
        // promoted seam SalonService#removeAdmin/#removeMaster/#deleteSalonStaff use. Handles the
        // masters detach-or-delete branch, the invite-token cleanup (skipped for salonId == null —
        // an INDEPENDENT_MASTER was never invited), the users row delete, and the after-commit
        // tokensValidAfter/user-profile cache evictions for this one user.
        staffAccountDisposalService.dispose(userId, salonId, List.of(userId), StaffDisposalReason.SELF_DELETE);

        // Step 8 — denylist the caller's OWN access token, exactly as AuthService#logout and
        // ClientAccountDeletionService#deleteOwnAccount do. Refresh-token cleanup needs no
        // separate call: every refresh_tokens row for this user cascades on the users delete above.
        authService.denylistAccessToken(accessToken);

        // Step 9 — R2 blob sweep, registered to run strictly after commit (Anti-Bug §O8), via the
        // promoted AccountBlobPurgeRegistrar (Phase 301 — shared with ClientAccountDeletionService,
        // never re-typed). Deliberately NOT swept by staffAccountDisposalService.dispose itself —
        // that shared seam's other three callers (removeAdmin/removeMaster/deleteSalonStaff) leak
        // R2 blobs today (R6, pre-existing, out of scope); only THIS self-delete path sweeps.
        accountBlobPurgeRegistrar.registerAfterCommit(userId, avatarR2Key, mediaRows);

        // Step 10 — audit trail. Ids and counts only, never an email or any other PII.
        log.info("Staff/independent-master account self-delete: user {} (role {}) deleted, "
                        + "{} future booking(s) disposed of",
                userId, user.getRole(), futureBookingIds.size());
    }
}
