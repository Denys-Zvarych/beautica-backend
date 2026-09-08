package com.beautica.salon.service;

import com.beautica.auth.TokensValidAfterCache;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The staff-account hard-delete seam promoted out of {@code SalonService#disposeStaffAccounts}
 * (Phase 301 — REUSE-FIRST: a private method is MOVED and de-privatised, never copied). Three
 * owner-initiated callers depended on it before this promotion — {@code SalonService
 * #deleteSalonStaff}, {@code #removeMaster}, {@code #removeAdmin} — and salon deletion is a
 * shipped, in-production path, so the body below is byte-for-byte the same statement order as
 * before promotion; the ONE additive change is the {@code salonId != null} guard around the
 * invite-token cleanup (see {@link #dispose}'s javadoc), which is unreachable for all three
 * existing callers and exists only for the new {@code INDEPENDENT_MASTER} self-delete caller
 * (Phase 301), who was never invited into a salon and has no {@code invite_tokens} history to
 * clean up.
 *
 * <p>Do not add a second caller-specific branch here. This class stays the single shared
 * disposal seam for every "hard-delete this staff/independent-master account" caller, exactly as
 * {@code SalonService#disposeStaffAccounts} was before the promotion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffAccountDisposalService {

    private final UserRepository userRepository;
    private final InviteTokenRepository inviteTokenRepository;
    private final MasterRepository masterRepository;
    private final Clock clock;
    private final TokensValidAfterCache tokensValidAfterCache;
    private final UserProfileCacheEvictor userProfileCacheEvictor;

    /**
     * HARD-DELETES the given {@code staffUserIds}' accounts, and the {@code masters} rows behind
     * them wherever nothing historical still points at one (phase 295, the 2026-09-04 reversal
     * recorded in phase 294 § <i>Decisions — CLOSED</i>; extracted into this standalone seam by
     * phase 297 D1; promoted out of {@code SalonService} into this dedicated class by phase 301
     * D-R2 so the new staff/independent-master self-delete path can call it too). Replaces phase
     * 290's {@code deactivateSalonStaff} and deletes phase 291's PII-scrub apparatus outright
     * rather than leaving it as an unreachable second erasure policy (D2).
     *
     * <p><b>Four callers, one body (Phase 297 D1 — REUSE-FIRST; joined by {@code removeAdmin} in
     * Phase 299, and by {@code StaffAccountSelfDeletionService} in Phase 301).</b> {@code
     * SalonService#deleteSalonStaff(UUID, UUID)} calls this with the whole salon's resolved staff
     * list; {@code SalonService#removeMaster(UUID, UUID, UUID)} and {@code SalonService
     * #removeAdmin(UUID, UUID, UUID)} each call it with a single-element {@code
     * List.of(userId)}; {@code StaffAccountSelfDeletionService#deleteOwnAccount} calls it with a
     * single-element list for the caller's own id, passing {@code salonId = null} for an {@code
     * INDEPENDENT_MASTER} (Phase 301 §3a — the only additive change to this body since the
     * promotion, see below). Not a copy, not a variant — the disposal, its binding statement
     * order, the {@code chk_masters_detachment_coherent} interaction, the invite-token cleanup and
     * both cache evictions below are a property of the CHECK constraint and the cache contracts,
     * not of the salon-deletion caller, so they live in exactly one place regardless of how many
     * staff accounts are being removed at once. An admin normally contributes an empty {@code
     * staffMasters} fork below (see {@code removeAdmin}'s javadoc for why); nothing about this
     * method special-cases that — it is simply what an empty per-user {@code masters} lookup does.
     *
     * <p>Idempotent by construction (D4 below) even for a one-element call: a caller that has
     * already resolved an empty or already-disposed id list writes nothing.
     *
     * <h3>The order is not a preference — it is the only representable one</h3>
     * <pre>
     *   1. UPDATE masters SET user_id = NULL, detached_first_name = …, detached_last_name = …,
     *                         detached_at = now(), is_active = false   -- Master#detach(...)
     *   2. DELETE FROM users
     * </pre>
     * A bare {@code DELETE FROM users} cannot work, and no transaction ordering rescues it.
     * {@code fk_masters_user_id} is {@code ON DELETE SET NULL} (V157), so the delete writes
     * {@code masters.user_id = NULL} and nothing else — and that row then satisfies NEITHER arm of
     * {@code chk_masters_detachment_coherent}: the ATTACHED arm needs {@code detached_at IS NULL}
     * with a non-null {@code user_id}, the DETACHED arm needs the name snapshot AND
     * {@code detached_at}. {@code detached_at} cannot be pre-set while the row is still attached
     * (the ATTACHED arm forbids it), and Postgres cannot defer a CHECK — only UNIQUE / PK / FK /
     * EXCLUDE are deferrable. So the snapshot and the null MUST land in one statement, before the
     * account delete. Pinned by {@code MasterDetachmentContractIT} case 5, which is marked
     * <i>BINDING ON PHASE 295</i>; do NOT weaken the CHECK to make a one-step delete work.
     *
     * <h3>Masters — deleted when they can be, detached when they cannot (D1)</h3>
     * The caller is responsible for deactivating every affected master row FIRST (see {@code
     * SalonService#deleteSalonStaff(UUID, UUID)}'s use of {@link MasterService#deactivateMasters},
     * or {@code removeMaster}'s use of {@link MasterService#deactivateMaster}) — this method only
     * disposes of the account and the master row behind it, it does not deactivate.
     *
     * <p>For the staff master rows AS A BATCH:
     * {@link MasterRepository#findIdsWithHistoricalReferences} asks — in ONE query, never per
     * master (phase 295 audit HIGH-2) — which of them still have a {@code bookings.master_id} /
     * {@code reviews.master_id} / {@code client_reviews.author_master_id} row pointing at them.
     * The loop that follows is a pure in-memory branch over that id set.
     * <ul>
     *   <li><b>absent from the set</b> → {@code DELETE FROM masters}. The common case: an invited
     *       master who never
     *       took a booking. {@code master_services}, {@code weekly_schedules},
     *       {@code schedule_exceptions} and {@code working_hours} all cascade.</li>
     *   <li><b>present in the set</b> → {@link Master#detach} — name snapshot, {@code user_id = NULL},
     *       {@code is_active = false}. The account is still deleted; what survives is a name label
     *       on a record belonging to somebody else (a client's own past receipt, a review a client
     *       wrote). Accepted by the user on 2026-09-04 (phase 294 R2); deleting that third-party
     *       history to erase the label was offered and REJECTED.</li>
     * </ul>
     *
     * <h3>Users — deleted unconditionally (D1)</h3>
     * {@code staffUserIds} is the caller's resolved {@code SALON_MASTER}/{@code SALON_ADMIN}
     * account set — for {@code SalonService#deleteSalonStaff(UUID, UUID)} via {@code
     * StaffClientReferenceAuditService#resolveSalonStaffUserIds} (phase 289's {@code
     * findSalonStaffUserIds} reused, not re-derived; that resolution structurally can NEVER
     * include the salon's owner — the owner-account exemption, phase 290 D3 / 270 D4, is a
     * structural property of that query), for {@code removeMaster} the one already-validated
     * master's user id, for {@code removeAdmin} the one already-validated admin's user id, for
     * {@code StaffAccountSelfDeletionService} the caller's own already-authenticated user id.
     *
     * <p>Everything with {@code ON DELETE CASCADE} on {@code users} goes with the row: refresh
     * tokens, device tokens, password-reset tickets, media rows, favourites.
     * {@code created_by_user_id} on {@code bookings}/{@code appointments} nulls out (phase 294
     * D5), so a walk-in the deleted staff member rang up survives with its attribution cleared.
     *
     * <h3>Idempotency comes from row absence, not a flag (D4)</h3>
     * Deleting a row is idempotent by construction — an empty {@code staffUserIds} writes nothing.
     * The phase 291 scrub marker column and its guard are gone with the column (V158).
     *
     * <h3>Known limit — R2 avatar blobs</h3>
     * {@code media_files} cascades on {@code uploader_id} and {@code users.avatar_r2_key} vanishes
     * with the row, so the R2 objects behind both are orphaned (§O-8). That is phase 268's media
     * purge, explicitly out of scope here for the three owner-initiated callers — recorded, not
     * forgotten (Phase 301 R6). The new self-delete caller sweeps R2 itself, from its OWN
     * after-commit registrar, never from inside this shared seam — see
     * {@code StaffAccountSelfDeletionService}.
     *
     * <h3>{@code salonId == null} — the ONE additive change since promotion (Phase 301 §3a)</h3>
     * An {@code INDEPENDENT_MASTER} was never invited into any salon and carries no {@code
     * invite_tokens} history, so {@code salonId} may be {@code null} for that caller only. Audited
     * line by line against every other statement in this body: {@code salonId} is read nowhere
     * else — the cache-eviction loop, {@code findAllByUserIdInWithUser}, {@code
     * findIdsWithHistoricalReferences}, the detach-or-delete loop, the flush and the {@code users}
     * delete are all keyed on user/master ids, never on {@code salonId}. Only the invite-token
     * cleanup and the trailing log line touch it, and both tolerate {@code null} — the log line
     * purely cosmetically (renders {@code null} for an independent master's audit trail; it is
     * never used programmatically). Every existing caller passes a non-null {@code salonId}, so
     * this guard is a pure no-op for all three of them.
     *
     * @param actorId      the deleting actor's id — the salon owner for the three owner-initiated
     *                     callers, or the departing account's own id for a self-delete
     * @param salonId      the salon {@code staffUserIds} belongs to, or {@code null} for an {@code
     *                     INDEPENDENT_MASTER} self-delete (Phase 301 §3a — the only role with no
     *                     salon above it)
     * @param staffUserIds the resolved staff/independent-master account ids to hard-delete
     */
    public void dispose(UUID actorId, @Nullable UUID salonId, List<UUID> staffUserIds) {
        if (staffUserIds.isEmpty()) {
            return;
        }

        // Phase 295 (replaces phase 291 D9's tombstone REDACTION of the same rows). MUST run
        // BEFORE the users delete below: the statement joins invite_tokens.email against the staff
        // member's live users.email, so once the account row is gone the join matches nothing and
        // the address is stranded in this salon's invite history forever — and a stale PENDING row
        // would collide with the phase 296 re-invite. One bulk statement for the whole list.
        //
        // Phase 301 §3a: guarded on salonId != null. An INDEPENDENT_MASTER self-delete caller was
        // never invited into any salon, so there is nothing to clean up; every existing
        // owner-initiated caller passes a non-null salonId and this guard is a pure no-op for them.
        if (salonId != null) {
            inviteTokenRepository.deleteBySalonIdAndStaffUserIds(salonId, staffUserIds);
        }

        // Cache evictions stay per-user — each key is a different person, so the fan-out is
        // correctly proportionate to N. Both caches are read AFTER this transaction commits by
        // paths that would otherwise serve a deleted account: TokensValidAfterCache is what
        // JwtAuthenticationFilter consults, and the user-profile cache backs GET /users/me.
        //
        // !! LOAD-BEARING FOR SECURITY, not a perf nicety (phase 295 audit HIGH-1). !!
        // The row this cascade deletes is exactly what that cache reads, and the filter's guard is
        // driven by its answer. Until the audit fix, "no row" and "row with a null
        // tokens_valid_after" were the SAME Optional.empty() to the filter, so a deleted account's
        // access token stayed valid for the rest of its 3600s TTL. TokensValidAfterCache now
        // answers TokenValidityState.ABSENT for a missing row and the filter refuses to
        // authenticate on it; this eviction is what makes that effective on the very NEXT request
        // instead of after the cache's 60s TTL, which is only the fallback bound. Do not drop it,
        // and do not move it inline — invalidateAfterCommit is afterCommit for the
        // read-through-race reason its own javadoc gives.
        for (UUID staffUserId : staffUserIds) {
            tokensValidAfterCache.invalidateAfterCommit(staffUserId);
            userProfileCacheEvictor.evictAfterCommit(staffUserId);
        }

        // ── step 1 of the binding order: settle every masters row that references a staff
        // account, so that DELETE FROM users below has nothing left pointing at it.
        //
        // Deliberately re-fetched by user id rather than trusting a caller-supplied master list:
        // an is_active-scoped list (e.g. deleteSalonStaff's now-deactivated salonMasters) or a
        // master deactivated by an earlier operation still owns an ATTACHED masters row whose FK
        // would fire ON DELETE SET NULL and violate chk_masters_detachment_coherent if skipped.
        Instant now = clock.instant();
        int detached = 0;
        int deleted = 0;
        List<Master> staffMasters = masterRepository.findAllByUserIdInWithUser(staffUserIds);

        // ONE set-based probe for the whole batch, resolved BEFORE the loop (phase 295 audit
        // HIGH-2). The loop below is then a pure in-memory branch and issues no query of its own.
        // The predecessor asked per master, and because that finder is a native query with no
        // declared query spaces Hibernate flushed the whole session before each call — so every
        // iteration flushed the previous iteration's detach UPDATE alone and re-dirty-checked a
        // persistence context still holding everything the phase 293 decline cascade loaded.
        // Removing it also makes masterRepository.flush() below the ONLY flush in this method,
        // which is what finally lets the detach UPDATEs and the master DELETEs batch.
        //
        // A salon whose only staff are SALON_ADMINs (no masters rows at all) reaches here with an
        // empty list — pinned by SalonStaffHardDeleteIT case 5b.
        //
        // CORRECTION, measured 2026-09-04 (phase 295 QA). An earlier version of this comment
        // claimed the guard was load-bearing because "an empty bind renders `IN ()`, which is a
        // syntax error". That is FALSE on this stack: Hibernate 6 rewrites an empty list bind for
        // an IN predicate into an always-false form, and calling
        // findIdsWithHistoricalReferences(List.of()) directly returns an empty list without
        // throwing (probed against the Testcontainers Postgres, and confirmed by deleting this
        // guard and watching all 17 cases stay green). The guard is kept because skipping a
        // pointless round trip is worth one branch — NOT because the query would fail. Do not
        // "restore" a correctness rationale here.
        Set<UUID> mastersWithHistory = staffMasters.isEmpty()
                ? Set.of()
                : Set.copyOf(masterRepository.findIdsWithHistoricalReferences(
                        staffMasters.stream().map(Master::getId).toList()));

        for (Master staffMaster : staffMasters) {
            if (!mastersWithHistory.contains(staffMaster.getId())) {
                masterRepository.delete(staffMaster);
                deleted++;
            } else {
                // ONE Hibernate UPDATE writing all five columns together — the whole-row CHECK is
                // evaluated against the result, so the snapshot and the null must not be split.
                // The name is read off the live users row that is about to be destroyed; this is
                // the last moment it exists.
                staffMaster.detach(
                        staffMaster.getUser().getFirstName(),
                        staffMaster.getUser().getLastName(),
                        now);
                detached++;
            }
        }

        // ── step 2 of the binding order. The explicit flush is MANDATORY, not defensive:
        // deleteAllByIdInBatch is a bulk JPQL DELETE, and Hibernate's AUTO flush only flushes
        // pending work whose query space overlaps the statement's — `users`, not `masters`. Without
        // this the detach UPDATEs would still be sitting in the persistence context when the
        // account rows disappear, the FK's ON DELETE SET NULL would fire first, and the flush that
        // followed would try to update rows that no longer satisfy the coherence CHECK. flush() on
        // any repository flushes the whole EntityManager, which is exactly what is wanted here.
        masterRepository.flush();
        userRepository.deleteAllByIdInBatch(staffUserIds);

        // Audit trail — one line per BATCH, ids and counts only, never an email or any other
        // scrubbed value (this repo's PII-in-logs convention). A hard delete of N accounts is the
        // single most consequential mutation this service performs; it must leave a record of who
        // ordered it even though the rows it names are gone.
        log.info("Salon deletion staff hard-delete: {} account(s) deleted, {} master row(s) deleted, "
                        + "{} master row(s) detached for salon {} by actor {}",
                staffUserIds.size(), deleted, detached, salonId, actorId);
    }
}
