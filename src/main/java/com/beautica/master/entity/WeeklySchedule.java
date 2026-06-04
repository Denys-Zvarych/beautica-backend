package com.beautica.master.entity;

import com.beautica.common.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 15.1: the active-window weekly template for a master.
 *
 * <p>{@code validFrom}/{@code validTo} bound the date range the template applies to;
 * {@code validTo == null} means open-ended (legacy backfill or "no end set"). Per-master
 * schedule overlap is enforced in the service layer (Phase 15.4), not via a DB constraint
 * (a GiST {@code daterange} exclusion with NULL-bound handling is deferred).
 */
@Entity
@Table(
        name = "weekly_schedules",
        indexes = {
                // Mirrors idx_weekly_schedules_master_window (V69): "schedule covering date D" lookups.
                @Index(name = "idx_weekly_schedules_master_window", columnList = "master_id, valid_from, valid_to")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeeklySchedule extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_id", nullable = false)
    private Master master;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Nullable
    @Column(name = "valid_to")
    private LocalDate validTo;

    @OneToMany(
            mappedBy = "schedule",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @Builder.Default
    private List<WorkingInterval> intervals = new ArrayList<>();
}
