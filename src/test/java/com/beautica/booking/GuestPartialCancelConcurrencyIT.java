package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.GuestPartialCancelSupport.Visit;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * Phase 436 — concurrency of the guest partial cancel by link. Two real racers on one token: the header lock
 * serialises them, so every line is cancelled exactly once and the outbox / in-app / token state is consistent with
 * the requests that won. A deterministic interleaving then moves a line out of {@code CONFIRMED} on its own
 * connection between the line read and the conditional UPDATE — the window only the UPDATE's {@code status} guard
 * and the row-count check close (mutation-tested).
 *
 * <p>Same bean overrides as {@code GuestVisitLinkParityIT} ({@link SmsService} mock + {@link BookingRepository} spy),
 * so it shares that cached context. No {@code @Transactional}: each request commits on its own.
 */
@DisplayName("Phase 436 — guest partial cancel concurrency: each line cancelled once, guard closes the read→UPDATE gap")
class GuestPartialCancelConcurrencyIT extends AbstractIntegrationTest {

    private static final List<String> NAMES = List.of("Масаж-436", "Обгортання-436", "Пілінг-436");
    private static final int DAYS_AHEAD = 3;
    private static final int RACERS = 2;
    private static final int RACE_TIMEOUT_S = 30;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GuestTokenProvider guestTokenProvider;
    @Autowired private Clock clock;
    /** A connection OUTSIDE the request's transaction, so the interleaved write commits mid-request. */
    @Autowired private DataSource dataSource;

    @MockBean private SmsService smsService;
    @MockitoSpyBean private BookingRepository bookingRepository;

    private GuestPartialCancelSupport guest;
    private Visit visit;

    @BeforeEach
    void bookVisit() throws Exception {
        guest = new GuestPartialCancelSupport(
                restTemplate, objectMapper, guestTokenProvider, jdbcTemplate, passwordEncoder, clock);
        String slug = guest.masterSlug("race");
        visit = guest.bookVisit(slug, guest.services(slug, NAMES), GuestPartialCancelSupport.GUEST_PHONE, DAYS_AHEAD);
    }

    @Test
    @DisplayName("two OVERLAPPING partial cancels ([A,B] vs [B,C]) race: one 200, one 422; B cancelled once, "
            + "exactly one outbox row and one in-app row, the visit stays live")
    void should_cancelEachLineOnce_when_overlappingPartialCancelsRace() throws Exception {
        List<HttpStatus> outcomes = race(List.of(visit.line(0), visit.line(1)), List.of(visit.line(1), visit.line(2)));

        assertThat(outcomes).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNPROCESSABLE_ENTITY);
        List<String> statuses = guest.statuses(visit);
        assertThat(statuses.get(1)).as("the shared line is cancelled").isEqualTo("CANCELLED");
        assertThat(statuses).as("only the winner's two lines").containsOnlyOnce("CONFIRMED");
        assertThat(cancelOutboxRows()).as("one request won → one e-mail row").isEqualTo(1);
        assertThat(cancelInAppRows()).isEqualTo(1);
        assertThat(guest.headerStatus(visit.appointmentId())).isEqualTo("CONFIRMED");
        assertThat(guest.headerHoldsToken(visit.appointmentId())).isTrue();
    }

    @Test
    @DisplayName("two DISJOINT partial cancels ([A,B] vs [C]) that together end the visit both win; the header is "
            + "consumed once and each request has its own outbox + in-app row")
    void should_consumeHeaderOnce_when_disjointPartialCancelsRaceToEndVisit() throws Exception {
        List<HttpStatus> outcomes = race(List.of(visit.line(0), visit.line(1)), List.of(visit.line(2)));

        assertThat(outcomes).containsExactly(HttpStatus.OK, HttpStatus.OK);
        assertThat(guest.statuses(visit)).containsOnly("CANCELLED");
        assertThat(guest.headerStatus(visit.appointmentId())).isEqualTo("CANCELLED");
        assertThat(guest.headerHoldsToken(visit.appointmentId())).as("token consumed").isFalse();
        assertThat(cancelOutboxRows()).isEqualTo(2);
        assertThat(cancelInAppRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("a line that leaves CONFIRMED between the line read and the UPDATE makes the request 422 and "
            + "rolls it back — the other line stays CONFIRMED and the moved line is not overwritten")
    void should_return422AndRollBack_when_lineLeavesConfirmedBeforeUpdate() throws Exception {
        UUID moved = visit.line(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        // Spring Data repositories are interface proxies: forward to the genuine repository, nothing is stubbed.
        Answer<?> realRepository = OverlapRaceSupport.forwardingAnswerOf(bookingRepository);
        doAnswer(inv -> {
            Object lines = realRepository.answer(inv);
            if (armed.getAndSet(false)) {
                completeOnOwnConnection(moved);
            }
            return lines;
        }).when(bookingRepository).findByAppointmentIdWithGraph(any());

        HttpStatus outcome = HttpStatus.valueOf(
                guest.post(visit.token(), List.of(visit.line(0), moved)).getStatusCode().value());

        verify(bookingRepository).cancelGuestVisitItemsByIds(any(), any(), any());
        assertThat(armed).as("the interleaving fired").isFalse();
        assertThat(outcome).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(guest.statuses(visit)).containsExactly("CONFIRMED", "COMPLETED", "CONFIRMED");
        assertThat(cancelOutboxRows()).as("rolled back → no e-mail row").isZero();
        assertThat(guest.headerHoldsToken(visit.appointmentId())).isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private List<HttpStatus> race(List<UUID> first, List<UUID> second) throws InterruptedException {
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(RACERS);
        var outcomes = new ConcurrentLinkedQueue<HttpStatus>();
        for (List<UUID> ids : List.of(first, second)) {
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    outcomes.add(HttpStatus.valueOf(guest.post(visit.token(), ids).getStatusCode().value()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertThat(done.await(RACE_TIMEOUT_S, TimeUnit.SECONDS)).as("both racers finish").isTrue();
        assertThat(outcomes).as("both racers got a response").hasSize(RACERS);
        return List.copyOf(outcomes);
    }

    private void completeOnOwnConnection(UUID bookingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE bookings SET status = 'COMPLETED' WHERE id = ?")) {
            connection.setAutoCommit(true);
            update.setObject(1, bookingId);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private int cancelOutboxRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'CLIENT_CANCELLED'", Integer.class);
    }

    private int cancelInAppRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE type = 'BOOKING_CANCELLED_BY_CLIENT' "
                        + "AND appointment_id = ?", Integer.class, visit.appointmentId());
    }
}
