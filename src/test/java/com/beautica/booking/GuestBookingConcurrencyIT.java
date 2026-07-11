package com.beautica.booking;

import com.beautica.auth.phoneotp.GuestTokenProvider;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headline concurrency regression for Phase 13.3 — two guest bookings race for the
 * SAME master+slot. Exactly one must win (201) and one must lose (409), proving the
 * per-master {@code pg_advisory_xact_lock} guard inside
 * {@link com.beautica.booking.service.GuestBookingService} closes the check-then-act
 * (TOCTOU) race that a naive "isSlotAvailable → save" would leave open.
 *
 * <p>No {@code @Transactional} — each HTTP request is its own committing transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Guest booking — concurrency: exactly one of two racers wins the same slot")
class GuestBookingConcurrencyIT {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
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
    @Autowired private GuestTokenProvider guestTokenProvider;

    // SMS is irrelevant to the lock contention — mock it so no provider call is attempted.
    @MockBean private SmsService smsService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @AfterEach
    void cleanUp() {
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
    @DisplayName("should allow exactly one guest booking when two requests target the same slot")
    void should_allowExactlyOne_when_twoGuestsRaceSameSlot() throws InterruptedException {
        String slug = "concurrency-master-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug(slug);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);
        String body = """
                {"serviceId":"%s","startsAt":"%s","name":"Олена","surname":"Коваль"}
                """.formatted(masterServiceId, startsAt.toOffsetDateTime());

        String url = "/api/v1/book/" + slug + "/booking";
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(THREAD_COUNT);
        var created = new AtomicInteger(0);
        var conflicts = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            // distinct phone per racer so they are genuinely two different guests
            String token = guestTokenProvider.generate("+38050000000" + i);
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    ResponseEntity<String> resp = restTemplate.exchange(
                            url, HttpMethod.POST, new HttpEntity<>(body, guestHeaders(token)), String.class);
                    if (resp.getStatusCode() == HttpStatus.CREATED) {
                        created.incrementAndGet();
                    } else if (resp.getStatusCode() == HttpStatus.CONFLICT) {
                        conflicts.incrementAndGet();
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
        assertThat(created.get()).as("exactly one 201").isEqualTo(1);
        assertThat(conflicts.get()).as("exactly one 409").isEqualTo(1);
        assertThat(bookingRepository.count()).as("exactly one booking row persisted").isEqualTo(1L);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID createMasterWithSlug(String slug) {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', true, true, 'Марія', 'Левченко')",
                ownerId, "owner-" + System.nanoTime() + "@beautica.test");

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, ?, NOW(), NOW())",
                masterId, ownerId, slug);
        return masterId;
    }

    private UUID createMasterService(UUID masterId) {
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Манікюр', ?, 60, 350.00, 0, true, NOW(), NOW())",
                serviceDefId, ownerId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    /**
     * Resolves a real, selectable {@code service_types.id} (V111 made this column NOT NULL).
     */
    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    private void addWorkingHoursForEveryDay(UUID masterId) {
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_hours (id, master_id, day_of_week, start_time, end_time, is_active) "
                            + "VALUES (?, ?, ?, '08:00', '20:00', true)",
                    UUID.randomUUID(), masterId, day);
        }
    }

    private HttpHeaders guestHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
