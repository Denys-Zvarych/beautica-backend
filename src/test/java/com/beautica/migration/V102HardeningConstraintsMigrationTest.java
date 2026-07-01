package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for V102__harden_checks_freetext_and_katotth.sql.
 *
 * <p>V102 adds defense-in-depth {@code CHECK} constraints for fields previously
 * guarded ONLY at the DTO (Bean Validation) layer. The whole point of a
 * storage-layer guard is that a raw-SQL / seed / future-admin insert bypasses
 * the application entirely — so the only meaningful proof is a RAW insert that
 * never touches a DTO. The build-verifier boot proves V102 <em>applies</em>; it
 * says nothing about whether the constraints actually REJECT bad data. Per QA
 * playbook Q21, a hardening migration's correctness IS its contract.
 *
 * <p>Each test fires a native JDBC insert (no JPA, no DTO) and asserts:
 * <ul>
 *   <li>the bad value is rejected with a {@link DataIntegrityViolationException}
 *       naming the exact V102 constraint (so we know the RIGHT guard fired, not
 *       some incidental NOT NULL / FK / UNIQUE failure), and</li>
 *   <li>the legitimate value (and the NULL-safe path) is accepted.</li>
 * </ul>
 *
 * <p>{@link AbstractIntegrationTest#cleanDb()} clears the transactional tables
 * (users / salons / reviews + graph) after every test. It does NOT touch the
 * locality reference tables, so {@link #cleanupLocality()} removes the rows this
 * class inserts there, keeping the suite order-independent.
 */
@DisplayName("V102 migration — free-text control-char + blank-comment + KATOTTH-format CHECK constraints")
class V102HardeningConstraintsMigrationTest extends AbstractIntegrationTest {

    /** ASCII BEL (0x07) — inside the banned [\x00-\x1F\x7F] control-char range. */
    private static final String CTRL = String.valueOf((char) 0x07);
    /** A well-formed KATOTTH code: literal "UA" + exactly 17 digits = 19 chars. */
    private static final String VALID_KATOTTH = "UA90000000000000001";
    private static final String LOCALITY_MARKER = "V102TEST";

    @AfterEach
    void cleanupLocality() {
        // FK order: districts → cities → oblasts. cleanDb() (base @AfterEach) does not
        // touch these reference tables, so rows inserted here would otherwise leak and
        // collide on the UNIQUE(katotth_code) guard on a context-reused re-run.
        jdbcTemplate.update("DELETE FROM city_districts WHERE name_uk LIKE ?", LOCALITY_MARKER + "%");
        jdbcTemplate.update("DELETE FROM cities WHERE name_uk LIKE ?", LOCALITY_MARKER + "%");
        jdbcTemplate.update("DELETE FROM oblasts WHERE name_uk LIKE ?", LOCALITY_MARKER + "%");
    }

    // =========================================================================
    // reviews.comment — chk_reviews_comment_not_blank
    // CHECK (comment IS NULL OR btrim(comment) <> '')
    // Building a real review row is the only honest test: a fake booking_id would
    // trip the FK (23503) before the CHECK, proving nothing. The graph is built
    // once per test in @BeforeEach; each review needs its own booking (UNIQUE).
    // =========================================================================

    @Nested
    @DisplayName("reviews.comment — blank rejected, NULL accepted")
    class ReviewCommentConstraint {

        private UUID clientId;
        private UUID masterId;
        private UUID salonId;
        private UUID masterServiceId;

        @BeforeEach
        void buildReviewGraph() {
            clientId = insertUser("CLIENT", null, null, null);
            UUID ownerId = insertUser("SALON_OWNER", null, null, null);
            salonId = insertSalon(ownerId, null, null, null);
            masterId = insertMaster(ownerId, salonId);
            UUID serviceDefId = insertServiceDefinition(salonId);
            masterServiceId = insertMasterService(masterId, serviceDefId);
        }

        @Test
        @DisplayName("INSERT review with NULL comment is accepted (NULL-safe path)")
        void should_acceptInsert_when_commentIsNull() {
            UUID bookingId = insertCompletedBooking(clientId, masterId, salonId, masterServiceId);

            assertThatCode(() -> insertReview(bookingId, null))
                    .as("chk_reviews_comment_not_blank must accept a NULL comment "
                            + "(its predicate short-circuits on `comment IS NULL`)")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("INSERT review with a non-blank comment is accepted")
        void should_acceptInsert_when_commentHasVisibleText() {
            UUID bookingId = insertCompletedBooking(clientId, masterId, salonId, masterServiceId);

            assertThatCode(() -> insertReview(bookingId, "Чудовий майстер!"))
                    .as("a real comment with visible characters must pass the CHECK")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("INSERT review with an all-whitespace comment is rejected (23514)")
        void should_rejectInsert_when_commentIsWhitespaceOnly() {
            UUID bookingId = insertCompletedBooking(clientId, masterId, salonId, masterServiceId);

            assertThatThrownBy(() -> insertReview(bookingId, "   "))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_reviews_comment_not_blank");
        }
    }

    // =========================================================================
    // users free-text address — chk_users_{street,building_no,location_note}_no_ctrl
    // CHECK (col !~ '[\x00-\x1F\x7F]')  (NULL-safe)
    // =========================================================================

    @Nested
    @DisplayName("users free-text address — control chars rejected, clean/NULL accepted")
    class UserAddressControlCharConstraint {

        @Test
        @DisplayName("INSERT user.street containing a control char is rejected (23514)")
        void should_rejectInsert_when_streetHasControlChar() {
            assertThatThrownBy(() -> insertUser("CLIENT", "ab" + CTRL + "cd", null, null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_users_street_no_ctrl");
        }

        @Test
        @DisplayName("INSERT user.building_no containing a control char is rejected (23514)")
        void should_rejectInsert_when_buildingNoHasControlChar() {
            assertThatThrownBy(() -> insertUser("CLIENT", null, "5" + CTRL, null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_users_building_no_no_ctrl");
        }

        @Test
        @DisplayName("INSERT user.location_note containing a control char is rejected (23514)")
        void should_rejectInsert_when_locationNoteHasControlChar() {
            assertThatThrownBy(() -> insertUser("CLIENT", null, null, "note" + CTRL))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_users_location_note_no_ctrl");
        }

        @Test
        @DisplayName("INSERT user with clean address values is accepted")
        void should_acceptInsert_when_addressHasNoControlChars() {
            assertThatCode(() ->
                    insertUser("CLIENT", "вул. Хрещатик", "12А", "поряд з аптекою"))
                    .as("printable Unicode (incl. Cyrillic) must pass — only ASCII control chars are banned")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("INSERT user with all-NULL address values is accepted (NULL-safe path)")
        void should_acceptInsert_when_addressIsNull() {
            assertThatCode(() -> insertUser("CLIENT", null, null, null))
                    .as("the `col !~ ...` predicate is UNKNOWN (accepted) for NULL")
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // salons free-text address — chk_salons_street_no_ctrl (same exposure as users)
    // =========================================================================

    @Nested
    @DisplayName("salons free-text address — control chars rejected, clean accepted")
    class SalonAddressControlCharConstraint {

        @Test
        @DisplayName("INSERT salon.street containing a control char is rejected (23514)")
        void should_rejectInsert_when_salonStreetHasControlChar() {
            UUID ownerId = insertUser("SALON_OWNER", null, null, null);

            assertThatThrownBy(() -> insertSalon(ownerId, "Main St" + CTRL, null, null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_salons_street_no_ctrl");
        }

        @Test
        @DisplayName("INSERT salon.location_note containing a control char is rejected (23514)")
        void should_rejectInsert_when_salonLocationNoteHasControlChar() {
            UUID ownerId = insertUser("SALON_OWNER", null, null, null);

            assertThatThrownBy(() -> insertSalon(ownerId, null, null, "third floor" + CTRL))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_salons_location_note_no_ctrl");
        }

        @Test
        @DisplayName("INSERT salon with clean address values is accepted")
        void should_acceptInsert_when_salonAddressHasNoControlChars() {
            UUID ownerId = insertUser("SALON_OWNER", null, null, null);

            assertThatCode(() -> insertSalon(ownerId, "вул. Дерибасівська", "7", "вхід з двору"))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // KATOTTH format — chk_{oblasts,cities,city_districts}_katotth_code_format
    // CHECK (katotth_code ~ '^UA[0-9]{17}$')
    // =========================================================================

    @Nested
    @DisplayName("KATOTTH code format — malformed rejected, well-formed accepted")
    class KatotthFormatConstraint {

        @Test
        @DisplayName("INSERT oblast with too-short KATOTTH ('UA123') is rejected (23514)")
        void should_rejectInsert_when_oblastKatotthTooShort() {
            assertThatThrownBy(() -> insertOblast("UA123"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_oblasts_katotth_code_format");
        }

        @Test
        @DisplayName("INSERT oblast with wrong prefix ('XX' + 17 digits) is rejected (23514)")
        void should_rejectInsert_when_oblastKatotthWrongPrefix() {
            assertThatThrownBy(() -> insertOblast("XX12345678901234567"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_oblasts_katotth_code_format");
        }

        @Test
        @DisplayName("INSERT oblast with 16 digits (off-by-one) is rejected (23514)")
        void should_rejectInsert_when_oblastKatotthHasSixteenDigits() {
            assertThatThrownBy(() -> insertOblast("UA1234567890123456"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_oblasts_katotth_code_format");
        }

        @Test
        @DisplayName("INSERT oblast with well-formed KATOTTH ('UA' + 17 digits) is accepted")
        void should_acceptInsert_when_oblastKatotthWellFormed() {
            assertThatCode(() -> insertOblast(VALID_KATOTTH))
                    .as("literal 'UA' + exactly 17 digits (19 chars) must satisfy ^UA[0-9]{17}$")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("INSERT city with malformed KATOTTH is rejected (23514)")
        void should_rejectInsert_when_cityKatotthMalformed() {
            UUID oblastId = insertOblast(VALID_KATOTTH);

            assertThatThrownBy(() -> insertCity(oblastId, "UA-not-a-code"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_cities_katotth_code_format");
        }

        @Test
        @DisplayName("INSERT city_district with malformed KATOTTH is rejected (23514)")
        void should_rejectInsert_when_districtKatotthMalformed() {
            UUID oblastId = insertOblast(VALID_KATOTTH);
            UUID cityId = insertCity(oblastId, "UA90000000000000002");

            assertThatThrownBy(() -> insertDistrict(cityId, "UA9999"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_city_districts_katotth_code_format");
        }
    }

    // ── insert helpers (raw JDBC, bypassing the DTO layer) ───────────────────

    private UUID insertUser(String role, String street, String buildingNo, String locationNote) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, street, building_no, location_note) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, "v102-" + id + "@beautica.test",
                "$2a$04$placeholder.for.migration.test.only", role,
                street, buildingNo, locationNote);
        return id;
    }

    private UUID insertSalon(UUID ownerId, String street, String buildingNo, String locationNote) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, street, building_no, location_note) "
                        + "VALUES (?, ?, ?, true, ?, ?, ?)",
                id, ownerId, "V102 Test Salon", street, buildingNo, locationNote);
        return id;
    }

    private UUID insertMaster(UUID userId, UUID salonId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', ?, ?)",
                id, userId, salonId, now, now);
        return id;
    }

    private UUID insertServiceDefinition(UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, base_duration_minutes, base_price, price_type, is_active) "
                        + "VALUES (?, 'SALON', ?, 'V102 Test Service', 60, 350.00, 'FIXED', true)",
                id, salonId);
        return id;
    }

    private UUID insertMasterService(UUID masterId, UUID serviceDefId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active) "
                        + "VALUES (?, ?, ?, true)",
                id, masterId, serviceDefId);
        return id;
    }

    private UUID insertCompletedBooking(UUID clientId, UUID masterId, UUID salonId, UUID masterServiceId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.now().minusDays(1);
        OffsetDateTime end = start.plusHours(1);
        OffsetDateTime now = OffsetDateTime.now();
        // COMPLETED is the review-eligible terminal state and is excluded from the
        // no_overlapping_bookings partial constraint, so repeated calls cannot clash.
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, 350.00, 60, ?, ?)",
                id, clientId, masterId, masterServiceId, salonId, start, end, now, now);
        return id;
    }

    private void insertReview(UUID bookingId, String comment) {
        UUID id = UUID.randomUUID();
        UUID clientId = jdbcTemplate.queryForObject(
                "SELECT client_id FROM bookings WHERE id = ?", UUID.class, bookingId);
        UUID masterId = jdbcTemplate.queryForObject(
                "SELECT master_id FROM bookings WHERE id = ?", UUID.class, bookingId);
        UUID salonId = jdbcTemplate.queryForObject(
                "SELECT salon_id FROM bookings WHERE id = ?", UUID.class, bookingId);
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 5, ?, ?, ?)",
                id, bookingId, clientId, masterId, salonId, comment, now, now);
    }

    private UUID insertOblast(String katotthCode) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO oblasts (id, katotth_code, name_uk, name_en) VALUES (?, ?, ?, ?)",
                id, katotthCode, LOCALITY_MARKER + "-oblast-" + id, "V102 Test Oblast");
        return id;
    }

    private UUID insertCity(UUID oblastId, String katotthCode) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO cities (id, oblast_id, katotth_code, name_uk, name_en) VALUES (?, ?, ?, ?, ?)",
                id, oblastId, katotthCode, LOCALITY_MARKER + "-city-" + id, "V102 Test City");
        return id;
    }

    private UUID insertDistrict(UUID cityId, String katotthCode) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO city_districts (id, city_id, katotth_code, name_uk, name_en) VALUES (?, ?, ?, ?, ?)",
                id, cityId, katotthCode, LOCALITY_MARKER + "-district-" + id, "V102 Test District");
        return id;
    }
}
