package com.beautica.booking.retention;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.notification.inapp.dto.NotificationResponse;
import com.beautica.notification.inapp.entity.InAppNotification;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.push.PushCopy;
import com.beautica.notification.inapp.repository.InAppNotificationRepository;
import com.beautica.notification.inapp.service.NotificationViewAssembler;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.beautica.booking.retention.GuestPiiFixtures.GUEST_NAME;
import static com.beautica.booking.retention.GuestPiiFixtures.GUEST_SURNAME;
import static com.beautica.booking.retention.GuestPiiFixtures.OTHER_PHONE;
import static com.beautica.booking.retention.GuestPiiFixtures.PHONE;
import static com.beautica.booking.retention.GuestPiiFixtures.PHONE_LOCAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 373 — guest-PII retention job + internal erase, against real Postgres (V199 constraints,
 * {@code FOR UPDATE SKIP LOCKED}, the guest-cancel seams and the provider notifications).
 */
@DisplayName("Guest PII retention + erase (phase 373)")
@ExtendWith(OutputCaptureExtension.class)
class GuestPiiRetentionIT extends AbstractIntegrationTest {

    private static final String SENTINEL = GuestPii.ANONYMISED_NAME;

    @Autowired private GuestPiiAnonymisationService service;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private InAppNotificationRepository inAppNotificationRepository;
    @Autowired private NotificationViewAssembler viewAssembler;
    @Autowired private NotificationOutboxDrainWorker drainWorker;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;

    @SpyBean private BookingRepository bookingRepository;
    @MockBean private SmsService smsService;

    private GuestPiiFixtures fx;
    private GuestPiiFixtures.Provider provider;
    private Instant cutoff;

    @BeforeEach
    void setUp() {
        fx = new GuestPiiFixtures(jdbcTemplate);
        provider = fx.createIndependentMaster();
        cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusMonths(12).toInstant();
    }

    private OffsetDateTime beforeCutoff(long hours) {
        return cutoff.atOffset(ZoneOffset.UTC).minusHours(hours);
    }

    private static void assertAnonymised(Map<String, Object> row) {
        assertThat(row.get("guest_name")).isEqualTo(SENTINEL);
        assertThat(row.get("guest_surname")).isNull();
        assertThat(row.get("guest_phone")).isNull();
        assertThat(row.get("client_comment")).isNull();
        assertThat(row.get("client_cancellation_note")).isNull();
        assertThat(row.get("cancel_token")).isNull();
        assertThat(row.get("guest_pii_anonymised_at")).isNotNull();
    }

    // ── retention job ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("anonymiseDue")
    class Retention {

        @Test
        @DisplayName("anonymises a row ending 1 min before the cutoff and leaves one ending 1 min after byte-identical")
        void should_respectCutoffBoundary_when_rowsStraddleIt() {
            UUID due = fx.link(provider, "COMPLETED", cutoff.atOffset(ZoneOffset.UTC).minusMinutes(1), PHONE, null);
            UUID notDue = fx.link(provider, "COMPLETED", cutoff.atOffset(ZoneOffset.UTC).plusMinutes(1), PHONE, null);
            Map<String, Object> before = fx.booking(notDue);

            GuestPiiAnonymisationResult result = service.anonymiseDue(cutoff);

            assertThat(result.bookingsAnonymised()).isEqualTo(1);
            assertAnonymised(fx.booking(due));
            assertThat(fx.booking(notDue)).isEqualTo(before);
        }

        @Test
        @DisplayName("anonymises every status (incl. a stale CONFIRMED) and keeps status, price, times, master and provider_comment")
        void should_anonymiseAllStatuses_and_keepBusinessFields() {
            List<UUID> ids = new ArrayList<>();
            String[] statuses = {"COMPLETED", "CANCELLED", "DECLINED", "NOT_COMPLETED", "CONFIRMED"};
            for (int i = 0; i < statuses.length; i++) {
                ids.add(fx.link(provider, statuses[i], beforeCutoff(i * 2L + 1), PHONE,
                        "CONFIRMED".equals(statuses[i]) ? UUID.randomUUID() : null));
            }
            List<Map<String, Object>> before = ids.stream().map(fx::booking).toList();

            service.anonymiseDue(cutoff);

            for (int i = 0; i < ids.size(); i++) {
                Map<String, Object> after = fx.booking(ids.get(i));
                Map<String, Object> was = before.get(i);
                assertAnonymised(after);
                for (String kept : List.of("status", "price_at_booking", "starts_at", "ends_at", "master_id",
                        "master_service_id", "provider_comment", "booking_source", "created_at", "reminder_sent")) {
                    assertThat(after.get(kept)).as(kept + " of " + statuses[i]).isEqualTo(was.get(kept));
                }
            }
            assertThat(fx.booking(ids.get(2)).get("provider_comment")).isEqualTo("Клієнт не прийшов");
        }

        @Test
        @DisplayName("never touches an APP booking or its users row, nor a STAFF booking of a registered client")
        void should_leaveRegisteredClientRowsUntouched() {
            UUID clientId = fx.createClient();
            UUID appRow = fx.app(provider, "COMPLETED", beforeCutoff(5), clientId);
            UUID staffRegistered = fx.staffRegistered(provider, "COMPLETED", beforeCutoff(7), clientId);
            Map<String, Object> appBefore = fx.booking(appRow);
            Map<String, Object> staffBefore = fx.booking(staffRegistered);
            Map<String, Object> userBefore = jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", clientId);

            service.anonymiseDue(cutoff);

            assertThat(fx.booking(appRow)).isEqualTo(appBefore);
            assertThat(fx.booking(staffRegistered)).isEqualTo(staffBefore);
            assertThat(jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", clientId)).isEqualTo(userBefore);
        }

        @Test
        @DisplayName("anonymises a STAFF walk-in guest row")
        void should_anonymiseStaffWalkIn() {
            UUID walkIn = fx.staffWalkIn(provider, "COMPLETED", beforeCutoff(3), PHONE);

            service.anonymiseDue(cutoff);

            assertAnonymised(fx.booking(walkIn));
        }

        @Test
        @DisplayName("a visit header is anonymised only once every child is")
        void should_anonymiseVisitHeader_onlyAfterAllChildren() {
            UUID header = fx.appointment("LINK", "COMPLETED", PHONE, null, null);
            UUID oldItem = fx.visitItem(provider, header, "LINK", "COMPLETED", beforeCutoff(2), PHONE);
            UUID recentItem = fx.visitItem(provider, header, "LINK", "COMPLETED",
                    cutoff.atOffset(ZoneOffset.UTC).plusHours(1), PHONE);

            GuestPiiAnonymisationResult first = service.anonymiseDue(cutoff);

            assertThat(first.visitsAnonymised()).isZero();
            assertAnonymised(fx.booking(oldItem));
            assertThat(fx.appointmentRow(header).get("guest_phone")).isEqualTo(PHONE);

            GuestPiiAnonymisationResult second = service.anonymiseDue(cutoff.plusSeconds(7_200));

            assertThat(second.visitsAnonymised()).isEqualTo(1);
            assertAnonymised(fx.booking(recentItem));
            assertAnonymised(fx.appointmentRow(header));
        }

        @Test
        @DisplayName("a second run is a no-op: 0/0 and no updated_at moves")
        void should_beIdempotent_when_runTwice() {
            UUID row = fx.link(provider, "COMPLETED", beforeCutoff(1), PHONE, null);
            service.anonymiseDue(cutoff);
            Object updatedAt = fx.booking(row).get("updated_at");

            GuestPiiAnonymisationResult again = service.anonymiseDue(cutoff);

            assertThat(again.bookingsAnonymised()).isZero();
            assertThat(again.visitsAnonymised()).isZero();
            assertThat(fx.booking(row).get("updated_at")).isEqualTo(updatedAt);
        }

        @Test
        @DisplayName("batches until a short batch; the per-run budget leaves the remainder for the next run")
        void should_batch_and_respectRunBudget() {
            fx.bulkCompletedLink(provider, 10, beforeCutoff(1));

            assertThat(service.anonymiseDue(cutoff, 3, 10).bookingsAnonymised()).isEqualTo(10);

            fx.bulkCompletedLink(provider, 10, beforeCutoff(100));
            assertThat(service.anonymiseDue(cutoff, 3, 2).bookingsAnonymised()).isEqualTo(6);
            assertThat(service.anonymiseDue(cutoff, 3, 2).bookingsAnonymised()).isEqualTo(4);
        }

        @Test
        @DisplayName("two concurrent claimers split 1 000 rows with no overlap, no deadlock")
        void should_splitWorkWithoutOverlap_when_twoInstancesRunConcurrently() throws Exception {
            fx.bulkCompletedLink(provider, 1_000, beforeCutoff(1));
            OffsetDateTime utcCutoff = cutoff.atOffset(ZoneOffset.UTC);
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<List<UUID>>> futures = new ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        List<UUID> mine = new ArrayList<>();
                        List<UUID> batch;
                        int rounds = 0;
                        do {
                            batch = tx.execute(s -> bookingRepository.anonymiseDueGuestBookings(utcCutoff, SENTINEL, 25));
                            mine.addAll(batch);
                        } while (!batch.isEmpty() && ++rounds < 200); // cap: a broken claim re-claims forever
                        return mine;
                    }));
                }

                start.countDown();
                List<UUID> a = futures.get(0).get(120, TimeUnit.SECONDS);
                List<UUID> b = futures.get(1).get(120, TimeUnit.SECONDS);

                Set<UUID> overlap = new HashSet<>(a);
                overlap.retainAll(b);
                assertThat(overlap).as("no row claimed by both instances").isEmpty();
                assertThat(a.size() + b.size()).isEqualTo(1_000);
                assertThat(new HashSet<>(a).size()).isEqualTo(a.size());
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("rows locked by another instance are skipped, not waited on (SKIP LOCKED)")
        void should_skipLockedRows_when_anotherTransactionHoldsThem() throws Exception {
            List<UUID> ids = fx.bulkCompletedLink(provider, 10, beforeCutoff(1));
            List<UUID> held = ids.subList(0, 4);
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                    held.forEach(id -> jdbcTemplate.queryForList(
                            "SELECT id FROM bookings WHERE id = ? FOR UPDATE", id));
                    locked.countDown();
                    try {
                        release.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
                assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

                Future<GuestPiiAnonymisationResult> run = pool.submit(() -> service.anonymiseDue(cutoff));
                GuestPiiAnonymisationResult result = run.get(15, TimeUnit.SECONDS);

                assertThat(result.bookingsAnonymised()).isEqualTo(6);
                release.countDown();
                holder.get(30, TimeUnit.SECONDS);
                assertThat(service.anonymiseDue(cutoff).bookingsAnonymised()).isEqualTo(4);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("the relaxed CHECK still rejects a non-anonymised LINK row without a phone")
        void should_rejectHalfPopulatedLinkRow() {
            assertThatThrownBy(() -> fx.link(provider, "COMPLETED", beforeCutoff(1), null, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("the scope CHECK rejects marking a registered client's APP row as anonymised")
        void should_rejectAnonymisedMarker_when_rowHasClient() {
            UUID appRow = fx.app(provider, "COMPLETED", beforeCutoff(1), fx.createClient());

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE bookings SET guest_pii_anonymised_at = now() WHERE id = ?", appRow))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ── cancel link + provider view after anonymisation ────────────────────────

    @Nested
    @DisplayName("public cancel link and provider view")
    class CancelLinkAndProviderView {

        @Test
        @DisplayName("an anonymised stale booking's former token answers GET/POST 404 exactly like an unknown token")
        void should_return404_when_tokenOfAnonymisedBooking() throws Exception {
            UUID token = UUID.randomUUID();
            fx.link(provider, "CONFIRMED", beforeCutoff(1), PHONE, token);
            service.anonymiseDue(cutoff);

            ResponseEntity<String> get = restTemplate.getForEntity("/api/v1/book/cancel/" + token, String.class);
            ResponseEntity<String> post = restTemplate.exchange("/api/v1/book/cancel/" + token,
                    HttpMethod.POST, HttpEntity.EMPTY, String.class);
            ResponseEntity<String> unknown = restTemplate.getForEntity(
                    "/api/v1/book/cancel/" + UUID.randomUUID(), String.class);

            assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(post.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(message(get)).isEqualTo(message(unknown));
            assertThat(message(post)).isEqualTo(message(unknown));
        }

        @Test
        @DisplayName("seam regression: a live booking >2 h out still cancels by token with the guest SMS; inside 2 h is 422")
        void should_keepTokenPathBehaviour() {
            UUID farToken = UUID.randomUUID();
            UUID nearToken = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID far = fx.link(provider, "CONFIRMED", now.plusDays(2).plusHours(1), PHONE, farToken);
            fx.link(provider, "CONFIRMED", now.plusMinutes(90), PHONE, nearToken);

            ResponseEntity<String> ok = restTemplate.exchange("/api/v1/book/cancel/" + farToken,
                    HttpMethod.POST, HttpEntity.EMPTY, String.class);
            ResponseEntity<String> tooLate = restTemplate.exchange("/api/v1/book/cancel/" + nearToken,
                    HttpMethod.POST, HttpEntity.EMPTY, String.class);

            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(tooLate.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(fx.booking(far).get("status")).isEqualTo("CANCELLED");
            assertThat(fx.count("SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'CLIENT_CANCELLED' "
                    + "AND aggregate_id = ?", far)).isEqualTo(1);
            verify(smsService).send(eq(PHONE), anyString());
        }

        @Test
        @DisplayName("provider history is unchanged and the booking detail renders «Гість» with no phone")
        void should_keepProviderHistory_and_renderSentinel() {
            UUID row = fx.link(provider, "COMPLETED", beforeCutoff(1), PHONE, null);
            String countSql = "SELECT COUNT(*) FROM bookings WHERE master_id = ? AND status = 'COMPLETED'";
            int before = fx.count(countSql, provider.masterId());

            service.anonymiseDue(cutoff);

            assertThat(fx.count(countSql, provider.masterId())).isEqualTo(before);
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(jwtTokenProvider.generateAccessToken(
                    provider.ownerUserId(), provider.ownerEmail(), Role.INDEPENDENT_MASTER));
            ResponseEntity<String> detail = restTemplate.exchange("/api/v1/bookings/" + row,
                    HttpMethod.GET, new HttpEntity<>(headers), String.class);
            assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(detail.getBody()).contains("\"clientFirstName\":\"" + SENTINEL + "\"")
                    .doesNotContain("501234567").doesNotContain(GUEST_NAME).doesNotContain(GUEST_SURNAME);
        }

        private String message(ResponseEntity<String> response) throws Exception {
            JsonNode body = objectMapper.readTree(response.getBody());
            return body.path("success").asText() + "|" + body.path("message").asText();
        }
    }

    // ── erase by phone ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("eraseByPhone")
    class Erase {

        private UUID a;
        private UUID b1;
        private UUID b2;
        private UUID visit;
        private UUID c;
        private UUID d;
        private UUID e1;
        private UUID e2;

        private void seedFiveShapes() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            a = fx.link(provider, "CONFIRMED", now.plusDays(3).plusHours(1), PHONE, UUID.randomUUID());
            visit = fx.appointment("LINK", "CONFIRMED", PHONE, UUID.randomUUID(), null);
            b1 = fx.visitItem(provider, visit, "LINK", "CONFIRMED", now.plusDays(4).plusHours(1), PHONE);
            b2 = fx.visitItem(provider, visit, "LINK", "CONFIRMED", now.plusDays(4).plusHours(2), PHONE);
            c = fx.link(provider, "CONFIRMED", now.plusMinutes(30), PHONE, UUID.randomUUID());
            d = fx.staffWalkIn(provider, "CONFIRMED", now.plusDays(5).plusHours(1), PHONE);
            e1 = fx.link(provider, "COMPLETED", now.minusDays(30), PHONE, null);
            e2 = fx.link(provider, "CONFIRMED", now.minusDays(2), PHONE, UUID.randomUUID());
        }

        @Test
        @DisplayName("cancels upcoming rows through the guest-cancel path, notifies the provider once each, then wipes everything")
        void should_cancelUpcomingThenWipe_when_phoneHasUpcomingBookings() {
            seedFiveShapes();
            fx.insertOtp(PHONE);
            fx.insertOtp(OTHER_PHONE);
            UUID otherPhoneRow = fx.link(provider, "COMPLETED", beforeCutoff(500), OTHER_PHONE, null);
            Map<String, Object> otherBefore = fx.booking(otherPhoneRow);

            GuestPiiAnonymisationResult result = service.eraseByPhone(PHONE_LOCAL);

            assertThat(result.bookingsCancelled()).isEqualTo(3);
            assertThat(result.visitsCancelled()).isEqualTo(1);
            assertThat(result.bookingsAnonymised()).isEqualTo(7);
            assertThat(result.visitsAnonymised()).isEqualTo(1);
            assertThat(result.otpRowsDeleted()).isEqualTo(1);
            for (UUID id : List.of(a, b1, b2, c, d)) {
                assertThat(fx.booking(id).get("status")).as("upcoming row cancelled").isEqualTo("CANCELLED");
            }
            assertThat(fx.appointmentRow(visit).get("status")).isEqualTo("CANCELLED");
            assertThat(fx.booking(e1).get("status")).isEqualTo("COMPLETED");
            assertThat(fx.booking(e2).get("status")).isEqualTo("CONFIRMED");
            for (UUID id : List.of(a, b1, b2, c, d, e1, e2)) {
                assertAnonymised(fx.booking(id));
            }
            assertAnonymised(fx.appointmentRow(visit));

            for (UUID id : List.of(a, c, d, b1)) {
                assertThat(fx.count("SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'CLIENT_CANCELLED' "
                        + "AND aggregate_id = ? AND payload IS NULL", id)).isEqualTo(1);
            }
            assertThat(fx.count("SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'CLIENT_CANCELLED'"))
                    .isEqualTo(4);
            assertThat(fx.count("SELECT COUNT(*) FROM in_app_notification WHERE type = 'BOOKING_CANCELLED_BY_CLIENT' "
                    + "AND recipient_user_id = ?", provider.ownerUserId())).isEqualTo(4);
            assertThat(fx.count("SELECT COUNT(*) FROM in_app_notification WHERE type = 'BOOKING_CANCELLED_BY_CLIENT' "
                    + "AND appointment_id = ?", visit)).isEqualTo(1);
            verifyNoInteractions(smsService);

            assertThat(fx.count("SELECT COUNT(*) FROM phone_otps WHERE phone = ?", PHONE)).isZero();
            assertThat(fx.count("SELECT COUNT(*) FROM phone_otps WHERE phone = ?", OTHER_PHONE)).isEqualTo(1);
            assertThat(fx.booking(otherPhoneRow)).isEqualTo(otherBefore);
        }

        @Test
        @DisplayName("after the wipe, the drained provider email, the feed view and the push body all say «Гість»")
        void should_renderSentinel_when_notificationsDrainAfterWipe() {
            UUID single = fx.link(provider, "CONFIRMED",
                    OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), PHONE, UUID.randomUUID());
            service.eraseByPhone(PHONE);

            drainWorker.drain();

            ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
            verify(emailNotificationService, atLeastOnce())
                    .sendClientCancelledEmail(eq(provider.ownerEmail()), captor.capture());
            Booking emailed = captor.getValue();
            assertThat(emailed.getId()).isEqualTo(single);
            assertThat(emailed.getGuestName()).isEqualTo(SENTINEL);
            assertThat(emailed.getGuestSurname()).isNull();
            assertThat(emailed.getGuestPhone()).isNull();

            List<InAppNotification> rows = inAppNotificationRepository
                    .findByRecipientUserIdOrderByCreatedAtDescIdDesc(provider.ownerUserId(), PageRequest.of(0, 10))
                    .getContent();
            List<NotificationResponse> views =
                    viewAssembler.assemble(rows, provider.ownerUserId(), Role.INDEPENDENT_MASTER);
            assertThat(views).hasSize(1);
            assertThat(views.get(0).params().counterpartName()).isEqualTo(SENTINEL);
            // PushCopy renders at drain time from the same read-time params → «Гість», never the phone.
            String pushBody = PushCopy.body(InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, false,
                    views.get(0).params());
            assertThat(pushBody).contains(SENTINEL).doesNotContain(GUEST_NAME).doesNotContain("501234567");
        }

        @Test
        @DisplayName("a failure after the cancel step rolls everything back: no cancel, no notification, PII intact")
        void should_rollBackEverything_when_wipeFails() {
            UUID single = fx.link(provider, "CONFIRMED",
                    OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), PHONE, UUID.randomUUID());
            doThrow(new IllegalStateException("simulated wipe failure"))
                    .when(bookingRepository).anonymiseGuestBookingsByPhone(any(), any());

            assertThatThrownBy(() -> service.eraseByPhone(PHONE)).isInstanceOf(IllegalStateException.class);

            Map<String, Object> row = fx.booking(single);
            assertThat(row.get("status")).isEqualTo("CONFIRMED");
            assertThat(row.get("guest_phone")).isEqualTo(PHONE);
            assertThat(row.get("cancel_token")).isNotNull();
            assertThat(fx.count("SELECT COUNT(*) FROM notification_outbox")).isZero();
            assertThat(fx.count("SELECT COUNT(*) FROM in_app_notification")).isZero();
        }

        @Test
        @DisplayName("erase racing a token cancel: the booking is cancelled exactly once, one CLIENT_CANCELLED row")
        void should_cancelExactlyOnce_when_eraseRacesTokenCancel() throws Exception {
            UUID token = UUID.randomUUID();
            UUID single = fx.link(provider, "CONFIRMED",
                    OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), PHONE, token);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> erase = pool.submit(() -> {
                    start.await();
                    return service.eraseByPhone(PHONE);
                });
                Future<ResponseEntity<String>> cancel = pool.submit(() -> {
                    start.await();
                    return restTemplate.exchange("/api/v1/book/cancel/" + token,
                            HttpMethod.POST, HttpEntity.EMPTY, String.class);
                });

                start.countDown();
                erase.get(60, TimeUnit.SECONDS);
                HttpStatus cancelStatus = HttpStatus.valueOf(cancel.get(60, TimeUnit.SECONDS).getStatusCode().value());

                assertThat(cancelStatus).isIn(HttpStatus.NO_CONTENT, HttpStatus.NOT_FOUND);
                assertThat(fx.booking(single).get("status")).isEqualTo("CANCELLED");
                assertThat(fx.count("SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'CLIENT_CANCELLED' "
                        + "AND aggregate_id = ?", single)).isEqualTo(1);
                assertAnonymised(fx.booking(single));
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("unknown phone and a repeat call both return zeros")
        void should_returnZeros_when_nothingLeftToErase() {
            fx.link(provider, "COMPLETED", beforeCutoff(1), PHONE, null);
            service.eraseByPhone(PHONE);

            GuestPiiAnonymisationResult repeat = service.eraseByPhone(PHONE);
            GuestPiiAnonymisationResult unknown = service.eraseByPhone("+380931234567");

            assertThat(repeat).isEqualTo(new GuestPiiAnonymisationResult(0, 0, 0, 0, 0));
            assertThat(unknown).isEqualTo(new GuestPiiAnonymisationResult(0, 0, 0, 0, 0));
        }
    }

    @Test
    @DisplayName("neither the job nor the erase logs the guest's phone or name")
    void should_notLogPii_when_jobAndEraseRun(CapturedOutput output) {
        fx.link(provider, "COMPLETED", beforeCutoff(1), PHONE, null);
        fx.link(provider, "CONFIRMED", OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), PHONE, UUID.randomUUID());

        service.anonymiseDue(cutoff);
        service.eraseByPhone(PHONE_LOCAL);

        assertThat(output.getAll())
                .contains("Guest PII retention: anonymised")
                .contains("Guest PII erase:")
                .doesNotContain("501234567")
                .doesNotContain(GUEST_NAME)
                .doesNotContain(GUEST_SURNAME);
    }
}
