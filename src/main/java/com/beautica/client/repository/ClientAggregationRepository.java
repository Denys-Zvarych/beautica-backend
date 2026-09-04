package com.beautica.client.repository;

import com.beautica.booking.entity.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only aggregation repository backing the BEAUTY PASSPORT + BEAUTY TIMELINE
 * (Phase 19.5). Owns a concern distinct from {@code BookingRepository}'s booking
 * management — passport/timeline projections over the signed-in client's history —
 * so per the feature-packaging + SRP rules it lives in the {@code client} package
 * rather than widening the booking repository. Spring Data permits several
 * repositories over the same {@link Booking} aggregate.
 *
 * <p><b>Every booking-derived query is scoped {@code client_id = :clientId}</b> and the callers
 * must pass the authenticated client's own user id — that predicate is the ownership boundary
 * (Anti-Bug §E-4). All ranking/aggregation is done IN SQL (GROUP BY + ORDER BY count DESC,
 * bounded by {@code Pageable}); none of these methods pulls the full booking set into memory.
 *
 * <p><b>{@code status = COMPLETED} is no longer universal (2026-08-26).</b> Every aggregate in
 * this interface EXCEPT {@link #findTimeline} still scopes strictly to {@code status = COMPLETED}
 * — {@link #findTopDistricts}, {@link #findTopCities}, and {@link #aggregateBudget} all count only
 * closed, provider-confirmed visits, matching the passport headline ({@code considered}, spend
 * band, top districts/cities). {@link #findTimeline} alone was widened, by explicit product
 * decision, to also surface elapsed-but-unclosed {@code CONFIRMED} bookings — see its own javadoc
 * for the predicate and the reasoning. This is a DELIBERATE, KNOWN divergence: the timeline can now
 * list more rows than the passport headline counts (e.g. "15 entries" in the timeline vs. "12
 * visits" in the passport) whenever a provider has unclosed visits in the client's history. Widening
 * the other aggregates to match is a product call, not something to "fix" opportunistically —
 * see the class javadoc note on {@link #findTimeline} before touching any sibling query.
 *
 * <p><b>One query here is NOT booking-rooted:</b> {@link #findStanding} reads the passport's
 * identity strip from {@code User} (+ a correlated {@code Review} count). It lives here rather
 * than on {@code UserRepository}/{@code ReviewRepository} for the same reason this interface
 * exists at all — it is a passport projection, owned by the {@code client} feature package,
 * not a widening of another aggregate's repository. Spring Data does not constrain a
 * {@code @Query}'s root to the interface's entity type; the {@code JpaRepository<Booking, …>}
 * parameterisation only supplies the inherited CRUD surface, which this read never uses.
 */
public interface ClientAggregationRepository extends JpaRepository<Booking, UUID> {

    /**
     * The passport's identity strip — registration instant + authored-review count — in ONE
     * statement (2026-08 perf audit F2).
     *
     * <p>Replaces two unconditional serial round trips ({@code ReviewRepository.countByClientId}
     * then {@code UserRepository.findCreatedAtById}), both single-row lookups on the same
     * principal. On the empty-passport path those were 2 of only 3 statements; folding them in
     * takes the empty load from 3 statements to 2 and the populated load from 8 to 7.
     *
     * <p>A scalar projection, never {@code findById}: hydrating the whole {@link
     * com.beautica.user.User} would pull {@code passwordHash} and the rest of the PII surface
     * into the persistence context to read one timestamp (§I).
     *
     * <p>The review count is a correlated subquery rather than a {@code LEFT JOIN … GROUP BY}
     * so the {@code users} row is returned exactly once regardless of review count. It is
     * index-served by {@code idx_reviews_client_created} (V96) — note the actual index name;
     * the removed {@code countByClientId} javadoc mis-named it {@code reviews_client_created_index}
     * (2026-08 perf audit F8).
     *
     * <p>Returns empty when no such user row exists — the caller must raise
     * {@code NotFoundException}, never substitute a default year.
     */
    @Query("""
            SELECT new com.beautica.client.repository.ClientStanding(
                u.createdAt,
                (SELECT COUNT(r) FROM Review r WHERE r.client.id = u.id)
            )
            FROM User u
            WHERE u.id = :userId
            """)
    Optional<ClientStanding> findStanding(@Param("userId") UUID userId);

    /**
     * Top discovery districts by COMPLETED-booking count, most-visited first, as
     * {@link DistrictCount} rows carrying the FK id only (the service resolves the label
     * via the taxonomy join — occupied-territory ban). Discovery district is
     * district-primary: salon-presence wins outright via
     * {@code CASE WHEN s.id IS NOT NULL THEN s.districtId ELSE mu.districtId END}, even
     * when the salon's own {@code districtId} is {@code NULL} (a legacy salon predating
     * the Phase 10.3 locality columns, or a salon whose city has no urban districts).
     * Rows whose resolved district id is null are excluded — including a salon-employed
     * master's completed booking whose salon has no district, which is deliberately
     * dropped from the aggregate rather than falling back to {@code mu.districtId}.
     *
     * <p><b>Do not use {@code COALESCE(s.districtId, mu.districtId)}</b> — unlike
     * {@code SearchService} / {@code FavoriteRepository} (where the salon join is
     * structurally always {@code NULL} for the rows those queries can ever return, making
     * the {@code COALESCE} vs {@code CASE WHEN} choice moot), {@code Booking.master} here
     * is unrestricted by role: a {@code SALON_MASTER} or {@code SALON_OWNER}-type master's
     * completed booking DOES join a real salon row. {@code COALESCE} would silently fall
     * through to {@code mu.districtId} whenever {@code s.districtId} is {@code NULL},
     * exactly the class of bug fixed in {@code findClientBookingDetails} (19.3) — for a
     * {@code SALON_OWNER}-type master, {@code mu.districtId} is a one-time mirror of the
     * salon's OWN locality taken at salon-creation time ({@code SalonService.createSalon}),
     * never re-synced on salon relocation ({@code SalonService.updateSalon} does not touch
     * it), so it can go stale and point at a district the salon no longer occupies. This
     * mirrors {@link com.beautica.booking.dto.BookingDetailResponse#from}'s ternary exactly.
     *
     * <p><b>{@code m.user} is a {@code LEFT JOIN} and must stay one</b> (V157 / phase 294 D1 —
     * 2026-09 audit finding 6). It was an INNER join, which SILENTLY UNDER-COUNTED: every COMPLETED
     * booking whose master has since been detached (staff {@code users} row hard-deleted) dropped
     * out of the aggregate entirely, so a salon-employed provider's visits stopped contributing to
     * the client's own passport ranking even though the salon locality that ranking reads is on the
     * {@code s} row, not on {@code mu}. With the LEFT join the salon arm of the {@code CASE} is
     * unaffected and keeps counting; a DETACHED INDEPENDENT master (no salon) resolves to a null
     * locality and is excluded by this query's own {@code IS NOT NULL} predicate — the correct
     * answer, since there is no locality left to attribute the visit to.
     */
    @Query("""
            SELECT new com.beautica.client.repository.DistrictCount(
                CASE WHEN s.id IS NOT NULL THEN s.districtId ELSE mu.districtId END,
                COUNT(b)
            )
            FROM Booking b
            JOIN b.master m
            LEFT JOIN m.user mu
            LEFT JOIN m.salon s
            WHERE b.client.id = :clientId
              AND b.status = com.beautica.booking.enums.BookingStatus.COMPLETED
              AND CASE WHEN s.id IS NOT NULL THEN s.districtId ELSE mu.districtId END IS NOT NULL
            GROUP BY CASE WHEN s.id IS NOT NULL THEN s.districtId ELSE mu.districtId END
            ORDER BY COUNT(b) DESC
            """)
    List<DistrictCount> findTopDistricts(@Param("clientId") UUID clientId, Pageable pageable);

    /**
     * Top discovery cities by COMPLETED-booking count, most-visited first, as
     * {@link CityCount} rows carrying the FK id only (the service resolves the label via the
     * taxonomy join — occupied-territory ban). The structural twin of
     * {@link #findTopDistricts} and deliberately a SECOND aggregate rather than a join up
     * from the top districts: {@code city_districts.city_id} is NOT NULL, so the districts
     * could in principle be widened to their cities for free, but that drops every client
     * whose providers carry a {@code city_id} with no {@code district_id} (a legal shape —
     * the district column is nullable on both {@code salons} and {@code users}) and would
     * rank cities by district frequency rather than by booking frequency. The two lines the
     * passport renders are two independent rankings.
     *
     * <p>Salon presence wins outright via
     * {@code CASE WHEN s.id IS NOT NULL THEN s.cityId ELSE mu.cityId END}, even when the
     * salon's own {@code cityId} is {@code NULL}; rows whose resolved city id is null are
     * excluded rather than falling back to {@code mu.cityId}.
     *
     * <p><b>Do not use {@code COALESCE(s.cityId, mu.cityId)}</b> — the same reasoning that
     * governs {@link #findTopDistricts} applies unchanged to the city column, and is repeated
     * here rather than cross-referenced so a future edit to one method cannot silently strand
     * the other. {@code Booking.master} is unrestricted by role: a {@code SALON_MASTER} or
     * {@code SALON_OWNER}-type master's completed booking DOES join a real salon row.
     * {@code COALESCE} would silently fall through to {@code mu.cityId} whenever
     * {@code s.cityId} is {@code NULL} — and for a {@code SALON_OWNER}-type master
     * {@code mu.cityId} is a one-time mirror of the salon's OWN locality taken at
     * salon-creation time ({@code SalonService.createSalon}), never re-synced on salon
     * relocation ({@code SalonService.updateSalon} does not touch it), so it can go stale and
     * point at a city the salon no longer occupies. Exactly the class of bug fixed in
     * {@code findClientBookingDetails} (19.3).
     *
     *
     * <p><b>{@code m.user} is a {@code LEFT JOIN} and must stay one</b> (V157 / phase 294 D1 —
     * 2026-09 audit finding 6). It was an INNER join, which SILENTLY UNDER-COUNTED: every COMPLETED
     * booking whose master has since been detached (staff {@code users} row hard-deleted) dropped
     * out of the aggregate entirely, so a salon-employed provider's visits stopped contributing to
     * the client's own passport ranking even though the salon locality that ranking reads is on the
     * {@code s} row, not on {@code mu}. With the LEFT join the salon arm of the {@code CASE} is
     * unaffected and keeps counting; a DETACHED INDEPENDENT master (no salon) resolves to a null
     * locality and is excluded by this query's own {@code IS NOT NULL} predicate — the correct
     * answer, since there is no locality left to attribute the visit to.
     * <p>Ranking is done IN SQL (GROUP BY + ORDER BY count DESC, bounded by {@code Pageable});
     * never in memory.
     */
    @Query("""
            SELECT new com.beautica.client.repository.CityCount(
                CASE WHEN s.id IS NOT NULL THEN s.cityId ELSE mu.cityId END,
                COUNT(b)
            )
            FROM Booking b
            JOIN b.master m
            LEFT JOIN m.user mu
            LEFT JOIN m.salon s
            WHERE b.client.id = :clientId
              AND b.status = com.beautica.booking.enums.BookingStatus.COMPLETED
              AND CASE WHEN s.id IS NOT NULL THEN s.cityId ELSE mu.cityId END IS NOT NULL
            GROUP BY CASE WHEN s.id IS NOT NULL THEN s.cityId ELSE mu.cityId END
            ORDER BY COUNT(b) DESC
            """)
    List<CityCount> findTopCities(@Param("clientId") UUID clientId, Pageable pageable);

    /**
     * Single-row spend aggregate over the client's COMPLETED bookings. {@code avg/min/max}
     * are null and {@code total} is 0 when there are none (empty-set aggregate) — the
     * service maps that to the empty-state.
     */
    @Query("""
            SELECT new com.beautica.client.repository.BudgetAggregate(
                CAST(AVG(b.priceAtBooking) AS BigDecimal),
                MIN(b.priceAtBooking),
                MAX(b.priceAtBooking),
                COUNT(b)
            )
            FROM Booking b
            WHERE b.client.id = :clientId
              AND b.status = com.beautica.booking.enums.BookingStatus.COMPLETED
            """)
    BudgetAggregate aggregateBudget(@Param("clientId") UUID clientId);

    /**
     * Paginated, most-recent-first timeline of the client's CLOSED-OR-ELAPSED bookings. Scalar
     * projection, so Hibernate applies a correct SQL LIMIT/OFFSET for {@code pageable} (no
     * HHH90003004).
     *
     * <p><b>Widened beyond {@code status = COMPLETED} (2026-08-26, this session's locked product
     * decision).</b> A master who never marks a finished visit {@code COMPLETED} used to make it
     * invisible in the client's beauty history forever — no {@code @Scheduled} job ever closes an
     * elapsed {@code CONFIRMED} booking (see {@link com.beautica.booking.domain.BookingClosureRule}'s
     * header javadoc). The predicate is now:
     *
     * <pre>
     * status = COMPLETED
     * OR (status = CONFIRMED AND endsAt &lt; :now)
     * </pre>
     *
     * <p>This is exactly the {@code PAST} partition's own shape (see {@link
     * com.beautica.booking.enums.BookingPartition}'s class javadoc and {@link
     * com.beautica.booking.repository.BookingSpecifications#partition}) MINUS the {@code
     * NOT_COMPLETED} disjunct — deliberately, by explicit product decision, NOT reused wholesale.
     * {@code NOT_COMPLETED} is an explicit provider no-show marking: the service never happened, so
     * it has no place in a beauty history. Do not "fix" this to match {@code PAST} exactly by
     * adding {@code NOT_COMPLETED} back in.
     *
     * <p>A JPQL {@code @Query} cannot invoke {@link
     * com.beautica.booking.domain.BookingClosureRule#isAwaitingClosure} (a Criteria/in-memory API,
     * not a JPQL-callable one), so the elapsed-{@code CONFIRMED} leg is re-expressed here as JPQL
     * rather than delegated by method call — but the {@code :now} VALUE is not re-derived: the
     * caller ({@code ClientPassportService#getTimeline}) resolves it exactly the way {@code
     * BookingService} does for the {@code PAST}/{@code AWAITING_CLOSURE} partition boundary —
     * {@code OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)}, the same injected {@link
     * java.time.Clock} bean, never {@code Instant.now()}/{@code OffsetDateTime.now()} (Anti-Bug §G)
     * — so this predicate and {@code BookingSpecifications#partition}'s {@code PAST} arm can never
     * disagree about whether a given booking has elapsed. {@link
     * com.beautica.common.TimeZones#KYIV} must never appear here — this is an absolute-instant
     * comparison, not a calendar-day one.
     *
     * <p><b>Both the content query and {@code countQuery} carry the identical predicate</b> — they
     * are separate strings in this annotation and a mismatch here silently breaks pagination
     * totals (the exact trap this javadoc calls out so a future edit does not reintroduce it).
     */
    @Query(value = """
            SELECT new com.beautica.client.repository.TimelineItemProjection(
                b.id,
                sd.category,
                b.startsAt,
                m.id,
                sd.name
            )
            FROM Booking b
            JOIN b.master m
            JOIN b.masterService ms
            JOIN ms.serviceDefinition sd
            WHERE b.client.id = :clientId
              AND ( b.status = com.beautica.booking.enums.BookingStatus.COMPLETED
                    OR (b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED AND b.endsAt < :now) )
            ORDER BY b.startsAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            WHERE b.client.id = :clientId
              AND ( b.status = com.beautica.booking.enums.BookingStatus.COMPLETED
                    OR (b.status = com.beautica.booking.enums.BookingStatus.CONFIRMED AND b.endsAt < :now) )
            """)
    Page<TimelineItemProjection> findTimeline(
            @Param("clientId") UUID clientId, @Param("now") OffsetDateTime now, Pageable pageable);
}
