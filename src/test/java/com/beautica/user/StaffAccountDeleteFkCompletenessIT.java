package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The highest-value test in the Phase 301 QA pass, mirroring {@link ClientAccountDeleteFkCompletenessIT}
 * for the staff/independent-master track. Builds a {@code SALON_MASTER} holding a row in EVERY
 * table this repo's migrations FK to {@code users(id)} that a staff account can plausibly populate,
 * then self-deletes and asserts it SUCCEEDS. A missed FK relaxation is a runtime
 * {@code DataIntegrityViolationException} → 500 in production; this is what would have caught V157
 * shipping incomplete, and what catches a FUTURE migration adding a new unrelaxed FK on a table a
 * staff account can hold a row in.
 *
 * <p><b>Table list, re-derived independently from the migrations (grep "REFERENCES users" across
 * every {@code db/migration/*.sql}), cross-checked against — not copied from — the phase 301 plan's
 * own §Q7 table</b>: {@code masters.user_id} (V4→V157 SET NULL, this master's OWN account — the
 * detach-vs-delete branch is exercised, not the FK), {@code bookings.created_by_user_id} (V137→V157
 * SET NULL, a walk-in this master rang up), {@code appointments.created_by_user_id} (V139→V157 SET
 * NULL, same walk-in's header), {@code refresh_tokens.user_id} (V1 CASCADE), {@code
 * device_tokens.user_id} (V29 CASCADE), {@code media_files.uploader_id} (V37 CASCADE), {@code
 * password_reset_tickets.user_id} (V55→V107 rename, CASCADE), {@code
 * platform_categories.requested_by_user_id} (V65 SET NULL), {@code
 * service_type_suggestion.requested_by_user_id} (V76 SET NULL).
 *
 * <p><b>Deliberately NOT covered here</b> — the re-derivation surfaced exactly the same four
 * client-scoped FKs the CLIENT-track completeness test already owns ({@code bookings.client_id},
 * {@code appointments.client_id}, {@code reviews.client_id}, {@code
 * client_reviews.subject_client_id}) plus {@code favorites.client_id} — all five structurally
 * unreachable for a staff/independent-master account (roles are immutable, `InviteService` never
 * upgrades an existing CLIENT in place, and every one of those write paths is
 * {@code hasRole('CLIENT')}-gated). {@code salons.owner_id} is the one FK that MUST NOT be
 * reachable at all — pinned separately by {@code StaffAccountSelfDeletionServiceTest}'s R9
 * precondition test, not exercised here (a salon owner never reaches this endpoint). No FK beyond
 * the phase 301 plan's own §Q7 inventory was found by this independent re-derivation.
 */
@DisplayName("DELETE /api/v1/users/me — every users(id)-referencing FK a staff account can hold a "
        + "row behind is satisfied (Phase 301 FK completeness)")
class StaffAccountDeleteFkCompletenessIT extends AbstractIntegrationTest {

    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    // platform_categories and service_type_suggestion outlive cleanDb() (V65/V76: platform-audit
    // data survives the requester) — clean up explicitly, mirroring ClientAccountDeleteFkCompletenessIT.
    private Long insertedCategoryId;
    private UUID insertedSuggestionId;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    @AfterEach
    void cleanUpAuditTables() {
        if (insertedCategoryId != null) {
            jdbcTemplate.update("DELETE FROM platform_categories WHERE id = ?", insertedCategoryId);
        }
        if (insertedSuggestionId != null) {
            jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE id = ?", insertedSuggestionId);
        }
    }

    @Test
    @DisplayName("a SALON_MASTER with a row in every reachable users(id)-referencing table is "
            + "deleted with 204, not a 500 FK-violation abort")
    void should_deleteSuccessfully_when_masterHoldsRowInEveryReachableTable() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID masterUserId = salon.masterUserId();
        UUID otherClientId = csd.createClient();
        String token = fixtures.tokenFor(emailOf(masterUserId)); // seeds refresh_tokens.user_id

        // bookings.created_by_user_id + appointments.created_by_user_id — a walk-in this master
        // rang up for a DIFFERENT client, scoped to a PAST slot so it never enters the self-delete
        // future-booking cascade.
        UUID appointmentId = csd.insertAppointmentHeader(otherClientId, salon.salonId(), "COMPLETED");
        UUID walkInBookingId = csd.insertBooking(otherClientId, salon, "COMPLETED", PAST, appointmentId);
        jdbcTemplate.update(
                "UPDATE bookings SET created_by_user_id = ? WHERE id = ?", masterUserId, walkInBookingId);
        jdbcTemplate.update(
                "UPDATE appointments SET created_by_user_id = ? WHERE id = ?", masterUserId, appointmentId);

        // device_tokens.user_id
        csd.insertDeviceToken(masterUserId);

        // media_files.uploader_id
        csd.insertMediaFile(masterUserId);

        // password_reset_tickets.user_id
        csd.insertPasswordResetTicket(masterUserId);

        // platform_categories.requested_by_user_id
        insertedCategoryId = csd.insertPlatformCategoryRequest(masterUserId);

        // service_type_suggestion.requested_by_user_id
        insertedSuggestionId = csd.insertServiceTypeSuggestion(masterUserId);

        // Fixture check — every row genuinely exists and genuinely names this master BEFORE the
        // delete, so a vacuous 204 (nothing was actually inserted) cannot pass this test.
        assertThat(csd.count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", masterUserId))
                .isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", masterUserId))
                .isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM media_files WHERE uploader_id = ?", masterUserId))
                .isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM password_reset_tickets WHERE user_id = ?", masterUserId))
                .isEqualTo(1);
        assertThat(csd.count(
                "SELECT COUNT(*) FROM platform_categories WHERE requested_by_user_id = ?", masterUserId))
                .isEqualTo(1);
        assertThat(csd.count(
                "SELECT COUNT(*) FROM service_type_suggestion WHERE requested_by_user_id = ?", masterUserId))
                .isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM bookings WHERE created_by_user_id = ?", masterUserId))
                .isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM appointments WHERE created_by_user_id = ?", masterUserId))
                .isEqualTo(1);
        assertThat(csd.masterUserId(salon.masterId())).isEqualTo(masterUserId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);

        assertThat(response.getStatusCode())
                .as("a 500 here means a FK on some table above was never relaxed")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(masterUserId)).isFalse();

        // The two audit-data FKs (SET NULL) survive the delete with the reference cleared.
        assertThat(csd.count("SELECT COUNT(*) FROM platform_categories WHERE id = ?", insertedCategoryId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT requested_by_user_id FROM platform_categories WHERE id = ?",
                UUID.class, insertedCategoryId))
                .isNull();
        assertThat(csd.count("SELECT COUNT(*) FROM service_type_suggestion WHERE id = ?",
                insertedSuggestionId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT requested_by_user_id FROM service_type_suggestion WHERE id = ?",
                UUID.class, insertedSuggestionId))
                .isNull();

        // masters.user_id — the WALK-IN'S own FK, and this master's own row: history (the walk-in
        // booking) forces the DETACH branch, never the FK's bare ON DELETE SET NULL.
        assertThat(csd.masterExists(salon.masterId())).isTrue();
        assertThat(csd.masterIsActive(salon.masterId())).isFalse();
        assertThat(csd.masterUserId(salon.masterId())).isNull();

        // bookings/appointments.created_by_user_id — SET NULL, walk-in survives with attribution
        // cleared.
        assertThat(csd.bookingExists(walkInBookingId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM bookings WHERE id = ?", UUID.class, walkInBookingId))
                .isNull();
        assertThat(csd.appointmentExists(appointmentId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM appointments WHERE id = ?", UUID.class, appointmentId))
                .isNull();
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
