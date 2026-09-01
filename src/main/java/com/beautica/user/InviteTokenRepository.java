package com.beautica.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
