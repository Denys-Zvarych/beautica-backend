package com.beautica.notification.inapp.push;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.common.TimeZones;
import com.beautica.config.FirebaseConfig;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Phase 339 — shared rig for the two end-to-end push ITs ({@link InAppPushEndToEndIT},
 * FIREBASE_ENABLED=true, and {@link InAppPushDisabledIT}, flag off). Real HTTP booking actions write
 * the feed rows; the real drainer sends; only the FCM edge ({@link FirebaseConfig.FirebaseSender}) is
 * a mock — the one external service in this chain.
 *
 * <p>REUSE-FIRST: salon/user/service fixtures are {@link BookingTestFixtures}; the HTTP helpers mirror
 * {@code NotificationFeedRealWritePathIT}.
 */
@Import(TestSecurityConfig.class)
abstract class AbstractInAppPushFlowIT extends AbstractIntegrationTest {

    /** Distinctive values that must NEVER surface in an outbox row, a push title or a push body. */
    static final String NOTE_SENTINEL = "SENTINEL-NOTE-zq91-do-not-leak";
    static final String PHONE_SENTINEL = "+380677771234";
    static final String EMAIL_SENTINEL = "sentinel-client-zq91";
    static final String CLIENT_NAME = "Оксана Гончарук";

    @MockBean
    protected FirebaseConfig.FirebaseSender firebaseSender;

    @Autowired
    protected TestRestTemplate restTemplate;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    protected BookingTestFixtures fixtures;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    /** One salon (owner, SALON_MASTER, SALON_ADMIN), one service, a named client; every provider has a token. */
    protected record Rig(UUID salonId, UUID ownerId, UUID masterUserId, UUID adminId, UUID masterId,
                         UUID masterServiceId, UUID clientId, String clientToken) {
        Map<UUID, String> tokenByRecipient() {
            return Map.of(ownerId, tokenOf(ownerId), masterUserId, tokenOf(masterUserId),
                    adminId, tokenOf(adminId));
        }
    }

    static String tokenOf(UUID userId) {
        return "e2e-push-token-" + userId;
    }

    protected Rig seedRigWithProviderDeviceTokens() throws Exception {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("push-e2e-owner-" + System.nanoTime() + "@beautica.test");
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, salon.masterId());
        UUID adminId = fixtures.createUser("push-e2e-admin-" + System.nanoTime() + "@beautica.test",
                "SALON_ADMIN", salon.salonId());
        UUID masterServiceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        fixtures.addWorkingHoursForEveryDay(salon.masterId());

        String clientEmail = EMAIL_SENTINEL + "-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        jdbcTemplate.update("UPDATE users SET first_name = ?, last_name = ?, phone_number = ? WHERE id = ?",
                "Оксана", "Гончарук", PHONE_SENTINEL, clientId);

        for (UUID recipient : List.of(ownerId, masterUserId, adminId)) {
            jdbcTemplate.update(
                    "INSERT INTO device_tokens (user_id, token, platform) VALUES (?, ?, 'ANDROID')",
                    recipient, tokenOf(recipient));
        }
        return new Rig(salon.salonId(), ownerId, masterUserId, adminId, salon.masterId(), masterServiceId,
                clientId, fixtures.tokenFor(clientEmail));
    }

    protected ZonedDateTime startsAt() {
        return ZonedDateTime.now(TimeZones.KYIV).plusDays(2).withHour(12).withMinute(0).withSecond(0).withNano(0);
    }

    /** Real {@code POST /api/v1/bookings} as the client, with a note sentinel in {@code clientComment}. */
    protected UUID clientBooks(Rig rig, ZonedDateTime startsAt) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "masterId", rig.masterId().toString(),
                "masterServiceId", rig.masterServiceId().toString(),
                "startsAt", startsAt.toOffsetDateTime().toString(),
                "clientComment", NOTE_SENTINEL));
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(body, fixtures.bearerHeaders(rig.clientToken())), String.class);
        assertThat(resp.getStatusCode()).as("booking create must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(resp.getBody()).path("data").path("id").asText());
    }

    protected long countOutbox(String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE event_type = ?", Long.class, eventType);
    }

    protected List<UUID> feedRowIds() {
        return jdbcTemplate.queryForList("SELECT id FROM in_app_notification", UUID.class);
    }

    /** Waits (Mockito timeout, no sleep) for exactly {@code expected} FCM sends and returns them. */
    protected List<Message> awaitSends(int expected) throws FirebaseMessagingException {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(firebaseSender, timeout(10_000).times(expected)).send(captor.capture());
        return captor.getAllValues();
    }

    // ── FCM Message has no getters; read the fields the SDK builder set (as PushNotificationServiceTest does) ──

    @SuppressWarnings("unchecked")
    protected static Map<String, String> dataOf(Message message) {
        return (Map<String, String>) field(message, "data");
    }

    protected static String targetTokenOf(Message message) {
        return (String) field(message, "token");
    }

    protected static String titleOf(Message message) {
        return (String) field(field(message, "notification"), "title");
    }

    protected static String bodyOf(Message message) {
        return (String) field(field(message, "notification"), "body");
    }

    private static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
