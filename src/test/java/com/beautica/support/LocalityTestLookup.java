package com.beautica.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * The ONE place a test resolves a {@code cities} row by its Ukrainian name.
 *
 * <p>Exists because twelve fixture sites had each hand-copied
 * {@code "SELECT id FROM cities WHERE name_uk = ? LIMIT 1"}. That was harmless while {@code cities}
 * held V53's 356 category-M rows, but Phase 325 (V170 + V171) widened the table to 25 698
 * settlements: 2 990 distinct names are now shared by 12 833 rows, so «Київ» is both the capital and
 * a village in Миколаївська oblast, and «Львів» is also villages in Дніпропетровська and
 * Миколаївська — villages that sort BEFORE the real city by {@code katotth_code}. A {@code LIMIT 1}
 * fixture is therefore one classifier update away from silently pointing a salon at a village with
 * no districts and no salons, and passing while it does.
 *
 * <p>Two deliberate choices make {@link #majorCityIdByName(JdbcTemplate, String)} hard to defang
 * again:
 * <ul>
 *   <li>{@code settlement_type = 'CITY'} drops the namesake villages and селища;</li>
 *   <li>there is <b>no {@code LIMIT}</b> — a name still ambiguous among cities raises
 *       {@code IncorrectResultSizeDataAccessException} instead of quietly picking one. («Городок»,
 *       «Миколаїв» and «Південне» are ambiguous even among cities; none is used by any fixture
 *       today, and the loud throw is the correct answer if one ever is.)</li>
 * </ul>
 *
 * <p>{@link com.beautica.AbstractIntegrationTest#majorCityIdByName(String)} delegates here, so
 * subclasses keep their inherited one-argument call and the five fixture classes that deliberately
 * do <em>not</em> extend {@code AbstractIntegrationTest} (they run their own containers or are
 * plain collaborator objects) share the identical SQL rather than re-copying it — REUSE-FIRST, one
 * definition, one place to fix.
 */
public final class LocalityTestLookup {

    private LocalityTestLookup() {
    }

    /**
     * Resolves the {@code cities.id} of the one settlement of type {@code CITY} with this Ukrainian
     * name.
     *
     * @param jdbcTemplate the caller's template (its own container / datasource)
     * @param nameUk       canonical Ukrainian city name, e.g. {@code "Вінниця"}
     * @return the id of the one CITY with that name
     * @throws org.springframework.dao.IncorrectResultSizeDataAccessException when the name matches
     *         no city, or more than one
     */
    public static UUID majorCityIdByName(JdbcTemplate jdbcTemplate, String nameUk) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = ? AND settlement_type = 'CITY'",
                UUID.class, nameUk);
    }
}
