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

@Entity
@Table(name = "service_definitions",
        indexes = {
                @Index(name = "idx_service_def_owner_active",      columnList = "owner_id, is_active"),
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

    @Enumerated(EnumType.STRING)
    @Column(length = 100)
    private ServiceCategory category;

    @Column(name = "base_duration_minutes", nullable = false)
    private int baseDurationMinutes;

    @Column(name = "base_price", precision = 10, scale = 2)
    private BigDecimal basePrice;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_type_id")
    private ServiceType serviceType;
}
