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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Real-DB regression coverage for outbox coherence on the STAFF/MASTER self-delete track (sibling
 * of {@link ClientAccountSelfDeleteOutboxCoherenceIT}). Since Phase 337,
 * {@code BookingService#disposeFutureConfirmedForMasterSelfDelete} DECLINES and KEEPS every future
 * CONFIRMED booking (no hard delete) and enqueues one {@code MASTER_REMOVED} outbox row per visit.
 * A PRE-EXISTING outbox row for such a booking (e.g. a reminder enqueued before the master
 * self-deleted) therefore stays valid: the booking still exists, so the drain worker must resolve
 * and dispatch both rows without dead-lettering anything or hitting "Booking not found".
 */
@DisplayName("DELETE /api/v1/users/me — notification_outbox stays coherent after a SALON_MASTER "
        + "self-delete (Phase 337, future bookings declined and kept)")
class StaffAccountSelfDeleteOutboxCoherenceIT extends AbstractIntegrationTest {

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
    @DisplayName("the future booking is DECLINED and kept with a MASTER_REMOVED row beside its "
            + "pre-existing row; the drain runs clean with no DEAD row; another master's row and "
            + "this master's own detached-past-booking outbox row are untouched and remain dispatchable")
    void should_keepFutureBookingDeclinedWithMasterRemovedRow_when_masterSelfDeletes() throws Exception {
        // Arrange — masterA: one future CONFIRMED booking with a PRE-EXISTING outbox row (the cascade
        // adds a MASTER_REMOVED row beside it) and one past COMPLETED
        // booking (DETACHED, not deleted — its own pre-existing outbox row is the "sibling case").
        ClientSelfDeleteTestFixtures.Salon salonA = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID futureBookingId = csd.insertBooking(clientId, salonA, "CONFIRMED", FUTURE);
        UUID futureBookingOutboxId = enqueue(OutboxEventType.STATUS_CHANGED, futureBookingId);
        UUID pastBookingId = csd.insertBooking(clientId, salonA, "COMPLETED", PAST);
        UUID pastBookingOutboxId = enqueue(OutboxEventType.REVIEW_REQUESTED, pastBookingId);

        // masterB — an unrelated salon with its own future booking and its own pre-existing PENDING
        // outbox row. masterA's self-delete must be scoped to masterA's own bookings only.
        ClientSelfDeleteTestFixtures.Salon salonB = csd.createSalon();
        UUID clientBId = csd.createClient();
        UUID masterBBookingId = csd.insertBooking(clientBId, salonB, "CONFIRMED", FUTURE);
        UUID masterBOutboxId = enqueue(OutboxEventType.STATUS_CHANGED, masterBBookingId);

        String masterAToken = fixtures.tokenFor(emailOf(salonA.masterUserId()));

        // Act — masterA self-deletes. disposeFutureConfirmedForMasterSelfDelete bulk-declines
        // futureBookingId (kept, not deleted) and enqueues one MASTER_REMOVED row for the visit.
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(masterAToken)), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Assert 1 — the future booking is kept as DECLINED, with exactly the pre-existing
        // STATUS_CHANGED row plus the cascade's one MASTER_REMOVED row (standalone booking: one visit).
        assertThat(bookingStatusOf(futureBookingId))
                .as("the future booking must be declined and kept, not hard-deleted")
                .isEqualTo("DECLINED");
        assertThat(eventTypesByAggregateId(futureBookingId))
                .as("pre-existing STATUS_CHANGED row plus one MASTER_REMOVED row for the visit")
                .containsExactlyInAnyOrder("STATUS_CHANGED", "MASTER_REMOVED");

        // Assert (scoping) — the other master's row and this master's own past-booking row must be
        // completely untouched.
        assertThat(outboxRepository.findById(masterBOutboxId))
                .as("masterB's own outbox row must not be swept by masterA's self-delete")
                .isPresent();
        assertThat(outboxRepository.findById(pastBookingOutboxId))
                .as("the detached (not deleted) past booking's pre-existing outbox row must survive")
                .isPresent();

        // Assert 2 — draining now must not throw and must not dead-letter anything.
        assertThatCode(() -> {
            drainWorker.drain();
            drainWorker.drain();
            drainWorker.drain();
        }).doesNotThrowAnyException();

        assertThat(countByStatus("DEAD"))
                .as("no row may dead-letter — the future booking still exists for both of its rows")
                .isZero();
        assertThat(lastErrorMentionsMissingBooking())
                .as("no row's last_error records a drain-worker IllegalStateException for a missing "
                        + "booking")
                .isFalse();

        assertThat(statusOf(masterBOutboxId))
                .as("masterB's row must have dispatched normally")
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

    private List<String> eventTypesByAggregateId(UUID aggregateId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM notification_outbox WHERE aggregate_id = ?", String.class, aggregateId);
    }

    private String bookingStatusOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
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
