package com.beautica.service.entity;

import com.beautica.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A service offered by an owner (a salon or a master).
 *
 * <p><b>Uniqueness — one ACTIVE service per {@code (ownerType, ownerId, serviceType)}.</b>
 * Enforced by the partial unique index {@code ux_service_def_owner_service_type_active}
 * (V121):
 *
 * <pre>{@code
 * CREATE UNIQUE INDEX ux_service_def_owner_service_type_active
 *     ON service_definitions (owner_type, owner_id, service_type_id)
 *     WHERE is_active = true;
 * }</pre>
 *
 * <p>The key is {@code service_type_id}, never {@code name}: the name is derived from the type
 * ({@code ServiceCatalogService#resolveCreateName} defaults to {@code serviceType.nameUk}, and
 * the bulk path always uses it), so a custom name must not be able to bypass the rule. Price and
 * duration are deliberately NOT part of the key — two rows of the same type at different prices
 * are duplicates by the locked product decision, not variants.
 *
 * <p>The index is <b>partial on {@code is_active = true}</b> because deletion is soft
 * ({@code ServiceRepository#deactivateById} flips the flag and the row survives). An
 * unconditional constraint would make a once-deleted service permanently uncreatable, with no
 * reactivate endpoint to escape.
 *
 * <p><b>Intentionally absent from {@code @Table(uniqueConstraints = …)}.</b> Hibernate cannot
 * express a partial (filtered) unique index; declaring it there would model an
 * <em>unconditional</em> one, putting {@code ddl-auto=validate} permanently at odds with the
 * real schema and re-breaking soft-delete on any generated DDL. The migration is the single
 * source of truth; {@code ServiceCatalogService} translates violations into a
 * {@code DUPLICATE_SERVICE} 409.
 */
@Entity
// Every @Index below mirrors a real index in db/migration — keep it that way. Two entries here once
// named indices that existed in NO migration: idx_service_def_owner_active (owner_id, is_active) and
// idx_service_def_owner_type_active (owner_type, owner_id, is_active). V122 resolved them in opposite
// directions — it CREATED the (owner_type, owner_id, is_active) one (ServiceRepository
// #findBookableServicesBySalon filters exactly those three columns) and the owner_id-leading one was
// deleted from this list as unjustified, since nothing queries owner_id without owner_type. V122 also
// dropped idx_service_def_owner (owner_type, owner_id) — a strict leading prefix of the index below.
//
// This drift was invisible rather than loud: Hibernate 6.5's hbm2ddl.auto=validate does NOT verify
// @Table(indexes = ...) against the live schema, so an @Index naming a nonexistent index is silently
// cosmetic and will never fail a boot. Only a reader can catch it — hence this note.
//
// Not every real index appears here: idx_service_def_category, the GIN trigram
// idx_service_definitions_name_trgm (V98) and the PARTIAL unique ux_service_def_owner_service_type_active
// (V121) are absent because JPA's @Index cannot express GIN or partial indices at all — see the class
// javadoc above for the unique one. Absent-but-real is harmless; declared-but-absent is the drift.
@Table(name = "service_definitions",
        indexes = {
                @Index(name = "idx_service_def_owner_type_active", columnList = "owner_type, owner_id, is_active"),
                @Index(name = "idx_service_def_service_type",      columnList = "service_type_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceDefinition extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Polymorphic owner type. Type-safe enum — SALON or INDEPENDENT_MASTER.
     * No FK constraint — enforced at the application layer.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private OwnerType ownerType;

    /**
     * Raw UUID of the owning entity (Salon.id or Master.id).
     * Stored without a @ManyToOne to support the polymorphic pattern.
     */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Category name string — validated against {@code platform_categories.name} at the
     * service layer. No DB FK is declared (see class Javadoc on PlatformCategory).
     * Stores values like {@code "MANICURE"}, {@code "NAIL_ART"}, etc.
     */
    @Column(length = 100)
    private String category;

    @Column(name = "base_duration_minutes", nullable = false)
    private int baseDurationMinutes;

    /**
     * Pricing mode for this service.
     * <ul>
     *   <li>{@code FIXED} — {@code basePrice} is the one static amount; {@code priceMax} is {@code null}.</li>
     *   <li>{@code RANGE} — {@code basePrice} is the minimum (floor); {@code priceMax} is the ceiling.
     *       The DB CHECK constraint {@code chk_service_def_price_mode} enforces {@code priceMax >= basePrice}.</li>
     * </ul>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", nullable = false, length = 10)
    private PriceType priceType;

    /**
     * Canonical price floor. For {@code FIXED} mode this is the single amount; for {@code RANGE} mode
     * this is the minimum. Used by {@code masters.min_effective_price} (V58) and
     * {@code bookings.price_at_booking} — neither requires changes when RANGE is introduced.
     */
    @Column(name = "base_price", precision = 10, scale = 2, nullable = false)
    private BigDecimal basePrice;

    /**
     * RANGE ceiling. {@code NULL} for {@code FIXED} mode.
     * DB constraint guarantees {@code priceMax >= basePrice} when non-null.
     */
    @Column(name = "price_max", precision = 10, scale = 2)
    private BigDecimal priceMax;

    /**
     * Prep/cleanup buffer blocked after the appointment ends.
     * The slot calculator adds this to baseDurationMinutes to determine the
     * next available start time. Defaults to 0.
     */
    @Builder.Default
    @Column(name = "buffer_minutes_after", nullable = false)
    private int bufferMinutesAfter = 0;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    /**
     * Optional URL pointing to the service photo (presigned R2 URL or direct HTTPS URL).
     * Nullable — services without a photo return {@code null} in the response DTO.
     * The DB column carries a CHECK (photo_url LIKE 'https://%') constraint (V62 migration).
     */
    @Column(name = "photo_url", length = 2048)
    private String photoUrl;

    /**
     * The finer service taxonomy (the column service-type search filters on). Mandatory on
     * every creation path — DB column {@code service_type_id} is NOT NULL with an
     * {@code ON DELETE RESTRICT} FK (V111), so an untyped service can never be persisted and a
     * referenced service type cannot be deleted.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_type_id", nullable = false)
    private ServiceType serviceType;
}
