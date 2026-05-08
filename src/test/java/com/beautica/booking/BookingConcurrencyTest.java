package com.beautica.booking;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * Concurrency regression: ten threads race to book the same slot for the same master.
 *
 * No {@code @Transactional} annotation — each HTTP call is its own transaction that
 * must commit independently. The advisory lock inside BookingService serialises the
 * critical section; the GiST exclusion constraint is the final backstop.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Booking — concurrency: exactly one booking wins when 10 threads race for the same slot")
class BookingConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(BookingConcurrencyTest.class);
    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "password123";
    private static final int THREAD_COUNT = 10;
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    // Dedicated container so this class does not share context with @MockBean-free tests
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

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BookingRepository bookingRepository;

    @MockBean
    private NotificationOutboxService notificationOutboxService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM bookings");
        jdbcTemplate.execute("DELETE FROM master_services");
        jdbcTemplate.execute("DELETE FROM service_definitions");
        jdbcTemplate.execute("DELETE FROM working_hours");
        jdbcTemplate.execute("DELETE FROM schedule_exceptions");
        jdbcTemplate.execute("DELETE FROM masters");
        jdbcTemplate.execute("DELETE FROM invite_tokens");
        jdbcTemplate.execute("DELETE FROM salons");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @Test
    @DisplayName("should allow exactly one booking when 10 concurrent requests target the same slot")
    void should_allowExactlyOneBooking_when_10ConcurrentRequestsForSameSlot() throws InterruptedException {
        // Arrange
        String clientToken = createClientAndGetToken("concurrency-client-" + System.nanoTime() + "@beautica.test");
        UUID masterId = createSalonOwnerSalonAndMaster("concurrency-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        // Use a date far in the future on a specific hour that no other test has touched
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3)
                .withHour(14)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        var go   = new CountDownLatch(1);
        var done = new CountDownLatch(THREAD_COUNT);
        var created   = new AtomicInteger(0);
        var conflicts = new AtomicInteger(0);

        // Act — ten threads released simultaneously via the go latch
        for (int i = 0; i < THREAD_COUNT; i++) {
            final String idempotencyKey = "concurrency-key-" + i + "-" + System.nanoTime();
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    // Each thread uses a unique idempotency key to prevent dedup short-circuit
                    var request = new CreateBookingRequest(masterId, masterServiceId, startsAt, idempotencyKey);
                    ResponseEntity<String> response = restTemplate.exchange(
                            BOOKINGS_URL, HttpMethod.POST,
                            new HttpEntity<>(request, bearerHeaders(clientToken)),
                            String.class);
                    if (response.getStatusCode() == HttpStatus.CREATED) {
                        created.incrementAndGet();
                    } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
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

        // Assert
        assertThat(finished)
                .as("all threads must complete within 30 seconds")
                .isTrue();
        assertThat(created.get())
                .as("exactly one thread must receive HTTP 201")
                .isEqualTo(1);
        assertThat(conflicts.get())
                .as("exactly nine threads must receive HTTP 409")
                .isEqualTo(THREAD_COUNT - 1);
        assertThat(bookingRepository.count())
                .as("exactly one booking row must exist in the database")
                .isEqualTo(1L);

        log.debug("Concurrency test result: created={}, conflicts={}", created.get(), conflicts.get());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String createClientAndGetToken(String email) throws InterruptedException {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'CLIENT', true)",
                UUID.randomUUID(), email, hash);
        return loginAndGetToken(email);
    }

    private String loginAndGetToken(String email) throws InterruptedException {
        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
            return body.data().accessToken();
        } catch (Exception e) {
            throw new RuntimeException("Failed to login as " + email, e);
        }
    }

    private UUID createSalonOwnerSalonAndMaster(String ownerEmail) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'SALON_OWNER', true)",
                ownerId, ownerEmail, hash);

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Concurrency-Salon-" + ownerId);

        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "master-" + System.nanoTime() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active) VALUES (?, ?, ?, 'SALON_MASTER', ?, true)",
                masterUserId, masterEmail, hash, salonId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        return masterId;
    }

    private UUID createMasterService(UUID masterId) {
        UUID salonId = jdbcTemplate.queryForObject(
                "SELECT salon_id FROM masters WHERE id = ?", UUID.class, masterId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) VALUES (?, 'SALON', ?, 'Concurrency Service', 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId);

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        return masterServiceId;
    }

    private void addWorkingHoursForEveryDay(UUID masterId) {
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_hours (id, master_id, day_of_week, start_time, end_time, is_active) VALUES (?, ?, ?, '08:00', '20:00', true)",
                    UUID.randomUUID(), masterId, day);
        }
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
