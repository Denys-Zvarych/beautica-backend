package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.common.AuditableEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Invite token for SALON_MASTER / SALON_ADMIN onboarding.
 *
 * <p><strong>DB-side indexes (Flyway, not mirrored as JPA {@code @Index}):</strong>
 * <ul>
 *   <li>{@code idx_invite_tokens_email_active} — partial on {@code (email, salon_id) WHERE
 *       is_used = false AND revoked_at IS NULL} (V153, replacing V16's plain
 *       {@code (email, is_used)}). Serves the dispatch idempotency lookup
 *       {@code findByEmailAndSalonIdAndIsUsedFalseAndRevokedAtIsNull}, whose predicate it now
 *       covers in full — no residual filter. V16's shape left {@code revoked_at IS NULL} as a
 *       residual, which stopped being free the moment this phase started RETAINING superseded
 *       rows with {@code is_used = false}: a churned address accumulated one discarded heap fetch
 *       per past invite, without bound. Partial, not a third key column, because a row leaves the
 *       live set permanently once accepted/cancelled/superseded — so the index stays a handful of
 *       rows platform-wide instead of growing with every invite ever sent.</li>
 *   <li>{@code ux_invite_tokens_active} — partial UNIQUE on
 *       {@code (lower(email), salon_id) WHERE is_used = false AND revoked_at IS NULL} (V101,
 *       re-scoped by V153). The INSERT-time uniqueness/concurrency backstop: enforces at most one
 *       ACTIVE invite per {@code (salon, lower(email))} and rejects concurrent same-salon
 *       double-submits. A SUPERSEDED row keeps {@code is_used = false} (it was never accepted),
 *       so {@code revoked_at IS NULL} is what releases the slot for the replacement invite. It
 *       does NOT serve the lookup above — Postgres cannot use a {@code lower(email)} expression
 *       index for a bare {@code email = ?} predicate. It is an expression-based partial index,
 *       which cannot be expressed via {@code @Index columnList}, and Hibernate
 *       {@code ddl-auto=validate} does not verify indexes — hence documented here instead of
 *       annotated.</li>
 *   <li>{@code idx_invite_tokens_salon_created} — {@code (salon_id, created_at DESC, id DESC)}
 *       (V153, replacing V149's {@code idx_invite_tokens_salon_pending}). Serves the invite
 *       HISTORY listing {@code findSalonInviteHistory} behind
 *       {@code GET /api/v1/salons/{salonId}/invites}. That query has no {@code is_used} or
 *       {@code expires_at} predicate at all — it returns every invite ever dispatched by the
 *       salon, newest-first — so V149's partial {@code WHERE is_used = false} index could never
 *       serve it. Column order mirrors the {@code ORDER BY created_at DESC, id DESC} exactly, so
 *       the {@code LIMIT 200} is an index-ordered scan with no sort node.</li>
 * </ul>
 *
 * <p><strong>Lifecycle / status derivation.</strong> There is deliberately NO cleanup job for
 * this table. Display status is derived on read from {@code (revokedReason, isUsed, expiresAt,
 * clock)} — see {@code com.beautica.salon.dto.SalonInviteResponse#from}. {@code isUsed} alone is
 * ambiguous: acceptance AND cancellation both set it, which is exactly why {@link #revokedReason}
 * exists. Rows are never hard-deleted; an expired row displaced by a re-invite is marked
 * {@link RevocationReason#SUPERSEDED} so the history stays complete.
 *
 * <p><strong>{@code email} rows are DELETED when the recipient's account is deleted.</strong>
 * Phase 295's salon-deletion cascade ({@code SalonService#deleteSalonStaff}) removes every row
 * THIS salon dispatched to a staff member whose {@code users} row it is about to hard-delete, via
 * {@code InviteTokenRepository#deleteBySalonIdAndStaffUserIds} — scoped to THIS salon, so an
 * unrelated pending invite to the same address from a different salon is untouched. Two reasons,
 * both load-bearing: the address would otherwise survive the account deletion here and keep
 * showing in the owner's own invite history ({@code GET /api/v1/salons/{salonId}/invites}), and a
 * stale PENDING row for this salon would collide with the re-invite that phase 296 exists to
 * prove works. This supersedes phase 291's tombstone REDACTION of the same rows, which only made
 * sense while the {@code users} row survived the deletion.
 */
@Entity
@Table(name = "invite_tokens")
public class InviteToken extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "token", nullable = false, unique = true, length = 512)
    private String token;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "salon_id")
    private UUID salonId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 50)
    private Role role;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "is_used", nullable = false)
    private boolean isUsed = false;

    /**
     * When the token was revoked (cancelled or superseded), or {@code null} while it is still
     * live. Paired with {@link #revokedReason} by the {@code ck_invite_tokens_revoked_pair}
     * CHECK — both null or both set, never one of the two.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 16)
    private RevocationReason revokedReason;

    protected InviteToken() {
    }

    public InviteToken(String token, String email, UUID salonId, Role role, Instant expiresAt) {
        this.token = token;
        this.email = email;
        this.salonId = salonId;
        this.role = role;
        this.expiresAt = expiresAt;
        this.isUsed = false;
    }

    public UUID getId() {
        return id;
    }

    @JsonIgnore
    public String getToken() {
        return token;
    }

    public String getEmail() {
        return email;
    }

    public UUID getSalonId() {
        return salonId;
    }

    public Role getRole() {
        return role;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * THE canonical invite-expiry predicate. Every code path that asks "is this invite past its
     * deadline?" MUST route through here or through {@link #isExpiredAt(Instant)} — never
     * open-code a comparison.
     *
     * <p><strong>Why this exists.</strong> Three call sites had independently open-coded the
     * comparison and two of them disagreed at the boundary: the history status ladder used
     * {@code !expiresAt.isAfter(now)} (dead AT equality) while {@code InviteService#previewInvite},
     * {@code InviteService#acceptInvite} and {@code InvitePersistenceService}'s recycle filter used
     * {@code expiresAt.isBefore(now)} (still alive AT equality). For the one instant
     * {@code now == expiresAt} the owner's history read EXPIRED while the link still worked, and —
     * worse — the recycle filter refused to supersede a token the accept path would have rejected,
     * so a re-invite at that instant would trip {@code ux_invite_tokens_active}, be swallowed as an
     * idempotent success, and never send an email.
     *
     * <p><strong>Why {@code !isAfter} (dead at equality) and not {@code isBefore}.</strong>
     * {@code expiresAt} is a deadline, so validity is the half-open interval
     * {@code [createdAt, expiresAt)} — the standard reading of an expiry timestamp, and the same
     * one {@code RefreshToken}/JWT {@code exp} handling assumes. It is also the fail-closed
     * direction: at the boundary instant a credential is rejected rather than honoured, which is
     * the correct default for anything that provisions an account.
     *
     * @param expiresAt the invite deadline
     * @param now       the instant to judge it against — always from an injected {@code Clock}
     *                  (Anti-Bug §G), never {@code Instant.now()}
     * @return {@code true} when the invite is no longer usable at {@code now}
     */
    public static boolean isExpired(Instant expiresAt, Instant now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * Instance form of {@link #isExpired(Instant, Instant)} for callers holding the entity.
     * The static form exists for the history read path, which projects the row rather than
     * loading the entity (see {@code InviteHistoryRow}).
     */
    public boolean isExpiredAt(Instant now) {
        return isExpired(this.expiresAt, now);
    }

    public boolean isUsed() {
        return isUsed;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public RevocationReason getRevokedReason() {
        return revokedReason;
    }

    /**
     * Marks the token consumed by ACCEPTANCE. Leaves {@link #revokedAt}/{@link #revokedReason}
     * null — that null is precisely what distinguishes an accepted token from a cancelled one,
     * since both carry {@code isUsed = true}.
     */
    public void markUsed() {
        this.isUsed = true;
    }

    /**
     * Marks the token revoked by a salon owner/admin.
     *
     * <p>Deliberately sets {@code isUsed = true} IN ADDITION to the revocation marker: every
     * pre-existing "already consumed?" guard ({@code acceptInvite}, {@code previewInvite},
     * double-cancel) tests {@code isUsed()}, so keeping that flag preserves their behaviour
     * byte-for-byte. {@link RevocationReason#CANCELLED} is the added bit that stops the row from
     * being misreported as ACCEPTED on the history endpoint.
     *
     * @param at revocation instant, supplied by the caller's injected {@code Clock} (never
     *           {@code Instant.now()} — Anti-Bug §G)
     */
    public void markCancelled(Instant at) {
        this.isUsed = true;
        this.revokedAt = at;
        this.revokedReason = RevocationReason.CANCELLED;
    }

    /**
     * Retires an expired-but-unused token so it stops holding the {@code ux_invite_tokens_active}
     * slot for its {@code (salon, lower(email))}, letting a fresh invite to the same address be
     * inserted while this row survives as history.
     *
     * <p>Leaves {@code isUsed = false} — the invite was never accepted, and the display ladder
     * classifies it EXPIRED, not ACCEPTED.
     *
     * @param at supersession instant, supplied by the caller's injected {@code Clock}
     */
    public void markSuperseded(Instant at) {
        this.revokedAt = at;
        this.revokedReason = RevocationReason.SUPERSEDED;
    }
}
