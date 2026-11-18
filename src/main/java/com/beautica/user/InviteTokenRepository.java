package com.beautica.user;

import com.beautica.common.repository.TransactionLockTimeout;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InviteTokenRepository extends JpaRepository<InviteToken, UUID>, TransactionLockTimeout {

    Optional<InviteToken> findByToken(String token);

    /** {@code hashtextextended} seed of the per-inviter invite-SMS cap lock (0–7 are taken; see {@code TelegramLinkRepository}). */
    int INVITER_CAP_LOCK_SEED = 8;
    /** {@code hashtextextended} seed of the per-salon invite-SMS cap lock. */
    int SALON_CAP_LOCK_SEED = 9;
    /** {@code hashtextextended} seed of the per-phone invite-SMS cap lock (AUTH-D14). */
    int PHONE_CAP_LOCK_SEED = 10;

    /**
     * B520 — salon-scoped ACTIVE phone-invite lookup, the dispatch idempotency check (a live invite to the same phone
     * for the same salon is a 201 with no second token and no second SMS). Each salon decides independently (the
     * cross-salon silent-drop fix). {@code revokedAt IS NULL} is load-bearing: SUPERSEDED rows keep
     * {@code is_used = false}, so without it a twice re-invited phone would match two rows. The predicate is the one of
     * {@code ux_invite_tokens_active_phone} (V251), which serves it and guarantees at most one match.
     */
    Optional<InviteToken> findByPhoneAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(String phone, UUID salonId);

    /**
     * B520 (AUTH-52) — serialises the invite-SMS caps per inviter, then per salon (always in that order, so two
     * dispatches never wait on each other crosswise): the count-then-insert in {@code InvitePersistenceService} cannot
     * be raced past a cap. Transaction-scoped, released at commit; fused 3 s {@code lock_timeout} like every
     * first-in-transaction advisory lock in the codebase.
     */
    @Query(value = "SELECT 1 FROM (SELECT set_config('lock_timeout', '3s', true), "
            + "pg_advisory_xact_lock(hashtextextended(CAST(:inviterId AS text), " + INVITER_CAP_LOCK_SEED + ")), "
            + "pg_advisory_xact_lock(hashtextextended(CAST(:salonId AS text), " + SALON_CAP_LOCK_SEED + "))) sub",
            nativeQuery = true)
    Integer acquireInviteSmsCapLocks(@Param("inviterId") UUID inviterId, @Param("salonId") UUID salonId);

    /**
     * B520 audit-fix (AUTH-D14) — serialises the per-PHONE invite-SMS cap across every salon and inviter. Taken AFTER
     * {@link #acquireInviteSmsCapLocks} (one global order: inviter, salon, phone — no deadlock); the 3 s
     * {@code lock_timeout} set there still applies (transaction-local).
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:phone, " + PHONE_CAP_LOCK_SEED
            + "))) sub", nativeQuery = true)
    Integer acquireInvitePhoneCapLock(@Param("phone") String phone);

    /** B520 (AUTH-52) — phone invites (= invite SMS) the user sent since {@code since}; idx_invite_tokens_invited_by_created. */
    @Query("SELECT COUNT(t) FROM InviteToken t WHERE t.invitedBy = :inviterId AND t.phone IS NOT NULL "
            + "AND t.createdAt > :since")
    long countPhoneInvitesByInviterSince(@Param("inviterId") UUID inviterId, @Param("since") Instant since);

    /** B520 (AUTH-52) — phone invites (= invite SMS) of the salon since {@code since}; idx_invite_tokens_salon_created. */
    @Query("SELECT COUNT(t) FROM InviteToken t WHERE t.salonId = :salonId AND t.phone IS NOT NULL "
            + "AND t.createdAt > :since")
    long countPhoneInvitesBySalonSince(@Param("salonId") UUID salonId, @Param("since") Instant since);

    /**
     * B520 (D5, SAL-48) — deletes every UNACCEPTED phone invite that expired, or was revoked (cancelled /
     * superseded), before {@code cutoff}. Accepted invites ({@code is_used} without a revocation) and pre-B520 email
     * rows are kept. One bounded statement, no per-row materialisation.
     *
     * @return number of rows deleted
     */
    @Modifying
    @Query(value = """
            DELETE FROM invite_tokens
            WHERE phone IS NOT NULL
              AND (is_used = false OR revoked_at IS NOT NULL)
              AND COALESCE(revoked_at, expires_at) < :cutoff
            """, nativeQuery = true)
    int deleteUnacceptedPhoneInvitesEndedBefore(@Param("cutoff") Instant cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM InviteToken t WHERE t.token = :token")
    Optional<InviteToken> findByTokenForUpdateNoLockTimeout(@Param("token") String token);

    /** {@link #findByTokenForUpdateNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout} ({@link TransactionLockTimeout}). */
    default Optional<InviteToken> findByTokenForUpdate(String token) {
        applyTransactionLockTimeout();
        return findByTokenForUpdateNoLockTimeout(token);
    }

    /**
     * {@code PESSIMISTIC_WRITE}-locked lookup by id — Phase 23.1 QA audit (Security MEDIUM) fix.
     * {@code SalonService#cancelInvite} now uses this instead of the plain {@code findById} it
     * originally shipped with, so it takes the SAME row lock {@link #findByTokenForUpdate} takes
     * for {@code InviteService#acceptInvite}. Before this fix, a cancel racing an accept could read
     * an unlocked, pre-accept snapshot (isUsed=false), and — because {@code cancelInvite} never
     * re-reads before flushing — blindly report 204 success after the accept had already committed
     * and provisioned the account, with no re-check catching the staleness. With both sides locking
     * the same row, whichever transaction's {@code SELECT ... FOR UPDATE} runs first serialises the
     * other; the loser's lock grant always returns the FRESH post-commit row (Postgres READ
     * COMMITTED semantics for a blocked {@code FOR UPDATE}), so its {@code isUsed()} check is never
     * stale. See {@code PendingInviteCancelAcceptRaceIT}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM InviteToken t WHERE t.id = :id")
    Optional<InviteToken> findByIdForUpdateNoLockTimeout(@Param("id") UUID id);

    /** {@link #findByIdForUpdateNoLockTimeout} bounded by the transaction-local 3s {@code lock_timeout} ({@link TransactionLockTimeout}). */
    default Optional<InviteToken> findByIdForUpdate(UUID id) {
        applyTransactionLockTimeout();
        return findByIdForUpdateNoLockTimeout(id);
    }

    /**
     * FULL invite history for a salon — {@code GET /salons/{salonId}/invites}. Deliberately
     * UNFILTERED: accepted, expired, cancelled and pending invites are all returned, because the
     * endpoint's whole purpose is showing the owner/admin what happened to every invitation they
     * sent. Display status is derived per row by
     * {@code com.beautica.salon.dto.SalonInviteResponse#from} — it is NOT a query predicate, so
     * there is nothing here for the caller to filter on.
     *
     * <p>This REPLACES the pending-only finder it grew out of; no non-graph/filtered variant is
     * kept alongside it (Anti-Bug §E1) — a caller wanting only pending rows filters the derived
     * status, it does not get a second query.
     *
     * <p><strong>Returns {@link InviteHistoryRow}, not the entity.</strong> A constructor
     * expression selects only the seven columns the DTO and the status ladder read, so
     * {@code invite_tokens.token} — a SHA-256 digest of a live credential — is never fetched on
     * this path and 200 rows cost no persistence-context management. See that record's Javadoc.
     * Because the projection carries no associations there is nothing to {@code JOIN FETCH}
     * (§E2), and {@code salonId} is a plain column on {@code InviteToken}, so the filter needs no
     * join through {@code users.salon_id} either.
     *
     * <p>Bounded by the caller-supplied {@code Pageable} rather than returning an unbounded
     * {@code List} (§E3); see {@code SalonService.MAX_INVITE_HISTORY}. The tie-break on
     * {@code t.id} makes the order total: {@code created_at} has millisecond-ish resolution and
     * two invites dispatched in one request batch can share it, which would otherwise let
     * Postgres return them in an arbitrary — and unstable — relative order.
     *
     * <p>Served index-ordered (no sort node) by {@code idx_invite_tokens_salon_created}
     * {@code (salon_id, created_at DESC, id DESC)} (V153).
     *
     * <p><strong>B520 (SAL-48):</strong> a pre-deploy email invite that would still read PENDING at {@code now}
     * (no phone, unused, unrevoked, unexpired) is left out — it can no longer be accepted. Its predicate is the
     * negation of the PENDING rung of the status ladder, with the same canonical expiry boundary
     * ({@code expiresAt <= now} = {@link InviteToken#isExpired}).
     */
    @Query("""
            SELECT new com.beautica.user.InviteHistoryRow(
                       t.id, t.email, t.phone, t.role, t.isUsed, t.revokedReason, t.expiresAt, t.createdAt)
            FROM InviteToken t
            WHERE t.salonId = :salonId
              AND (t.phone IS NOT NULL OR t.isUsed = true OR t.revokedAt IS NOT NULL OR t.expiresAt <= :now)
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<InviteHistoryRow> findSalonInviteHistory(@Param("salonId") UUID salonId, @Param("now") Instant now,
                                                  Pageable pageable);

    /**
     * Deletes every {@code invite_tokens} row this salon dispatched to any of
     * {@code staffUserIds}, matched by the staff member's CURRENT {@code users.email} or — B520, phone invites —
     * {@code users.phone_number} (phase 295 — the salon-deletion staff HARD-DELETE cascade).
     *
     * <p><strong>Supersedes {@code redactEmailsBySalonIdAndStaffUserIds} (phase 291 D9).</strong>
     * That statement rewrote the same rows to a {@code deleted+<uuid>@beautica-deleted.invalid}
     * tombstone because the staff {@code users} row SURVIVED the deletion and its address had to
     * stop being recoverable by a join. Under the 2026-09-04 reversal the {@code users} row does
     * not survive, so redaction is the wrong verb twice over: an invite row addressed to an
     * account that no longer exists is not history, it is a dangling address, and a stale PENDING
     * one for this salon would collide with the re-invite phase 296 exists to prove works.
     *
     * <p>Called from {@code SalonService#deleteSalonStaff} ONCE for the entire
     * {@code staffUserIds} list.
     *
     * <p><strong>MUST run BEFORE the {@code users} rows are deleted.</strong> The join predicate
     * resolves each invite row through the staff member's live {@code users} row. Once that row is
     * gone the join matches nothing and this statement silently deletes zero rows, leaving the
     * address behind forever. Pinned by {@code SalonStaffHardDeleteIT}; see the call-site comment
     * in {@code SalonService}.
     *
     * <p><strong>{@code lower(t.email) = lower(u.email)}, not a bare {@code =} (phase 295 audit,
     * LOW-7).</strong> Every write path in this codebase lowercases before persisting
     * ({@code InviteService:151}, {@code AuthService:119/165/249/382}), but neither column is
     * {@code citext} and nothing in the schema enforces the case — this repo already hedges the
     * same way with the {@code lower(email)} expression index {@code ux_invite_tokens_active}
     * (V101). One legacy or externally-seeded mixed-case row on either side made an exact join
     * delete zero and strand a PENDING invite, which then blocks the phase 296 re-invite that this
     * whole statement exists to unblock. The expression form is also what {@code ux_invite_tokens_active}
     * can serve, so it is not a planner regression.
     *
     * <p>Scoped to {@code salonId} — the salon actually being deleted — NOT a global match on
     * email. A staff member can have a separate, unrelated PENDING invite waiting at the same
     * address from a DIFFERENT salon; that row belongs to that other salon's own history and must
     * survive this cascade untouched.
     *
     * <p>Deliberately status-blind — no {@code is_used}/{@code revoked_at} predicate — so PENDING,
     * ACCEPTED, EXPIRED and SUPERSEDED rows for this salon+address all go alike. A salon can
     * plausibly have invited or re-accepted the same address more than once, and every one of
     * those rows carries the same now-deleted address.
     *
     * <p><strong>Why a join, not {@code WHERE email IN (:emails)}:</strong> the caller resolves
     * staff by {@code users.id} (phase 289's {@code findSalonStaffUserIds}) and never handles
     * their addresses — keeping the email inside the statement is also what keeps it out of the
     * application's logs and heap.
     *
     * <p>Naturally idempotent: a second run finds no matching {@code users} rows (they are gone)
     * and deletes nothing.
     *
     * @param salonId      the salon being deleted
     * @param staffUserIds the resolved staff {@code users.id} list, never empty (the caller
     *                     short-circuits on an empty staff set)
     * @return number of {@code invite_tokens} rows deleted
     */
    @Modifying
    @Query(value = """
            DELETE FROM invite_tokens t
            USING users u
            WHERE u.id IN (:staffUserIds)
              AND t.salon_id = :salonId
              AND (lower(t.email) = lower(u.email) OR t.phone = u.phone_number)
            """, nativeQuery = true)
    int deleteBySalonIdAndStaffUserIds(@Param("salonId") UUID salonId,
                                       @Param("staffUserIds") List<UUID> staffUserIds);

    /**
     * Phase 488 (SUP-67) — removes EVERY invite the given salons ever dispatched, when their owner's
     * account is deleted and the salons are detached (SAL-47). An invite is the owner's own outbound
     * history; once the account is gone nobody can view, revoke or accept it as that salon's staff, and a
     * re-registered account with the same address must not inherit it (AC5). Salon-scoped, never email-keyed.
     *
     * @return number of {@code invite_tokens} rows deleted
     */
    @Modifying
    @Query(value = "DELETE FROM invite_tokens WHERE salon_id IN (:salonIds)", nativeQuery = true)
    int deleteBySalonIdIn(@Param("salonIds") Collection<UUID> salonIds);
}
