package com.beautica.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InviteTokenRepository extends JpaRepository<InviteToken, UUID> {

    Optional<InviteToken> findByToken(String token);

    /**
     * Salon-scoped active-invite lookup used by the invite-dispatch idempotency check.
     * Scoping by {@code salonId} lets independent salons each hold a pending invite for
     * the same email — one salon's pending invite no longer short-circuits another salon's
     * dispatch (the cross-salon silent-drop fix).
     *
     * <p><strong>{@code revokedAt IS NULL} IS LOAD-BEARING, NOT COSMETIC.</strong> Since V153 an
     * expired invite displaced by a re-invite is no longer hard-deleted — it is marked
     * {@link RevocationReason#SUPERSEDED} and KEPT as history, still carrying
     * {@code is_used = false}. Without this clause the second re-invite of any address would see
     * two matching rows and this {@code Optional} return would throw
     * {@code IncorrectResultSizeDataAccessException} — a 500 on
     * {@code POST /salons/{id}/invite}. The predicate is deliberately identical to the
     * {@code ux_invite_tokens_active} partial-unique predicate, which is what guarantees at most
     * one match.
     *
     * <p><strong>Index roles:</strong> this {@code email = ? AND salon_id = ? AND is_used = false
     * AND revoked_at IS NULL} predicate is served by the partial index
     * {@code idx_invite_tokens_email_active (email, salon_id) WHERE is_used = false AND revoked_at
     * IS NULL} (V153, replacing V16's plain {@code (email, is_used)}). The whole predicate is now
     * covered — no residual filter. That matters BECAUSE of this phase: superseded rows are
     * retained forever with {@code is_used = false}, so under the old index a churned address
     * accumulated one extra heap fetch per re-invite (measured: {@code Rows Removed by Filter: 50}
     * after 50 re-invites, growing linearly). Postgres cannot use the expression index
     * {@code ux_invite_tokens_active} on {@code lower(email)} for a bare {@code email = ?}
     * comparison, which is why a second, non-expression index is required at all;
     * {@code ux_invite_tokens_active} is the INSERT-time uniqueness/concurrency backstop only.
     */
    Optional<InviteToken> findByEmailAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(String email, UUID salonId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM InviteToken t WHERE t.token = :token")
    Optional<InviteToken> findByTokenForUpdate(@Param("token") String token);

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
    Optional<InviteToken> findByIdForUpdate(@Param("id") UUID id);

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
     */
    @Query("""
            SELECT new com.beautica.user.InviteHistoryRow(
                       t.id, t.email, t.role, t.isUsed, t.revokedReason, t.expiresAt, t.createdAt)
            FROM InviteToken t
            WHERE t.salonId = :salonId
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<InviteHistoryRow> findSalonInviteHistory(@Param("salonId") UUID salonId, Pageable pageable);

    /**
     * Deletes every {@code invite_tokens} row this salon dispatched to any of
     * {@code staffUserIds}, matched by the staff member's CURRENT {@code users.email}
     * (phase 295 — the salon-deletion staff HARD-DELETE cascade).
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
              AND lower(t.email) = lower(u.email)
            """, nativeQuery = true)
    int deleteBySalonIdAndStaffUserIds(@Param("salonId") UUID salonId,
                                       @Param("staffUserIds") List<UUID> staffUserIds);
}
