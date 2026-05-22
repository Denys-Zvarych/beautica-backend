package com.beautica.notification;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.service.NotificationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Full-stack integration test for the notification outbox pattern.
 *
 * <p>Closes the Phase 5.16 HIGH finding: validates the four end-to-end guarantees
 * the unit-test layer cannot prove on its own:
 * <ol>
 *   <li>{@code POST /bookings} writes exactly one PENDING outbox row inside the
 *       booking transaction.</li>
 *   <li>An outbox INSERT honours the surrounding transaction boundary — rolling
 *       back the caller's transaction also rolls back the outbox row.</li>
 *   <li>The drain worker advances PENDING entries to SENT when
 *       {@link NotificationService} dispatches without throwing.</li>
 *   <li>{@code SELECT FOR UPDATE SKIP LOCKED} hands two concurrent claimers
 *       distinct entries — never the same row to both.</li>
 * </ol>
 *
 * <p>{@link NotificationService} is mocked to keep SMTP and Firebase from firing.
 * The outbox itself ({@link NotificationOutboxService}, the cipher, the repository
 * query) is exercised real — that's the whole point.
 */
@Import(TestSecurityConfig.class)
@DisplayName("NotificationOutbox — full-stack integration")
class NotificationOutboxIntegrationTest extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationOutboxService outboxService;

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private NotificationService notificationService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("POST /bookings — notification_outbox has one NEW_BOOKING/PENDING row when booking is created")
    void should_persistOutboxEntry_when_bookingCreated() throws Exception {
        String clientToken = createClientAndGetToken("integ-outbox-client-" + System.nanoTime() + "@beautica.test");
        UUID masterId = createSalonOwnerSalonAndMaster("integ-outbox-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        assertThat(outboxRepository.count())
                .as("outbox must be empty before booking creation — AbstractIntegrationTest.cleanDb() ran")
                .isZero();

        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        var request = new CreateBookingRequest(masterId, masterServiceId, startsAt, null, null);

        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("valid booking creation must return 201")
                .isEqualTo(HttpStatus.CREATED);
        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<BookingResponse>>() {});
        UUID bookingId = body.data().id();

        List<NotificationOutboxEntry> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);

        NotificationOutboxEntry only = rows.get(0);
        assertThat(only.getEventType()).isEqualTo(OutboxEventType.NEW_BOOKING);
        assertThat(only.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(only.getAttempts()).isZero();
        assertThat(only.getPayload()).isNull();
        assertThat(only.getAggregateId())
                .as("aggregateId must point to the created booking")
                .isEqualTo(bookingId);
    }

    @Test
    @DisplayName("notification_outbox is empty when the booking transaction rolls back after enqueueNewBooking")
    void should_notPersistOutboxEntry_when_bookingTransactionRollsBack() {
        assertThat(outboxRepository.count()).isZero();

        TransactionTemplate template = new TransactionTemplate(txManager);

        template.execute(status -> {
            outboxService.enqueueNewBooking(UUID.randomUUID());
            status.setRollbackOnly();
            return null;
        });

        assertThat(outboxRepository.count())
                .as("outbox row must roll back with the surrounding transaction")
                .isZero();
    }

    @Test
    @DisplayName("drain() flips PENDING entry to SENT when NotificationService dispatches successfully")
    void should_markEntrySent_when_drainWorkerProcessesIt() throws Exception {
        // Seed a real booking — the drain worker loads bookings via findAllByIdsWithGraph,
        // so the row must exist for the dispatch path to find it.
        String clientToken = createClientAndGetToken("integ-outbox-drain-client-" + System.nanoTime() + "@beautica.test");
        UUID masterId = createSalonOwnerSalonAndMaster("integ-outbox-drain-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        // Use the real POST path so the booking is wired up correctly through JPA. This
        // also enqueues an outbox row, which we delete before inserting our controlled one
        // so the assertion below targets a known entry.
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId);
        outboxRepository.deleteAll();
        outboxRepository.flush();

        NotificationOutboxEntry entry = NotificationOutboxEntry.builder()
                .eventType(OutboxEventType.NEW_BOOKING)
                .aggregateId(bookingId)
                .build();
        outboxRepository.saveAndFlush(entry);
        UUID entryId = entry.getId();

        drainWorker.drain();

        NotificationOutboxEntry reloaded = outboxRepository.findById(entryId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(reloaded.getLastError()).isNull();
        verify(notificationService, times(1)).notifyNewBooking(any(Booking.class));
    }

    @Test
    @DisplayName("two concurrent SELECT FOR UPDATE SKIP LOCKED claims each return a distinct entry — no entry double-claimed")
    void should_skipLockedEntries_when_twoWorkersRunConcurrently() throws Exception {
        // Seed two PENDING rows. aggregateId can be random — the test never advances
        // these rows past the claim, so the dispatch path is not exercised.
        outboxRepository.saveAndFlush(NotificationOutboxEntry.builder()
                .eventType(OutboxEventType.NEW_BOOKING)
                .aggregateId(UUID.randomUUID())
                .build());
        outboxRepository.saveAndFlush(NotificationOutboxEntry.builder()
                .eventType(OutboxEventType.NEW_BOOKING)
                .aggregateId(UUID.randomUUID())
                .build());

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch claimed = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Set<UUID>> claimsByThread = Collections.synchronizedList(new ArrayList<>());

        Callable<Void> task = () -> {
            new TransactionTemplate(txManager).execute(status -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                List<NotificationOutboxEntry> batch = outboxRepository.claimPendingBatch(1);
                Set<UUID> ids = batch.stream()
                        .map(NotificationOutboxEntry::getId)
                        .collect(Collectors.toSet());
                claimsByThread.add(ids);
                claimed.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            return null;
        };

        try {
            Future<Void> f1 = exec.submit(task);
            Future<Void> f2 = exec.submit(task);
            start.countDown();
            boolean both = claimed.await(5, TimeUnit.SECONDS);
            assertThat(both)
                    .as("both threads must reach the SELECT FOR UPDATE within 5s")
                    .isTrue();
            release.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
        } finally {
            exec.shutdown();
            exec.awaitTermination(2, TimeUnit.SECONDS);
        }

        assertThat(claimsByThread).hasSize(2);
        Set<UUID> a = claimsByThread.get(0);
        Set<UUID> b = claimsByThread.get(1);
        assertThat(a).hasSize(1);
        assertThat(b).hasSize(1);
        assertThat(a)
                .as("SKIP LOCKED proof — concurrent claimers must receive disjoint rows")
                .doesNotContainAnyElementsOf(b);

        Set<UUID> union = new HashSet<>(a);
        union.addAll(b);
        assertThat(union).hasSize(2);
    }

    // ── helpers (copied verbatim from BookingIntegrationTest — extraction is a
    //    backlog follow-up flagged by the QA audit) ──────────────────────────────

    private String createClientAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        // email_verified = true so Phase 1.7 login gate does not return 403 EMAIL_NOT_VERIFIED
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'CLIENT', true, true)",
                UUID.randomUUID(), email, hash);
        return loginAndGetToken(email);
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private UUID createSalonOwnerSalonAndMaster(String ownerEmail) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID ownerId = UUID.randomUUID();
        // email_verified = true so Phase 1.7 login gate does not return 403 EMAIL_NOT_VERIFIED
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, ownerEmail, hash);

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + ownerId);

        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "master-" + System.nanoTime() + "@beautica.test";
        // email_verified = true — consistent with contract even though master never logs in here
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, 'SALON_MASTER', ?, true, true)",
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
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) VALUES (?, 'SALON', ?, 'Test Service', 60, 500.00, 0, true, NOW(), NOW())",
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

    private UUID createBooking(String clientToken, UUID masterId, UUID masterServiceId) throws Exception {
        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        var request = new CreateBookingRequest(masterId, masterServiceId, startsAt, null, null);

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<BookingResponse>>() {});
        return body.data().id();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
