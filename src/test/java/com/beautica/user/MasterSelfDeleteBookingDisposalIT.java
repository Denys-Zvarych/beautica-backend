package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.support.HibernateStatistics;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
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
 * Real-DB coverage for {@code BookingService#disposeFutureConfirmedForMasterSelfDelete} (Phase 337
 * — REVERSES Phase 301 Q3's decline-then-hard-delete: future bookings are now DECLINED and KEPT,
 * exactly like the owner-initiated {@code declineFutureConfirmedBookingsForMasterRemoval} cascade)
 * — the master-role booking-disposal cascade a {@code SALON_MASTER}/{@code INDEPENDENT_MASTER}
 * self-delete triggers. Complements {@code StaffAccountSelfDeletionServiceTest}'s mocked-collaborator
 * coverage and {@code MasterSelfDeletionFutureBookingsIT}'s outbox-focused coverage with what only a
 * real Postgres schema and real cascades can prove: the appointment-header survivorship split
 * (mixed-master headers survive untouched status-wise, single-master headers collapse to DECLINED
 * but are never deleted), that past bookings and BOTH review directions are left untouched, and the
 * D7 asymmetry — {@code client_reviews} authored by the departing master survive and the SUBJECT
 * CLIENT's own rating never moves — is the single most important behavioural difference from the
 * CLIENT self-delete track and is asserted here, not assumed.
 */
@DisplayName("DELETE /api/v1/users/me (SALON_MASTER) — future-booking disposal cascade (Phase 337)")
class MasterSelfDeleteBookingDisposalIT extends AbstractIntegrationTest {

    private static final OffsetDateTime FUTURE = OffsetDateTime.now().plusDays(5);
    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(3);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManagerFactory emf;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    @Test
    @DisplayName("every future CONFIRMED booking of the departing master is DECLINED and KEPT "
            + "after self-delete (Phase 337 — reverses the old hard-delete)")
    void should_declineAndKeepFutureConfirmedBookings_belongingToTheDepartingMaster() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID futureBookingId = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(futureBookingId))
                .as("Phase 337 — declined and KEPT, never hard-deleted, so phase 333's feed write "
                        + "has a row to reference")
                .isTrue();
        assertThat(bookingStatus(futureBookingId)).isEqualTo("DECLINED");
        assertThat(bookingCancellationReason(futureBookingId)).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("a multi-master appointment header SURVIVES CONFIRMED when only one of its two "
            + "masters self-deletes — the other master's leg keeps the header CONFIRMED, and the "
            + "departing master's own leg is DECLINED, not deleted")
    void should_surviveAppointmentHeader_when_siblingMasterLegRemains() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        ClientSelfDeleteTestFixtures.SecondMaster masterB = csd.addSecondMaster(salon);
        UUID clientId = csd.createClient();
        UUID appointmentId = csd.insertAppointmentHeader(clientId, salon.salonId(), "CONFIRMED");
        UUID masterALegId = csd.insertBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                "CONFIRMED", FUTURE, appointmentId);
        UUID masterBLegId = csd.insertBooking(
                clientId, masterB.masterId(), masterB.masterServiceId(), salon.salonId(),
                "CONFIRMED", FUTURE, appointmentId);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(masterALegId))
                .as("masterA's own leg is declined and KEPT, never hard-deleted")
                .isTrue();
        assertThat(bookingStatus(masterALegId)).isEqualTo("DECLINED");
        assertThat(csd.bookingExists(masterBLegId))
                .as("masterB never self-deleted — their leg is untouched")
                .isTrue();
        assertThat(bookingStatus(masterBLegId))
                .as("masterB's own leg is untouched by masterA's self-delete")
                .isEqualTo("CONFIRMED");
        assertThat(csd.appointmentExists(appointmentId))
                .as("the header still has a surviving CONFIRMED leg (masterB's) — it must NOT collapse")
                .isTrue();
        assertThat(appointmentStatus(appointmentId))
                .as("one sibling is still CONFIRMED, so the all-or-nothing header status stays "
                        + "CONFIRMED too")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("an appointment header whose ONLY leg belonged to the departing master is DECLINED "
            + "and KEPT once every one of its legs is — never deleted (Phase 337)")
    void should_declineAndKeepAppointmentHeader_when_allLegsBelongedToTheDepartingMaster() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID appointmentId = csd.insertAppointmentHeader(clientId, salon.salonId(), "CONFIRMED");
        UUID onlyLegId = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE, appointmentId);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(onlyLegId)).isTrue();
        assertThat(bookingStatus(onlyLegId)).isEqualTo("DECLINED");
        assertThat(csd.appointmentExists(appointmentId))
                .as("a fully-declined header is KEPT (Phase 337) — never collapsed to a deleted "
                        + "row, since every declined booking still exists")
                .isTrue();
        assertThat(appointmentStatus(appointmentId))
                .as("every leg declined — the all-or-nothing header status follows to DECLINED")
                .isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("a past booking of the departing master survives untouched — only FUTURE CONFIRMED "
            + "bookings are disposed of")
    void should_leavePastBookingsUntouched() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID pastBookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(pastBookingId)).isTrue();
    }

    @Test
    @DisplayName("client→provider reviews about the departing master survive untouched — "
            + "reviews.master_id is NO ACTION, a predicate that forces DETACH, and the aggregate "
            + "freezes on the detached stub (mirrors phase 300's D3 for the master side)")
    void should_leaveClientToProviderReviewsUntouched() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID pastBookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        csd.insertReview(pastBookingId, clientId, salon.masterId(), salon.salonId(), 5, "Чудово!");
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.count("SELECT COUNT(*) FROM reviews WHERE master_id = ?", salon.masterId()))
                .as("the review a client left ABOUT this master must survive — this is the very "
                        + "predicate that forces the DETACH branch, not deletion")
                .isEqualTo(1);
        assertThat(csd.masterIsActive(salon.masterId())).isFalse();
    }

    @Test
    @DisplayName("D7 — client_reviews AUTHORED by the departing master survive, and the SUBJECT "
            + "CLIENT's own rating is UNCHANGED — deleting them would silently drop a living "
            + "client's rating, the exact data-loss bug D7 exists to prevent")
    void should_keepAuthoredClientReviews_andLeaveSubjectClientRatingUnchanged() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID subjectClientId = csd.createClient();
        UUID pastBookingId = csd.insertBooking(subjectClientId, salon, "COMPLETED", PAST);
        csd.insertClientReview(pastBookingId, subjectClientId, salon.masterId(), salon.salonId(), 5);
        // Simulate the rating aggregate this client_review contributed to, exactly as the real
        // rating-recompute path would have written it — the fixture must genuinely name a
        // non-default value so a silent reset back to a zero/null default cannot pass this test.
        jdbcTemplate.update(
                "UPDATE users SET avg_rating = ?, review_count = 1 WHERE id = ?",
                new BigDecimal("5.00"), subjectClientId);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.count(
                "SELECT COUNT(*) FROM client_reviews WHERE author_master_id = ?", salon.masterId()))
                .as("D7 — authored client_reviews are KEPT, the opposite of the CLIENT self-delete "
                        + "flow's D3")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT avg_rating FROM users WHERE id = ?", BigDecimal.class, subjectClientId))
                .as("the subject client's own rating must not move — that client is still using the "
                        + "app and did nothing")
                .isEqualByComparingTo("5.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_count FROM users WHERE id = ?", Integer.class, subjectClientId))
                .isEqualTo(1);
        assertThat(csd.userExists(subjectClientId))
                .as("the subject client's own account is completely unrelated to the master's "
                        + "self-delete")
                .isTrue();
    }

    @Test
    @DisplayName("perf re-audit (2026-09, Finding 1): the appointment-visit decline leg's JDBC "
            + "statement count is FLAT in the number of DISTINCT multi-service visits — 1, 20 and "
            + "60 (above Hibernate's default_batch_fetch_size=50 staircase) all cost the SAME "
            + "number of statements, proving the batched lock/write/collapse trio replaced the "
            + "former ~4-round-trips-per-visit shape rather than merely moving the cost around")
    void should_keepStatementCountFlat_asDistinctAppointmentVisitCountGrows() throws Exception {
        long statementsForOneVisit = declineAndCountStatements(1);
        long statementsForTwentyVisits = declineAndCountStatements(20);
        long statementsForSixtyVisits = declineAndCountStatements(60);

        assertThat(statementsForTwentyVisits)
                .as("20 distinct multi-service visits must cost the SAME statement count as 1 — "
                        + "self-delete skips per-visit provider authorization entirely "
                        + "(skipProviderAuthorization), so the whole appointment leg is exactly 3 "
                        + "bulk statements (lock, write, collapse) regardless of visit count")
                .isEqualTo(statementsForOneVisit);
        assertThat(statementsForSixtyVisits)
                .as("crossing the default_batch_fetch_size=50 staircase must not reintroduce "
                        + "per-visit scaling either — this leg never loads a lazy association at "
                        + "all, so there is no batch-fetch boundary to cross in the first place")
                .isEqualTo(statementsForOneVisit);
    }

    /**
     * Self-deletes a fresh master owning {@code visitCount} distinct 2-leg CONFIRMED future
     * appointment visits, and returns the number of JDBC statements the WHOLE {@code DELETE
     * /api/v1/users/me} call issued. Every step OTHER than the appointment-visit decline leg
     * (role/precondition checks, the client-residue audit, the account+masters-row hard delete,
     * the token denylist) is structurally constant in {@code visitCount} — none of them loop over
     * bookings — so the delta between calls with different {@code visitCount} isolates exactly the
     * leg this test is pinning.
     */
    private long declineAndCountStatements(int visitCount) throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        for (int i = 0; i < visitCount; i++) {
            UUID appointmentId = csd.insertAppointmentHeader(clientId, salon.salonId(), "CONFIRMED");
            // Each visit's own two legs are 3h apart, and consecutive visits are 6h apart — well
            // clear of insertBooking's 60-minute default duration, so no_overlapping_bookings
            // (V113) never trips across either axis.
            OffsetDateTime visitStart = FUTURE.plusHours(6L * i);
            csd.insertBooking(clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                    "CONFIRMED", visitStart, appointmentId);
            csd.insertBooking(clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                    "CONFIRMED", visitStart.plusHours(3), appointmentId);
        }
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));
        Statistics statistics = HibernateStatistics.enabledOn(emf);
        statistics.clear();

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        return statistics.getPrepareStatementCount();
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private String bookingStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String bookingCancellationReason(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }
}
