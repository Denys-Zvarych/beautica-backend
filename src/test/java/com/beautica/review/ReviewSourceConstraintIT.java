package com.beautica.review;

import com.beautica.TestPostgresImage;
import com.beautica.review.support.GuestReviewFixtures;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 399 D1 — V212 {@code reviews.source} / {@code guest_display_name} over a database holding pre-V212 reviews.
 * A private container is migrated to V211, seeded with an app review and a phase-300 detached one (client_id NULL),
 * then migrated to the latest version once (pattern of {@code V211ClientBlocksIdentityMigrationIT}). Every legal
 * author shape is accepted and every illegal one is rejected by the database itself.
 */
@DisplayName("V212 reviews.source — backfill + chk_reviews_source_author")
class ReviewSourceConstraintIT {

    private static final String MIGRATIONS = "classpath:db/migration";
    private static final String GUEST_PHONE = "+380501234567";

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static GuestReviewFixtures fixtures;
    private static UUID masterId;
    private static UUID masterServiceId;
    private static UUID legacyAppReview;
    private static UUID legacyDetachedReview;

    @BeforeAll
    static void migrateTo211SeedReviewsThenMigrateToLatest() {
        postgres = new PostgreSQLContainer<>(TestPostgresImage.IMAGE);
        postgres.start();
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        fixtures = new GuestReviewFixtures(jdbc);
        flyway(dataSource).target(MigrationVersion.fromVersion("211")).load().migrate();

        masterId = fixtures.independentMaster();
        masterServiceId = fixtures.masterService(masterId);
        UUID client = fixtures.user("CLIENT", "Олена", "Кравець");
        legacyAppReview = fixtures.legacyReview(fixtures.appBooking(client, masterId, masterServiceId), client, masterId);
        UUID leaver = fixtures.user("CLIENT", "Оксана", "Бондар");
        legacyDetachedReview = fixtures.legacyReview(
                fixtures.appBooking(leaver, masterId, masterServiceId), leaver, masterId);
        jdbc.update("UPDATE reviews SET client_id = NULL WHERE id = ?", legacyDetachedReview);

        flyway(dataSource).load().migrate();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    // ── backfill ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("V212, V213 and V214 apply cleanly over existing reviews")
    void should_recordV212ToV214Successful_when_reviewsExist() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('212', '213', '214') AND success",
                Integer.class);

        assertThat(applied).isEqualTo(3);
    }

    @Test
    @DisplayName("V214 validated every V212 NOT VALID CHECK (pg_constraint.convalidated)")
    void should_markV212ChecksValidated_when_migratedToLatest() {
        Map<String, Boolean> validated = new HashMap<>();
        jdbc.queryForList("SELECT conname, convalidated FROM pg_constraint WHERE conrelid = 'reviews'::regclass "
                        + "AND conname IN ('chk_reviews_source', 'chk_reviews_source_author', "
                        + "'chk_reviews_guest_display_name_no_digits')")
                .forEach(row -> validated.put((String) row.get("conname"), (Boolean) row.get("convalidated")));

        assertThat(validated).containsOnly(
                Map.entry("chk_reviews_source", true),
                Map.entry("chk_reviews_source_author", true),
                Map.entry("chk_reviews_guest_display_name_no_digits", true));
    }

    @Test
    @DisplayName("existing reviews — attached and phase-300 detached — become source APP with no guest name")
    void should_backfillSourceApp_when_reviewExistedBeforeV212() {
        Map<String, Object> attached = sourceAndName(legacyAppReview);
        Map<String, Object> detached = sourceAndName(legacyDetachedReview);

        assertThat(attached.get("source")).isEqualTo("APP");
        assertThat(attached.get("guest_display_name")).isNull();
        assertThat(detached.get("source")).isEqualTo("APP");
        assertThat(detached.get("guest_display_name")).isNull();
    }

    @Test
    @DisplayName("a writer that omits the new columns (previous app version) still inserts an APP review")
    void should_defaultToApp_when_insertOmitsSource() {
        UUID client = fixtures.user("CLIENT", "Ігор", "Мельник");

        UUID id = fixtures.legacyReview(fixtures.appBooking(client, masterId, masterServiceId), client, masterId);

        assertThat(sourceAndName(id).get("source")).isEqualTo("APP");
    }

    // ── legal shapes ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GUEST_LINK with no account and a display name is accepted")
    void should_acceptGuestReview_when_noClientAndDisplayName() {
        UUID booking = fixtures.guestBooking(masterId, masterServiceId, GUEST_PHONE);

        UUID id = fixtures.review(booking, null, masterId, "GUEST_LINK", "Марія Ш.");

        assertThat(sourceAndName(id)).containsEntry("source", "GUEST_LINK")
                .containsEntry("guest_display_name", "Марія Ш.");
    }

    @Test
    @DisplayName("an APP review whose author later self-deletes (FK SET NULL outcome) stays valid")
    void should_acceptDetachedAppReview_when_clientNulledAfterInsert() {
        UUID client = fixtures.user("CLIENT", "Тарас", "Шевчук");
        UUID id = fixtures.review(fixtures.appBooking(client, masterId, masterServiceId), client, masterId, "APP", null);

        assertThatCode(() -> jdbc.update("UPDATE reviews SET client_id = NULL WHERE id = ?", id))
                .doesNotThrowAnyException();
    }

    // ── illegal shapes ───────────────────────────────────────────────────────

    @Test
    @DisplayName("GUEST_LINK with an account is rejected")
    void should_rejectGuestReview_when_clientSet() {
        UUID client = fixtures.user("CLIENT", "Анна", "Лисенко");
        UUID booking = fixtures.appBooking(client, masterId, masterServiceId);

        assertThatThrownBy(() -> fixtures.review(booking, client, masterId, "GUEST_LINK", "Анна Л."))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_reviews_source_author");
    }

    @Test
    @DisplayName("GUEST_LINK without a display name is rejected")
    void should_rejectGuestReview_when_displayNameNull() {
        UUID booking = fixtures.guestBooking(masterId, masterServiceId, GUEST_PHONE);

        assertThatThrownBy(() -> fixtures.review(booking, null, masterId, "GUEST_LINK", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_reviews_source_author");
    }

    @Test
    @DisplayName("GUEST_LINK with a blank display name is rejected")
    void should_rejectGuestReview_when_displayNameBlank() {
        UUID booking = fixtures.guestBooking(masterId, masterServiceId, GUEST_PHONE);

        assertThatThrownBy(() -> fixtures.review(booking, null, masterId, "GUEST_LINK", "   "))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_reviews_source_author");
    }

    @Test
    @DisplayName("GUEST_LINK display name holding a phone fragment (3+ digits) is rejected")
    void should_rejectGuestReview_when_displayNameHoldsDigits() {
        UUID booking = fixtures.guestBooking(masterId, masterServiceId, GUEST_PHONE);

        assertThatThrownBy(() -> fixtures.review(booking, null, masterId, "GUEST_LINK", "Марія 050"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_reviews_guest_display_name_no_digits");
    }

    @ParameterizedTest(name = "[{index}] name=«{0}»")
    @ValueSource(strings = {"Марія ٠٥٠", "Марія ۰۵۰", "Марія ０５０", "Марія 0٥5"})
    @DisplayName("GUEST_LINK display name holding Arabic-Indic / fullwidth / mixed-script digits is rejected")
    void should_rejectGuestReview_when_displayNameHoldsNonAsciiDigits(String displayName) {
        UUID booking = fixtures.guestBooking(masterId, masterServiceId, GUEST_PHONE);

        assertThatThrownBy(() -> fixtures.review(booking, null, masterId, "GUEST_LINK", displayName))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_reviews_guest_display_name_no_digits");
    }

    @Test
    @DisplayName("APP carrying a guest display name is rejected")
    void should_rejectAppReview_when_guestDisplayNameSet() {
        UUID client = fixtures.user("CLIENT", "Петро", "Гнатюк");
        UUID booking = fixtures.appBooking(client, masterId, masterServiceId);

        assertThatThrownBy(() -> fixtures.review(booking, client, masterId, "APP", "Петро Г."))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_reviews_source_author");
    }

    @Test
    @DisplayName("an unknown source is rejected")
    void should_rejectReview_when_sourceUnknown() {
        UUID client = fixtures.user("CLIENT", "Віра", "Остапенко");
        UUID booking = fixtures.appBooking(client, masterId, masterServiceId);

        assertThatThrownBy(() -> fixtures.review(booking, client, masterId, "WEB", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_reviews_source");
    }

    @Test
    @DisplayName("a NULL source is rejected (NOT NULL keeps the CHECK from passing on NULL)")
    void should_rejectReview_when_sourceNull() {
        UUID client = fixtures.user("CLIENT", "Леся", "Українка");
        UUID booking = fixtures.appBooking(client, masterId, masterServiceId);

        assertThatThrownBy(() -> fixtures.review(booking, client, masterId, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("source");
    }

    @Test
    @DisplayName("a guest review cannot later be attached to an account")
    void should_rejectUpdate_when_guestReviewGainsClient() {
        UUID id = fixtures.review(fixtures.guestBooking(masterId, masterServiceId, GUEST_PHONE), null, masterId,
                "GUEST_LINK", "Марія Ш.");
        UUID client = fixtures.user("CLIENT", "Марія", "Шевченко");

        assertThatThrownBy(() -> jdbc.update("UPDATE reviews SET client_id = ? WHERE id = ?", client, id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_reviews_source_author");
    }

    private static Map<String, Object> sourceAndName(UUID reviewId) {
        return jdbc.queryForMap("SELECT source, guest_display_name FROM reviews WHERE id = ?", reviewId);
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations(MIGRATIONS);
    }
}
