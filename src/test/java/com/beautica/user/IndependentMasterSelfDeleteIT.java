package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB coverage for the {@code INDEPENDENT_MASTER} self-delete delta (Phase 301 §3) — the ONE
 * salon-assumption in the promoted {@code StaffAccountDisposalService#dispose} body is the {@code
 * salonId != null} guard around the invite-token cleanup, so an independent master (never invited
 * into any salon, {@code salonId = null} throughout) exercises the whole cascade with that guard
 * on the "skip" branch. Mirrors {@link MasterSelfDeleteBookingDisposalIT}'s domain assertions
 * (review-direction symmetry, D7's client-rating-untouched invariant), scoped to a role with no
 * salon above it, and additionally proves the {@code masters(id)}-owned CASCADE tables behave
 * correctly on BOTH branches.
 */
@DisplayName("DELETE /api/v1/users/me (INDEPENDENT_MASTER) — no-salon self-delete (Phase 301 §3)")
class IndependentMasterSelfDeleteIT extends AbstractIntegrationTest {

    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(3);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    @Test
    @DisplayName("no booking/review history: both the users row AND the masters row are physically "
            + "deleted, and every masters(id)-owned CASCADE child (master_services, weekly_schedules) "
            + "goes with it")
    void should_hardDeleteMasterAndCascadeSchedule_when_noHistoryExists() throws Exception {
        String email = "im-selfdelete-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);
        UUID masterUserId = masterUserIdOf(masterId);
        String token = fixtures.tokenFor(email);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(masterUserId)).isFalse();
        assertThat(csd.masterExists(masterId)).isFalse();
        assertThat(csd.count("SELECT COUNT(*) FROM master_services WHERE id = ?", masterServiceId))
                .as("master_services CASCADEs on masters(id)")
                .isZero();
        assertThat(csd.count("SELECT COUNT(*) FROM weekly_schedules WHERE master_id = ?", masterId))
                .as("weekly_schedules CASCADEs on masters(id)")
                .isZero();
    }

    @Test
    @DisplayName("WITH booking history: the masters row DETACHes (never deletes), and the "
            + "masters(id)-owned schedule/catalogue rows SURVIVE untouched, pointing at the stub "
            + "(R8 — reachable only through historical hydration, never a live discovery surface)")
    void should_detachMasterAndSurviveSchedule_when_historyExists() throws Exception {
        String email = "im-selfdelete-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);
        UUID masterUserId = masterUserIdOf(masterId);
        UUID clientId = csd.createClient();
        UUID pastBookingId = csd.insertBooking(clientId, masterId, masterServiceId, null, "COMPLETED", PAST);
        String token = fixtures.tokenFor(email);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(masterUserId)).isFalse();
        assertThat(csd.masterExists(masterId)).isTrue();
        assertThat(csd.masterIsActive(masterId)).isFalse();
        assertThat(csd.masterUserId(masterId)).isNull();
        assertThat(csd.bookingExists(pastBookingId)).isTrue();
        assertThat(csd.count("SELECT COUNT(*) FROM master_services WHERE id = ?", masterServiceId))
                .as("no invite-token/salon cleanup ever touches this — it survives, unreachable "
                        + "through any live discovery surface (is_active=false)")
                .isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM weekly_schedules WHERE master_id = ?", masterId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("salonId == null throughout: no invite_tokens row is touched (there was never one "
            + "to clean up), and the self-delete still succeeds — Phase 301 §3a's one salon "
            + "assumption is provably a no-op for this role")
    void should_succeed_withNoSalonAssumptionAnywhere() throws Exception {
        String email = "im-selfdelete-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        String token = fixtures.tokenFor(email);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode())
                .as("salonId == null must not throw an NPE anywhere in the promoted disposal body")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("D7 mirrored for the independent-master role: client_reviews AUTHORED by the "
            + "departing master survive, and the SUBJECT CLIENT's own rating is unchanged")
    void should_keepAuthoredClientReviews_andLeaveSubjectClientRatingUnchanged() throws Exception {
        String email = "im-selfdelete-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        UUID subjectClientId = csd.createClient();
        UUID pastBookingId = csd.insertBooking(
                subjectClientId, masterId, masterServiceId, null, "COMPLETED", PAST);
        csd.insertClientReview(pastBookingId, subjectClientId, masterId, null, 4);
        jdbcTemplate.update(
                "UPDATE users SET avg_rating = ?, review_count = 1 WHERE id = ?",
                new BigDecimal("4.00"), subjectClientId);
        String token = fixtures.tokenFor(email);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.count(
                "SELECT COUNT(*) FROM client_reviews WHERE author_master_id = ?", masterId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT avg_rating FROM users WHERE id = ?", BigDecimal.class, subjectClientId))
                .isEqualByComparingTo("4.00");
    }

    private UUID masterUserIdOf(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }
}
