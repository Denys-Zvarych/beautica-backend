package com.beautica.booking;

import com.beautica.auth.Role;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.job.BookingReminderJob;
import com.beautica.booking.job.RegisteredBookingReminderSweep;
import com.beautica.booking.service.BookingService;
import com.beautica.common.TimeZones;
import com.beautica.config.FirebaseConfig;
import com.beautica.notification.inapp.push.BookingReminderPushExpectedCopy;
import com.beautica.notification.inapp.push.PushTestSupport;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.support.MutableTestClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.beautica.notification.inapp.push.FcmMessageFields.bodyOf;
import static com.beautica.notification.inapp.push.FcmMessageFields.dataOf;
import static com.beautica.notification.inapp.push.FcmMessageFields.titleOf;
import static com.beautica.notification.inapp.push.FcmMessageFields.tokenOf;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Phase 495 (NTF-48) — the registered 24 h reminder also writes ONE {@code BOOKING_REMINDER} feed row for the
 * booking's client, in the claim transaction, and (FIREBASE_ENABLED=true) exactly one {@code INAPP_PUSH} outbox row
 * through the feed fan-out (NTF-12) — never from the e-mail outbox row (NTF-S10). Phase 522 — the 2 h reminder does
 * the same under a {@code …:H2} key, behind the 2 h leg's night / short-notice gates.
 *
 * <p>Real sweeps, real write path, real {@code GET /api/v1/notifications}; only the FCM edge is a mock. The clock is
 * the shared {@link GuestTwoHourReminderIT.ClockConfig} (12:00 Kyiv on the real current date). The flag-on context
 * is its own cached context (FIREBASE_ENABLED differs from every other reminder IT).
 */
@TestPropertySource(properties = {"FIREBASE_ENABLED=true", "app.booking.reminder-2h.enabled=true"})
@Import({GuestTwoHourReminderIT.ClockConfig.class, PushTestSupport.class})
@DisplayName("BookingReminderFeedIT — phase 495 BOOKING_REMINDER feed row + push")
class BookingReminderFeedIT extends AbstractStaffBookingIT {

    /** 12:00 Kyiv on the real current date — the same instant {@link GuestTwoHourReminderIT.ClockConfig} starts at. */
    private static final Instant NOW = java.time.LocalDate.now(com.beautica.common.TimeZones.KYIV)
            .atTime(12, 0).atZone(com.beautica.common.TimeZones.KYIV).toInstant();

    /** Short slot so two same-master CONFIRMED bookings fit the 30-minute 2 h window without overlapping. */
    private static final int SHORT_SLOT_MINUTES = 10;

    @Autowired private FirebaseConfig.FirebaseSender firebaseSender; // Mockito mock, PushTestSupport

    @Autowired private BookingReminderJob reminderJob;
    @Autowired private RegisteredBookingReminderSweep registeredSweep;
    @Autowired private BookingService bookingService;
    @Autowired private com.beautica.booking.repository.BookingRepository bookingRepository;
    @Autowired private MutableTestClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private NotificationOutboxDrainWorker drainWorker;
    @Autowired private javax.sql.DataSource dataSource;

    private SeededUser client;

    @BeforeEach
    void seedClient() {
        clock.set(NOW);
        client = insertUser("CLIENT", null);
        // The push twin enqueues INAPP_PUSH only for a recipient with an active device token.
        PushTestSupport.registerAndroidDevice(jdbcTemplate, client.id(), "reminder-feed-token-" + client.id());
    }

    @AfterEach
    void restoreClock() {
        clock.set(NOW);
    }

    @Test
    @DisplayName("24 h reminder claimed → one BOOKING_REMINDER row for the client, booking-keyed, start-keyed dedup")
    void should_writeOneFeedRowForClient_when_24hReminderClaimed() {
        // Arrange
        OffsetDateTime start = at(NOW.plus(Duration.ofHours(24)));
        UUID bookingId = insertAppBooking(start, NOW.minus(Duration.ofDays(2)));

        // Act
        reminderJob.sendReminders();

        // Assert
        assertThat(jdbcTemplate.queryForList("""
                SELECT recipient_user_id, booking_id, appointment_id, salon_id, dedup_key
                  FROM in_app_notification WHERE type = 'BOOKING_REMINDER'
                """)).singleElement().satisfies(row -> {
            assertThat(row.get("recipient_user_id")).isEqualTo(client.id());
            assertThat(row.get("booking_id")).isEqualTo(bookingId);
            assertThat(row.get("appointment_id")).isNull();
            assertThat(row.get("salon_id")).isEqualTo(salon.salonId());
            assertThat(row.get("dedup_key")).isEqualTo("BOOKING_REMINDER:" + bookingId + ":" + start.toEpochSecond());
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM in_app_notification WHERE recipient_user_id <> ?", Long.class, client.id()))
                .as("never a provider row").isZero();
    }

    @Test
    @DisplayName("FIREBASE_ENABLED=true → exactly one INAPP_PUSH row, for the feed row; the e-mail row stays one")
    void should_writePushRow_when_firebaseEnabled() {
        // Arrange
        insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));

        // Act
        reminderJob.sendReminders();

        // Assert
        UUID feedRowId = jdbcTemplate.queryForObject(
                "SELECT id FROM in_app_notification WHERE type = 'BOOKING_REMINDER'", UUID.class);
        assertThat(jdbcTemplate.queryForList(
                "SELECT aggregate_id FROM notification_outbox WHERE event_type = 'INAPP_PUSH'", UUID.class))
                .containsExactly(feedRowId);
        assertThat(outboxCount("BOOKING_REMINDER")).isOne();
    }

    @Test
    @DisplayName("two bookings claimed in ONE tick (two clients) → two BOOKING_REMINDER rows, each its own client, key "
            + "and exactly one INAPP_PUSH row (bulk write, audit-fix cycle 2)")
    void should_writeOneRowAndOnePushPerBooking_when_twoBookingsClaimedInOneTick() {
        // Arrange
        SeededUser secondClient = insertUser("CLIENT", null);
        PushTestSupport.registerAndroidDevice(jdbcTemplate, secondClient.id(), "reminder-feed-token-" + secondClient.id());
        // Both inside the 24 h window [23h, 25h]; 60-minute slots that do not overlap (same master).
        OffsetDateTime firstStart = at(NOW.plus(Duration.ofMinutes(24 * 60 + 30)));
        OffsetDateTime secondStart = at(NOW.plus(Duration.ofMinutes(23 * 60 + 10)));
        UUID first = insertAppBooking(client.id(), firstStart, NOW.minus(Duration.ofDays(2)));
        UUID second = insertAppBooking(secondClient.id(), secondStart, NOW.minus(Duration.ofDays(2)));

        // Act
        reminderJob.sendReminders();

        // Assert
        assertThat(jdbcTemplate.queryForList("""
                SELECT recipient_user_id, booking_id, salon_id, dedup_key
                  FROM in_app_notification WHERE type = 'BOOKING_REMINDER' ORDER BY dedup_key
                """)).extracting(row -> row.get("booking_id") + "|" + row.get("recipient_user_id") + "|"
                        + row.get("salon_id") + "|" + row.get("dedup_key"))
                .containsExactlyInAnyOrder(
                        first + "|" + client.id() + "|" + salon.salonId() + "|BOOKING_REMINDER:" + first + ":"
                                + firstStart.toEpochSecond(),
                        second + "|" + secondClient.id() + "|" + salon.salonId() + "|BOOKING_REMINDER:" + second + ":"
                                + secondStart.toEpochSecond());
        assertThat(jdbcTemplate.queryForList("""
                SELECT count(o.id) FROM in_app_notification n
                  LEFT JOIN notification_outbox o ON o.aggregate_id = n.id AND o.event_type = 'INAPP_PUSH'
                 WHERE n.type = 'BOOKING_REMINDER'
                 GROUP BY n.id
                """, Long.class)).as("push count per reminder feed row").containsExactly(1L, 1L);
        assertThat(outboxCount("INAPP_PUSH")).isEqualTo(2);
        assertThat(outboxCount("BOOKING_REMINDER")).isEqualTo(2);
    }

    @Test
    @DisplayName("B522: 2 h reminder claimed → its e-mail row, one BOOKING_REMINDER row keyed …:H2 for the client and "
            + "exactly one INAPP_PUSH row for it; no provider row; unread count 1")
    void should_writeFeedRowAndPush_when_2hReminderClaimed() {
        // Arrange
        OffsetDateTime start = at(NOW.plus(Duration.ofMinutes(110)));
        UUID bookingId = insertAppBooking(start, NOW.minus(Duration.ofDays(1)));

        // Act
        reminderJob.sweepTwoHour();

        // Assert
        assertThat(outboxCount("BOOKING_REMINDER")).isOne();
        assertThat(jdbcTemplate.queryForList("""
                SELECT recipient_user_id, booking_id, appointment_id, salon_id, dedup_key
                  FROM in_app_notification WHERE type = 'BOOKING_REMINDER'
                """)).singleElement().satisfies(row -> {
            assertThat(row.get("recipient_user_id")).isEqualTo(client.id());
            assertThat(row.get("booking_id")).isEqualTo(bookingId);
            assertThat(row.get("appointment_id")).isNull();
            assertThat(row.get("salon_id")).isEqualTo(salon.salonId());
            assertThat(row.get("dedup_key"))
                    .isEqualTo("BOOKING_REMINDER:" + bookingId + ":" + start.toEpochSecond() + ":H2");
        });
        UUID feedRowId = jdbcTemplate.queryForObject(
                "SELECT id FROM in_app_notification WHERE type = 'BOOKING_REMINDER'", UUID.class);
        assertThat(jdbcTemplate.queryForList(
                "SELECT aggregate_id FROM notification_outbox WHERE event_type = 'INAPP_PUSH'", UUID.class))
                .containsExactly(feedRowId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM in_app_notification WHERE recipient_user_id <> ?", Long.class, client.id()))
                .as("never a provider row").isZero();
        ResponseEntity<String> unread = restTemplate.exchange("/api/v1/notifications/unread-count", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenFor(client.email()))), String.class);
        assertThat(read(unread.getBody()).path("data").path("count").asLong()).isOne();
    }

    @Test
    @DisplayName("B522: one booking reminded at 24 h and then at 2 h → both rows coexist under distinct keys, each with "
            + "exactly one INAPP_PUSH row")
    void should_keepBothRows_when_24hAndH2SameBooking() {
        // Arrange
        OffsetDateTime start = at(NOW.plus(Duration.ofHours(24)));
        UUID bookingId = insertAppBooking(start, NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();

        // Act
        clock.set(NOW.plus(Duration.ofHours(22))); // 10:00 Kyiv the next day, the visit 2 h later
        reminderJob.sweepTwoHour();

        // Assert
        assertThat(jdbcTemplate.queryForList(
                "SELECT dedup_key FROM in_app_notification WHERE type = 'BOOKING_REMINDER'", String.class))
                .containsExactlyInAnyOrder(
                        "BOOKING_REMINDER:" + bookingId + ":" + start.toEpochSecond(),
                        "BOOKING_REMINDER:" + bookingId + ":" + start.toEpochSecond() + ":H2");
        assertThat(jdbcTemplate.queryForList("""
                SELECT count(o.id) FROM in_app_notification n
                  LEFT JOIN notification_outbox o ON o.aggregate_id = n.id AND o.event_type = 'INAPP_PUSH'
                 WHERE n.type = 'BOOKING_REMINDER'
                 GROUP BY n.id
                """, Long.class)).as("push count per reminder feed row").containsExactly(1L, 1L);
    }

    @Test
    @DisplayName("B522: the 2 h sweep replaying (next tick, booking still in the window) adds no second row or push")
    void should_notDuplicate_when_2hSweepReplays() {
        // Arrange
        insertAppBooking(at(NOW.plus(Duration.ofMinutes(125))), NOW.minus(Duration.ofDays(1)));
        reminderJob.sweepTwoHour();

        // Act
        clock.set(NOW.plus(Duration.ofMinutes(15)));
        reminderJob.sweepTwoHour();

        // Assert
        assertThat(feedCount()).isOne();
        assertThat(outboxCount("INAPP_PUSH")).isOne();
        assertThat(outboxCount("BOOKING_REMINDER")).isOne();
    }

    /** Due 2 h bookings the 2 h leg must skip: a Kyiv-night tick, or a booking made less than 3 h before it starts. */
    enum TwoHourSkip { KYIV_NIGHT, SHORT_NOTICE }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TwoHourSkip.class)
    @DisplayName("B522: 2 h reminder skipped (Kyiv night / booked <3 h ahead) → no e-mail, no feed row, no push")
    void should_writeNoRow_when_2hSkippedAtNightOrShortNotice(TwoHourSkip skip) {
        // Arrange
        Instant tick = skip == TwoHourSkip.KYIV_NIGHT ? NOW.plus(Duration.ofMinutes(10 * 60 + 30)) : NOW; // 22:30 Kyiv
        Instant created = skip == TwoHourSkip.SHORT_NOTICE ? tick.minus(Duration.ofMinutes(30))
                : NOW.minus(Duration.ofDays(1));
        insertAppBooking(at(tick.plus(Duration.ofMinutes(110))), created);
        clock.set(tick);

        // Act
        reminderJob.sweepTwoHour();

        // Assert
        assertThat(feedCount()).isZero();
        assertThat(outboxCount("INAPP_PUSH")).isZero();
        assertThat(outboxCount("BOOKING_REMINDER")).isZero();
    }

    @Test
    @DisplayName("B522: rescheduled after the 2 h reminder (B493 re-arm) → the next 2 h window writes a second H2 row "
            + "under the new start's key")
    void should_writeNewH2Row_when_rescheduledAfterReminder() {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofMinutes(110))), NOW.minus(Duration.ofDays(1)));
        reminderJob.sweepTwoHour();
        OffsetDateTime newStart = at(NOW.plus(Duration.ofDays(3)));
        bookingService.rescheduleBooking(salon.ownerId(), Role.SALON_OWNER, bookingId,
                new RescheduleBookingRequest(newStart, false));

        // Act
        clock.set(newStart.toInstant().minus(Duration.ofMinutes(110))); // 10:10 Kyiv on the new date
        reminderJob.sweepTwoHour();

        // Assert
        assertThat(jdbcTemplate.queryForList(
                "SELECT dedup_key FROM in_app_notification WHERE type = 'BOOKING_REMINDER' ORDER BY created_at",
                String.class)).hasSize(2).last()
                .isEqualTo("BOOKING_REMINDER:" + bookingId + ":" + newStart.toEpochSecond() + ":H2");
    }

    @Test
    @DisplayName("B522: the 2 h leg's own transaction failing (outbox insert raises) rolls back its claim, H2 feed row "
            + "and push together (audit-fix 4: the leg is REQUIRES_NEW, so the failure is inside it, not a caller's)")
    void should_rollBackRow_when_2hClaimFails() {
        // Arrange — a test-only trigger makes the leg's last write (the BOOKING_REMINDER outbox row) fail.
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofMinutes(110))), NOW.minus(Duration.ofDays(1)));
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION test_b522_fail_reminder_outbox() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'boom in the 2 h leg'; END $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER test_b522_fail_reminder_outbox BEFORE INSERT ON notification_outbox
                   FOR EACH ROW WHEN (NEW.event_type = 'BOOKING_REMINDER')
                   EXECUTE FUNCTION test_b522_fail_reminder_outbox()
                """);

        // Act
        try {
            assertThatThrownBy(() -> registeredSweep.remindTwoHoursBefore(
                    OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), Duration.ofHours(3)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("boom in the 2 h leg");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_b522_fail_reminder_outbox ON notification_outbox");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_b522_fail_reminder_outbox()");
        }

        // Assert
        assertThat(feedCount()).isZero();
        assertThat(outboxCount("INAPP_PUSH")).isZero();
        assertThat(outboxCount("BOOKING_REMINDER")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT reminder_2h_sent FROM bookings WHERE id = ?", Boolean.class,
                bookingId)).isFalse();
    }

    @Test
    @DisplayName("B522 (acceptance 3): a booking cancelled before the 2 h tick gets no H2 row and no push; the CONFIRMED "
            + "control booking in the same tick does")
    void should_writeNoH2Row_when_bookingCancelledBeforeTwoHourSweep() {
        // Arrange — the cancelled row is inserted first: the overlap EXCLUDE constraint only covers CONFIRMED rows.
        UUID cancelled = insertAppBooking(client.id(), at(NOW.plus(Duration.ofMinutes(120))),
                NOW.minus(Duration.ofDays(1)), SHORT_SLOT_MINUTES);
        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ?", cancelled);
        UUID control = insertAppBooking(client.id(), at(NOW.plus(Duration.ofMinutes(110))),
                NOW.minus(Duration.ofDays(1)), SHORT_SLOT_MINUTES);

        // Act
        reminderJob.sweepTwoHour();

        // Assert
        assertThat(h2FeedBookingIds())
                .as("only the CONFIRMED control (%s), never the cancelled booking %s", control, cancelled)
                .containsExactly(control);
        assertThat(pushCountForBooking(cancelled)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NoFeedShape.class)
    @DisplayName("B522 (acceptance 5): a due guest / walk-in / staff-made / detached booking gets no H2 row and no "
            + "push at the 2 h tick; the APP control booking in the same tick does (NTF-08)")
    void should_writeNoH2Row_when_dueBookingHasNoRegisteredAppClient(NoFeedShape shape) {
        // Arrange — 10-minute slots so both fit the 30-minute 2 h window without tripping the overlap constraint.
        UUID control = insertAppBooking(client.id(), at(NOW.plus(Duration.ofMinutes(110))),
                NOW.minus(Duration.ofDays(1)), SHORT_SLOT_MINUTES);
        UUID excluded = insertNoFeedBooking(shape, at(NOW.plus(Duration.ofMinutes(120))), SHORT_SLOT_MINUTES);

        // Act
        reminderJob.sweepTwoHour();

        // Assert
        assertThat(h2FeedBookingIds())
                .as("only the APP control booking (%s), never %s %s", control, shape, excluded)
                .containsExactly(control);
        assertThat(pushCountForBooking(excluded)).isZero();
    }

    @Test
    @DisplayName("B522 (acceptance 3): a deactivated client's due APP booking gets no H2 feed row and no push; the "
            + "active client's control booking in the same tick does")
    void should_writeNoH2Row_when_clientDeactivated() {
        // Arrange
        SeededUser inactiveClient = insertUser("CLIENT", null);
        PushTestSupport.registerAndroidDevice(jdbcTemplate, inactiveClient.id(), "reminder-feed-token-" + inactiveClient.id());
        UUID inactiveBooking = insertAppBooking(inactiveClient.id(), at(NOW.plus(Duration.ofMinutes(120))),
                NOW.minus(Duration.ofDays(1)), SHORT_SLOT_MINUTES);
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", inactiveClient.id());
        UUID control = insertAppBooking(client.id(), at(NOW.plus(Duration.ofMinutes(110))),
                NOW.minus(Duration.ofDays(1)), SHORT_SLOT_MINUTES);

        // Act
        reminderJob.sweepTwoHour();

        // Assert
        assertThat(h2FeedBookingIds())
                .as("only the active client's control (%s), never the deactivated client's %s", control,
                        inactiveBooking)
                .containsExactly(control);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM in_app_notification WHERE recipient_user_id = ?", Long.class,
                inactiveClient.id())).as("no feed row of any type for the deactivated client").isZero();
        assertThat(pushCountForBooking(inactiveBooking)).isZero();
    }

    @Test
    @DisplayName("B522 audit-fix 2: a deactivated client's due APP booking gets no 24 h feed row and no push; the "
            + "active client's control booking in the same tick does")
    void should_writeNo24hRow_when_clientDeactivated() {
        // Arrange
        SeededUser inactiveClient = insertUser("CLIENT", null);
        PushTestSupport.registerAndroidDevice(jdbcTemplate, inactiveClient.id(), "reminder-feed-token-" + inactiveClient.id());
        // Both inside the 24 h window [now+23h, now+25h]; 10-minute slots so they never overlap.
        UUID inactiveBooking = insertAppBooking(inactiveClient.id(), at(NOW.plus(Duration.ofMinutes(24 * 60 + 30))),
                NOW.minus(Duration.ofDays(2)), SHORT_SLOT_MINUTES);
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", inactiveClient.id());
        UUID control = insertAppBooking(client.id(), at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)),
                SHORT_SLOT_MINUTES);

        // Act
        reminderJob.sendReminders();

        // Assert
        assertThat(jdbcTemplate.queryForList(
                "SELECT booking_id FROM in_app_notification WHERE type = 'BOOKING_REMINDER'", UUID.class))
                .as("only the active client's control (%s), never the deactivated client's %s", control,
                        inactiveBooking)
                .containsExactly(control);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM in_app_notification WHERE recipient_user_id = ?", Long.class,
                inactiveClient.id())).as("no feed row of any type for the deactivated client").isZero();
        assertThat(pushCountForBooking(inactiveBooking)).isZero();
    }

    @Test
    @DisplayName("B522 audit-fix 3: a booking cancelled between the 2 h finder and the registered claim is not "
            + "claimed (flag stays false); the CONFIRMED booking in the same claim is")
    void should_notClaimTwoHour_when_bookingCancelledAfterFinder() {
        // Arrange
        UUID cancelled = insertAppBooking(client.id(), at(NOW.plus(Duration.ofMinutes(120))),
                NOW.minus(Duration.ofDays(1)), SHORT_SLOT_MINUTES);
        UUID confirmed = insertAppBooking(client.id(), at(NOW.plus(Duration.ofMinutes(110))),
                NOW.minus(Duration.ofDays(1)), SHORT_SLOT_MINUTES);
        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ?", cancelled);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Act
        List<UUID> claimed = tx.execute(status ->
                bookingRepository.claimRegisteredTwoHourRemindersByIds(List.of(cancelled, confirmed)));

        // Assert
        assertThat(claimed).containsExactly(confirmed);
        assertThat(jdbcTemplate.queryForObject("SELECT reminder_2h_sent FROM bookings WHERE id = ?", Boolean.class,
                cancelled)).isFalse();
    }

    @Test
    @DisplayName("B522 audit-fix 3: a booking cancelled between the 24 h finder and the registered claim is not "
            + "claimed (flag stays false); the CONFIRMED booking in the same claim is")
    void should_notClaimDayBefore_when_bookingCancelledAfterFinder() {
        // Arrange
        UUID cancelled = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ?", cancelled);
        UUID confirmed = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Act
        List<UUID> claimed = tx.execute(status ->
                bookingRepository.claimRegisteredRemindersByIds(List.of(cancelled, confirmed)));

        // Assert
        assertThat(claimed).containsExactly(confirmed);
        assertThat(jdbcTemplate.queryForObject("SELECT reminder_sent FROM bookings WHERE id = ?", Boolean.class,
                cancelled)).isFalse();
    }

    @Test
    @DisplayName("B522 audit-fix 4: the registered 2 h leg commits its own transaction — called with none open, its "
            + "claim + H2 feed row are durable on return and a separate transaction updates the row without waiting")
    void should_commitOwnTransaction_when_registeredTwoHourLegReturns() {
        // Arrange
        UUID bookingId = insertAppBooking(client.id(), at(NOW.plus(Duration.ofMinutes(120))),
                NOW.minus(Duration.ofDays(1)), SHORT_SLOT_MINUTES);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Act
        int enqueued = registeredSweep.remindTwoHoursBefore(at(NOW), Duration.ofHours(3));

        // Assert
        assertThat(enqueued).isOne();
        assertThat(h2FeedBookingIds()).containsExactly(bookingId);
        Integer updated = tx.execute(status -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '100ms'");
            return jdbcTemplate.update("UPDATE bookings SET updated_at = updated_at WHERE id = ? AND "
                    + "reminder_2h_sent", bookingId);
        });
        assertThat(updated).as("claim committed and its row lock released").isOne();
    }

    @Test
    @DisplayName("B522 audit-fix cycle 2: remindTwoHoursBefore called INSIDE an outer transaction is REQUIRES_NEW, not "
            + "REQUIRED — a separate connection sees its claim committed before the outer transaction ends")
    void should_commitTwoHourClaimBeforeOuterTransactionEnds_when_calledInsideOne() {
        // Arrange
        UUID bookingId = insertAppBooking(client.id(), at(NOW.plus(Duration.ofMinutes(120))),
                NOW.minus(Duration.ofDays(1)), SHORT_SLOT_MINUTES);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        // Act
        Boolean seenInsideOuter = outer.execute(status -> {
            registeredSweep.remindTwoHoursBefore(at(NOW), Duration.ofHours(3));
            return flagFromSeparateConnection("reminder_2h_sent", bookingId);
        });

        // Assert
        assertThat(seenInsideOuter).as("claim visible to another connection while the outer tx is still open")
                .isTrue();
    }

    @Test
    @DisplayName("B522 audit-fix cycle 2: remindDayBefore called INSIDE an outer transaction is REQUIRES_NEW, not "
            + "REQUIRED/MANDATORY — a separate connection sees its claim committed before the outer transaction ends")
    void should_commitDayBeforeClaimBeforeOuterTransactionEnds_when_calledInsideOne() {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        // Act
        Boolean seenInsideOuter = outer.execute(status -> {
            registeredSweep.remindDayBefore(at(NOW));
            return flagFromSeparateConnection("reminder_sent", bookingId);
        });

        // Assert
        assertThat(seenInsideOuter).as("claim visible to another connection while the outer tx is still open")
                .isTrue();
    }

    /**
     * Reads a booking's reminder flag on a raw pool connection, outside any Spring-managed transaction (a
     * {@code JdbcTemplate} would join the caller's bound connection and see its uncommitted writes).
     */
    private boolean flagFromSeparateConnection(String column, UUID bookingId) {
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM bookings WHERE id = ?")) {
            statement.setObject(1, bookingId);
            try (java.sql.ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getBoolean(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the 24 h sweep replaying (next tick, same window) adds no second row and no second push")
    void should_notDuplicate_when_sweepReplays() {
        // Arrange
        insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();

        // Act
        clock.set(NOW.plus(Duration.ofMinutes(30)));
        reminderJob.sendReminders();

        // Assert
        assertThat(feedCount()).isOne();
        assertThat(outboxCount("INAPP_PUSH")).isOne();
    }

    @Test
    @DisplayName("rescheduled after the reminder (B493 re-arm) → the next 24 h window writes a second row, new key")
    void should_writeNewRow_when_rescheduledAfterReminder() {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        OffsetDateTime newStart = at(NOW.plus(Duration.ofDays(3)));
        bookingService.rescheduleBooking(salon.ownerId(), Role.SALON_OWNER, bookingId,
                new RescheduleBookingRequest(newStart, false));

        // Act
        clock.set(NOW.plus(Duration.ofDays(2)));
        reminderJob.sendReminders();

        // Assert
        assertThat(jdbcTemplate.queryForList(
                "SELECT dedup_key FROM in_app_notification WHERE type = 'BOOKING_REMINDER' ORDER BY created_at",
                String.class)).hasSize(2).last()
                .isEqualTo("BOOKING_REMINDER:" + bookingId + ":" + newStart.toEpochSecond());
    }

    @Test
    @DisplayName("GET /api/v1/notifications → BOOKING_REMINDER with the BOOKING target and the contract params; "
            + "unread count 1")
    void should_returnBookingTargetAndParams_when_feedListed() {
        // Arrange
        OffsetDateTime start = at(NOW.plus(Duration.ofHours(24)));
        UUID bookingId = insertAppBooking(start, NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        String token = tokenFor(client.email());

        // Act
        ResponseEntity<String> feed = restTemplate.exchange("/api/v1/notifications", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        ResponseEntity<String> unread = restTemplate.exchange("/api/v1/notifications/unread-count", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);

        // Assert
        assertThat(feed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode item = read(feed.getBody()).path("data").path(0);
        assertThat(item.path("type").asText()).isEqualTo("BOOKING_REMINDER");
        assertThat(item.path("read").asBoolean()).isFalse();
        JsonNode target = item.path("target");
        assertThat(target.path("kind").asText()).isEqualTo("BOOKING");
        assertThat(target.path("bookingId").asText()).isEqualTo(bookingId.toString());
        assertThat(target.path("appointmentId").isNull()).isTrue();
        assertThat(target.path("salonId").asText()).isEqualTo(salon.salonId().toString());
        JsonNode params = item.path("params");
        assertThat(params.path("counterpartName").asText()).isEqualTo(fixtureSalonName(salon.salonId()));
        assertThat(params.path("serviceName").asText()).isEqualTo(SERVICE_NAME);
        assertThat(params.path("serviceCount").asInt()).isOne();
        assertThat(Instant.parse(params.path("startsAt").asText())).isEqualTo(start.toInstant());
        assertThat(params.path("salonName").asText()).isEqualTo(fixtureSalonName(salon.salonId()));
        assertThat(params.path("masterName").asText()).isEqualTo(MASTER_NAME);
        assertThat(read(unread.getBody()).path("data").path("count").asLong()).isOne();
    }

    @Test
    @DisplayName("the 24 h leg's own transaction failing (outbox insert raises) rolls back its claim, feed row and push "
            + "together (B522 audit-fix cycle 2: the leg is REQUIRES_NEW, so the failure is inside it, not a caller's)")
    void should_rollBackRow_when_claimTransactionFails() {
        // Arrange — a test-only trigger makes the leg's last write (the BOOKING_REMINDER outbox row) fail.
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION test_b522_fail_reminder_outbox() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'boom in the 24 h leg'; END $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER test_b522_fail_reminder_outbox BEFORE INSERT ON notification_outbox
                   FOR EACH ROW WHEN (NEW.event_type = 'BOOKING_REMINDER')
                   EXECUTE FUNCTION test_b522_fail_reminder_outbox()
                """);

        // Act
        try {
            assertThatThrownBy(() -> registeredSweep.remindDayBefore(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("boom in the 24 h leg");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_b522_fail_reminder_outbox ON notification_outbox");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_b522_fail_reminder_outbox()");
        }

        // Assert
        assertThat(feedCount()).isZero();
        assertThat(outboxCount("INAPP_PUSH")).isZero();
        assertThat(outboxCount("BOOKING_REMINDER")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT reminder_sent FROM bookings WHERE id = ?", Boolean.class,
                bookingId)).isFalse();
    }

    @Test
    @DisplayName("drain after the 24 h sweep → ONE FCM send to the client: reminder title, «service · завтра о HH:mm — "
            + "salon» body, BOOKING target data; the reminder e-mail row is SENT and pushes nothing (NTF-S10)")
    void should_sendOneReminderPushWithExpectedCopy_when_outboxDrained() throws Exception {
        // Arrange
        OffsetDateTime start = at(NOW.plus(Duration.ofHours(24)));
        UUID bookingId = insertAppBooking(start, NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        UUID feedRowId = jdbcTemplate.queryForObject(
                "SELECT id FROM in_app_notification WHERE type = 'BOOKING_REMINDER'", UUID.class);

        // Act
        drainWorker.drain();

        // Assert
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(firebaseSender, timeout(10_000).times(1)).send(sent.capture());
        Message push = sent.getValue();
        assertThat(tokenOf(push)).isEqualTo("reminder-feed-token-" + client.id());
        assertThat(titleOf(push)).isEqualTo(BookingReminderPushExpectedCopy.TITLE);
        assertThat(bodyOf(push)).isEqualTo(BookingReminderPushExpectedCopy.body(
                SERVICE_NAME, BookingReminderPushExpectedCopy.TOMORROW, kyivHhMm(start),
                fixtureSalonName(salon.salonId())));
        assertThat(dataOf(push)).containsEntry("type", "BOOKING_REMINDER")
                .containsEntry("targetKind", "BOOKING")
                .containsEntry("bookingId", bookingId.toString())
                .containsEntry("notificationId", feedRowId.toString());
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM notification_outbox WHERE event_type = 'BOOKING_REMINDER'", String.class))
                .as("the e-mail row was dispatched in this drain").containsExactly("SENT");
        verifyNoMoreInteractions(firebaseSender);
    }

    @Test
    @DisplayName("reminded, rescheduled, reminded again → each BOOKING_REMINDER row has exactly one INAPP_PUSH row")
    void should_pushEachReminderRowExactlyOnce_when_rescheduledAfterReminder() {
        // Arrange
        UUID bookingId = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        reminderJob.sendReminders();
        bookingService.rescheduleBooking(salon.ownerId(), Role.SALON_OWNER, bookingId,
                new RescheduleBookingRequest(at(NOW.plus(Duration.ofDays(3))), false));

        // Act
        clock.set(NOW.plus(Duration.ofDays(2)));
        reminderJob.sendReminders();

        // Assert
        assertThat(jdbcTemplate.queryForList("""
                SELECT count(o.id) FROM in_app_notification n
                  LEFT JOIN notification_outbox o ON o.aggregate_id = n.id AND o.event_type = 'INAPP_PUSH'
                 WHERE n.type = 'BOOKING_REMINDER'
                 GROUP BY n.id
                """, Long.class)).as("push count per reminder feed row").containsExactly(1L, 1L);
    }

    /** Due bookings the registered 24 h leg must never feed: no client account, or not an APP booking. */
    enum NoFeedShape { STAFF_WALK_IN_GUEST, STAFF_REGISTERED_CLIENT, APP_DETACHED_CLIENT }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NoFeedShape.class)
    @DisplayName("a due guest / walk-in / staff-made / detached booking gets no BOOKING_REMINDER row and no push; "
            + "the APP control booking in the same tick does (NTF-08)")
    void should_writeNoRow_when_dueBookingHasNoRegisteredAppClient(NoFeedShape shape) {
        // Arrange
        UUID control = insertAppBooking(at(NOW.plus(Duration.ofHours(24))), NOW.minus(Duration.ofDays(2)));
        UUID excluded = insertNoFeedBooking(shape, at(NOW.plus(Duration.ofMinutes(24 * 60 + 90))));

        // Act
        reminderJob.sendReminders();

        // Assert
        assertThat(jdbcTemplate.queryForList(
                "SELECT booking_id FROM in_app_notification WHERE type = 'BOOKING_REMINDER'", UUID.class))
                .as("only the APP control booking (%s), never %s %s", control, shape, excluded)
                .containsExactly(control);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM notification_outbox o JOIN in_app_notification n ON n.id = o.aggregate_id
                 WHERE o.event_type = 'INAPP_PUSH' AND n.booking_id = ?
                """, Long.class, excluded)).isZero();
    }

    private UUID insertNoFeedBooking(NoFeedShape shape, OffsetDateTime startsAt) {
        return insertNoFeedBooking(shape, startsAt, DURATION_MINUTES);
    }

    private UUID insertNoFeedBooking(NoFeedShape shape, OffsetDateTime startsAt, int durationMinutes) {
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(2)), ZoneOffset.UTC);
        String source = shape == NoFeedShape.APP_DETACHED_CLIENT ? "APP" : "STAFF";
        UUID clientId = shape == NoFeedShape.STAFF_REGISTERED_CLIENT ? client.id() : null;
        String guestName = shape == NoFeedShape.STAFF_REGISTERED_CLIENT ? null : "Гість";
        String guestSurname = shape == NoFeedShape.STAFF_WALK_IN_GUEST ? "Тестовий" : null;
        String guestPhone = shape == NoFeedShape.STAFF_WALK_IN_GUEST ? "+380501112233" : null;
        OffsetDateTime detachedAt = shape == NoFeedShape.APP_DETACHED_CLIENT ? created : null;
        jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, salon_id, client_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, client_detached_at, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, ?, 0, ?, ?, ?, ?, ?, ?, ?)",
                bookingId, salon.masterId(), salon.masterServiceId(), salon.salonId(), clientId, startsAt,
                startsAt.plusMinutes(durationMinutes), durationMinutes, source, guestName, guestSurname, guestPhone,
                detachedAt, created, created);
        return bookingId;
    }

    private static String kyivHhMm(OffsetDateTime instant) {
        java.time.ZonedDateTime kyiv = instant.atZoneSameInstant(TimeZones.KYIV);
        return String.format("%02d:%02d", kyiv.getHour(), kyiv.getMinute());
    }

    /** A CONFIRMED 60-minute APP booking for {@link #client} at the seeded salon (same shape as B494's IT). */
    private UUID insertAppBooking(OffsetDateTime startsAt, Instant createdAt) {
        return insertAppBooking(client.id(), startsAt, createdAt);
    }

    private UUID insertAppBooking(UUID clientId, OffsetDateTime startsAt, Instant createdAt) {
        return insertAppBooking(clientId, startsAt, createdAt, DURATION_MINUTES);
    }

    private UUID insertAppBooking(UUID clientId, OffsetDateTime startsAt, Instant createdAt, int durationMinutes) {
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, salon_id, client_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, ?, 0, 'APP', ?, ?)",
                bookingId, salon.masterId(), salon.masterServiceId(), salon.salonId(), clientId, startsAt,
                startsAt.plusMinutes(durationMinutes), durationMinutes, created, created);
        return bookingId;
    }

    /** Booking ids of every 2 h ({@code …:H2}) reminder feed row. */
    private List<UUID> h2FeedBookingIds() {
        return jdbcTemplate.queryForList("""
                SELECT booking_id FROM in_app_notification
                 WHERE type = 'BOOKING_REMINDER' AND dedup_key LIKE '%:H2'
                """, UUID.class);
    }

    /** INAPP_PUSH outbox rows fanned out from any feed row of the given booking. */
    private long pushCountForBooking(UUID bookingId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM notification_outbox o JOIN in_app_notification n ON n.id = o.aggregate_id
                 WHERE o.event_type = 'INAPP_PUSH' AND n.booking_id = ?
                """, Long.class, bookingId);
    }

    private long feedCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM in_app_notification WHERE type = 'BOOKING_REMINDER'", Long.class);
    }

    private long outboxCount(String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE event_type = ?", Long.class, eventType);
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
