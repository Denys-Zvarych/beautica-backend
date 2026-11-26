package com.beautica.service.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 *   <li>Self-service request — {@code PENDING}, {@code active = false}, until the platform operator decides it in
 *       the catalog portal (B545: the operator email only announces it).</li>
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
                @Index(name = "idx_platform_categories_approved_active", columnList = "name"),
                // V159 (phase 295 audit MEDIUM-6). requested_by_user_id is REFERENCES users(id)
                // ON DELETE SET NULL, whose RI trigger fires once per DELETEd user — unindexed,
                // that was a Seq Scan per staff account in the salon-deletion hard delete.
                @Index(name = "idx_platform_categories_requested_by", columnList = "requested_by_user_id")
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

    /**
     * System-A {@code service_categories.id} bucket this System-B category maps to
     * (V78, Phase 16.9). Read-only — seeded/backfilled by Flyway, never set from the
     * application. Used by {@code ServiceTypePromotionService} to resolve the NOT-NULL
     * {@code category_id} FK when creating a new {@link ServiceType} on approval.
     *
     * <p>{@code null} for a brand-new self-service category that never got a V75 bucket;
     * the promotion writer treats a null bucket as "cannot create a ServiceType" and
     * falls back to a draft {@link ServiceDefinition} with {@code serviceType = null}.
     */
    @org.springframework.lang.Nullable
    @Column(name = "service_category_id", updatable = false, insertable = false)
    private UUID serviceCategoryId;

    @Column(name = "requested_by_user_id")
    private UUID requestedByUserId;

    @Column(name = "requested_at")
    private OffsetDateTime requestedAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    /** Optional admin reason typed on the reject form (V196, max 300). Never logged. */
    @Column(name = "decision_reason", length = 300)
    private String decisionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * B539 (SVC-58) — R2 object key of the category icon ({@code catalog/category-icons/{name}/{sha16}.svg}), never the
     * URL: {@code iconUrl} is built at read time (track D8). {@code null} until an icon is set; the three icon columns
     * are set together (V267 {@code chk_platform_categories_icon_all_or_none}).
     *
     * <p>External-storage contract: the object is purged after commit when the icon is replaced
     * ({@code CategoryIconService}) and when the unused category is deleted (B505 delete path,
     * {@code CatalogEntryAdminService#deleteCategory}). There is no FK CASCADE onto this table. Every purge of a key
     * under {@code catalog/category-icons/} re-checks, at purge time, that no row still references it
     * ({@code CategoryIconReferenceGuard}), because the content-addressed key can be re-used by a later write.
     *
     * <p>{@code @JsonIgnore}: a storage key is internal; clients only ever get the built {@code iconUrl}.
     */
    @JsonIgnore
    @Column(name = "icon_key", length = 200)
    private String iconKey;

    /** B539 — lowercase hex sha256 of the canonical (stored) SVG bytes; the same-icon no-op compares this. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "icon_sha256", length = 64)
    private String iconSha256;

    @Column(name = "icon_updated_at")
    private OffsetDateTime iconUpdatedAt;

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
     * Creates a PENDING self-service request: not selectable until approved.
     *
     * @param initialServiceName OPTIONAL initial service-type name (trimmed-to-null by the
     *                           caller); turned into a PENDING suggestion on approval
     */
    public static PlatformCategory ofPendingRequest(
            String name,
            String displayName,
            UUID requestedByUserId,
            OffsetDateTime requestedAt,
            String initialServiceName) {
        PlatformCategory category =
                new PlatformCategory(name, displayName, PlatformCategoryStatus.PENDING, false);
        category.requestedByUserId = requestedByUserId;
        category.requestedAt = requestedAt;
        category.initialServiceName = initialServiceName;
        return category;
    }

    /**
     * Approves a PENDING request: becomes selectable. Caller guarantees the current status is PENDING.
     */
    public void approve(OffsetDateTime decidedAt) {
        this.status = PlatformCategoryStatus.APPROVED;
        this.active = true;
        this.decidedAt = decidedAt;
    }

    /**
     * Rejects a PENDING request: stays non-selectable. Caller guarantees the current status is PENDING.
     */
    public void reject(OffsetDateTime decidedAt) {
        reject(decidedAt, null);
    }

    /** Rejects with an optional, already-validated reason (null when none was given). */
    public void reject(OffsetDateTime decidedAt, String reason) {
        this.status = PlatformCategoryStatus.REJECTED;
        this.active = false;
        this.decidedAt = decidedAt;
        this.decisionReason = reason;
    }

    public boolean isPending() {
        return this.status == PlatformCategoryStatus.PENDING;
    }

    /**
     * B502 (SVC-55) — corrects the requested display name (and, optionally, the carried initial service name) of a
     * PENDING request. The wire {@code name} never changes (track D4). Caller has
     * already validated and canonicalised both values.
     *
     * @param initialServiceName the new value, {@code null} to clear it
     * @throws IllegalStateException when the request is no longer PENDING (the service checks first)
     */
    public void editPendingRequest(String displayName, String initialServiceName) {
        if (!isPending()) {
            throw new IllegalStateException("Only a PENDING category request can be edited");
        }
        this.displayName = displayName;
        this.initialServiceName = initialServiceName;
    }

    public boolean isApproved() {
        return this.status == PlatformCategoryStatus.APPROVED;
    }

    /**
     * B504 (SVC-56) — the platform operator renames a live category. Only the display name changes; the wire
     * {@code name} is immutable (track D4). Caller has validated and canonicalised the value.
     *
     * @throws IllegalStateException when the category is not APPROVED (the service checks first)
     */
    public void renameDisplayName(String displayName) {
        requireApproved();
        this.displayName = displayName;
    }

    /**
     * B504 (SVC-56) — hides ({@code false}) or unhides ({@code true}) a live category: a hidden one is not offered for
     * new services; services already using it keep working (track D4).
     *
     * @throws IllegalStateException when the category is not APPROVED (the service checks first)
     */
    public void changeActive(boolean active) {
        requireApproved();
        this.active = active;
    }

    public boolean isRejected() {
        return this.status == PlatformCategoryStatus.REJECTED;
    }

    /**
     * B539 (SVC-58) — sets or replaces the icon. Caller has sanitised the SVG, uploaded it under {@code iconKey} and
     * checked the category is not REJECTED.
     */
    public void changeIcon(String iconKey, String iconSha256, OffsetDateTime updatedAt) {
        this.iconKey = iconKey;
        this.iconSha256 = iconSha256;
        this.iconUpdatedAt = updatedAt;
    }

    private void requireApproved() {
        if (!isApproved()) {
            throw new IllegalStateException("Only an APPROVED category can be renamed, hidden or unhidden");
        }
    }
}
