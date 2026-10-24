package com.beautica.migration;

import com.beautica.TestPostgresImage;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 376 — V200 ({@code salons.booking_slug}) applied to a POPULATED database.
 *
 * <p>{@code AbstractIntegrationTest}'s container is empty when Flyway runs, so it cannot prove V200
 * applies over existing salons. Mirrors {@code V197PendingSuggestionCollapseMigrationTest}: a private
 * container is migrated to V199, two salons are seeded (the state production is in), then Flyway
 * migrates to head ONCE. The pre-existing rows must survive with {@code booking_slug NULL} (no
 * backfill — D4 lazy issue), and the CHECK / partial unique index must behave as V86's do for masters.
 */
@DisplayName("V200 migration — salons.booking_slug on a populated DB: CHECK, partial unique, NULLs allowed")
class SalonBookingSlugMigrationIT {

    private static final String FORMAT_CHECK = "ck_salons_booking_slug_format";
    private static final String UNIQUE_INDEX = "idx_salons_booking_slug";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");
    private static final UUID LEGACY_A = UUID.fromString("d0000000-0000-0000-0000-000000000001");
    private static final UUID LEGACY_B = UUID.fromString("d0000000-0000-0000-0000-000000000002");

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static UUID ownerId;

    @BeforeAll
    static void migrateTo199SeedSalonsThenMigrateToHead() {
        postgres = new PostgreSQLContainer<>(TestPostgresImage.IMAGE);
        postgres.start();
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("199")).load().migrate();

        ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, 'SALON_OWNER')",
                ownerId, "v200-" + ownerId + "@beautica.test", PASSWORD_HASH);
        insertSalon(LEGACY_A, "Legacy Alfa");
        insertSalon(LEGACY_B, "Legacy Beta");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void insertSalon(UUID id, String name) {
        UUID cityId = jdbc.queryForObject(
                "SELECT id FROM cities WHERE katotth_code = 'UA05020030010063857'", UUID.class);
        jdbc.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)", id, ownerId, name, cityId);
    }

    private static String slugOf(UUID salonId) {
        return jdbc.queryForObject("SELECT booking_slug FROM salons WHERE id = ?", String.class, salonId);
    }

    private static UUID freshSalon() {
        UUID id = UUID.randomUUID();
        insertSalon(id, "Fresh " + id);
        return id;
    }

    @Test
    @DisplayName("pre-existing salons survive V200 with booking_slug NULL (lazy issue, no backfill)")
    void should_leaveExistingSalonsWithNullSlug_when_migratedOverPopulatedDb() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '200' AND success", Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(slugOf(LEGACY_A)).isNull();
        assertThat(slugOf(LEGACY_B)).isNull();
    }

    @Test
    @DisplayName("CHECK rejects a slug with a space or uppercase ('Bad Slug')")
    void should_rejectSlug_when_formatInvalid() {
        UUID id = freshSalon();

        assertThatThrownBy(() -> jdbc.update("UPDATE salons SET booking_slug = 'Bad Slug' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(FORMAT_CHECK);
    }

    @Test
    @DisplayName("CHECK rejects a leading hyphen and a path separator")
    void should_rejectSlug_when_leadingHyphenOrSlash() {
        UUID id = freshSalon();

        assertThatThrownBy(() -> jdbc.update("UPDATE salons SET booking_slug = '-lab-ab12' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(FORMAT_CHECK);
        assertThatThrownBy(() -> jdbc.update("UPDATE salons SET booking_slug = 'a/../b' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(FORMAT_CHECK);
    }

    @Test
    @DisplayName("partial unique index rejects a duplicate salon slug")
    void should_rejectDuplicate_when_slugAlreadyTakenBySalon() {
        UUID first = freshSalon();
        UUID second = freshSalon();
        jdbc.update("UPDATE salons SET booking_slug = 'dup-salon-ab12' WHERE id = ?", first);

        assertThatThrownBy(() -> jdbc.update("UPDATE salons SET booking_slug = 'dup-salon-ab12' WHERE id = ?", second))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(UNIQUE_INDEX);
    }

    @Test
    @DisplayName("generator-shaped slug accepted; many NULL rows coexist")
    void should_acceptGeneratorShapeAndMultipleNulls_when_written() {
        UUID id = freshSalon();

        assertThatCode(() -> jdbc.update("UPDATE salons SET booking_slug = 'beauty-lab-kyiv-x7k2' WHERE id = ?", id))
                .doesNotThrowAnyException();
        Integer nulls = jdbc.queryForObject("SELECT count(*) FROM salons WHERE booking_slug IS NULL", Integer.class);
        assertThat(nulls).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("uniqueness is per table (D2): a salon slug may equal a master slug")
    void should_allowSalonSlugEqualToMasterSlug_when_namespacesDiffer() {
        UUID salonId = freshSalon();
        jdbc.update("INSERT INTO masters (id, user_id, salon_id, master_type, is_active, booking_slug, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'SALON_OWNER', true, 'shared-name-ab12', NOW(), NOW())",
                UUID.randomUUID(), ownerId, salonId);

        assertThatCode(() -> jdbc.update("UPDATE salons SET booking_slug = 'shared-name-ab12' WHERE id = ?", salonId))
                .doesNotThrowAnyException();
    }
}
