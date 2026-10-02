package com.beautica.notification.inapp.push;

import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 339 integration-test gate (FIREBASE_ENABLED=true). Real HTTP booking actions -> in-app write
 * path -> CTE-enqueued {@code INAPP_PUSH} outbox rows -> the real drainer -> the (mock) FCM sender.
 *
 * <p>Skipped because already proven elsewhere: per-type fan-out/dedup of the CTE twins
 * ({@code InAppNotificationRepositoryIT} "Phase 339 — push twins"), the per-type copy
 * ({@code PushCopyTest}), the D8 skips and the D6 key set at unit level
 * ({@code InAppPushDispatcherTest}), drain statement bounds ({@code InAppPushDrainStatementCountIT}).
 * This class proves the chain COMPOSES through the real endpoints.
 */
@TestPropertySource(properties = "FIREBASE_ENABLED=true")
@DisplayName("Phase 339 — booking action -> INAPP_PUSH outbox -> drain -> FCM (FIREBASE_ENABLED=true)")
class InAppPushEndToEndIT extends AbstractInAppPushFlowIT {

    private static final Set<String> ALLOWED_DATA_KEYS = Set.of(
            "v", "notificationId", "type", "targetKind", "bookingId", "appointmentId", "salonId");

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Test
    @DisplayName("client books over HTTP: exactly one INAPP_PUSH outbox row per feed row, aggregate = feed id, "
            + "no payload and no personal text anywhere in the row")
    void should_enqueueOneIdOnlyPushRowPerFeedRow_when_clientBooksOverHttp() throws Exception {
        Rig rig = seedRigWithProviderDeviceTokens();

        clientBooks(rig, startsAt());

        List<UUID> feedIds = feedRowIds();
        assertThat(feedIds).as("owner + admin + master each get one BOOKING_CREATED feed row").hasSize(3);
        List<UUID> pushAggregates = jdbcTemplate.queryForList(
                "SELECT aggregate_id FROM notification_outbox WHERE event_type = 'INAPP_PUSH'", UUID.class);
        assertThat(pushAggregates).as("one push row per feed row, keyed by the feed id")
                .containsExactlyInAnyOrderElementsOf(feedIds);
        List<String> rowsAsJson = jdbcTemplate.queryForList(
                "SELECT row_to_json(o)::text FROM notification_outbox o WHERE event_type = 'INAPP_PUSH'",
                String.class);
        assertThat(rowsAsJson).hasSize(3).allSatisfy(json -> assertThat(json)
                .as("ids only — the stored row must carry no name/note/phone/email: %s", json)
                .doesNotContain("Оксана", "Гончарук", NOTE_SENTINEL, PHONE_SENTINEL, EMAIL_SENTINEL)
                .contains("\"payload\":null"));
    }

    @Test
    @DisplayName("drain after a booking: FCM gets one message per provider token, title/body rendered at send "
            + "time (name, service, Kyiv time), only the 7 allowed data keys, no note/phone/email")
    void should_sendRenderedPushWithAllowedKeysOnly_when_drainRunsAfterBooking() throws Exception {
        Rig rig = seedRigWithProviderDeviceTokens();
        UUID bookingId = clientBooks(rig, startsAt());
        Map<UUID, UUID> feedIdByRecipient = new HashMap<>();
        jdbcTemplate.query("SELECT id, recipient_user_id FROM in_app_notification",
                rs -> {
                    feedIdByRecipient.put(rs.getObject("recipient_user_id", UUID.class),
                            rs.getObject("id", UUID.class));
                });

        drainWorker.drain();

        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM notification_outbox WHERE event_type = 'INAPP_PUSH'", String.class))
                .as("every push entry is handed off and SENT")
                .containsOnly(OutboxStatus.SENT.name()).hasSize(3);
        List<Message> sent = awaitSends(3);
        Map<String, UUID> recipientByToken = rig.tokenByRecipient().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        assertThat(sent).extracting(m -> targetTokenOf(m))
                .containsExactlyInAnyOrderElementsOf(rig.tokenByRecipient().values());
        for (Message message : sent) {
            UUID recipient = recipientByToken.get(targetTokenOf(message));
            Map<String, String> data = dataOf(message);
            assertThat(data.keySet()).as("only allowed D6 keys, actual=%s", data.keySet())
                    .isSubsetOf(ALLOWED_DATA_KEYS);
            assertThat(data).containsEntry("v", "1")
                    .containsEntry("type", "BOOKING_CREATED")
                    .containsEntry("targetKind", "BOOKING")
                    .containsEntry("bookingId", bookingId.toString())
                    .containsEntry("salonId", rig.salonId().toString())
                    .containsEntry("notificationId", feedIdByRecipient.get(recipient).toString());
            assertThat(titleOf(message)).isEqualTo("Новий запис");
            assertThat(bodyOf(message)).as("body rendered from live data at send time, actual=%s", bodyOf(message))
                    .contains(CLIENT_NAME, "Test Service", "12:00");
            assertThat(titleOf(message) + " " + bodyOf(message) + " " + data)
                    .doesNotContain(NOTE_SENTINEL, PHONE_SENTINEL, EMAIL_SENTINEL);
        }
    }

    @Test
    @DisplayName("client cancels with a note: a second push per provider (BOOKING_CANCELLED_BY_CLIENT), "
            + "the cancellation note is in neither the outbox rows nor the push")
    void should_pushCancellationWithoutNote_when_clientCancelsWithNote() throws Exception {
        Rig rig = seedRigWithProviderDeviceTokens();
        UUID bookingId = clientBooks(rig, startsAt());
        drainWorker.drain();
        awaitSends(3);

        ResponseEntity<String> cancel = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId + "/cancel", HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                        "cancellationReason", "CLIENT_CANCELLED", "comment", NOTE_SENTINEL)),
                        fixtures.bearerHeaders(rig.clientToken())), String.class);
        assertThat(cancel.getStatusCode()).as("cancel must succeed — body=%s", cancel.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(countOutbox("INAPP_PUSH")).as("3 create rows + 3 cancel rows").isEqualTo(6);
        assertThat(jdbcTemplate.queryForList(
                "SELECT row_to_json(o)::text FROM notification_outbox o WHERE event_type = 'INAPP_PUSH'",
                String.class)).allSatisfy(json -> assertThat(json).doesNotContain(NOTE_SENTINEL));
        drainWorker.drain();
        List<Message> sent = awaitSends(6);
        List<Message> cancelPushes = sent.stream()
                .filter(m -> "BOOKING_CANCELLED_BY_CLIENT".equals(safeData(m).get("type"))).toList();
        assertThat(cancelPushes).as("one cancellation push per provider").hasSize(3).allSatisfy(m -> {
            assertThat(titleOf(m)).isEqualTo("Клієнт скасував запис");
            assertThat(safeData(m).get("bookingId")).isEqualTo(bookingId.toString());
            assertThat(titleOf(m) + " " + bodyOf(m) + " " + safeData(m))
                    .doesNotContain(NOTE_SENTINEL, PHONE_SENTINEL, EMAIL_SENTINEL);
        });
    }

    @Test
    @DisplayName("recipient loses access between write and send (admin detached from the salon): the push "
            + "still goes out but with the generic body, no client name and no service")
    void should_sendGenericBody_when_adminLostSalonBetweenWriteAndSend() throws Exception {
        Rig rig = seedRigWithProviderDeviceTokens();
        clientBooks(rig, startsAt());
        jdbcTemplate.update("UPDATE users SET salon_id = NULL WHERE id = ?", rig.adminId());

        drainWorker.drain();

        List<Message> sent = awaitSends(3);
        Message adminPush = sent.stream().filter(m -> {
            try {
                return tokenOf(rig.adminId()).equals(targetTokenOf(m));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).findFirst().orElseThrow();
        assertThat(bodyOf(adminPush)).isEqualTo(PushCopy.GENERIC_BODY);
        assertThat(titleOf(adminPush) + " " + bodyOf(adminPush)).doesNotContain("Оксана", "Test Service");
        Message masterPush = sent.stream().filter(m -> {
            try {
                return tokenOf(rig.masterUserId()).equals(targetTokenOf(m));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).findFirst().orElseThrow();
        assertThat(bodyOf(masterPush)).as("a recipient who kept access still gets the rendered body")
                .contains(CLIENT_NAME);
    }

    private static Map<String, String> safeData(Message message) {
        try {
            return dataOf(message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
