package com.beautica.booking.retention;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.AppointmentRepository;
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
    @Autowired private AppointmentRepository appointmentRepository;
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

    // ── audit-fix cycle 1 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("hardening (audit-fix cycle 1)")
    class Hardening {

        @Test
        @DisplayName("PII can never be written back onto a row marked anonymised — bookings LINK, walk-in, visit header")
        void should_rejectPiiRestore_when_rowMarkedAnonymised() {
            // CANCELLED rows: a client_cancellation_note write-back is then rejected ONLY by the guest
            // CHECK, not also by the note-requires-CANCELLED status CHECK.
            UUID link = fx.link(provider, "CANCELLED", beforeCutoff(1), PHONE, null);
            UUID walkIn = fx.staffWalkIn(provider, "CANCELLED", beforeCutoff(2), PHONE);
            UUID header = fx.appointment("LINK", "CANCELLED", PHONE, null, null);
            fx.visitItem(provider, header, "LINK", "CANCELLED", beforeCutoff(3), PHONE);
            service.anonymiseDue(cutoff);
            assertAnonymised(fx.booking(link));
            assertAnonymised(fx.appointmentRow(header));

            for (Object[] target : List.of(
                    new Object[]{"bookings", link, "chk_bookings_guest_fields"},
                    new Object[]{"bookings", walkIn, "chk_bookings_guest_fields"},
                    new Object[]{"appointments", header, "chk_appointment_guest_fields"})) {
                String table = (String) target[0];
                UUID id = (UUID) target[1];
                String constraint = (String) target[2];
                assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE " + table + " SET guest_phone = ? WHERE id = ?", PHONE, id))
                        .as(table + ": phone restore").isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining(constraint);
                assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE " + table + " SET client_comment = 'Мій номер 0501234567' WHERE id = ?", id))
                        .as(table + ": note restore").isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining(constraint);
                assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE " + table + " SET client_cancellation_note = 'Не встигаю, Олена' WHERE id = ?", id))
                        .as(table + ": cancellation-note restore").isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining(constraint);
                assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE " + table + " SET guest_name = ?, guest_surname = ?, guest_phone = ? WHERE id = ?",
                        GUEST_NAME, GUEST_SURNAME, PHONE, id))
                        .as(table + ": full live-shape restore").isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining(constraint);
            }
        }

        @Test
        @DisplayName("a header entity loaded before the erase and flushed after it cannot write the PII back")
        void should_notRestorePii_when_staleHeaderFlushedAfterErase() {
            UUID header = fx.appointment("LINK", "COMPLETED", PHONE, null, null);
            fx.visitItem(provider, header, "LINK", "COMPLETED",
                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(3), PHONE);
            TransactionTemplate outer = new TransactionTemplate(transactionManager);
            TransactionTemplate inner = new TransactionTemplate(transactionManager);
            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            outer.executeWithoutResult(status -> {
                Appointment stale = appointmentRepository.findById(header).orElseThrow();
                assertThat(stale.getGuestPhone()).isEqualTo(PHONE);
                inner.execute(s -> service.eraseByPhone(PHONE, null));
                stale.setStatus(BookingStatus.CANCELLED);
                appointmentRepository.flush();
            });

            Map<String, Object> row = fx.appointmentRow(header);
            assertThat(row.get("status")).isEqualTo("CANCELLED");
            assertAnonymised(row);
        }

        @Test
        @DisplayName("a child-less header is anonymised only once its own created_at is past the cutoff")
        void should_anonymiseChildlessHeader_onlyWhenDueByItsOwnAge() {
            UUID fresh = fx.appointment("LINK", "CANCELLED", PHONE, null, null);
            UUID old = fx.appointment("LINK", "CANCELLED", PHONE, null, null);
            jdbcTemplate.update("UPDATE appointments SET created_at = ? WHERE id = ?", beforeCutoff(1), old);

            GuestPiiAnonymisationResult result = service.anonymiseDue(cutoff);

            assertThat(result.visitsAnonymised()).isEqualTo(1);
            assertThat(fx.appointmentRow(fresh).get("guest_phone")).isEqualTo(PHONE);
            assertThat(fx.appointmentRow(fresh).get("guest_pii_anonymised_at")).isNull();
            assertAnonymised(fx.appointmentRow(old));
        }

        @Test
        @DisplayName("the leftover sweep anonymises a due header whose children were all anonymised elsewhere")
        void should_sweepLeftoverHeader_when_childrenAlreadyAnonymised() {
            UUID header = fx.appointment("LINK", "COMPLETED", PHONE, null, null);
            UUID item = fx.visitItem(provider, header, "LINK", "COMPLETED", beforeCutoff(2), PHONE);
            jdbcTemplate.update("UPDATE appointments SET created_at = ? WHERE id = ?", beforeCutoff(5), header);
            // Simulates the cross-instance race: the child was claimed, its header was not.
            jdbcTemplate.update("UPDATE bookings SET guest_name = ?, guest_surname = NULL, guest_phone = NULL, "
                    + "client_comment = NULL, client_cancellation_note = NULL, cancel_token = NULL, "
                    + "guest_pii_anonymised_at = now() WHERE id = ?", SENTINEL, item);

            GuestPiiAnonymisationResult result = service.anonymiseDue(cutoff);

            assertThat(result.bookingsAnonymised()).isZero();
            assertThat(result.visitsAnonymised()).isEqualTo(1);
            assertAnonymised(fx.appointmentRow(header));
        }

        @Test
        @DisplayName("a late-recorded past visit's header is due by its children's ends_at, not its own created_at")
        void should_anonymiseHeader_when_allChildrenEndedBeforeCutoff_evenIfHeaderIsFresh() {
            UUID header = fx.appointment("STAFF", "COMPLETED", PHONE, null, provider.ownerUserId());
            UUID item = fx.visitItem(provider, header, "STAFF", "COMPLETED", beforeCutoff(48), PHONE);
            UUID liveHeader = fx.appointment("STAFF", "COMPLETED", PHONE, null, provider.ownerUserId());
            UUID liveOld = fx.visitItem(provider, liveHeader, "STAFF", "COMPLETED", beforeCutoff(50), PHONE);
            fx.visitItem(provider, liveHeader, "STAFF", "COMPLETED",
                    cutoff.atOffset(ZoneOffset.UTC).plusHours(3), PHONE);
            // Simulates the cross-instance race: the children were claimed, their headers were not
            // (header created_at = now, so the created_at sweep alone would keep it for 12 more months).
            for (UUID child : List.of(item, liveOld)) {
                jdbcTemplate.update("UPDATE bookings SET guest_name = ?, guest_surname = NULL, guest_phone = NULL, "
                        + "client_comment = NULL, client_cancellation_note = NULL, cancel_token = NULL, "
                        + "guest_pii_anonymised_at = now() WHERE id = ?", SENTINEL, child);
            }

            GuestPiiAnonymisationResult result = service.anonymiseDue(cutoff);

            assertThat(result.visitsAnonymised()).isEqualTo(1);
            assertAnonymised(fx.appointmentRow(header));
            assertThat(fx.appointmentRow(liveHeader).get("guest_phone"))
                    .as("a header with a child ending after the cutoff stays").isEqualTo(PHONE);
        }

        @Test
        @DisplayName("the tokenless cancel-by-id statements stamp updated_at (D12)")
        void should_stampUpdatedAt_when_cancelledById() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID walkIn = fx.staffWalkIn(provider, "CONFIRMED", now.plusDays(3), PHONE);
            UUID header = fx.appointment("STAFF", "CONFIRMED", PHONE, null, provider.ownerUserId());
            fx.visitItem(provider, header, "STAFF", "CONFIRMED", now.plusDays(4), PHONE);
            jdbcTemplate.update("UPDATE bookings SET updated_at = now() - interval '1 day' WHERE id = ?", walkIn);
            jdbcTemplate.update("UPDATE appointments SET updated_at = now() - interval '1 day' WHERE id = ?", header);
            Object bookingBefore = fx.booking(walkIn).get("updated_at");
            Object headerBefore = fx.appointmentRow(header).get("updated_at");
            TransactionTemplate tx = new TransactionTemplate(transactionManager);

            int bookingRows = tx.execute(s -> bookingRepository.cancelGuestRowById(walkIn));
            int headerRows = tx.execute(s -> appointmentRepository.cancelGuestVisitById(header));

            assertThat(bookingRows).isEqualTo(1);
            assertThat(headerRows).isEqualTo(1);
            assertThat(fx.booking(walkIn).get("status")).isEqualTo("CANCELLED");
            assertThat((java.sql.Timestamp) fx.booking(walkIn).get("updated_at"))
                    .isAfter((java.sql.Timestamp) bookingBefore);
            assertThat((java.sql.Timestamp) fx.appointmentRow(header).get("updated_at"))
                    .isAfter((java.sql.Timestamp) headerBefore);
        }

        @Test
        @DisplayName("erase cancels several upcoming visits of one phone (batch-loaded headers + items)")
        void should_cancelEveryUpcomingVisit_when_phoneHasSeveral() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID v1 = fx.appointment("LINK", "CONFIRMED", PHONE, UUID.randomUUID(), null);
            fx.visitItem(provider, v1, "LINK", "CONFIRMED", now.plusDays(3).plusHours(1), PHONE);
            fx.visitItem(provider, v1, "LINK", "CONFIRMED", now.plusDays(3).plusHours(2), PHONE);
            UUID v2 = fx.appointment("LINK", "CONFIRMED", PHONE, UUID.randomUUID(), null);
            fx.visitItem(provider, v2, "LINK", "CONFIRMED", now.plusDays(6).plusHours(1), PHONE);

            GuestPiiAnonymisationResult result = service.eraseByPhone(PHONE, null);

            assertThat(result.visitsCancelled()).isEqualTo(2);
            assertThat(fx.appointmentRow(v1).get("status")).isEqualTo("CANCELLED");
            assertThat(fx.appointmentRow(v2).get("status")).isEqualTo("CANCELLED");
            assertThat(fx.count("SELECT COUNT(*) FROM bookings WHERE appointment_id IN (?, ?) AND status = 'CANCELLED'",
                    v1, v2)).isEqualTo(3);
            assertThat(fx.count("SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'CLIENT_CANCELLED'"))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("EXPLAIN: every retention claim is answerable from an index whose predicate matches it")
        void should_planClaimsOnIndexes() {
            // Realistic volume + fresh statistics: an empty-table plan is meaningless.
            List<UUID> bookingIds = fx.bulkCompletedLink(
                    provider, 3_000, OffsetDateTime.now(ZoneOffset.UTC).plusDays(200));
            // Open (not yet due) headers must dwarf one claim batch, as in production (12 months of
            // visits vs 500 claimed ids) — at toy sizes a hash join over all headers IS cheaper.
            fx.bulkLinkAppointments(20_000);
            // The 3 000 rows become visit items, one per header — the real visit shape.
            jdbcTemplate.update("""
                    UPDATE bookings b SET appointment_id = h.id
                      FROM (SELECT id, row_number() OVER (ORDER BY id) rn FROM appointments) h,
                           (SELECT id, row_number() OVER (ORDER BY id) rn FROM bookings) bb
                     WHERE bb.id = b.id AND h.rn = bb.rn""");
            jdbcTemplate.execute("ANALYZE bookings");
            jdbcTemplate.execute("ANALYZE appointments");
            TransactionTemplate tx = new TransactionTemplate(transactionManager);

            String bookingClaim = explain(tx, """
                    SELECT x.id FROM bookings x
                     WHERE x.client_id IS NULL AND x.guest_pii_anonymised_at IS NULL
                       AND x.client_detached_at IS NULL AND x.booking_source IN ('LINK', 'STAFF')
                       AND x.ends_at < now()
                     ORDER BY x.ends_at LIMIT 500 FOR UPDATE SKIP LOCKED""");
            String headerOfBookings = explain(tx, """
                    SELECT x.id FROM appointments x
                     WHERE x.id = ANY (ARRAY(SELECT cb.appointment_id FROM bookings cb
                                     WHERE cb.id IN (%s)
                                       AND cb.appointment_id IS NOT NULL))
                       AND x.client_id IS NULL AND x.guest_pii_anonymised_at IS NULL
                       AND x.client_detached_at IS NULL AND x.booking_source IN ('LINK', 'STAFF')
                       AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.appointment_id = x.id
                                          AND b.guest_pii_anonymised_at IS NULL)
                     FOR UPDATE SKIP LOCKED""".formatted(uuidList(bookingIds.subList(0, 500))));
            String endedVisitSweep = explain(tx, """
                    SELECT x.id FROM appointments x
                     WHERE x.id IN (SELECT r.appointment_id FROM bookings r
                                     WHERE r.appointment_id IS NOT NULL
                                       AND r.guest_pii_anonymised_at IS NOT NULL
                                       AND r.guest_pii_anonymised_at >= now() - interval '30 days')
                       AND x.client_id IS NULL AND x.guest_pii_anonymised_at IS NULL
                       AND x.client_detached_at IS NULL AND x.booking_source IN ('LINK', 'STAFF')
                       AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.appointment_id = x.id
                                          AND (b.ends_at >= now() OR b.guest_pii_anonymised_at IS NULL))
                     ORDER BY x.id LIMIT 500 FOR UPDATE SKIP LOCKED""");
            String leftoverSweep = explain(tx, """
                    SELECT x.id FROM appointments x
                     WHERE x.client_id IS NULL AND x.guest_pii_anonymised_at IS NULL
                       AND x.client_detached_at IS NULL AND x.created_at < now()
                       AND x.booking_source IN ('LINK', 'STAFF')
                       AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.appointment_id = x.id
                                          AND b.guest_pii_anonymised_at IS NULL)
                     ORDER BY x.created_at LIMIT 500 FOR UPDATE SKIP LOCKED""");
            String erasePhone = explain(tx, """
                    SELECT id FROM bookings WHERE guest_phone = '+380501234567' AND client_id IS NULL
                       AND guest_pii_anonymised_at IS NULL AND client_detached_at IS NULL""");

            assertThat(bookingClaim).contains("idx_bookings_guest_pii_due");
            // Real batch shape (500 claimed ids): headers are reached BY ID from the claimed bookings
            // in a PK nested loop — never a scan of open headers.
            assertThat(headerOfBookings).contains("Nested Loop Anti Join")
                    .contains("Index Scan using bookings_pkey on bookings cb")
                    .containsPattern("Index Scan (using|on) appointments_pkey")
                    .contains("Index Cond: (id = ANY ((InitPlan 1).col1))")
                    .contains("Index Cond: (appointment_id = x.id)")
                    .doesNotContain("Seq Scan on appointments")
                    .doesNotContain("idx_appointments_guest_phone_open")
                    .doesNotContain("idx_appointments_guest_pii_due");
            assertThat(leftoverSweep).contains("idx_appointments_guest_pii_due");
            assertThat(endedVisitSweep).contains("idx_bookings_guest_pii_anonymised_visit")
                    .doesNotContain("Seq Scan on appointments");
            assertThat(erasePhone).contains("idx_bookings_guest_phone_open");
        }

        private String uuidList(List<UUID> ids) {
            return ids.stream().map(id -> "'" + id + "'::uuid").collect(java.util.stream.Collectors.joining(", "));
        }

        /** Plan with seq scans priced out, so the assertion pins index APPLICABILITY, not table size. */
        private String explain(TransactionTemplate tx, String sql) {
            return tx.execute(s -> {
                jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
                return String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + sql, String.class));
            });
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

            GuestPiiAnonymisationResult result = service.eraseByPhone(PHONE_LOCAL, null);

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
            service.eraseByPhone(PHONE, null);

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

            assertThatThrownBy(() -> service.eraseByPhone(PHONE, null)).isInstanceOf(IllegalStateException.class);

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
                    return service.eraseByPhone(PHONE, null);
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
            service.eraseByPhone(PHONE, null);

            GuestPiiAnonymisationResult repeat = service.eraseByPhone(PHONE, null);
            GuestPiiAnonymisationResult unknown = service.eraseByPhone("+380931234567", null);

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
        service.eraseByPhone(PHONE_LOCAL, null);

        assertThat(output.getAll())
                .contains("Guest PII retention: anonymised")
                .contains("Guest PII erase (ticket none):")
                .doesNotContain("501234567")
                .doesNotContain(GUEST_NAME)
                .doesNotContain(GUEST_SURNAME);
    }
}
