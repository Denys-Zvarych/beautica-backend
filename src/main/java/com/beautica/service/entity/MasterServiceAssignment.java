package com.beautica.service.entity;

import com.beautica.common.AuditableEntity;
import com.beautica.master.entity.Master;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
     * Master-specific price override. Null means the base price from the
     * ServiceDefinition is used.
     */
    @Column(name = "price_override", precision = 10, scale = 2)
    private BigDecimal priceOverride;

    /**
     * Master-specific duration override in minutes. Null means the base
     * duration from the ServiceDefinition is used.
     */
    @Column(name = "duration_override_minutes")
    private Integer durationOverrideMinutes;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;
}
