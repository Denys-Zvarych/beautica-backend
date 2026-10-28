package com.beautica.booking.retention;

import com.beautica.TestPostgresImage;
import com.beautica.booking.domain.CancelTokenHasher;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.beautica.booking.retention.GuestPiiFixtures.PHONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V206 over a database that already holds PLAINTEXT guest cancel tokens (links already sent by SMS).
 * A private container is migrated to V205, legacy rows are seeded with known tokens, then Flyway
 * migrates to head ONCE (pattern of {@code V199GuestPiiRetentionMigrationTest}).
 *
 * <p>The application resolves and consumes a token only by {@link CancelTokenHasher#hash}, so "the old
 * link still cancels" is proven by (a) the backfilled hash equalling the application's hash of the token
 * and (b) the application's exact consume statement matching the row by that hash.
 */
@DisplayName("V206 migration — plaintext cancel tokens become hashes; existing links keep working")
class V206CancelTokenHashMigrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
    private static final UUID SINGLE_TOKEN = UUID.randomUUID();
    private static final UUID VISIT_TOKEN = UUID.randomUUID();
    private static final UUID CONSUMED_TOKEN = UUID.randomUUID();

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static GuestPiiFixtures fx;
    private static GuestPiiFixtures.Provider provider;
    private static UUID singleBooking;
    private static UUID consumedBooking;
    private static UUID cancelledBooking;
    private static UUID visit;
    private static UUID appBooking;

    @BeforeAll
    static void migrateTo205SeedPlaintextTokensThenMigrateToHead() {
        postgres = new PostgreSQLContainer<>(TestPostgresImage.IMAGE);
        postgres.start();
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("205")).load().migrate();

        fx = new GuestPiiFixtures(jdbc);
        provider = fx.createIndependentMaster();
        UUID clientId = fx.createClient();
        singleBooking = fx.link(provider, "CONFIRMED", NOW.plusDays(3), PHONE, SINGLE_TOKEN);
        consumedBooking = fx.link(provider, "CONFIRMED", NOW.plusDays(5), PHONE, CONSUMED_TOKEN);
        cancelledBooking = fx.link(provider, "CANCELLED", NOW.minusDays(2), PHONE, null);
        visit = fx.appointment("LINK", "CONFIRMED", PHONE, VISIT_TOKEN, null);
        fx.visitItem(provider, visit, "LINK", "CONFIRMED", NOW.plusDays(4), PHONE);
        appBooking = fx.app(provider, "COMPLETED", NOW.minusDays(1), clientId);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("V206 applies cleanly over rows holding plaintext tokens")
    void should_recordV206Successful_when_plaintextTokensExistBeforeMigration() {
        Boolean success = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '206'", Boolean.class);

        assertThat(success).isTrue();
    }

    @Test
    @DisplayName("single booking plaintext token is backfilled to the application hash and cleared")
    void should_backfillApplicationHash_when_bookingHadPlaintextToken() {
        Map<String, Object> row = fx.booking(singleBooking);

        assertThat(row.get("cancel_token_hash")).isEqualTo(CancelTokenHasher.hash(SINGLE_TOKEN));
        assertThat(row.get("cancel_token")).as("plaintext cleared").isNull();
    }

    @Test
    @DisplayName("visit header plaintext token is backfilled to the application hash and cleared")
    void should_backfillApplicationHash_when_visitHeaderHadPlaintextToken() {
        Map<String, Object> row = fx.appointmentRow(visit);

        assertThat(row.get("cancel_token_hash")).isEqualTo(CancelTokenHasher.hash(VISIT_TOKEN));
        assertThat(row.get("cancel_token")).isNull();
    }

    @Test
    @DisplayName("tokenless rows (terminal LINK, APP) keep a NULL hash")
    void should_leaveHashNull_when_rowHadNoToken() {
        assertThat(fx.booking(cancelledBooking).get("cancel_token_hash")).isNull();
        assertThat(fx.booking(appBooking).get("cancel_token_hash")).isNull();
    }

    @Test
    @DisplayName("no bookings / appointments row keeps a plaintext token after V206")
    void should_keepNoPlaintextAtRest_when_migrated() {
        Integer bookings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE cancel_token IS NOT NULL", Integer.class);
        Integer visits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE cancel_token IS NOT NULL", Integer.class);

        assertThat(bookings).isZero();
        assertThat(visits).isZero();
    }

    @Test
    @DisplayName("a pre-V206 link is consumed once by its hash; the replay matches nothing")
    void should_consumeByHash_when_preExistingLinkIsUsed() {
        // BookingRepository#consumeCancelTokenHash, verbatim in SQL form (the application's one-time consume).
        String hash = CancelTokenHasher.hash(CONSUMED_TOKEN);

        int first = jdbc.update("UPDATE bookings SET status = 'CANCELLED', cancel_token_hash = NULL "
                + "WHERE cancel_token_hash = ? AND status = 'CONFIRMED'", hash);
        int replay = jdbc.update("UPDATE bookings SET status = 'CANCELLED', cancel_token_hash = NULL "
                + "WHERE cancel_token_hash = ? AND status = 'CONFIRMED'", hash);

        assertThat(first).as("the pre-V206 link resolves by its hash").isEqualTo(1);
        assertThat(replay).as("one-time: the consumed hash is NULL").isZero();
        assertThat(fx.booking(consumedBooking).get("status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("legacy writer INSERT of a plaintext token is hashed and cleared by the trigger")
    void should_hashAndClearPlaintext_when_legacyWriterInsertsToken() {
        UUID token = UUID.randomUUID();

        UUID inserted = fx.link(provider, "CONFIRMED", NOW.plusDays(9), PHONE, token);

        Map<String, Object> row = fx.booking(inserted);
        assertThat(row.get("cancel_token")).isNull();
        assertThat(row.get("cancel_token_hash")).isEqualTo(CancelTokenHasher.hash(token));
    }

    @Test
    @DisplayName("legacy anonymiser nulling the plaintext token also clears the hash")
    void should_clearHash_when_legacyAnonymiserNullsPlaintextToken() {
        UUID token = UUID.randomUUID();
        UUID row = fx.link(provider, "CONFIRMED", NOW.minusMonths(13), PHONE, token);

        jdbc.update("""
                UPDATE bookings SET guest_name = ?, guest_surname = NULL, guest_phone = NULL,
                       client_comment = NULL, client_cancellation_note = NULL, cancel_token = NULL,
                       guest_pii_anonymised_at = NOW()
                 WHERE id = ?
                """, GuestPii.ANONYMISED_NAME, row);

        assertThat(fx.booking(row).get("cancel_token_hash"))
                .as("the previous image's anonymiser still satisfies the anonymised CHECK arm")
                .isNull();
    }

    @Test
    @DisplayName("a stored hash that is not 64 lower-hex chars is rejected by the format CHECK")
    void should_rejectHash_when_notLowerHex64() {
        String upper = CancelTokenHasher.hash(UUID.randomUUID()).toUpperCase();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE bookings SET cancel_token_hash = ? WHERE id = ?", upper, singleBooking))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_cancel_token_hash_format");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE appointments SET cancel_token_hash = ? WHERE id = ?", "abc", visit))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_appointments_cancel_token_hash_format");
        assertThat(fx.booking(singleBooking).get("cancel_token_hash"))
                .as("the live link is untouched by the rejected write")
                .isEqualTo(CancelTokenHasher.hash(SINGLE_TOKEN));
    }

    @Test
    @DisplayName("a second row carrying an already-live token is rejected by the partial UNIQUE hash index")
    void should_rejectDuplicate_when_secondRowReusesLiveToken() {
        assertThatThrownBy(() -> fx.link(provider, "CONFIRMED", NOW.plusDays(11), PHONE, SINGLE_TOKEN))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_bookings_cancel_token_hash");
    }

    @Test
    @DisplayName("partial UNIQUE indexes exist on both hash columns")
    void should_createPartialUniqueHashIndexes_when_migrated() {
        List<String> defs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname IN "
                        + "('ux_bookings_cancel_token_hash', 'ux_appointments_cancel_token_hash')",
                String.class);

        assertThat(defs).hasSize(2).allSatisfy(def -> assertThat(def)
                .contains("UNIQUE")
                .contains("cancel_token_hash IS NOT NULL"));
    }
}
