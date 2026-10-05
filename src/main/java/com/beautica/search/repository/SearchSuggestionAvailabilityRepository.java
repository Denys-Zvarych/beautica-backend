package com.beautica.search.repository;

import com.beautica.master.repository.MasterBookabilitySql;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.salon.repository.SalonSearchSql;
import com.beautica.search.service.SearchService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The D5 grouped availability query for {@code GET /api/v1/search/suggestions} (Phase 331) —
 * ONE native query per place, run at most once per {@code searchSuggestionAvailability} cache
 * TTL (10 min), never per suggestion or per keystroke. Also backs the {@code searchSuggestionActivePlaces}
 * short-circuit (audit-fix cycle 1 finding 2) via {@link #findActivePlaces()}.
 *
 * <h3>No re-typed SQL (D3)</h3>
 * Both branches reuse the EXACT fragments {@code SearchService} and {@code SalonSearchSql} already
 * use for {@code /search/masters} / {@code /search/salons}, so this query can never disagree with
 * what the results page itself would show:
 * <ul>
 *   <li><b>master branch</b> — {@link SearchService#DISCOVERY_CITY_EXPR} /
 *       {@link SearchService#DISCOVERY_DISTRICT_EXPR} and
 *       {@link SearchService#appendMasterActiveIndependentPredicate}, the same active/role/locality
 *       predicate {@code SearchService.appendWhereClause} emits;</li>
 *   <li><b>salon branch</b> — {@link SalonSearchSql#STATIC_DISTRICT_PREDICATE} /
 *       {@link SalonSearchSql#STATIC_CITY_PREDICATE} and
 *       {@link SearchService#appendSalonBookableGate}, the same coarse "actively performed by an
 *       active master of this salon" gate {@code SearchService}'s own salon-side queries use.</li>
 * </ul>
 * The one predicate NOT lifted from an existing constant is {@code s.is_active = true}: it is
 * embedded inside {@code SalonSearchSql}'s large compile-time projection block rather than a
 * standalone constant, and splitting that block for one trivial single-column boolean (no
 * COALESCE, no join, no realistic drift risk) was judged not worth the risk of touching a query
 * the existing salon-discovery ITs pin byte-for-byte. It is re-typed here as a one-line literal —
 * every other predicate with real drift risk (locality, role, the bookable-gate join) is shared.
 *
 * <h3>Locality — district-primary, national when both null</h3>
 * The locality predicate is applied to EACH branch independently: a district id, if present, wins
 * (mirroring {@code SearchService.appendWhereClause}); otherwise a city id; otherwise neither
 * branch gets a locality predicate at all (the national snapshot).
 *
 * <h3>UNION, not UNION ALL</h3>
 * Each branch is itself {@code SELECT DISTINCT category, service_type_id}; {@code UNION} then
 * dedups across the two branches too, so a service type offered by both an independent master and
 * a salon in the same place yields one row, not two.
 *
 * <h3>SQL-shape invariant (audit-fix cycle 1, finding 1 — LOW security)</h3>
 * {@link #SQL}'s four {@code %s} slots are filled EXCLUSIVELY from {@link #MASTER_ACTIVE_PREDICATE},
 * {@link #SALON_BOOKABLE_GATE}, and the {@link MasterLocalityFragment} / {@link SalonLocalityFragment}
 * enums below — every one of those is a {@code static final} literal fixed at class-load time, never a
 * value computed from a method parameter. {@code cityId}/{@code districtId} themselves NEVER touch the
 * SQL text at all; they are only ever bound as {@code :cityId}/{@code :districtId} query parameters.
 * This is enforced by construction, not merely true today: there is no code path in this class through
 * which a caller-supplied {@code String} could reach {@link String#formatted}, because
 * {@link #buildAvailabilitySql(LocalityMode)} accepts only a {@link LocalityMode} enum value, not a
 * {@code String} — a future locality variant must be added as a new fixed enum constant, not an inline
 * concatenation. {@code SearchSuggestionAvailabilityRepositorySqlShapeTest} pins the exact assembled
 * SQL text for every mode and would fail the moment any slot stopped being one of these fixed literals.
 */
@Repository
public class SearchSuggestionAvailabilityRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Source of the bound Kyiv "today" both branches' bookability fragments read
     * ({@link MasterBookabilitySql#TODAY_PARAM}) — the app clock, never the DB clock.
     */
    private final ScheduleDateMath scheduleDateMath;

    public SearchSuggestionAvailabilityRepository(ScheduleDateMath scheduleDateMath) {
        this.scheduleDateMath = scheduleDateMath;
    }

    private static final String SQL =
            "SELECT DISTINCT sd.category AS category, sd.service_type_id AS service_type_id "
                    + "FROM masters m "
                    + "JOIN users u ON u.id = m.user_id "
                    + "LEFT JOIN salons sal ON sal.id = m.salon_id "
                    + "JOIN master_services ms ON ms.master_id = m.id AND ms.is_active = true "
                    + "JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true "
                    + "WHERE %s"
                    + "%s"
                    + "UNION "
                    + "SELECT DISTINCT sd.category AS category, sd.service_type_id AS service_type_id "
                    + "FROM salons s "
                    + "JOIN service_definitions sd ON sd.owner_type = 'SALON' AND sd.owner_id = s.id "
                    + "AND sd.is_active = true "
                    + "WHERE s.is_active = true "
                    + "%s"
                    + "%s";

    /**
     * The master branch's active/role predicate ({@link SearchService#appendMasterActiveIndependentPredicate})
     * captured ONCE at class-load time. The text is fixed and contains only the {@code :includedRole}
     * placeholder — no request data is ever folded into it.
     */
    private static final String MASTER_ACTIVE_PREDICATE = captureMasterActivePredicate();

    /**
     * The salon branch's bookable gate ({@link SearchService#appendSalonBookableGate}) captured ONCE at
     * class-load time with the fixed literal aliases {@code "sd"}/{@code "s"} — no bound parameters, no
     * request data.
     */
    private static final String SALON_BOOKABLE_GATE = captureSalonBookableGate();

    /**
     * The active-places query (audit-fix cycle 1, finding 2) — the same two branches as {@link #SQL},
     * projecting {@code (city_id, district_id)} instead of {@code (category, service_type_id)}, with NO
     * locality predicate (every active place is wanted, not one place's availability). Fully assembled
     * from the same two class-load-time constants above; no {@code %s} formatting is needed at all
     * because nothing here varies per call.
     */
    private static final String ACTIVE_PLACES_SQL =
            "SELECT DISTINCT " + SearchService.DISCOVERY_CITY_EXPR + " AS city_id, "
                    + SearchService.DISCOVERY_DISTRICT_EXPR + " AS district_id "
                    + "FROM masters m "
                    + "JOIN users u ON u.id = m.user_id "
                    + "LEFT JOIN salons sal ON sal.id = m.salon_id "
                    + "JOIN master_services ms ON ms.master_id = m.id AND ms.is_active = true "
                    + "JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true "
                    + "WHERE " + MASTER_ACTIVE_PREDICATE
                    + "UNION "
                    + "SELECT DISTINCT s.city_id AS city_id, s.district_id AS district_id "
                    + "FROM salons s "
                    + "JOIN service_definitions sd ON sd.owner_type = 'SALON' AND sd.owner_id = s.id "
                    + "AND sd.is_active = true "
                    + "WHERE s.is_active = true "
                    + SALON_BOOKABLE_GATE;

    private static String captureMasterActivePredicate() {
        StringBuilder sb = new StringBuilder();
        SearchService.appendMasterActiveIndependentPredicate(sb, new HashMap<>());
        return sb.toString();
    }

    private static String captureSalonBookableGate() {
        StringBuilder sb = new StringBuilder();
        SearchService.appendSalonBookableGate(sb, "sd", "s");
        return sb.toString();
    }

    /**
     * Runs the grouped UNION query for one already-normalised place and returns the raw
     * {@code (category, serviceTypeId)} pairs. Callers (only {@code SearchSuggestionAvailability})
     * fold this into a {@code PlaceAvailability}; kept as raw pairs here so this repository has no
     * dependency on the caching service's package-private types.
     *
     * @param cityId     already district-primary-resolved: {@code null} whenever a district is
     *                   present, per {@code SuggestionPlaceKey}'s normalisation
     * @param districtId a district id, or {@code null}
     * @return every {@code (category, serviceTypeId)} pair available in the place; empty for an
     *         unknown or empty place (never throws for a well-formed-but-unmatched UUID)
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> findAvailable(UUID cityId, UUID districtId) {
        LocalityMode mode = LocalityMode.resolve(districtId, cityId);
        Map<String, Object> params = new HashMap<>();
        params.put("includedRole", SearchService.ROLE_INDEPENDENT_MASTER);
        if (mode == LocalityMode.DISTRICT) {
            params.put("districtId", districtId);
        } else if (mode == LocalityMode.CITY) {
            params.put("cityId", cityId);
        }

        String sql = buildAvailabilitySql(mode);
        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        bindToday(query, sql);
        return query.getResultList();
    }

    /**
     * The active-places set (audit-fix cycle 1, finding 2) — every {@code city_id}/{@code district_id}
     * that has at least one bookable offer, with NO locality restriction. Run once per
     * {@code searchSuggestionActivePlaces} cache TTL by {@code SearchSuggestionActivePlaces}, never per
     * request.
     *
     * @return every {@code (cityId, districtId)} pair a bookable master/salon sits in; a row may carry a
     *         {@code null} district (city-only placement)
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> findActivePlaces() {
        Query query = entityManager.createNativeQuery(ACTIVE_PLACES_SQL);
        query.setParameter("includedRole", SearchService.ROLE_INDEPENDENT_MASTER);
        bindToday(query, ACTIVE_PLACES_SQL);
        return query.getResultList();
    }

    private void bindToday(Query query, String sql) {
        if (MasterBookabilitySql.referencesToday(sql)) {
            query.setParameter(MasterBookabilitySql.TODAY_PARAM, scheduleDateMath.today());
        }
    }

    /**
     * Assembles the exact SQL text {@link #findAvailable} would execute for one {@link LocalityMode},
     * without touching a connection. Package-private and DB-free specifically so
     * {@code SearchSuggestionAvailabilityRepositorySqlShapeTest} can pin it byte-for-byte: every slot
     * this fills comes from a {@code static final} constant or an enum literal, so the returned text is
     * identical on every call for a given mode, and a caller-supplied city/district id can never appear
     * in it.
     */
    static String buildAvailabilitySql(LocalityMode mode) {
        return SQL.formatted(
                MASTER_ACTIVE_PREDICATE,
                MasterLocalityFragment.forMode(mode).sql,
                SalonLocalityFragment.forMode(mode).sql,
                SALON_BOOKABLE_GATE);
    }

    /** Which locality restriction (if any) applies — district wins over city, mirroring D3/D5. */
    enum LocalityMode {
        NATIONAL, CITY, DISTRICT;

        static LocalityMode resolve(UUID districtId, UUID cityId) {
            if (districtId != null) {
                return DISTRICT;
            }
            if (cityId != null) {
                return CITY;
            }
            return NATIONAL;
        }
    }

    /**
     * The master branch's locality predicate text per {@link LocalityMode} — FIXED literals only
     * (D3/finding 1). The varying id is never embedded here; it is bound separately as
     * {@code :cityId}/{@code :districtId} in {@link #findAvailable}.
     */
    private enum MasterLocalityFragment {
        NATIONAL(""),
        CITY("AND " + SearchService.DISCOVERY_CITY_EXPR + " = :cityId "),
        DISTRICT("AND " + SearchService.DISCOVERY_DISTRICT_EXPR + " = :districtId ");

        private final String sql;

        MasterLocalityFragment(String sql) {
            this.sql = sql;
        }

        static MasterLocalityFragment forMode(LocalityMode mode) {
            return switch (mode) {
                case NATIONAL -> NATIONAL;
                case CITY -> CITY;
                case DISTRICT -> DISTRICT;
            };
        }
    }

    /**
     * Salon-branch mirror of {@link MasterLocalityFragment}, reusing
     * {@link SalonSearchSql#STATIC_DISTRICT_PREDICATE} / {@link SalonSearchSql#STATIC_CITY_PREDICATE}
     * verbatim — same bind-param names ({@code districtId}/{@code cityId}), so one params map serves
     * both branches of the UNION.
     */
    private enum SalonLocalityFragment {
        NATIONAL(""),
        CITY(SalonSearchSql.STATIC_CITY_PREDICATE),
        DISTRICT(SalonSearchSql.STATIC_DISTRICT_PREDICATE);

        private final String sql;

        SalonLocalityFragment(String sql) {
            this.sql = sql;
        }

        static SalonLocalityFragment forMode(LocalityMode mode) {
            return switch (mode) {
                case NATIONAL -> NATIONAL;
                case CITY -> CITY;
                case DISTRICT -> DISTRICT;
            };
        }
    }
}
