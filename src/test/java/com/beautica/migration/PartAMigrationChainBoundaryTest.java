package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10.9 — Step 1 ({@code ddl-auto=validate} posture) and Step 7
 * (throwaway-cleanup boundary) contract for the permanent Part A migration
 * chain.
 *
 * <p>Neither concern is owned by the two existing migration tests
 * ({@code LocalityTaxonomySeedMigrationTest} pins V53's seeded <em>content</em>;
 * {@code LocalityAddressColumnsMigrationTest} pins V54's <em>catalog shape</em>).
 * This class governs two cross-cutting properties of the chain as a whole, so a
 * dedicated class is the right home (one responsibility: chain governance):
 *
 * <ul>
 *   <li><b>Step 1 — {@code ddl-auto=validate} startup contract.</b> Every
 *       {@link AbstractIntegrationTest} subclass already boots Hibernate against
 *       the live V54 schema, but no test <em>asserts</em> the validate posture.
 *       A silent regression to {@code none}/{@code update} would stop catching
 *       entity↔schema drift (a backlog-class bug). This pins the effective
 *       {@code hibernate.hbm2ddl.auto} at {@code validate} <em>and</em> proves
 *       the context booted with the real V52–V54 schema (the
 *       {@link EntityManagerFactory} is non-null and open — Hibernate's
 *       schema-validation pass succeeded against the migrated tables).</li>
 *   <li><b>Step 7 — no permanent backfill / no environment-specific data
 *       fix.</b> The Part A design (V54 header) is explicit: the permanent
 *       migration chain ships ZERO data DML and never backfills legacy
 *       free-text {@code users.city/region} / {@code salons.city/region/address}
 *       rows — dirty disposable dev rows are cleaned by hand, never by a
 *       Flyway migration that would be dead no-op code on every clean
 *       environment. This statically scans the source of every Part A
 *       migration ({@code V52}/{@code V53}/{@code V54} + any later {@code V*}
 *       that is Part A) and fails if it contains an {@code UPDATE}/{@code DELETE}
 *       against {@code users}/{@code salons}, or any write to a legacy
 *       free-text locality column. The seed's {@code INSERT}s into the three
 *       taxonomy tables are the only DML allowed.</li>
 * </ul>
 *
 * <p>Read-only and order-independent;
 * {@link AbstractIntegrationTest#cleanDb()} touches neither catalog metadata
 * nor the classpath, so no fixture or cleanup is required.
 */
@DisplayName("Phase 10.9 — Part A migration-chain boundary (ddl-validate + no-backfill)")
class PartAMigrationChainBoundaryTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // The permanent Part A migration chain. V52/V53/V54 are shipped and
    // immutable; any later Part A migration (e.g. a future "liberated
    // territory" one-row add) MUST be appended here so the no-backfill
    // boundary keeps governing the whole chain (Phase 10.9 Step 7).
    private static final List<String> PART_A_MIGRATIONS = List.of(
            "db/migration/V52__create_locality_taxonomy.sql",
            "db/migration/V53__seed_locality_taxonomy.sql",
            "db/migration/V54__add_locality_fk_and_address.sql");

    // Legacy free-text locality columns that a permanent migration must NEVER
    // write (kept NULLABLE forever per V54; cleanup is dev-only, by hand).
    private static final List<String> LEGACY_FREE_TEXT_COLUMNS = List.of(
            "users.city", "users.region",
            "salons.city", "salons.region", "salons.address");

    // ---------------------------------------------------------------------
    // Step 1 — ddl-auto=validate startup posture against the V54 schema
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Step 1 — ddl-auto=validate startup contract")
    class DdlValidatePosture {

        @Test
        @DisplayName("effective Hibernate hbm2ddl.auto is 'validate' (not none/update/create)")
        void should_runHibernateInValidateMode_when_contextBooted() {
            Object hbm2ddl = entityManagerFactory.getProperties()
                    .get("hibernate.hbm2ddl.auto");

            assertThat(hbm2ddl)
                    .as("Part A relies on ddl-auto=validate to catch entity↔V54 "
                            + "schema drift; a regression to none/update silently "
                            + "stops that guard")
                    .isEqualTo("validate");
        }

        @Test
        @DisplayName("context booted with the real V52–V54 schema — Hibernate schema validation passed")
        void should_haveOpenEntityManagerFactory_when_v54SchemaValidated() {
            // If any User/Salon locality field diverged from the V54 columns,
            // ddl-auto=validate would have failed context startup and this test
            // (and every IT) would never run. Asserting the EMF is non-null and
            // open makes that implicit success an explicit, named contract.
            assertThat(entityManagerFactory)
                    .as("EntityManagerFactory must be present — proves the "
                            + "validate pass succeeded against the migrated schema")
                    .isNotNull();
            assertThat(entityManagerFactory.isOpen())
                    .as("EMF open == Hibernate validated entities against V52–V54")
                    .isTrue();
        }
    }

    // ---------------------------------------------------------------------
    // Step 7 — throwaway-cleanup boundary: no permanent backfill
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Step 7 — no permanent backfill / no environment-specific data fix")
    class NoPermanentBackfill {

        @Test
        @DisplayName("no Part A migration issues UPDATE/DELETE against users or salons")
        void should_containNoMutationOfLegacyRows_when_partAChainScanned() throws IOException {
            for (String migration : PART_A_MIGRATIONS) {
                String sql = readMigration(migration);
                String executable = stripSqlComments(sql).toLowerCase(Locale.ROOT);

                assertThat(executable)
                        .as("%s must not UPDATE legacy users rows (no permanent backfill)",
                                migration)
                        .doesNotContainPattern("update\\s+users\\b");
                assertThat(executable)
                        .as("%s must not UPDATE legacy salons rows (no permanent backfill)",
                                migration)
                        .doesNotContainPattern("update\\s+salons\\b");
                assertThat(executable)
                        .as("%s must not DELETE users/salons rows (no environment-specific data fix)",
                                migration)
                        .doesNotContainPattern("delete\\s+from\\s+users\\b")
                        .doesNotContainPattern("delete\\s+from\\s+salons\\b");
            }
        }

        @Test
        @DisplayName("no Part A migration writes a legacy free-text locality column")
        void should_neverWriteLegacyFreeTextColumn_when_partAChainScanned() throws IOException {
            // The free-text columns are kept NULLABLE forever (V54). A
            // permanent migration assigning them (an aliased fuzzy-match
            // backfill — the explicitly-deleted old idea) would be dead no-op
            // code on every clean environment. The token "<col> =" appearing
            // in executable SQL of a Part A migration is the smell we forbid.
            for (String migration : PART_A_MIGRATIONS) {
                String executable = stripSqlComments(readMigration(migration))
                        .toLowerCase(Locale.ROOT);

                for (String qualified : LEGACY_FREE_TEXT_COLUMNS) {
                    String column = qualified.substring(qualified.indexOf('.') + 1);
                    // A backfill assignment looks like `SET city = …` or
                    // `users.city = …`. Match `<column> =` but not `<column>_id`
                    // (city_id IS a permitted FK column) — the boundary is the
                    // free-text column, never the FK.
                    Pattern assignment = Pattern.compile(
                            "\\b" + Pattern.quote(column) + "\\s*=");
                    assertThat(assignment.matcher(executable).find())
                            .as("%s must never assign the legacy free-text column "
                                    + "%s — it stays NULLABLE forever; cleanup is "
                                    + "dev-only and out of the permanent chain",
                                    migration, qualified)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("Part A DML is confined to taxonomy INSERTs — the seed is the only data write")
        void should_onlyInsertIntoTaxonomyTables_when_v53Scanned() throws IOException {
            // V52 (DDL) and V54 (DDL) ship zero DML; V53 is INSERT-only into
            // the three reference tables. Any INSERT INTO users/salons in the
            // seed would itself be an environment-specific data fix.
            String seed = stripSqlComments(readMigration(
                    "db/migration/V53__seed_locality_taxonomy.sql"))
                    .toLowerCase(Locale.ROOT);

            assertThat(seed)
                    .as("V53 seeds reference data only — never inserts a user/salon")
                    .doesNotContainPattern("insert\\s+into\\s+users\\b")
                    .doesNotContainPattern("insert\\s+into\\s+salons\\b");
            assertThat(seed)
                    .as("V53 must populate the taxonomy (sanity: it does insert reference rows)")
                    .containsPattern("insert\\s+into\\s+oblasts\\b");
        }

        @Test
        @DisplayName("V52 and V54 ship ZERO data DML (pure schema migrations)")
        void should_shipNoDml_when_schemaMigrationsScanned() throws IOException {
            for (String schemaMigration : List.of(
                    "db/migration/V52__create_locality_taxonomy.sql",
                    "db/migration/V54__add_locality_fk_and_address.sql")) {
                String executable = stripSqlComments(readMigration(schemaMigration))
                        .toLowerCase(Locale.ROOT);

                assertThat(executable)
                        .as("%s is a schema migration — no INSERT/UPDATE/DELETE", schemaMigration)
                        .doesNotContainPattern("\\binsert\\s+into\\b")
                        .doesNotContainPattern("\\bupdate\\s+\\w")
                        .doesNotContainPattern("\\bdelete\\s+from\\b");
            }
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private String readMigration(String classpathLocation) throws IOException {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Strips {@code -- line} and {@code /* block *}{@code /} comments so the
     * no-backfill scan only inspects executable SQL. The Part A migrations
     * deliberately <em>mention</em> "UPDATE users" / "salons.city" in their
     * rationale headers ("no permanent backfill"); matching raw text would
     * false-positive on exactly the comment that documents the rule.
     */
    private static String stripSqlComments(String sql) {
        String noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder out = new StringBuilder(noBlock.length());
        for (String line : noBlock.split("\n", -1)) {
            int dash = line.indexOf("--");
            out.append(dash >= 0 ? line.substring(0, dash) : line).append('\n');
        }
        return out.toString();
    }

    /**
     * Defensive: surfaces the resolved property map once if the validate
     * assertion ever needs debugging (kept private, unused in green runs).
     */
    @SuppressWarnings("unused")
    private Map<String, Object> emfProperties() {
        return entityManagerFactory.getProperties();
    }
}
