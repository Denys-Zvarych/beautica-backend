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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * Phase 15.8: discrete bookable start times for {@link WeekdayMode#EXPLICIT_TIMES} weekdays.
     *
     * <p>Modelled as a {@link Set} (not a {@code List}) deliberately: {@code intervals} is already a bag
     * ({@code List}), and Hibernate forbids fetch-joining two bags in one query
     * ({@code MultipleBagFetchException}); a {@code Set} keeps a second fetch-join legally POSSIBLE.
     * In practice no repository finder takes it — every one of them {@code LEFT JOIN FETCH}es
     * {@code ws.intervals} only, so this collection is hydrated by
     * {@code hibernate.default_batch_fetch_size=50}, exactly like {@link #getDayWindows()}. That costs one
     * extra batched query per fold (never one per date) and avoids the cartesian product a second
     * fetch-join would multiply in. Mode exclusivity (a day is EITHER in {@code intervals} OR here) is a
     * service-layer invariant.
     */
    @OneToMany(
            mappedBy = "schedule",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @Builder.Default
    private Set<DiscreteTime> discreteTimes = new LinkedHashSet<>();

    /**
     * Phase 15.12: optional, <b>display-only</b> working-window bounds per {@link WeekdayMode#INTERVAL}
     * weekday — see {@link WeeklyScheduleDayWindow} for why they exist and what they are NOT.
     *
     * <p>Modelled as a {@link Set} for the same reason as {@code discreteTimes}: {@code intervals} is
     * already a bag ({@code List}) and Hibernate forbids fetch-joining two bags. Like
     * {@code discreteTimes} this collection is intentionally NOT {@code LEFT JOIN FETCH}-ed by the
     * repository finders (a third fetch-join on top of {@code intervals} would multiply into a cartesian
     * product); it is hydrated by {@code hibernate.default_batch_fetch_size=50}, so a list of N schedules
     * costs one extra batched query, not N (§E).
     *
     * <p>At most one entry per weekday, and only for a weekday that has ≥1 {@link WorkingInterval} — both
     * are service-layer invariants ({@code MasterScheduleService}), the first also a DB UNIQUE.
     */
    @OneToMany(
            mappedBy = "schedule",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @Builder.Default
    private Set<WeeklyScheduleDayWindow> dayWindows = new LinkedHashSet<>();
}
