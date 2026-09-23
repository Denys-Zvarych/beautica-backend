package com.beautica.location.repository;

import com.beautica.location.entity.City;
import com.beautica.location.entity.SettlementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-access interface for {@link City} reference data.
 *
 * <p>All reads are read-only from the application perspective; no write
 * methods are defined here — mutations are exclusively via Flyway migrations.
 */
public interface CityRepository extends JpaRepository<City, UUID> {

    /**
     * Returns the settlements of one {@code settlementType} within the given oblast, sorted
     * alphabetically by Ukrainian name. Used to populate the second tier of the cascading
     * locality picker after an oblast is selected, which passes
     * {@link com.beautica.location.entity.SettlementType#CITY}.
     *
     * <p><b>Why the type argument exists (Phase 325 follow-up).</b> The predecessor
     * {@code findByOblastIdOrderByNameUkAsc} had no type predicate, which was harmless while
     * {@code cities} held V53's 356 category-M rows. V170/V171 widened the table to 25 698
     * settlements, and the largest oblast now holds 1 928 of them — so the unfiltered finder turned
     * a ~15-row picker response into a ~1 900-row one. The non-predicate variant is DELETED rather
     * than kept alongside (§E-1): a caller reaching for the shorter name would silently re-open the
     * same ~100× payload.
     *
     * <p>Bounded by data, not by a {@code LIMIT}: 353 rows are {@code CITY} (352 imported + Kyiv)
     * and the largest oblast holds 44 of them, so the picker is back at its pre-V170 magnitude with
     * NO silent truncation — a hard cap on an alphabetical picker would hide the tail of the list
     * from the user rather than page it. The full-settlement surface is Phase 326's search
     * endpoint, which owns paging and its own index.
     *
     * <p>Backed by {@code idx_cities_oblast_city_name} (V172) —
     * {@code (oblast_id, name_uk) WHERE settlement_type = 'CITY'}.
     *
     * <p><b>This comment previously claimed the type predicate was "a filter on the
     * already-ordered index range" of V52's {@code idx_cities_oblast_id}. It was not.</b> That
     * index has no predicate, so the filter ran ABOVE the scan, on the heap: measured on
     * Львівська (the largest oblast), <b>1 884 Rows Removed by Filter</b> to return 44 rows,
     * 54 shared buffers, 0.35–0.89 ms. V172's partial index holds only the 353 CITY rows,
     * pre-ordered by {@code name_uk} within each oblast, taking the same query to an Index Only
     * Scan with 0 rows filtered, 30 shared buffers and 0.18 ms — for 40 kB / 353 entries.
     *
     * @param oblastId       surrogate PK of the parent oblast
     * @param settlementType kind of populated place to return
     * @return matching settlements in the oblast ordered by {@code name_uk ASC}
     */
    List<City> findByOblastIdAndSettlementTypeOrderByNameUkAsc(UUID oblastId,
                                                               SettlementType settlementType);


    /**
     * Ranked settlement autocomplete behind the {@code permitAll}
     * {@code GET /api/v1/settlements?query=...} (Phase 326) — the surface that replaces the
     * oblast -> city cascade for address entry.
     *
     * <p><b>Three tiers, and why trigram similarity alone is the wrong ranking.</b>
     * {@code similarity('льв', 'Львів')} is only 0.43 because the length ratio dominates, so a
     * 3-character prefix — by far the most common input from an autocomplete — ranks the intended
     * answer below longer accidental matches. The ranking is therefore:
     *
     * <ol>
     *   <li><b>Prefix</b> — {@code name_uk ILIKE :prefixPattern}. A settlement whose name STARTS
     *       with what the user typed always outranks one that merely resembles it.</li>
     *   <li><b>{@code is_major}</b> — 50 curated rows (23 serviceable oblast centres + the 27
     *       next-largest cities, see {@code City#isMajor}).</li>
     *   <li><b>Trigram similarity</b> — the typo/abbreviation fallback.</li>
     * </ol>
     *
     * <p><b>{@code is_major} sits ABOVE similarity, not below it — this deviates from the phase
     * doc's prose and satisfies its ACCEPTANCE.</b> phase-326 lists {@code is_major} as tier 3
     * "so Львів beats Львівське", which raw similarity already achieves (0.43 vs 0.27). But the
     * same doc requires «іван фран» to return Івано-Франківськ, and with similarity ranked first
     * it does not: measured on the real 25 698-row table, {@code similarity('іван фран',
     * 'Івано-Франкове') = 0.500} beats {@code similarity('іван фран', 'Івано-Франківськ') = 0.444},
     * so the Львівська village won. Ordering by {@code is_major} first puts the oblast centre back
     * on top.
     *
     * <p>That promotion is SAFE because the prefix tier dominates it: a major city can only
     * outrank a settlement that is in the SAME tier. A user typing the exact name of a village
     * («тернове») puts that village in tier 1 and every non-prefix city in tier 2, so the village
     * wins no matter how major the city is — verified against «тернове», «львівка», «київс».
     * Within one tier, "the big place you have heard of, first" is the behaviour an address picker
     * wants.
     *
     * <p><b>Why {@code similarity(...) >= :minSimilarity} is present even though {@code %} already
     * applies a threshold.</b> The {@code %} operator compares against the SESSION GUC
     * {@code pg_trgm.similarity_threshold} (PostgreSQL default 0.3), which is the only indexable
     * form — {@code similarity() >= const} alone cannot drive a GIN scan and would seq-scan
     * 25 698 rows on every keystroke. Binding the floor explicitly makes the result set
     * deterministic if that GUC is ever lowered somewhere; a RAISED GUC would still narrow recall
     * silently, which is why {@code SettlementSearchIT} asserts the effective threshold rather
     * than trusting it.
     *
     * <p><b>Index (V173):</b> {@code idx_cities_name_uk_trgm}, GIN {@code gin_trgm_ops} on
     * {@code name_uk}. ONE index serves both predicates — the planner satisfies the {@code OR}
     * with a {@code BitmapOr} over two Bitmap Index Scans of it. Measured on the fully populated
     * table: 28.4 ms / 385 buffers sequential before, 0.27 ms / 46 buffers after for «льв»;
     * 4.47 ms for the worst measured 3-character query («нов», 1 065 candidates). The plan holds
     * under {@code force_generic_plan}, i.e. after pgJDBC promotes the statement to a server-side
     * prepare.
     *
     * <p><b>No occupation predicate</b> (phase-325 D3 / phase-326 D4): there is no
     * {@code occupation_status} column and no occupied row in this table — Phase 324's exclusion
     * set is applied to the import SOURCE. This finder must not grow one.
     *
     * <p>Bounded by {@code LIMIT :maxResults} (§E-3), never by the caller's paging.
     *
     * @param prefixPattern  the LIKE pattern for tier 1 — the caller's term, apostrophe-folded and
     *                       with {@code LIKE} metacharacters escaped, plus a trailing {@code %}.
     *                       Never the raw user string.
     * @param query          the caller's term for the trigram tiers, apostrophe-folded but NOT
     *                       {@code LIKE}-escaped (pg_trgm treats punctuation as a word separator)
     * @param minSimilarity  explicit trigram floor, mirroring the {@code %} operator's threshold
     * @param maxResults     hard result cap (phase-326 D8: 20)
     * @return ranked rows, at most {@code maxResults} of them
     */
    @Query(value = """
            SELECT c.id              AS "settlementId",
                   c.name_uk         AS "nameUk",
                   c.settlement_type AS "settlementType",
                   o.name_uk         AS "oblastNameUk",
                   CASE WHEN c.ambiguous_in_oblast THEN c.hromada_name_uk END AS "hromadaNameUk"
            FROM cities c
            JOIN oblasts o ON o.id = c.oblast_id
            WHERE c.name_uk ILIKE :prefixPattern
               OR (c.name_uk % :query AND similarity(c.name_uk, :query) >= :minSimilarity)
            ORDER BY (CASE WHEN c.name_uk ILIKE :prefixPattern THEN 0 ELSE 1 END),
                     c.is_major DESC,
                     similarity(c.name_uk, :query) DESC,
                     length(c.name_uk),
                     c.name_uk,
                     o.name_uk,
                     c.id
            LIMIT :maxResults
            """, nativeQuery = true)
    List<SettlementSearchRow> searchByName(@Param("prefixPattern") String prefixPattern,
                                           @Param("query") String query,
                                           @Param("minSimilarity") double minSimilarity,
                                           @Param("maxResults") int maxResults);

    /**
     * The "biggest places" list the settlement autocomplete shows BEFORE the user types
     * (phase-326 D3) — the {@code is_major} settlements, ordered by Ukrainian name.
     *
     * <p><b>Unbounded return, deliberately (§E-3).</b> {@code is_major} is not a runtime flag: it
     * is curated by hand in {@code scripts/locality/build_settlement_import.py} and pinned at
     * EXACTLY 50 rows by {@code V171__import_free_settlements.EXPECTED_MAJOR}, which ABORTS the
     * migration if the count drifts. The cardinality is a migration invariant, not an estimate, so
     * a {@code Pageable} would page a list that cannot grow. The same reasoning the CITY-tier
     * cascade finder records applies: a hard cap on an alphabetical picker hides its tail from the
     * user rather than paging it.
     *
     * <p>Backed by {@code idx_cities_major_name_uk} (V170) — {@code (name_uk) WHERE is_major}, 50
     * entries. Measured on the fully populated table: Bitmap Index Scan on that index, 0.31 ms.
     *
     * <p>Shares {@link SettlementSearchRow} with {@link #searchByName} so both halves of the
     * endpoint map through one projection and one DTO factory.
     *
     * @return the 50 curated major settlements with their oblast labels, ordered by {@code name_uk}
     */
    @Query(value = """
            SELECT c.id              AS "settlementId",
                   c.name_uk         AS "nameUk",
                   c.settlement_type AS "settlementType",
                   o.name_uk         AS "oblastNameUk",
                   CASE WHEN c.ambiguous_in_oblast THEN c.hromada_name_uk END AS "hromadaNameUk"
            FROM cities c
            JOIN oblasts o ON o.id = c.oblast_id
            WHERE c.is_major
            ORDER BY c.name_uk, o.name_uk, c.id
            """, nativeQuery = true)
    List<SettlementSearchRow> findMajorSettlements();

    /**
     * Typed Spring Data interface projection for the two settlement-autocomplete queries.
     *
     * <p>Column aliases are DOUBLE-QUOTED in the native SQL so PostgreSQL preserves their camel
     * case: an unquoted {@code AS settlementId} is folded to {@code settlementid} and the
     * projection binds nothing. This is also why a raw {@code Object[]} is not used — the repo has
     * already been bitten once by Hibernate collapsing an {@code Object[]} row shape
     * (see {@link TaxonomyFactsRow}).
     *
     * <p>{@code settlementType} is exposed as {@code String}, not as
     * {@link com.beautica.location.entity.SettlementType}: the column is a {@code VARCHAR(20)}
     * guarded by {@code chk_cities_settlement_type} and a native-query projection has no enum
     * converter, so the service does the {@code valueOf} once, at the DTO boundary.
     */
    interface SettlementSearchRow {

        /** Surrogate PK of the settlement — what the client stores (phase-326 D6). */
        UUID getSettlementId();

        /** Canonical Ukrainian settlement name. */
        String getNameUk();

        /** {@code cities.settlement_type} as stored; mapped to the enum by the service. */
        String getSettlementType();

        /**
         * Ukrainian name of the parent oblast — the disambiguating label (phase-326 D5).
         * 99 «Іванівка» rows exist across 20 oblasts, and «Львів» is also two villages.
         */
        String getOblastNameUk();

        /**
         * Bare hromada adjective, or {@code null} when the oblast label already identifies the row
         * (phase-327 D2/D3).
         *
         * <p>The {@code CASE WHEN c.ambiguous_in_oblast THEN ... END} in both queries is what makes
         * this nullable in the SQL sense rather than merely nullable in Java: the column is
         * populated for 25 695 of 25 698 rows, and projecting it unconditionally would grow the
         * label on the 76 % of rows «‹назва›, ‹область›» already identifies. The DECISION of
         * whether a hromada is needed is a property of the data, taken once at import time; the
         * client holds no ambiguity logic and simply renders the third part when it is non-null.
         */
        String getHromadaNameUk();
    }

    /**
     * Looks up a city by its stable KATOTTH code.
     * Used by Phase 10.2 seed validation and Phase 10.3 FK resolution.
     *
     * @param katotthCode official KATOTTH city code
     * @return the matching city, if present
     */
    Optional<City> findByKatotthCode(String katotthCode);

    /**
     * Fetches a city together with its parent {@link com.beautica.location.entity.Oblast}
     * in a single round-trip via {@code JOIN FETCH}.
     *
     * <p>Use this instead of the plain {@link #findById(Object)} whenever the
     * caller needs to read {@code city.getOblast()} — the plain finder leaves
     * the {@code LAZY} association un-initialised, causing either a secondary
     * SELECT (N+1) inside an active transaction or a
     * {@code LazyInitializationException} outside one.
     *
     * @param id surrogate PK of the city
     * @return the city with its oblast hydrated, or empty when not found
     */
    @Query("SELECT c FROM City c JOIN FETCH c.oblast WHERE c.id = :id")
    Optional<City> findByIdWithOblast(@Param("id") UUID id);

    /**
     * Batch-resolves city {@code name_uk} labels for a set of city ids in a
     * single {@code IN (...)} query.
     *
     * <p>Used by {@link com.beautica.location.DiscoveryLocationResolver} to
     * stamp human-readable {@code cityLabel}s onto a whole page of search
     * results at once. The set-based form is deliberate (§E): the caller
     * collects the distinct city ids of the page and resolves them with
     * <em>one</em> query — never a per-row lookup. The 2-element projection
     * {@code [id, name_uk]} avoids hydrating the {@link City} entity (and its
     * LAZY {@code oblast}) just to read one column.
     *
     * @param ids distinct city ids appearing on the current result page
     * @return rows of {@code [UUID id, String nameUk]}; empty when {@code ids}
     *         is empty
     */
    @Query("SELECT c.id, c.nameUk FROM City c WHERE c.id IN :ids")
    List<Object[]> findNameUkByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * Batch-resolves city → parent-oblast-id pairs for a set of city ids in a
     * single {@code IN (...)} query.
     *
     * <p>Sibling of {@link #findNameUkByIdIn(Collection)}: used by
     * {@code SalonService#getOwnerSalons} to stamp {@code oblastId} onto a whole
     * page of an owner's salons at once. The single-key {@link #findByIdWithOblast(UUID)}
     * (directly, or via {@code LocationQueryService#resolveCityOblastId}) would N+1 across the
     * list (§E) — this
     * fuses the resolution into one round-trip regardless of how many distinct
     * cities the owner's salons reference. The 2-element scalar projection
     * {@code [id, oblast.id]} avoids hydrating the {@link City} entity (and its
     * LAZY {@code oblast}) just to read one FK column.
     *
     * @param ids distinct, non-null city ids to resolve
     * @return rows of {@code [UUID cityId, UUID oblastId]}; empty when {@code ids}
     *         is empty
     */
    @Query("SELECT c.id, c.oblast.id FROM City c WHERE c.id IN :ids")
    List<Object[]> findOblastIdsByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * Single-query taxonomy resolution for the Phase 10.6 most-specific-node
     * write rule: in <em>one</em> round-trip it answers all three facts
     * {@link com.beautica.location.LocalityWriteValidator} needs about a
     * {@code (cityId, districtId)} pair.
     *
     * <p>This fuses what used to be three sequential existence calls
     * ({@code cityRepository.existsById}, {@code existsByCityId},
     * {@code existsByIdAndCityId}) into a single SELECT, and is the query that
     * backs the {@code localityTaxonomyFacts} cache — so a warm save issues
     * <em>zero</em> taxonomy queries and a cold save issues exactly one.
     *
     * <p>Returns at most one row exposed as a typed {@link TaxonomyFactsRow}
     * interface projection rather than a raw {@code Object[]}. The {@code
     * Object[]} shape was ambiguous under Hibernate: when both correlated
     * {@code EXISTS} subqueries resolved to {@code FALSE}, the row was
     * sometimes materialised as a 1-element array (the outer {@code TRUE}
     * only), causing an {@code ArrayIndexOutOfBoundsException} downstream. A
     * Spring Data interface projection binds JPQL select aliases to getter
     * names, so the row shape is fixed regardless of subquery values.
     *
     * <ul>
     *   <li>{@link TaxonomyFactsRow#getCityExists()} — always {@code true}
     *       when a row is returned (the {@code WHERE c.id = :cityId} matched);
     *       the caller treats an empty result as "city does not exist".</li>
     *   <li>{@link TaxonomyFactsRow#getCityHasDistricts()} — correlated
     *       {@code EXISTS} over {@code city_districts} for this city (served
     *       by {@code idx_city_districts_city_id}).</li>
     *   <li>{@link TaxonomyFactsRow#getDistrictBelongsToCity()} — {@code
     *       false} when {@code districtId} is {@code null}; otherwise a
     *       correlated {@code EXISTS} that the district row exists <em>and</em>
     *       its {@code city_id} is {@code :cityId}.</li>
     * </ul>
     *
     * <p>The result is keyed/cached per {@code (cityId, districtId)} pair by
     * {@link com.beautica.location.LocalityTaxonomyLookup}; the taxonomy is
     * static Flyway-seed data, so a cached resolution can never go stale at
     * runtime (same rationale as the {@code location*} read caches).
     *
     * @param cityId     candidate city PK (never {@code null} — the validator
     *                   short-circuits a {@code null} city before calling)
     * @param districtId candidate district PK, or {@code null} when the caller
     *                   supplied no district
     * @return a single projection row, or empty when the city does not exist
     */
    @Query("""
            SELECT
                TRUE AS cityExists,
                (SELECT CASE WHEN COUNT(d1.id) > 0 THEN TRUE ELSE FALSE END
                   FROM CityDistrict d1 WHERE d1.city.id = c.id) AS cityHasDistricts,
                (SELECT CASE WHEN COUNT(d2.id) > 0 THEN TRUE ELSE FALSE END
                   FROM CityDistrict d2
                  WHERE d2.id = :districtId AND d2.city.id = c.id) AS districtBelongsToCity
            FROM City c
            WHERE c.id = :cityId
            """)
    Optional<TaxonomyFactsRow> resolveTaxonomyFacts(@Param("cityId") UUID cityId,
                                                    @Param("districtId") UUID districtId);

    /**
     * Typed Spring Data interface projection for
     * {@link #resolveTaxonomyFacts(UUID, UUID)}.
     *
     * <p>Getter names mirror the JPQL select aliases ({@code cityExists},
     * {@code cityHasDistricts}, {@code districtBelongsToCity}) so Spring Data
     * can bind each column unambiguously. This replaces the legacy
     * {@code Object[]} return shape, which under Hibernate could collapse to
     * a 1-element array when both correlated {@code EXISTS} subqueries
     * returned {@code FALSE} — the array-collapse caused a runtime
     * {@code ArrayIndexOutOfBoundsException} in
     * {@link com.beautica.location.LocalityTaxonomyLookup#resolve} on
     * {@code PATCH /me} writes whose target city / district combinations did
     * not match the seeded happy-path data.
     */
    interface TaxonomyFactsRow {

        /** Always {@code true} when a row is returned (city row matched). */
        Boolean getCityExists();

        /** {@code true} when the city defines any urban districts. */
        Boolean getCityHasDistricts();

        /**
         * {@code true} when the supplied {@code districtId} both exists and
         * is a child of {@code cityId}; {@code false} when {@code districtId}
         * is {@code null} or no such child row exists.
         */
        Boolean getDistrictBelongsToCity();
    }
}
