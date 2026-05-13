package com.beautica.dashboard.service;

import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.dashboard.dto.RevenueByDateDto;
import com.beautica.dashboard.dto.RevenueByMasterDto;
import com.beautica.dashboard.dto.RevenueByServiceDto;
import com.beautica.dashboard.dto.RevenueResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.salon.repository.SalonRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only analytics service for the revenue dashboard.
 *
 * <p>All methods are read-only; the class-level {@code @Transactional(readOnly = true)}
 * covers every public method. Cache key is the 6-element composite
 * {@code {actorId, from, to, filterMasterId, serviceDefId, salonIdFilter}}. Eviction uses
 * {@code cache.clear()} on the whole {@code revenue-dashboard} region, called from
 * {@code BookingService.evictRevenueDashboardAfterCommit()} after any COMPLETED /
 * NOT_COMPLETED transition. Targeted single-key eviction is not feasible because
 * {@code BookingService} does not have access to the date/filter parameters at eviction time.
 *
 * <p>The native SQL aggregates against {@code price_at_booking} — the snapshot price
 * captured when the booking was created — never the live {@code master_services.price}.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardService {

    private static final ZoneId KYIV        = ZoneId.of("Europe/Kyiv");
    private static final int    MAX_RANGE_DAYS = 365;

    // ── private value objects ──────────────────────────────────────────────

    /** Typed holder for one result row from the native SQL query. */
    private record RawRow(
            LocalDate  date,
            UUID       masterId,
            String     masterName,
            UUID       serviceDefId,
            String     serviceName,
            long       bookingCount,
            BigDecimal revenue) {}

    /**
     * Resolved data-visibility scope for the requesting actor.
     *
     * <p>For SALON_OWNER: {@code salonIds} holds ALL active salon IDs the owner controls
     * (may be more than one). {@code actorMasterId} is null.
     * For INDEPENDENT_MASTER: {@code salonIds} is empty, {@code actorMasterId} is their master UUID.
     */
    private record ActorScope(List<UUID> salonIds, UUID actorMasterId) {
        /** Convenience: returns true if this scope is salon-based (SALON_OWNER). */
        boolean isSalonScope() {
            return actorMasterId == null;
        }
    }

    // ── native SQL constant ────────────────────────────────────────────────
    // price_at_booking is the snapshot captured at booking creation time;
    // never reference master_services.price (live price) here.
    //
    // Scope binding:
    //   :salonIds      — for SALON_OWNER: a String[] of all active salon UUID strings,
    //                    cast to uuid[] via CAST(:salonIds AS uuid[]). When the scope is
    //                    master-based (INDEPENDENT_MASTER), this array is empty and the
    //                    salonIds branch of the OR evaluates to false.
    //   :actorMasterId — for INDEPENDENT_MASTER: the master UUID string; NULL for salon scopes.
    //
    // The ANY(CAST(:salonIds AS uuid[])) construct handles multi-salon SALON_OWNERs
    // without requiring a JOIN or a dynamic IN clause.
    //
    // NOTE: the 'Europe/Kyiv' timezone literal must match the KYIV constant above.
    private static final String REVENUE_SQL = """
            SELECT
              DATE(b.starts_at AT TIME ZONE 'Europe/Kyiv') AS booking_date,
              ms.master_id,
              u.first_name || ' ' || u.last_name         AS master_name,
              sd.id                                       AS service_def_id,
              sd.name                                     AS service_name,
              COUNT(b.id)                                 AS booking_count,
              SUM(b.price_at_booking)                     AS total_revenue
            FROM bookings b
            JOIN master_services ms  ON ms.id  = b.master_service_id
            JOIN service_definitions sd ON sd.id = ms.service_def_id
            JOIN masters m           ON m.id   = ms.master_id
            JOIN users u             ON u.id   = m.user_id
            WHERE b.status = 'COMPLETED'
              AND b.starts_at >= :fromDate
              AND b.starts_at  < :toDate
              AND (CAST(:filterMasterId AS uuid) IS NULL OR ms.master_id        = CAST(:filterMasterId AS uuid))
              AND (CAST(:serviceDefId   AS uuid) IS NULL OR ms.service_def_id   = CAST(:serviceDefId   AS uuid))
              AND (
                    (array_length(CAST(:salonIds AS uuid[]), 1) > 0
                     AND b.salon_id  = ANY(CAST(:salonIds AS uuid[]))
                     AND m.salon_id  = ANY(CAST(:salonIds AS uuid[])))
                 OR (CAST(:actorMasterId AS uuid) IS NOT NULL AND b.master_id = CAST(:actorMasterId AS uuid)
                                                              AND m.id        = CAST(:actorMasterId AS uuid))
              )
            GROUP BY booking_date, ms.master_id, master_name, sd.id, sd.name
            ORDER BY booking_date
            """;

    // ── injected collaborators ─────────────────────────────────────────────

    private final EntityManager            em;
    private final MasterRepository         masterRepository;
    private final SalonRepository          salonRepository;
    private final MasterServiceRepository  masterServiceRepository;
    private final Clock                    clock;

    // ── public API ─────────────────────────────────────────────────────────

    /**
     * Returns a revenue summary for the given actor scoped to their salons (SALON_OWNER)
     * or their own master record (INDEPENDENT_MASTER).
     *
     * <p>Cache key is the 6-element composite {@code {actorId, from, to,
     * filterMasterId, serviceDefId, salonIdFilter}}. Eviction clears the whole
     * {@code revenue-dashboard} region (see {@code BookingService.evictRevenueDashboardAfterCommit()})
     * because the date/filter parameters are not available in {@code BookingService} at
     * eviction time.
     *
     * <p><b>Multi-salon SALON_OWNER support (FIX 1):</b> resolveScope now returns ALL active
     * salon IDs for the owner. The optional {@code salonIdFilter} narrows to a single salon
     * after verifying it is in the actor's scope (prevents IDOR across unowned salons).
     */
    // sync=true acts as the null-caching guard: Caffeine throws on null when sync=true,
    // so unless="#result==null" would be both redundant and illegal (Spring rejects the combo).
    @Cacheable(value = "revenue-dashboard",
               key   = "{#actorId, #from, #to, #filterMasterId, #serviceDefId, #salonIdFilter?.orElse(null)}",
               sync  = true)
    public RevenueResponse getRevenueSummary(
            UUID           actorId,
            Role           actorRole,
            LocalDate      from,
            LocalDate      to,
            UUID           filterMasterId,
            UUID           serviceDefId,
            Optional<UUID> salonIdFilter) {

        LocalDate[] range        = resolveRange(from, to);
        LocalDate   resolvedFrom = range[0];
        LocalDate   resolvedTo   = range[1];

        validateRange(resolvedFrom, resolvedTo);

        ActorScope scope = resolveScope(actorId, actorRole);

        // ── filterMasterId ownership guard ────────────────────────────────
        if (filterMasterId != null) {
            if (scope.isSalonScope()) {
                // SALON_OWNER: master must belong to one of the actor's active salons.
                // Single IN query replaces the previous N-query stream loop.
                if (!masterRepository.existsByIdAndSalonIdIn(filterMasterId, scope.salonIds())) {
                    throw new ForbiddenException("Master does not belong to actor's salon");
                }
            } else {
                // INDEPENDENT_MASTER: may only filter by their own master record
                if (!filterMasterId.equals(scope.actorMasterId())) {
                    throw new ForbiddenException("Independent master may not filter by another master");
                }
            }
        }

        // ── serviceDefId ownership guard (FIX 3) ─────────────────────────
        if (serviceDefId != null) {
            boolean owned = scope.isSalonScope()
                    ? masterServiceRepository.existsByServiceDefIdAndSalonIdIn(serviceDefId, scope.salonIds())
                    : masterServiceRepository.existsByServiceDefIdAndMasterId(serviceDefId, scope.actorMasterId());
            if (!owned) {
                // Return empty response — do not 403 so callers cannot enumerate whether
                // a UUID has been booked anywhere across all salons.
                return emptyRevenueResponse();
            }
        }

        // ── resolve effective salon ID list for the query ─────────────────
        List<UUID> effectiveSalonIds = resolveEffectiveSalonIds(scope, salonIdFilter);

        List<RawRow> rows = executeQuery(resolvedFrom, resolvedTo, filterMasterId, serviceDefId, scope, effectiveSalonIds);

        return aggregate(rows);
    }

    /** Returns an empty response with zero totals. Used for filtered-out cases. */
    private RevenueResponse emptyRevenueResponse() {
        return new RevenueResponse(0L, BigDecimal.ZERO, List.of(), List.of(), List.of());
    }

    /**
     * Resolves the effective list of salon IDs to bind to the query.
     *
     * <p>For SALON_OWNER: if a {@code salonIdFilter} is present and belongs to the actor's scope,
     * returns a singleton list; otherwise returns all of the actor's salon IDs.
     * For INDEPENDENT_MASTER: returns an empty list (master scope uses {@code actorMasterId}).
     */
    private List<UUID> resolveEffectiveSalonIds(ActorScope scope, Optional<UUID> salonIdFilter) {
        if (!scope.isSalonScope()) {
            return List.of();
        }
        if (salonIdFilter.isPresent()) {
            UUID requested = salonIdFilter.get();
            if (!scope.salonIds().contains(requested)) {
                throw new ForbiddenException("Salon does not belong to the authenticated owner");
            }
            return List.of(requested);
        }
        return scope.salonIds();
    }

    // ── private helpers ────────────────────────────────────────────────────

    private LocalDate[] resolveRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            LocalDate today = clock.instant().atZone(KYIV).toLocalDate();
            return new LocalDate[]{today.minusDays(30), today};
        }
        return new LocalDate[]{from, to};
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Start date must not be after end date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Date range must not exceed 365 days");
        }
    }

    private ActorScope resolveScope(UUID actorId, Role actorRole) {
        return switch (actorRole) {
            case SALON_OWNER -> {
                // Collect ALL active salon IDs — multi-salon owners must see all their salons.
                List<UUID> salonIds = salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId);
                if (salonIds.isEmpty()) {
                    // Return ForbiddenException (not NotFoundException) so authenticated callers
                    // cannot distinguish "resource absent" from "access denied" via 404 vs 403.
                    throw new ForbiddenException("Access denied");
                }
                yield new ActorScope(salonIds, null);
            }
            case INDEPENDENT_MASTER -> {
                Master master = masterRepository
                        .findByUserId(actorId)
                        // ForbiddenException instead of NotFoundException — prevents 404/403 oracle.
                        .orElseThrow(() -> new ForbiddenException("Access denied"));
                yield new ActorScope(List.of(), master.getId());
            }
            default -> throw new ForbiddenException("Access denied");
        };
    }

    @SuppressWarnings("unchecked")
    private List<RawRow> executeQuery(
            LocalDate  from,
            LocalDate  to,
            UUID       filterMasterId,
            UUID       serviceDefId,
            ActorScope scope,
            List<UUID> effectiveSalonIds) {

        OffsetDateTime fromDt = from.atStartOfDay(KYIV).toOffsetDateTime();
        OffsetDateTime toDt   = to.plusDays(1).atStartOfDay(KYIV).toOffsetDateTime();

        // Convert the salon ID list to a String[] for the CAST(:salonIds AS uuid[]) parameter.
        // The Postgres JDBC driver cannot bind a Java List<UUID> directly to a uuid[] column,
        // but it accepts a String[] and PostgreSQL's implicit cast from text[] to uuid[] works
        // when we use CAST(:salonIds AS uuid[]).
        String[] salonIdArray = effectiveSalonIds.stream()
                .map(UUID::toString)
                .toArray(String[]::new);

        Query q = em.createNativeQuery(REVENUE_SQL);
        q.setParameter("fromDate",       fromDt);
        q.setParameter("toDate",         toDt);
        q.setParameter("filterMasterId", filterMasterId != null ? filterMasterId.toString() : null);
        q.setParameter("serviceDefId",   serviceDefId   != null ? serviceDefId.toString()   : null);
        q.setParameter("salonIds",       salonIdArray);
        q.setParameter("actorMasterId",  scope.actorMasterId() != null ? scope.actorMasterId().toString() : null);

        List<Object[]> resultRows = q.getResultList();
        List<RawRow>   rows       = new ArrayList<>(resultRows.size());
        for (Object[] r : resultRows) {
            rows.add(mapRow(r));
        }
        return rows;
    }

    private RawRow mapRow(Object[] row) {
        // row[0]: java.sql.Date  — PostgreSQL DATE
        // row[1]: UUID           — PostgreSQL UUID, returned as UUID by Postgres JDBC
        // row[2]: String         — concatenated name
        // row[3]: UUID           — service_def_id
        // row[4]: String         — service name
        // row[5]: Number         — COUNT returns BigInteger from Postgres native; longValue() is safe
        // row[6]: BigDecimal     — SUM of NUMERIC column
        LocalDate  date         = ((java.sql.Date) row[0]).toLocalDate();
        UUID       masterId     = (UUID)       row[1];
        String     masterName   = (String)     row[2];
        UUID       serviceDefId = (UUID)       row[3];
        String     serviceName  = (String)     row[4];
        long       bookingCount = ((Number)    row[5]).longValue();
        BigDecimal revenue      = (BigDecimal) row[6];
        return new RawRow(date, masterId, masterName, serviceDefId, serviceName, bookingCount, revenue);
    }

    private RevenueResponse aggregate(List<RawRow> rows) {
        if (rows.isEmpty()) {
            return new RevenueResponse(0L, BigDecimal.ZERO, List.of(), List.of(), List.of());
        }

        // LinkedHashMap preserves insertion order; SQL result is already ORDER BY booking_date,
        // so the output lists will also be in chronological order.
        Map<UUID,      long[]>     masterCounts  = new LinkedHashMap<>();
        Map<UUID,      BigDecimal> masterRevenue = new LinkedHashMap<>();
        Map<UUID,      String>     masterNames   = new LinkedHashMap<>();

        Map<UUID,      long[]>     svcCounts     = new LinkedHashMap<>();
        Map<UUID,      BigDecimal> svcRevenue    = new LinkedHashMap<>();
        Map<UUID,      String>     svcNames      = new LinkedHashMap<>();

        Map<LocalDate, long[]>     dateCounts    = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> dateRevenue   = new LinkedHashMap<>();

        long       totalBookings = 0;
        BigDecimal totalRevenue  = BigDecimal.ZERO;

        for (RawRow row : rows) {
            totalBookings += row.bookingCount();
            totalRevenue   = totalRevenue.add(row.revenue());

            masterCounts.merge(row.masterId(),  new long[]{row.bookingCount()}, (a, b) -> new long[]{a[0] + b[0]});
            masterRevenue.merge(row.masterId(), row.revenue(), BigDecimal::add);
            masterNames.putIfAbsent(row.masterId(), row.masterName());

            svcCounts.merge(row.serviceDefId(),  new long[]{row.bookingCount()}, (a, b) -> new long[]{a[0] + b[0]});
            svcRevenue.merge(row.serviceDefId(), row.revenue(), BigDecimal::add);
            svcNames.putIfAbsent(row.serviceDefId(), row.serviceName());

            dateCounts.merge(row.date(),  new long[]{row.bookingCount()}, (a, b) -> new long[]{a[0] + b[0]});
            dateRevenue.merge(row.date(), row.revenue(), BigDecimal::add);
        }

        List<RevenueByMasterDto> byMaster = masterCounts.entrySet().stream()
                .map(e -> new RevenueByMasterDto(
                        e.getKey(),
                        masterNames.get(e.getKey()),
                        e.getValue()[0],
                        masterRevenue.get(e.getKey())))
                .toList();

        List<RevenueByServiceDto> byService = svcCounts.entrySet().stream()
                .map(e -> new RevenueByServiceDto(
                        e.getKey(),
                        svcNames.get(e.getKey()),
                        e.getValue()[0],
                        svcRevenue.get(e.getKey())))
                .toList();

        List<RevenueByDateDto> byDate = dateCounts.entrySet().stream()
                .map(e -> new RevenueByDateDto(
                        e.getKey(),
                        e.getValue()[0],
                        dateRevenue.get(e.getKey())))
                .toList();

        return new RevenueResponse(totalBookings, totalRevenue, byMaster, byService, byDate);
    }
}
