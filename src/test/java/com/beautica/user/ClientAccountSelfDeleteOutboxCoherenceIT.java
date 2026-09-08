package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Real-DB regression coverage for the self-delete orphan-{@code notification_outbox}-row bug
 * (sibling of {@link ClientAccountHardDeleteIT}, same raw-SQL fixture conventions via
 * {@link ClientSelfDeleteTestFixtures}).
 *
 * <p><b>The bug.</b> {@code ClientAccountDeletionService#deleteOwnAccount} cancels every future
 * {@code CONFIRMED} booking (step 4) — each {@code cancelBooking} call enqueues a
 * {@code STATUS_CHANGED} row keyed on the booking id — and then hard-deletes those same booking
 * rows (step 5) in the SAME transaction. {@code notification_outbox.aggregate_id} carries no FK to
 * {@code bookings} (V32), so nothing at the DB level stopped the delete from orphaning the just-
 * enqueued row. After commit the drain worker claimed it, {@code getBooking()} found nothing,
 * threw {@code IllegalStateException}, and three retries later the row was DEAD.
 *
 * <p><b>The fix.</b> {@code NotificationOutboxRepository#deleteByAggregateIdIn} removes the
 * just-enqueued rows for the about-to-be-deleted booking ids immediately before the booking rows
 * themselves go (same transaction, same step).
 *
 * <p>{@link NotificationService} is mocked (mirrors {@code NotificationOutboxIntegrationTest} /
 * {@code ClosureReminderOutboxIT}) so the drain calls below exercise the REAL outbox
 * claim/hydrate/persist machinery — the fix under test — without SMTP/Firebase actually firing.
 */
@DisplayName("DELETE /api/v1/users/me — notification_outbox stays coherent after self-delete (orphan-row regression)")
class ClientAccountSelfDeleteOutboxCoherenceIT extends AbstractIntegrationTest {

    private static final OffsetDateTime FUTURE = OffsetDateTime.now().plusDays(7);
    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @MockBean
    private NotificationService notificationService;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    @Test
    @DisplayName("no orphan outbox row survives for the cancelled+deleted future booking; the drain "
            + "runs clean afterwards with no DEAD row and no 'Booking not found' error; another "
            + "client's row and this client's own detached-past-booking row are untouched and "
            + "remain dispatchable")
    void should_leaveOutboxCoherent_when_clientSelfDeletesWithFutureAndPastBookings() throws Exception {
        // Arrange — clientA: one future CONFIRMED booking (self-delete cancels THEN hard-deletes
        // it — this is the row that must not orphan) and one past COMPLETED booking (self-delete
        // only DETACHES it — the pre-existing REVIEW_REQUESTED row pointing at it is the "sibling
        // case": it must survive, untouched, exactly as it was).
        UUID clientAId = csd.createClient();
        String clientAToken = fixtures.tokenFor(emailOf(clientAId));
        ClientSelfDeleteTestFixtures.Salon salonA = csd.createSalon();
        UUID futureBookingId = csd.insertBooking(clientAId, salonA, "CONFIRMED", FUTURE);
        UUID pastBookingId = csd.insertBooking(clientAId, salonA, "COMPLETED", PAST);
        UUID pastBookingOutboxId = enqueue(OutboxEventType.REVIEW_REQUESTED, pastBookingId);

        // clientB — an unrelated account with its own salon, its own future booking, and its own
        // pre-existing PENDING outbox row. clientA's self-delete must be scoped to clientA's own
        // bookings only — this proves it is not a blunt sweep of the whole outbox table.
        UUID clientBId = csd.createClient();
        ClientSelfDeleteTestFixtures.Salon salonB = csd.createSalon();
        UUID clientBBookingId = csd.insertBooking(clientBId, salonB, "CONFIRMED", FUTURE);
        UUID clientBOutboxId = enqueue(OutboxEventType.STATUS_CHANGED, clientBBookingId);

        // Act — clientA self-deletes. Step 4 cancels futureBookingId through the real
        // BookingService (enqueueing its OWN STATUS_CHANGED row); step 5 must remove that row
        // before hard-deleting futureBookingId in the same transaction.
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientAToken)), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Assert 1 — no outbox row survives pointing at the hard-deleted future booking. This is
        // the primary regression assertion: it fails immediately (before any drain) if the fix
        // regresses, because the orphaned row would still exist right here.
        assertThat(countByAggregateId(futureBookingId))
                .as("orphan STATUS_CHANGED row for the hard-deleted future booking")
                .isZero();

        // Assert (scoping) — the other client's row and this client's own past-booking row must
        // be completely untouched by clientA's self-delete.
        assertThat(outboxRepository.findById(clientBOutboxId))
                .as("clientB's own outbox row must not be swept by clientA's self-delete")
                .isPresent();
        assertThat(outboxRepository.findById(pastBookingOutboxId))
                .as("the detached (not deleted) past booking's pre-existing outbox row must survive")
                .isPresent();

        // Assert 2 — draining now must not throw and must not dead-letter anything. Three calls
        // (== NotificationOutboxDrainWorker.MAX_ATTEMPTS) is the exact number that would turn a
        // still-orphaned row DEAD if the fix regressed: claim -> IllegalStateException caught
        // internally -> back to PENDING (attempts=1) -> reclaimed -> PENDING (attempts=2) ->
        // reclaimed -> DEAD (attempts=3). The exception itself never escapes drain() (it is
        // caught in NotificationOutboxDrainWorker#dispatchAll and recorded as a DEAD/PENDING
        // outcome instead) — the DEAD row and its last_error are the observable defect.
        assertThatCode(() -> {
            drainWorker.drain();
            drainWorker.drain();
            drainWorker.drain();
        }).doesNotThrowAnyException();

        assertThat(countByStatus("DEAD"))
                .as("no row may dead-letter — in particular not the future booking's orphan, which "
                        + "must not exist to be claimed in the first place")
                .isZero();
        assertThat(lastErrorMentionsMissingBooking())
                .as("no row's last_error records the drain worker's IllegalStateException for a "
                        + "missing booking")
                .isFalse();

        // The two surviving rows must still be genuinely dispatchable — real BookingVisit / Booking
        // resolution against their still-existing (detached, in clientA's case) booking rows
        // succeeded, flipping both to SENT rather than leaving them stuck retrying.
        assertThat(statusOf(clientBOutboxId))
                .as("clientB's row must have dispatched normally")
                .isEqualTo("SENT");
        assertThat(statusOf(pastBookingOutboxId))
                .as("the detached past booking's row must still resolve and dispatch normally")
                .isEqualTo("SENT");
    }

    private UUID enqueue(OutboxEventType eventType, UUID aggregateId) {
        NotificationOutboxEntry entry = NotificationOutboxEntry.builder()
                .eventType(eventType)
                .aggregateId(aggregateId)
                .build();
        return outboxRepository.saveAndFlush(entry).getId();
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private long countByAggregateId(UUID aggregateId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE aggregate_id = ?", Long.class, aggregateId);
        return count == null ? -1 : count;
    }

    private long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE status = ?", Long.class, status);
        return count == null ? -1 : count;
    }

    private boolean lastErrorMentionsMissingBooking() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE last_error LIKE '%Booking not found%'",
                Integer.class);
        return count != null && count > 0;
    }

    private String statusOf(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM notification_outbox WHERE id = ?", String.class, id);
    }
}
