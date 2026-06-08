package com.beautica.service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Platform-managed service category — also the request/approval record for the
 * self-service category workflow.
 *
 * <p>This entity is the authoritative source of valid category values accepted by
 * {@link ServiceDefinition}. The {@code name} column stores the canonical string
 * (e.g. {@code "MANICURE"}, {@code "NAIL_ART"}) that is persisted in
 * {@code service_definitions.category} and validated at the service layer. A
 * category is only selectable when {@code status = APPROVED AND active = true}.
 *
 * <p>Three creation paths converge on this one table (no parallel table):
 * <ul>
 *   <li>Flyway seeds (7 enum values) — {@code APPROVED}.</li>
 *   <li>Internal ops CRUD ({@code X-Internal-Key}) — {@code APPROVED}.</li>
 *   <li>Self-service request — {@code PENDING}, {@code active = false}, carrying a
 *       hashed single-use token, until a platform admin approves it via email.</li>
 * </ul>
 *
 * <p>No FK from {@code service_definitions.category} to this table is declared
 * because the polymorphic write path would require deferrable FKs; the service
 * layer enforces the integrity constraint instead.
 *
 * <p>Public setters are intentionally absent. State transitions go through the
 * factory methods ({@link #ofApproved(String, String)},
 * {@link #ofPendingRequest}) and the {@link #approve(OffsetDateTime)} /
 * {@link #reject(OffsetDateTime)} mutators.
 */
@Entity
@Table(
        name = "platform_categories",
        indexes = {
                @Index(name = "idx_platform_categories_token_hash", columnList = "token_hash"),
                @Index(name = "idx_platform_categories_approved_active", columnList = "name")
        }
)
// @DynamicInsert: created_at is populated by the DB DEFAULT NOW() (see V64). Without
// dynamic insert Hibernate emits the column with an explicit NULL, which violates the
// NOT NULL constraint on every save() (a 500 on submitRequest). Dynamic insert omits
// null columns from the INSERT so the database default applies.
@DynamicInsert
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class PlatformCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Wire value stored in {@code service_definitions.category}. */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Human-readable label shown in the mobile picker (Ukrainian). */
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /**
     * OPTIONAL initial service-type name carried on a self-service request. When set on
     * a PENDING request, approval turns it into a PENDING {@link ServiceTypeSuggestion}
     * for the now-approved category (no auto-promotion). Null on the seed/ops-CRUD path.
     */
    @Column(name = "initial_service_name", length = 255)
    private String initialServiceName;

    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlatformCategoryStatus status = PlatformCategoryStatus.APPROVED;

    @Column(name = "requested_by_user_id")
    private UUID requestedByUserId;

    @Column(name = "requested_at")
    private OffsetDateTime requestedAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    /** SHA-256 hex of the raw approval token; never the raw token. Single-use. */
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "token_expires_at")
    private OffsetDateTime tokenExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    private PlatformCategory(String name, String displayName, PlatformCategoryStatus status, boolean active) {
        this.name = name;
        this.displayName = displayName;
        this.status = status;
        this.active = active;
    }

    /**
     * Creates an immediately-usable approved category (ops CRUD / seed parity path).
     * The DB DEFAULT NOW() populates {@code created_at}.
     */
    public static PlatformCategory ofApproved(String name, String displayName) {
        return new PlatformCategory(name, displayName, PlatformCategoryStatus.APPROVED, true);
    }

    /**
     * Creates a PENDING self-service request: not selectable until approved, with a
     * hashed single-use token and a 7-day expiry.
     *
     * @param tokenHash          SHA-256 hex of the raw token (raw token is emailed, never stored)
     * @param initialServiceName OPTIONAL initial service-type name (trimmed-to-null by the
     *                           caller); turned into a PENDING suggestion on approval
     */
    public static PlatformCategory ofPendingRequest(
            String name,
            String displayName,
            UUID requestedByUserId,
            String tokenHash,
            OffsetDateTime requestedAt,
            OffsetDateTime tokenExpiresAt,
            String initialServiceName) {
        PlatformCategory category =
                new PlatformCategory(name, displayName, PlatformCategoryStatus.PENDING, false);
        category.requestedByUserId = requestedByUserId;
        category.requestedAt = requestedAt;
        category.tokenHash = tokenHash;
        category.tokenExpiresAt = tokenExpiresAt;
        category.initialServiceName = initialServiceName;
        return category;
    }

    /**
     * Approves a PENDING request: becomes selectable and the token is cleared
     * (single-use). Caller guarantees the current status is PENDING.
     */
    public void approve(OffsetDateTime decidedAt) {
        this.status = PlatformCategoryStatus.APPROVED;
        this.active = true;
        this.decidedAt = decidedAt;
        clearToken();
    }

    /**
     * Rejects a PENDING request: stays non-selectable and the token is cleared
     * (single-use). Caller guarantees the current status is PENDING.
     */
    public void reject(OffsetDateTime decidedAt) {
        this.status = PlatformCategoryStatus.REJECTED;
        this.active = false;
        this.decidedAt = decidedAt;
        clearToken();
    }

    public boolean isPending() {
        return this.status == PlatformCategoryStatus.PENDING;
    }

    private void clearToken() {
        this.tokenHash = null;
        this.tokenExpiresAt = null;
    }
}
