package com.beautica.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for the corrective-data half of
 * {@code V170__widen_cities_to_full_settlement_taxonomy.sql} (Phase 325) — the two
 * {@code RAISE EXCEPTION} guards and the 17-city purge they protect.
 *
 * <p><b>Why these paths were invisible before.</b> {@link com.beautica.AbstractIntegrationTest}'s
 * singleton container boots fully migrated on an EMPTY schema, so V170's guards always see a count
 * of zero and the purge always deletes 17 unreferenced rows. Every branch that matters on a real
 * database — a salon standing on Мелітополь, a user whose district hangs off a doomed city — is
 * never entered there. {@code LocalityTaxonomySeedMigrationTest} can therefore only assert the
 * post-condition of the no-op case. This class enters the branches.
 *
 * <p><b>Harness.</b> A private container migrated ONCE to target 169 — the schema head V170 was
 * written against, where V53's 17 mistakenly-seeded occupied cities are still present. Each test
 * then seeds its fixture and applies V170's raw SQL body on a connection with autocommit OFF, and
 * the transaction is ALWAYS rolled back. PostgreSQL DDL is transactional, so the {@code ALTER TABLE
 * cities ADD COLUMN} half rolls back with everything else and every test sees the identical
 * pre-V170 state — no ordering coupling, no {@code @AfterEach} cleanup chain.
 *
 * <p>The whole script goes out as ONE {@link Statement#execute(String)}, a single PostgreSQL
 * simple-query message, mirroring {@code V164SalonOwnedBackfillMigrationTest}: that is how Flyway
 * itself runs a multi-statement script, and it is what makes the {@code DO $$ … $$} guards abort
 * the entire migration rather than leaving half of it applied.
 */
@DisplayName("V170 migration — occupied-city purge guards and the purge itself")
class V170OccupiedCityPurgeGuardTest {

    /** Мелітополь — one of the 17 occupied cities V53 seeded and V170 deletes. */
    private static final String MELITOPOL_CODE = "UA23080070010092407";

    /** Київ — a serviced city, used as the non-blocking control. */
    private static final String KYIV_CODE = "UA80000000000093317";

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static String v170Sql;

    @BeforeAll
    static void startContainerMigrateTo169AndLoadSql() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // Capped at 169 deliberately: V170 is the subject, so it must NOT have run yet, and V171
        // must not have run either — it would upsert 25 697 rows on top of a `cities` table whose
        // settlement_type column V170 has not added.
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("169"))
                .load()
                .migrate();

        try (InputStream in = new ClassPathResource(
                "db/migration/V170__widen_cities_to_full_settlement_taxonomy.sql").getInputStream()) {
            v170Sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    // ------------------------------------------------------------------ harness

    /**
     * Runs {@code body} against a private, always-rolled-back transaction. The callback gets a
     * {@link JdbcTemplate} bound to that ONE connection, so its seeds and V170's effects share the
     * transaction and vanish together.
     */
    private void inRolledBackTx(Consumer<TxFixture> body) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            SingleConnectionDataSource bound =
                    new SingleConnectionDataSource(connection, true);
            try {
                body.accept(new TxFixture(new JdbcTemplate(bound), connection));
            } finally {
                connection.rollback();
            }
        } catch (Exception e) {
            throw new IllegalStateException("transactional fixture failed", e);
        }
    }

    /** The one transaction under test: a template for seeding, and V170 applied on demand. */
    private record TxFixture(JdbcTemplate jdbc, Connection connection) {

        /**
         * Applies V170's whole body as one simple query. A savepoint is taken first so that an
         * expected abort leaves the surrounding transaction USABLE — without it PostgreSQL marks
         * the transaction failed and every later assertion in the same test dies with "current
         * transaction is aborted" instead of reporting what it found.
         */
        void applyV170() {
            Savepoint savepoint;
            try {
                savepoint = connection.setSavepoint("before_v170");
            } catch (SQLException e) {
                throw new IllegalStateException("cannot take savepoint", e);
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(v170Sql);
            } catch (Exception e) {
                try {
                    connection.rollback(savepoint);
                } catch (SQLException ignored) {
                    // the outer rollback still cleans up
                }
                throw new MigrationFailed(e);
            }
        }

        UUID cityIdByCode(String katotthCode) {
            return jdbc.queryForObject(
                    "SELECT id FROM cities WHERE katotth_code = ?", UUID.class, katotthCode);
        }

        UUID seedOwner() {
            UUID userId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                            + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                    userId, "v170-owner-" + userId + "@beautica.test", BCRYPT_FIXTURE);
            return userId;
        }

        UUID seedClientInCity(UUID cityId, UUID districtId) {
            UUID userId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, "
                            + "city_id, district_id) VALUES (?, ?, ?, 'CLIENT', true, true, ?, ?)",
                    userId, "v170-client-" + userId + "@beautica.test", BCRYPT_FIXTURE,
                    cityId, districtId);
            return userId;
        }

        UUID seedSalon(UUID ownerId, UUID cityId, UUID districtId) {
            UUID salonId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, "
                            + "city_id, district_id) VALUES (?, ?, ?, true, NOW(), NOW(), ?, ?)",
                    salonId, ownerId, "V170 fixture salon", cityId, districtId);
            return salonId;
        }

        UUID seedDistrictUnder(UUID cityId) {
            UUID districtId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO city_districts (id, city_id, katotth_code, name_uk, name_en) "
                            + "VALUES (?, ?, ?, 'V170 fixture district', 'V170 fixture district')",
                    // V102's chk_city_districts_katotth_code_format pins the business key to
                    // ^UA[0-9]{17}$ — a fixture code must be a well-formed KATOTTH code, not a
                    // readable marker. This one sits under Мелітополь's own prefix.
                    districtId, cityId,
                    "UA230800700" + String.format("%08d", FIXTURE_DISTRICT_SEQ.incrementAndGet()));
            return districtId;
        }
    }

    /**
     * Real BCrypt output, not a {@code '$2a$10$hashedpassword'} placeholder — the users table's
     * password_hash is never read by V170, but a fixture that ships a fake hash teaches the next
     * copy-paste the wrong habit.
     */
    private static final String BCRYPT_FIXTURE =
            "$2a$04$eFGjDqM/Wn/DrCQsqpSqCOEHb1Hl9uDAmBG7lQd.BCHLqRaS1ZqMK";

    /** Keeps fixture district codes unique and inside the VARCHAR(20) business key. */
    private static final AtomicInteger FIXTURE_DISTRICT_SEQ = new AtomicInteger();

    /** Marker so a V170 abort is distinguishable from a fixture error. */
    private static final class MigrationFailed extends RuntimeException {
        MigrationFailed(Throwable cause) {
            super(cause);
        }
    }

    // ------------------------------------------------------- the abort guards

    @Nested
    @DisplayName("abort guards")
    class AbortGuards {

        @Test
        @DisplayName("aborts with a named, actionable error when a salon stands on one of the 17 occupied cities")
        void should_abortWithANamedError_when_aSalonReferencesAnOccupiedCity() {
            inRolledBackTx(tx -> {
                UUID melitopol = tx.cityIdByCode(MELITOPOL_CODE);
                tx.seedSalon(tx.seedOwner(), melitopol, null);

                assertThatThrownBy(tx::applyV170)
                        .as("salons.city_id is NOT NULL, so the row cannot be repointed "
                                + "automatically — V170 must stop and hand the decision to a human")
                        .isInstanceOf(MigrationFailed.class)
                        .rootCause()
                        .hasMessageContaining("V170 blocked")
                        .hasMessageContaining("salon row(s) reference a city")
                        .hasMessageContaining("Phase 324 lists as currently occupied");
            });
        }

        @Test
        @DisplayName("does NOT abort when the salon stands on a serviced city — the guard discriminates")
        void should_notAbort_when_theSalonStandsOnAServicedCity() {
            // The negative half of the guard, and the one that catches the likelier regression: a
            // guard written as "any salon at all blocks" would pass every abort test above and
            // then refuse to migrate any production database that has ever had a salon.
            inRolledBackTx(tx -> {
                UUID kyiv = tx.cityIdByCode(KYIV_CODE);
                UUID salonId = tx.seedSalon(tx.seedOwner(), kyiv, null);

                tx.applyV170();

                UUID cityId = tx.jdbc().queryForObject(
                        "SELECT city_id FROM salons WHERE id = ?", UUID.class, salonId);

                assertThat(cityId)
                        .as("a salon in Київ must neither block V170 nor be repointed by it")
                        .isEqualTo(kyiv);
            });
        }

        @Test
        @DisplayName("aborts through the district guard, not a raw FK error, when only salons.district_id is occupied")
        void should_abortThroughTheDistrictGuard_when_onlyTheSalonDistrictIsOccupied() {
            // V170's header calls this branch unreachable on today's data — a salon's district is
            // a child of its city, so the city guard fires first. "Unreachable today" is an
            // invariant of the DATA, not of the code, so the branch is entered here deliberately:
            // salon in Київ (free) but pointing at a district of Мелітополь. Without the second
            // guard this aborts on `fk_salons_district_id` with a bare Postgres message that names
            // neither the migration nor the remedy.
            inRolledBackTx(tx -> {
                UUID melitopol = tx.cityIdByCode(MELITOPOL_CODE);
                UUID kyiv = tx.cityIdByCode(KYIV_CODE);
                UUID occupiedDistrict = tx.seedDistrictUnder(melitopol);
                tx.seedSalon(tx.seedOwner(), kyiv, occupiedDistrict);

                assertThatThrownBy(tx::applyV170)
                        .isInstanceOf(MigrationFailed.class)
                        .rootCause()
                        .hasMessageContaining("V170 blocked")
                        .hasMessageContaining("urban district of a city")
                        .as("the raw FK message would name neither the migration nor the remedy")
                        .hasMessageNotContaining("violates foreign key constraint");
            });
        }
    }

    // -------------------------------------------------------------- the purge

    @Nested
    @DisplayName("the 17-city purge")
    class Purge {

        @Test
        @DisplayName("deletes all 17 occupied cities when nothing blocks")
        void should_deleteAllSeventeenOccupiedCities_when_noSalonReferencesThem() {
            inRolledBackTx(tx -> {
                Integer before = tx.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM cities WHERE katotth_code = ANY (?)",
                        Integer.class,
                        new Object[] {V53_SEEDED_OCCUPIED_CITY_CODES});

                tx.applyV170();

                Integer after = tx.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM cities WHERE katotth_code = ANY (?)",
                        Integer.class,
                        new Object[] {V53_SEEDED_OCCUPIED_CITY_CODES});

                assertThat(before)
                        .as("V53 really did seed all 17 — if this is not 17 the test below proves "
                                + "nothing, because deleting nothing also leaves nothing")
                        .isEqualTo(17);
                assertThat(after).as("none of the 17 may survive V170").isZero();
            });
        }

        @Test
        @DisplayName("clears a user's city_id AND district_id together when their city is purged")
        void should_clearBothUserLocalityColumns_when_theirCityIsPurged() {
            // The asymmetric case V170's comment calls out: a user must never keep a district
            // whose city is gone. The district clear is scoped THROUGH city_districts, so a
            // repointed-city-only fix would leave a dangling district_id and a UI that renders a
            // district under no city.
            inRolledBackTx(tx -> {
                UUID melitopol = tx.cityIdByCode(MELITOPOL_CODE);
                UUID district = tx.seedDistrictUnder(melitopol);
                UUID clientId = tx.seedClientInCity(melitopol, district);

                tx.applyV170();

                var row = tx.jdbc().queryForMap(
                        "SELECT city_id, district_id FROM users WHERE id = ?", clientId);
                Integer survivingDistrict = tx.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM city_districts WHERE id = ?", Integer.class, district);

                assertThat(row.get("city_id")).as("purged city must be released").isNull();
                assertThat(row.get("district_id"))
                        .as("a district whose city is gone must be released in the same breath")
                        .isNull();
                assertThat(survivingDistrict)
                        .as("the district row itself is deleted with its parent city")
                        .isZero();
            });
        }

        @Test
        @DisplayName("leaves a user in a serviced city untouched — the purge is scoped, not a blanket clear")
        void should_leaveAServicedCityReferenceIntact_when_thePurgeRuns() {
            // Guards against the regression where the UPDATE loses its WHERE clause: a blanket
            // `SET city_id = NULL` would pass every "no dangling reference" assertion in the
            // sibling test class while silently wiping every user's address.
            inRolledBackTx(tx -> {
                UUID kyiv = tx.cityIdByCode(KYIV_CODE);
                UUID clientId = tx.seedClientInCity(kyiv, null);

                tx.applyV170();

                UUID cityId = tx.jdbc().queryForObject(
                        "SELECT city_id FROM users WHERE id = ?", UUID.class, clientId);

                assertThat(cityId)
                        .as("Київ is serviced — this user's address must survive V170 untouched")
                        .isEqualTo(kyiv);
            });
        }

        @Test
        @DisplayName("drops its staging table and adds both new columns on the success path")
        void should_completeTheSchemaChange_when_thePurgeSucceeds() {
            inRolledBackTx(tx -> {
                tx.applyV170();

                Integer staging = tx.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_name = 'v170_occupied_city_ids'", Integer.class);
                Integer columns = tx.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns "
                                + "WHERE table_name = 'cities' "
                                + "  AND column_name IN ('settlement_type','is_major')",
                        Integer.class);
                String defaultExpr = tx.jdbc().queryForObject(
                        "SELECT column_default FROM information_schema.columns "
                                + "WHERE table_name = 'cities' AND column_name = 'settlement_type'",
                        String.class);

                assertThat(staging).as("the staging table must not outlive the migration").isZero();
                assertThat(columns).as("settlement_type and is_major must both exist").isEqualTo(2);
                assertThat(defaultExpr)
                        .as("the 'CITY' default is a backfill device and is dropped immediately — "
                                + "leaving it would let V171 and future inserts omit the type")
                        .isNull();
            });
        }
    }

    /** The 17 codes V170 purges, mirrored from the migration's own literal. */
    private static final String[] V53_SEEDED_OCCUPIED_CITY_CODES = {
            "UA23020050010019935", "UA23020130010076068", "UA23040030010016724",
            "UA23040090010050034", "UA23040110010044100", "UA23040130010014334",
            "UA23080070010092407", "UA23100150010091297", "UA23100190010032690",
            "UA23100270010029314", "UA65040010010040633", "UA65060110010021041",
            "UA65060170010075325", "UA65060250010044738", "UA65080030010035864",
            "UA65080150010023642", "UA65100110010019482"
    };
}
