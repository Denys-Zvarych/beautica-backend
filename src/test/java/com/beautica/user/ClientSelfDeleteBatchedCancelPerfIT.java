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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Real-DB perf coverage for the 2026-09 audit of {@code ClientAccountDeletionService}'s
 * future-booking self-delete cascade (Phase 338 follow-up), items 2 and 4.
 *
 * <p><b>Item 2 (MEDIUM, perf).</b> Before this fix, the self-delete loop cancelled each future
 * booking through the ORDINARY {@code cancelBooking(UUID, UUID, CancelBookingRequest)} entry
 * point, which re-issues {@link com.beautica.booking.repository.BookingRepository
 * #findByIdWithFullGraph} — a 5-join {@code SELECT} — once PER booking. {@link
 * com.beautica.booking.service.BookingService#cancelFutureConfirmedBookingsForClientSelfDelete}
 * instead preloads every candidate's graph in ONE {@code findAllByIdsWithGraph} call up front, so
 * the marginal JDBC-statement cost of each ADDITIONAL future booking should reflect only the
 * per-booking transition's own intrinsic statements (the freshness recheck + the save), never that
 * extra graph {@code SELECT}. This class pins that by comparing the total statement count for a
 * 1-booking and a 20-booking self-delete and asserting the marginal (N=20 minus N=1, divided by 19)
 * per-booking cost stays low — a regression that reintroduces a per-booking graph load would push
 * it back up by one whole extra statement per booking.
 *
 * <p><b>Item 4 (LOW, perf).</b> A near-cap (50) self-delete could plausibly hold its DB connection
 * long enough to trip HikariCP's {@code leak-detection-threshold: 10000} (application.yml) with a
 * spurious WARN. This class measures the wall-clock time of an EXACTLY-at-cap ({@link
 * ClientAccountDeletionService#MAX_FUTURE_BOOKINGS_PER_SELF_DELETE}) self-delete and asserts it
 * stays comfortably under that threshold.
 */
@DisplayName("DELETE /api/v1/users/me — Phase 338 perf audit items 2 & 4 (batched-cancel statement "
        + "count + near-cap elapsed time)")
class ClientSelfDeleteBatchedCancelPerfIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ClientSelfDeleteBatchedCancelPerfIT.class);
    private static final OffsetDateTime BASE = OffsetDateTime.now().plusDays(9);

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
    @DisplayName("perf audit item 2: the marginal JDBC-statement cost per additional future "
            + "standalone booking stays low once the graph preload is batched into ONE query — a "
            + "20-booking self-delete must not cost anywhere near 20x a 1-booking one")
    void should_lowerPerBookingStatementCost_when_graphPreloadIsBatched() throws Exception {
        long statementsForOne = selfDeleteAndCountStatements(1);
        long statementsForTwenty = selfDeleteAndCountStatements(20);
        double marginalPerBooking = (statementsForTwenty - statementsForOne) / 19.0;

        log.info("Phase 338 perf audit item 2 — statement counts: N=1 -> {}, N=20 -> {}, marginal "
                + "per additional booking -> {}", statementsForOne, statementsForTwenty, marginalPerBooking);

        assertThat(marginalPerBooking)
                .as("measured marginal cost is 3.0 statements/booking: existsConfirmedById's "
                        + "freshness recheck; the CANCELLED save, whose UPDATE coalesces with the "
                        + "later detachClient() mutation on the SAME managed entity at flush; and the "
                        + "notification_outbox INSERT from enqueueClientCancelledPerVisit's "
                        + "outboxService.enqueueClientCancelled(representativeId) — each of these "
                        + "standalone bookings has no appointmentId, so visitKey() (= coalesce"
                        + "(appointmentId, id)) is distinct per booking and every one is its own "
                        + "\"visit\", enqueueing its own row rather than sharing one across the batch "
                        + "— the ordinary per-booking cancelBooking(UUID, UUID, CancelBookingRequest) "
                        + "entry point this replaces would add a 5-join findByIdWithFullGraph ON TOP "
                        + "of that, i.e. 4.0/booking; a regression that reintroduces a per-booking "
                        + "graph load pushes this back up by a full extra statement per booking, so "
                        + "3.5 cleanly separates the fixed (3.0) and regressed (4.0) shapes")
                .isLessThanOrEqualTo(3.5);
    }

    @Test
    @DisplayName("perf audit item 4: an EXACTLY-at-cap (50) future-booking self-delete completes "
            + "comfortably under HikariCP's 10s leak-detection-threshold (application.yml)")
    void should_completeWellUnderLeakDetectionThreshold_when_fiftyFutureBookingsSelfDelete() throws Exception {
        UUID clientId = csd.createClient();
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        for (int i = 0; i < ClientAccountDeletionService.MAX_FUTURE_BOOKINGS_PER_SELF_DELETE; i++) {
            csd.insertBooking(clientId, salon, "CONFIRMED", BASE.plusHours(2L * i));
        }
        String token = fixtures.tokenFor(emailOf(clientId));

        long startNanos = System.nanoTime();
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        log.info("Phase 338 perf audit item 4 — a {}-future-booking self-delete completed in {} ms",
                ClientAccountDeletionService.MAX_FUTURE_BOOKINGS_PER_SELF_DELETE, elapsedMs);

        assertThat(elapsedMs)
                .as("comfortably under HikariCP's 10s leak-detection-threshold (application.yml:15) "
                        + "— if this ever approaches it, MAX_FUTURE_BOOKINGS_PER_SELF_DELETE must be "
                        + "lowered, or the threshold raised with a comment explaining why")
                .isLessThan(5_000L);
    }

    /**
     * Self-deletes a fresh client owning {@code bookingCount} distinct future {@code CONFIRMED}
     * standalone bookings (all with the SAME master, 2h apart so {@code no_overlapping_bookings}
     * never trips), and returns the number of JDBC statements the WHOLE {@code DELETE
     * /api/v1/users/me} call issued.
     */
    private long selfDeleteAndCountStatements(int bookingCount) throws Exception {
        UUID clientId = csd.createClient();
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        for (int i = 0; i < bookingCount; i++) {
            csd.insertBooking(clientId, salon, "CONFIRMED", BASE.plusHours(2L * i));
        }
        String token = fixtures.tokenFor(emailOf(clientId));
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
}
