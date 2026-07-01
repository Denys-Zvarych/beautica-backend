package com.beautica.booking;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.SmsService;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atMost;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Headline concurrency regression for Phase 13.4 — two guests POST the SAME cancel token
 * at the same instant. The one-time atomic consume
 * ({@code BookingRepository.consumeCancelToken}) must let EXACTLY ONE racer win: one 204,
 * one 404, the booking ends CANCELLED with a nulled token, and the cancellation SMS fires
 * exactly once. This proves the conditional UPDATE closes the check-then-act double-cancel
 * race a naive "load → flip → save" flow would leave open.
 *
 * <p>No {@code @Transactional} — each HTTP request is its own committing transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Guest cancel — concurrency: exactly one of two racers consumes the cancel token")
class GuestCancelConcurrencyIT {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String GUEST_PHONE = "+380501234567";
    private static final int THREAD_COUNT = 2;

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BookingRepository bookingRepository;

    // The cancellation SMS is irrelevant to the consume race — mock it so the
    // exactly-once assertion is on the dispatch count, not a real provider call.
    @MockBean private SmsService smsService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @AfterEach
    void cleanUp() {
        // FK order: outbox/bookings reference masters → salons → users.
        jdbcTemplate.execute("DELETE FROM notification_outbox");
        jdbcTemplate.execute("DELETE FROM bookings");
        jdbcTemplate.execute("DELETE FROM master_services");
        jdbcTemplate.execute("DELETE FROM service_definitions");
        jdbcTemplate.execute("DELETE FROM working_hours");
        jdbcTemplate.execute("DELETE FROM masters");
        jdbcTemplate.execute("DELETE FROM salons");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @Test
    @DisplayName("should let exactly one racer cancel when two POSTs target the same cancel token")
    void should_consumeExactlyOnce_when_twoRacersCancelSameToken() throws InterruptedException {
        UUID masterId = createMaster();
        UUID masterServiceId = createMasterService(masterId);
        // Far outside the 2h cancel window so the cancellation is permitted.
        OffsetDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0)
                .toOffsetDateTime();
        UUID cancelToken = UUID.randomUUID();
        seedConfirmedGuestBooking(masterId, masterServiceId, startsAt, cancelToken);

        String url = "/api/v1/book/cancel/" + cancelToken;
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(THREAD_COUNT);
        var noContent = new AtomicInteger(0);
        var notFound = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    ResponseEntity<String> resp = restTemplate.exchange(
                            url, HttpMethod.POST, new HttpEntity<>(null), String.class);
                    if (resp.getStatusCode() == HttpStatus.NO_CONTENT) {
                        noContent.incrementAndGet();
                    } else if (resp.getStatusCode() == HttpStatus.NOT_FOUND) {
                        notFound.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        go.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);

        assertThat(finished).as("both racers must finish within 30s").isTrue();
        assertThat(noContent.get()).as("exactly one 204").isEqualTo(1);
        assertThat(notFound.get()).as("exactly one 404").isEqualTo(1);

        // The single surviving row is CANCELLED with a consumed (null) token.
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE master_id = ?", String.class, masterId);
        Integer liveTokens = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE cancel_token IS NOT NULL", Integer.class);
        assertThat(status).isEqualTo("CANCELLED");
        assertThat(liveTokens).as("cancel token consumed").isZero();
        assertThat(bookingRepository.count()).isEqualTo(1L);

        // The cancellation SMS (after-commit) is dispatched exactly once — only the winner.
        verify(smsService, timeout(5_000)).send(eq(GUEST_PHONE), anyString());
        verify(smsService, atMost(1)).send(eq(GUEST_PHONE), anyString());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID createMaster() {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', true, true, 'Марія', 'Левченко')",
                ownerId, "owner-" + System.nanoTime() + "@beautica.test");

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, ?, NOW(), NOW())",
                masterId, ownerId, "cancel-master-" + Long.toString(System.nanoTime(), 36));
        return masterId;
    }

    private UUID createMasterService(UUID masterId) {
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Манікюр', 60, 350.00, 0, true, NOW(), NOW())",
                serviceDefId, ownerId);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private void seedConfirmedGuestBooking(UUID masterId, UUID masterServiceId,
                                           OffsetDateTime startsAt, UUID cancelToken) {
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_phone, cancel_token, reminder_sent, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'LINK', 'Олена', ?, ?, false, NOW(), NOW())",
                UUID.randomUUID(), masterId, masterServiceId,
                startsAt, startsAt.plusMinutes(60), GUEST_PHONE, cancelToken);
    }
}
