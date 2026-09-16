package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for the two Phase 319 audit-fix migrations that make
 * {@code GET /bookings/salon/{salonId}?serviceId=...} plan sanely:
 * <ul>
 *   <li>{@code V166} &rarr; CREATE {@code idx_bookings_salon_service_starts_at} on {@code bookings}
 *       {@code (salon_id, master_service_id, starts_at DESC)} — the composite that keeps a
 *       SINGLE-valued service chip on the salon scope with an Incremental Sort instead of leading
 *       with the V18 FK index and paying a blocking Sort.</li>
 *   <li>{@code V167} &rarr; CREATE STATISTICS {@code st_bookings_salon_service} on
 *       {@code (salon_id, master_service_id)} — the extended statistics that stop the planner
 *       multiplying two selectivities it wrongly assumes are independent, which is what made
 *       TWO chips ({@code = ANY} on a middle index column) fall off the composite and back onto a
 *       blocking Sort over the whole match set.</li>
 * </ul>
 *
 * <p>Per QA playbook Q21, "Flyway applied with no error" proves nothing about a migration's actual
 * outcome — and that is sharper than usual for {@code V167}: a statistics object is INVISIBLE in
 * every functional test. No query returns a different row because of it, {@code ddl-auto=validate}
 * knows nothing about {@code pg_statistic_ext}, and a typo'd or silently-dropped
 * {@code CREATE STATISTICS} would leave this entire suite green while the IN(2) pathology it exists
 * to remove came straight back. This test is the only thing standing behind it.
 *
 * <p><b>Why the populated-data half is deliberately NOT asserted.</b> {@code V167} ends with
 * {@code ANALYZE bookings}, without which the object is declared but the planner sees nothing.
 * Asserting a {@code pg_statistic_ext_data} row here would nonetheless be a FIXTURE assertion, not
 * a migration one: Postgres stores no extended-statistics data for a zero-row relation (measured on
 * PG 16 — {@code CREATE STATISTICS} + {@code ANALYZE} on an empty table leaves
 * {@code pg_statistic_ext_data} empty), and {@code bookings} is empty when the Testcontainers chain
 * migrates. The assertion would therefore pin "the table was empty", go green forever, and say
 * nothing about the {@code ANALYZE} line at all.
 *
 * <p>Fully read-only and order-independent; {@code cleanDb()} never touches catalog metadata, so no
 * fixture or cleanup is required. ASCII-only.
 */
@DisplayName("V166/V167 migration — salon+service booking query plan (index + extended statistics)")
class V166V167SalonServiceQueryPlanMigrationTest extends AbstractIntegrationTest {

    private static final String INDEX_NAME = "idx_bookings_salon_service_starts_at";
    private static final String STATISTICS_NAME = "st_bookings_salon_service";

    @Test
    @DisplayName("V166 — idx_bookings_salon_service_starts_at exists on bookings and is ordered "
            + "(salon_id, master_service_id, starts_at DESC); a reordered or DESC-less definition "
            + "would still be an index but would no longer serve the ORDER BY without a Sort")
    void should_createSalonServiceStartsAtIndex_when_v166Applied() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'bookings' AND indexname = ?",
                Integer.class, INDEX_NAME);

        String definition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'bookings' AND indexname = ?",
                String.class, INDEX_NAME);

        assertThat(count)
                .as("V166's index must exist on bookings")
                .isEqualTo(1);
        assertThat(definition)
                .as("column ORDER is the whole point: salon_id equality, then the service equality, "
                        + "then starts_at as the sort key. A different order cannot serve "
                        + "findIdsBySalonIdFiltered's ORDER BY starts_at DESC without a Sort node.")
                .contains("(salon_id, master_service_id, starts_at DESC)");
    }

    @Test
    @DisplayName("V167 — st_bookings_salon_service exists on bookings over exactly "
            + "(salon_id, master_service_id) with all three kinds (ndistinct, dependencies, mcv); "
            + "dropping any one of them silently degrades a different estimate")
    void should_createSalonServiceExtendedStatistics_when_v167Applied() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_statistic_ext e
                JOIN pg_class c ON c.oid = e.stxrelid
                WHERE c.relname = 'bookings' AND e.stxname = ?
                """,
                Integer.class, STATISTICS_NAME);

        String kinds = jdbcTemplate.queryForObject(
                "SELECT stxkind::text FROM pg_statistic_ext WHERE stxname = ?",
                String.class, STATISTICS_NAME);

        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT a.attname
                FROM pg_statistic_ext e
                JOIN pg_attribute a ON a.attrelid = e.stxrelid AND a.attnum = ANY (e.stxkeys)
                WHERE e.stxname = ?
                ORDER BY a.attname
                """,
                String.class, STATISTICS_NAME);

        assertThat(count)
                .as("V167's statistics object must exist and must be attached to bookings")
                .isEqualTo(1);
        assertThat(kinds)
                .as("d = ndistinct, f = dependencies, m = mcv. `dependencies` alone is not enough: "
                        + "it is consulted only for plain equality, and the IN(2) shape this "
                        + "migration exists to fix is decomposed into ORed equalities whose "
                        + "combined selectivity the MCV list is what bounds.")
                .contains("d")
                .contains("f")
                .contains("m");
        assertThat(columns)
                .as("exactly the correlated pair — a third column would change which estimates the "
                        + "object answers")
                .containsExactly("master_service_id", "salon_id");
    }
}
