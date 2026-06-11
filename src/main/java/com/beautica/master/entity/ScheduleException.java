package com.beautica.master.entity;

import com.beautica.common.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

@Entity
@Table(name = "schedule_exceptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleException extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_id", nullable = false)
    private Master master;

    @Column(nullable = false)
    private LocalDate date;

    /**
     * Phase 15.1: DAY_OFF (closure, optional reason since V82) vs CUSTOM_HOURS (override, no reason, has intervals).
     * Existing rows default to DAY_OFF (DB DEFAULT, V71).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ScheduleExceptionKind kind = ScheduleExceptionKind.DAY_OFF;

    /**
     * Phase 15.1: nullable — only meaningful for {@code DAY_OFF}, where it is now OPTIONAL.
     * The DB CHECK {@code chk_exc_reason}, relaxed in V82, leaves DAY_OFF.reason unconstrained
     * (may be null or non-null) and still enforces null for {@code CUSTOM_HOURS}.
     */
    @Nullable
    @Enumerated(EnumType.STRING)
    private ScheduleExceptionReason reason;

    private String note;

    /**
     * Phase 15.1: custom working intervals for a {@code CUSTOM_HOURS} exception; empty for {@code DAY_OFF}.
     */
    @OneToMany(
            mappedBy = "exception",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @Builder.Default
    private List<ScheduleExceptionInterval> intervals = new ArrayList<>();
}
