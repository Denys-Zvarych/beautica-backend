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
 * The highest-value test in the Phase 300 QA pass. Builds a CLIENT holding a row in EVERY table
 * this repo's migrations FK to {@code users(id)} that a CLIENT account can plausibly populate,
 * then deletes the account and asserts it SUCCEEDS. A missed FK relaxation is a runtime
 * {@code DataIntegrityViolationException} → 500 in production; this test is what would have
 * caught V162 shipping incomplete, and what catches a FUTURE migration adding a new
 * unrelaxed/undeleted {@code users}-referencing FK on a table a CLIENT can hold a row in.
 *
 * <p><b>Table list, derived from the migrations (grep "REFERENCES users" across every
 * {@code db/migration/*.sql}), not from the task summary</b> — every one exercised below:
 * {@code bookings.client_id} (V18→V162 SET NULL), {@code appointments.client_id} (V124→V162 SET
 * NULL), {@code reviews.client_id} (V40→V162 SET NULL), {@code client_reviews.subject_client_id}
 * (V128, NO ACTION — deleted by the service, not relaxed), {@code favorites.client_id} (V92
 * CASCADE), {@code refresh_tokens.user_id} (V1 CASCADE), {@code device_tokens.user_id} (V29
 * CASCADE), {@code media_files.uploader_id} (V37 CASCADE), {@code password_reset_tickets.user_id}
 * (V55→V107 rename, CASCADE), {@code platform_categories.requested_by_user_id} (V65 SET NULL),
 * {@code service_type_suggestion.requested_by_user_id} (V76 SET NULL).
 *
 * <p><b>Deliberately NOT covered here</b> — three more {@code users}-referencing FKs exist
 * ({@code masters.user_id} V4/V157, {@code salons.owner_id} V3, {@code bookings
 * /appointments.created_by_user_id} V137/V139/V157) but a {@code CLIENT} role can never hold a row
 * behind any of them (only staff/owner accounts create {@code masters}/{@code salons} rows or
 * appear as a STAFF booking's creator) — see {@code ClientAccountDeletionService}'s own guard
 * (§3: {@code role != CLIENT || salonId != null} throws before any write). Those three are the
 * staff-hard-delete track's ({@code SalonStaffHardDeleteIT}) responsibility, not this one's.
 */
@DisplayName("DELETE /api/v1/users/me — every users(id)-referencing FK a CLIENT can hold a row "
        + "behind is satisfied (Phase 300 FK completeness)")
class ClientAccountDeleteFkCompletenessIT extends AbstractIntegrationTest {

    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    // platform_categories and service_type_suggestion are NOT among the tables
    // AbstractIntegrationTest#cleanDb() drains between tests (they are platform-audit data meant
    // to outlive a requester, per V65/V76) — this class is the only one in the suite that seeds
    // rows there, so it must clean up after itself rather than leak rows across the whole run.
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
    @DisplayName("a client with a row in every users(id)-referencing table is deleted with 204, "
            + "not a 500 FK-violation abort")
    void should_deleteSuccessfully_when_clientHoldsRowInEveryReferencingTable() throws Exception {
        UUID clientId = csd.createClient();
        String token = fixtures.tokenFor(emailOf(clientId));
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();

        // bookings.client_id + appointments.client_id
        UUID appointmentId = csd.insertAppointmentHeader(clientId, salon.salonId(), "COMPLETED");
        UUID bookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST, appointmentId);

        // reviews.client_id (client -> provider)
        csd.insertReview(bookingId, clientId, salon.masterId(), salon.salonId(), 5, "Great!");

        // client_reviews.subject_client_id (provider -> client) — needs its OWN uq_client_reviews_booking
        // slot, but that constraint is scoped to client_reviews only, so reusing the same bookingId
        // as the review above is legal (two different tables, two different UNIQUE constraints).
        csd.insertClientReview(bookingId, clientId, salon.masterId(), salon.salonId(), 4);

        // favorites.client_id
        csd.insertFavorite(clientId, "SALON", salon.salonId());

        // refresh_tokens.user_id (via the real login above) + device_tokens.user_id
        csd.insertDeviceToken(clientId);

        // media_files.uploader_id
        csd.insertMediaFile(clientId);

        // password_reset_tickets.user_id
        csd.insertPasswordResetTicket(clientId);

        // platform_categories.requested_by_user_id
        long categoryId = csd.insertPlatformCategoryRequest(clientId);
        insertedCategoryId = categoryId;

        // service_type_suggestion.requested_by_user_id
        UUID suggestionId = csd.insertServiceTypeSuggestion(clientId);
        insertedSuggestionId = suggestionId;

        // Fixture check — every row genuinely exists and genuinely names this client BEFORE the
        // delete, so a vacuous 204 (nothing was actually inserted) cannot pass this test.
        assertThat(csd.count("SELECT COUNT(*) FROM bookings WHERE client_id = ?", clientId)).isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM appointments WHERE client_id = ?", clientId)).isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM reviews WHERE client_id = ?", clientId)).isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM client_reviews WHERE subject_client_id = ?", clientId))
                .isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM favorites WHERE client_id = ?", clientId)).isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", clientId)).isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", clientId)).isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM media_files WHERE uploader_id = ?", clientId)).isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM password_reset_tickets WHERE user_id = ?", clientId))
                .isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM platform_categories WHERE requested_by_user_id = ?", clientId))
                .isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM service_type_suggestion WHERE requested_by_user_id = ?",
                clientId)).isEqualTo(1);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);

        assertThat(response.getStatusCode())
                .as("a 500 here means a FK on some table above was never relaxed/drained")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(clientId)).isFalse();

        // The two audit-data FKs (SET NULL) survive the delete with the reference cleared —
        // platform data and suggestion audit trail both outlive the requester on purpose (V65/V76
        // migration comments: "must survive the requester's account deletion").
        assertThat(csd.count("SELECT COUNT(*) FROM platform_categories WHERE id = ?", categoryId))
                .as("the category request itself survives — only the requester reference clears")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT requested_by_user_id FROM platform_categories WHERE id = ?", UUID.class, categoryId))
                .isNull();
        assertThat(csd.count("SELECT COUNT(*) FROM service_type_suggestion WHERE id = ?", suggestionId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT requested_by_user_id FROM service_type_suggestion WHERE id = ?", UUID.class, suggestionId))
                .isNull();

        // client_reviews (provider -> client) are DELETED outright (D3), never detached.
        assertThat(csd.count("SELECT COUNT(*) FROM client_reviews WHERE subject_client_id = ?", clientId))
                .isZero();
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
