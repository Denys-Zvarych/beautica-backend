package com.beautica.location.repository;

import com.beautica.location.entity.Oblast;
import com.beautica.location.entity.SettlementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-access interface for {@link Oblast} reference data.
 *
 * <p>All reads are read-only from the application perspective; no write
 * methods are defined here — mutations are exclusively via Flyway migrations.
 */
public interface OblastRepository extends JpaRepository<Oblast, UUID> {

    /**
     * Returns the oblasts that hold at least one settlement of {@code settlementType}, sorted
     * alphabetically by Ukrainian name. Populates the top-level tier of the cascading locality
     * picker, which passes {@link SettlementType#CITY}.
     *
     * <p><b>Why the existence predicate (Phase 325 follow-up).</b> The second tier
     * ({@link CityRepository#findByOblastIdAndSettlementTypeOrderByNameUkAsc}) is bounded to
     * {@code CITY}. Луганська has 13 free settlements and NONE of them is a city, so an
     * unfiltered oblast list offers the user a choice whose next screen is unconditionally
     * empty — a dead end with no way forward and no explanation. Measured CITY-row counts for
     * the thin oblasts: Луганська 0, Київ 1, Херсонська 2, Запорізька 4.
     *
     * <p>The cascade is a transitional surface — Phase 326's settlement search replaces it — and
     * until then an oblast that leads nowhere is strictly worse than one not offered. Bounding
     * tier 1 by the same predicate tier 2 applies keeps the two tiers consistent by construction.
     *
     * <p>The unfiltered {@code findAllByOrderByNameUkAsc} is DELETED rather than kept alongside
     * (§E-1): a caller reaching for the shorter name would silently re-open the dead end.
     * Anything that needs the full 25-row oblast table (row-count contract tests, Phase 326)
     * uses {@link #findAll()} explicitly, which is loud about being unfiltered.
     *
     * <p>Backed by {@code idx_cities_oblast_city_name} (V172) — the correlated {@code EXISTS}
     * resolves to a 353-entry partial-index probe per oblast, not a scan of 25 698 rows.
     *
     * @param settlementType the settlement kind an oblast must contain to be offered
     * @return matching oblasts ordered by {@code name_uk ASC}
     */
    @Query("""
            SELECT o FROM Oblast o
            WHERE EXISTS (
                SELECT 1 FROM City c
                 WHERE c.oblast = o
                   AND c.settlementType = :settlementType)
            ORDER BY o.nameUk ASC
            """)
    List<Oblast> findWithSettlementTypeOrderByNameUkAsc(
            @Param("settlementType") SettlementType settlementType);

    /**
     * Looks up an oblast by its stable KATOTTH code.
     * Used by Phase 10.2 seed validation and Phase 10.3 FK resolution.
     *
     * @param katotthCode official KATOTTH oblast code
     * @return the matching oblast, if present
     */
    Optional<Oblast> findByKatotthCode(String katotthCode);
}
