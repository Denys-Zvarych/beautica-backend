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
 * Real-DB regression coverage for self-delete's {@code notification_outbox} coherence (sibling of
 * {@link ClientAccountHardDeleteIT}, same raw-SQL fixture conventions via
 * {@link ClientSelfDeleteTestFixtures}).
 *
 * <p><b>Phase 338 rewrite (was the orphan-row bug regression guard).</b> Originally,
 * {@code ClientAccountDeletionService#deleteOwnAccount} cancelled every future {@code CONFIRMED}
 * booking (step 4) — each {@code cancelBooking} call enqueues a {@code STATUS_CHANGED} row keyed
 * on the booking id — and then HARD-DELETED those same booking rows (step 5) in the SAME
 * transaction, orphaning the just-enqueued outbox row. Phase 338 reverses the hard-delete: future
 * bookings are now KEPT (cancelled, detached), so the orphan-row failure mode this class used to
 * regression-guard can no longer occur on this path at all. What still matters, and what this class
 * now proves instead: exactly ONE {@code CLIENT_CANCELLED} row per visit is enqueued, pointing at a
 * booking that GENUINELY SURVIVES — so the drain worker resolves and dispatches it cleanly, never
 * dead-lettering.
 *
 * <p><b>Perf audit (2026-09, item 3, LOW — folded into this rewrite).</b> The future-booking cancel
 * cascade is now routed through {@code BookingService#cancelFutureConfirmedBookingsForClientSelfDelete}
 * / {@code #cancelBookingForBatch}, which never calls {@code outboxService.enqueueStatusChanged} at
 * all (unlike the ordinary per-booking {@code cancelBooking} entry point) — so there is no longer a
 * per-booking {@code STATUS_CHANGED} row to write-then-delete for THIS booking. Item 1 of the same
 * audit (security, LOW) flagged that the old delete-then-enqueue step used a scope-free {@code
 * notificationOutboxRepository.deleteByAggregateIdIn(futureBookingIds)} with no event-type filter,
 * which — now that future bookings survive instead of being hard-deleted — would have ALSO
 * destroyed any OTHER still-PENDING outbox row addressed to one of these same bookings (e.g. an
 * undrained {@code BOOKING_RESCHEDULED}). That delete call is gone entirely (resolved by
 * construction, item 3's fix), so the "scoping" proof below now asserts something stronger than
 * "did the delete respect its own IN-list": a PENDING, unrelated row on the very booking being
 * self-delete-cancelled must survive completely untouched, because nothing in this path deletes
 * from {@code notification_outbox} any more.
 *
 * <p>{@link NotificationService} is mocked (mirrors {@code NotificationOutboxIntegrationTest} /
 * {@code ClosureReminderOutboxIT}) so the drain calls below exercise the REAL outbox
 * claim/hydrate/persist machinery without SMTP/Firebase actually firing.
 */
@DisplayName("DELETE /api/v1/users/me — notification_outbox stays coherent after self-delete "
        + "(Phase 338: STATUS_CHANGED superseded by one CLIENT_CANCELLED per visit)")
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
    @DisplayName("Phase 338: self-delete enqueues exactly one CLIENT_CANCELLED row pointing at the "
            + "still-existing (kept) booking; a PRE-EXISTING PENDING non-STATUS_CHANGED row on that "
            + "SAME future booking survives untouched (item 1); the drain runs clean with no DEAD "
            + "row; another client's row and this client's own detached-past-booking row are "
            + "untouched and remain dispatchable")
    void should_leaveOutboxCoherent_when_clientSelfDeletesWithFutureAndPastBookings() throws Exception {
        // Arrange — clientA: one future CONFIRMED booking (self-delete cancels and KEEPS it, Phase
        // 338 — the row this test now proves resolves to exactly one CLIENT_CANCELLED entry) and
        // one past COMPLETED booking (self-delete only DETACHES it — the pre-existing
        // REVIEW_REQUESTED row pointing at it is the "sibling case": it must survive, untouched,
        // exactly as it was).
        UUID clientAId = csd.createClient();
        String clientAToken = fixtures.tokenFor(emailOf(clientAId));
        ClientSelfDeleteTestFixtures.Salon salonA = csd.createSalon();
        UUID futureBookingId = csd.insertBooking(clientAId, salonA, "CONFIRMED", FUTURE);
        UUID pastBookingId = csd.insertBooking(clientAId, salonA, "COMPLETED", PAST);
        UUID pastBookingOutboxId = enqueue(OutboxEventType.REVIEW_REQUESTED, pastBookingId);

        // Perf audit item 1 (security, LOW): a PRE-EXISTING, still-PENDING, non-STATUS_CHANGED row
        // that already points at the SAME future booking self-delete is about to cancel — e.g. an
        // undrained BOOKING_RESCHEDULED notification from an earlier reschedule of this booking.
        // The old cascade's scope-free deleteByAggregateIdIn(futureBookingIds) would have swept this
        // row away too, even though it has nothing to do with the cancellation just performed. Now
        // that the batched cancel path (item 3) never deletes from notification_outbox at all, this
        // row must survive completely untouched — proven below alongside the new CLIENT_CANCELLED
        // row landing on the SAME booking.
        UUID futureBookingReschedOutboxId = enqueue(OutboxEventType.BOOKING_RESCHEDULED, futureBookingId);

        // clientB — an unrelated account with its own salon, its own future booking, and its own
        // pre-existing PENDING outbox row. clientA's self-delete must be scoped to clientA's own
        // bookings only — this proves it is not a blunt sweep of the whole outbox table.
        UUID clientBId = csd.createClient();
        ClientSelfDeleteTestFixtures.Salon salonB = csd.createSalon();
        UUID clientBBookingId = csd.insertBooking(clientBId, salonB, "CONFIRMED", FUTURE);
        UUID clientBOutboxId = enqueue(OutboxEventType.STATUS_CHANGED, clientBBookingId);

        // Act — clientA self-deletes. Step 4 cancels futureBookingId through the batched
        // BookingService#cancelFutureConfirmedBookingsForClientSelfDelete entry point (Phase 338
        // perf fix, item 2/3) — which, unlike the ordinary per-booking cancelBooking entry point,
        // enqueues NO STATUS_CHANGED row of its own; step 5 enqueues exactly one CLIENT_CANCELLED
        // row for the same (now KEPT, not deleted) booking, without deleting anything first.
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientAToken)), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Assert 1 — the future booking itself survives, cancelled and detached (Phase 338).
        assertThat(csd.bookingExists(futureBookingId))
                .as("Phase 338 — the future booking is KEPT, never hard-deleted")
                .isTrue();

        // Assert 2 — TWO outbox rows now point at the future booking: the pre-existing
        // BOOKING_RESCHEDULED row (untouched — item 1) and the freshly-enqueued CLIENT_CANCELLED
        // row. There is no STATUS_CHANGED row to supersede any more — the batched cancel path never
        // writes one (item 3).
        assertThat(countByAggregateId(futureBookingId))
                .as("the pre-existing BOOKING_RESCHEDULED row must survive untouched, alongside the "
                        + "new CLIENT_CANCELLED row for the (single-booking) visit — no STATUS_CHANGED "
                        + "row was ever written by the batched cancel path to begin with")
                .isEqualTo(2);
        assertThat(outboxRepository.findById(futureBookingReschedOutboxId))
                .as("item 1 — the pre-existing, unrelated PENDING row on the SAME future booking "
                        + "must survive self-delete completely untouched (no scope-free delete runs "
                        + "any more on this path)")
                .isPresent();
        assertThat(eventTypesOf(futureBookingId))
                .as("the future booking's two surviving rows are exactly the pre-existing "
                        + "BOOKING_RESCHEDULED row and the new CLIENT_CANCELLED row")
                .containsExactlyInAnyOrder(
                        OutboxEventType.BOOKING_RESCHEDULED.name(), OutboxEventType.CLIENT_CANCELLED.name());

        // Assert (scoping) — the other client's row and this client's own past-booking row must
        // be completely untouched by clientA's self-delete.
        assertThat(outboxRepository.findById(clientBOutboxId))
                .as("clientB's own outbox row must not be swept by clientA's self-delete")
                .isPresent();
        assertThat(outboxRepository.findById(pastBookingOutboxId))
                .as("the detached (not deleted) past booking's pre-existing outbox row must survive")
                .isPresent();

        // Assert 3 — draining now must not throw and must not dead-letter anything. The future
        // booking's CLIENT_CANCELLED (and pre-existing BOOKING_RESCHEDULED) rows resolve against a
        // genuinely surviving (detached) booking row, exactly like the past booking's
        // REVIEW_REQUESTED row already did before Phase 338.
        assertThatCode(() -> {
            drainWorker.drain();
            drainWorker.drain();
            drainWorker.drain();
            drainWorker.drain();
        }).doesNotThrowAnyException();

        assertThat(countByStatus("DEAD"))
                .as("no row may dead-letter")
                .isZero();
        assertThat(lastErrorMentionsMissingBooking())
                .as("no row's last_error records the drain worker's IllegalStateException for a "
                        + "missing booking")
                .isFalse();

        // Every surviving row must be genuinely dispatchable — real BookingVisit / Booking
        // resolution against their still-existing (detached, in clientA's case) booking rows
        // succeeded, flipping every one of them to SENT rather than leaving them stuck retrying.
        assertThat(statusOf(clientBOutboxId))
                .as("clientB's row must have dispatched normally")
                .isEqualTo("SENT");
        assertThat(statusOf(pastBookingOutboxId))
                .as("the detached past booking's row must still resolve and dispatch normally")
                .isEqualTo("SENT");
        assertThat(statusOf(futureBookingReschedOutboxId))
                .as("item 1 — the surviving pre-existing BOOKING_RESCHEDULED row must still resolve "
                        + "and dispatch normally against the now-KEPT (detached) booking row")
                .isEqualTo("SENT");
        assertThat(statusesOfAggregate(futureBookingId))
                .as("both of the future booking's surviving rows must dispatch to SENT")
                .containsExactly("SENT", "SENT");
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

    private List<String> eventTypesOf(UUID aggregateId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM notification_outbox WHERE aggregate_id = ? ORDER BY event_type",
                String.class, aggregateId);
    }

    private List<String> statusesOfAggregate(UUID aggregateId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM notification_outbox WHERE aggregate_id = ? ORDER BY event_type",
                String.class, aggregateId);
    }
}
