package com.beautica.service.entity;

import com.beautica.common.AuditableEntity;
import com.beautica.master.entity.Master;
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
 * Per-master service assignment with optional price/duration overrides.
 *
 * Named MasterServiceAssignment (not MasterService) to avoid collision with
 * the Spring @Service stereotype annotation naming convention.
 */
@Entity
@Table(name = "master_services",
        indexes = {
                @Index(name = "idx_master_services_master_active", columnList = "master_id, is_active")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MasterServiceAssignment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_id", nullable = false)
    private Master master;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_def_id", nullable = false)
    private ServiceDefinition serviceDefinition;

    /**
     * Master-specific price override — the band's FLOOR when the assignment holds its own band
     * (Phase 311 D2). Null means the assignment is Inherited: the base price from the
     * ServiceDefinition is used.
     */
    @Column(name = "price_override", precision = 10, scale = 2)
    private BigDecimal priceOverride;

    /**
     * Phase 311 D2 — the assignment's own pricing MODE (FIXED or RANGE), independent of the
     * shared {@link ServiceDefinition#getPriceType()}. Null means the assignment is Inherited.
     *
     * <p>All-or-nothing with {@link #priceOverride} / {@link #priceMaxOverride}, enforced by the
     * V165 {@code chk_master_service_price_mode} CHECK constraint — never set this field alone.
     * {@link com.beautica.service.dto.MasterServiceBand#isLegal} is the single Java-side
     * coherence check for the trio; do not hand-roll a second one.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "price_type_override", length = 10)
    private PriceType priceTypeOverride;

    /**
     * Phase 311 D2 — the assignment's own RANGE ceiling. Null for an Inherited or FIXED-own-band
     * assignment. {@code >= priceOverride} when set (DB-enforced). See {@link #priceTypeOverride}.
     * The API never writes an equal pair — {@link com.beautica.service.dto.MasterServiceBand#isLegal}
     * is strict ({@code >}) while the CHECK is tolerant ({@code >=}); see Phase 312 D8.
     */
    @Column(name = "price_max_override", precision = 10, scale = 2)
    private BigDecimal priceMaxOverride;

    /**
     * Master-specific duration override in minutes. Null means the base
     * duration from the ServiceDefinition is used.
     *
     * <p>Deliberately OUTSIDE the price band (Phase 311 D2): duration carries no shape and no
     * ceiling, so it keeps this independent "null = inherit" semantics regardless of whether the
     * price band above is Inherited or the master's own.
     */
    @Column(name = "duration_override_minutes")
    private Integer durationOverrideMinutes;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;
}
