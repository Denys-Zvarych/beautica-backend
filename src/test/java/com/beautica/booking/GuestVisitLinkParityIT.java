package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.job.BookingReminderJob;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;
import javax.sql.DataSource;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Full-HTTP-stack smoke suite for BE-7 — guest (LINK) parity for multi-service visits. Proves, against a
 * real Postgres instance, that a phone-verified guest can book SEVERAL services from one master as a
 * single back-to-back visit through the public {@code POST /api/v1/book/{slug}/booking} endpoint, cancel
 * the WHOLE visit via one cancel token, and receive exactly ONE reminder for it — while the legacy
 * single-service guest booking + cancel-by-token path stays byte-for-byte intact.
 */
@DisplayName("BE-7 guest LINK multi-service visit — create / whole-visit cancel / one reminder over real Postgres")
class GuestVisitLinkParityIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final String GUEST_PHONE = "+380509998877";

    /**
     * Σ of the effective durations of the two 30-min / 0-buffer services every reminder-sweep test books —
     * the block length {@code SlotCalculationService} sizes its candidate slots to, and therefore the exact
     * width {@link #seedWeekAnchoredAt} must give the seeded interval.
     */
    private static final int CHAIN_MINUTES = 60;

    // A distinct client IP per test (RFC 5737 TEST-NET-3) sent via X-Forwarded-For, so each test gets
    // its OWN per-IP guest-booking / cancel rate-limit bucket (the caps — 5 booking, 10 cancel per 15
    // min — are hardcoded and shared across the cached Spring context, so a shared loopback key would
    // couple these tests to sibling guest-booking ITs).
    private static final java.util.concurrent.atomic.AtomicInteger IP_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(11);
    private final String clientIp = "203.0.113." + IP_SEQ.getAndIncrement();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private GuestTokenProvider guestTokenProvider;

    @Autowired
    private BookingReminderJob bookingReminderJob;

    @Autowired
    private Clock clock;

    @MockBean
    private SmsService smsService;

    /**
     * Spied (not mocked) so {@code should_rollbackWholeVisit_when_chainOverlapsExistingBooking} can inject
     * a deterministic interleaving into the {@code existsOverlap} → {@code flush()} window and, in the same
     * breath, VERIFY that the request actually reached {@code saveAll} — the assertion that keeps that
     * test's rollback counts from going vacuous. Every call still runs the real repository.
     */
    @MockitoSpyBean
    private BookingRepository bookingRepository;

    /**
     * Used only to open a connection OUTSIDE the request's transaction, so a row committed mid-request
     * survives that transaction's rollback. {@code jdbcTemplate} cannot serve here: on the request thread
     * it would join the very transaction under test via {@code DataSourceUtils}.
     */
    @Autowired
    private DataSource dataSource;

    private LocalDate today() {
        return LocalDate.now(clock.withZone(KYIV));
    }

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("a guest 2-service visit creates ONE LINK appointment + TWO chained CONFIRMED items "
            + "back-to-back, each guest-populated, under one cancel token")
    void should_create2ChainedConfirmedItemsUnderOneAppointment_when_guestBooksTwoServices() throws Exception {
        String slug = "guest-visit-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug("guest-visit-master-" + System.nanoTime() + "@beautica.test", slug);
        seedOpenWeek(masterId);
        UUID svc1 = createService(masterId, 30, 0);
        UUID svc2 = createService(masterId, 30, 1);

        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0);

        GuestBookingResponse created = postVisit(slug, List.of(svc1, svc2), startsAt);

        assertThat(created.appointmentId()).as("a multi-service visit carries an appointment id").isNotNull();
        assertThat(created.durationMinutes()).as("summed service duration (30 + 30)").isEqualTo(60);

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT status, starts_at, ends_at, booking_source, guest_phone, client_id, cancel_token "
                        + "FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                created.appointmentId());
        assertThat(items).as("two chained booking items under one appointment").hasSize(2);
        assertThat(items).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("CONFIRMED");
            assertThat(row.get("booking_source")).isEqualTo("LINK");
            assertThat(row.get("guest_phone")).isEqualTo(GUEST_PHONE);
            assertThat(row.get("client_id")).as("a guest item has no registered client").isNull();
            assertThat(row.get("cancel_token")).as("V91 requires a token on every CONFIRMED LINK row").isNotNull();
        });
        assertThat(items.get(0).get("ends_at"))
                .as("items are contiguous: item0 ends exactly where item1 starts")
                .isEqualTo(items.get(1).get("starts_at"));

        Map<String, Object> appt = jdbcTemplate.queryForMap(
                "SELECT status, booking_source, client_id, cancel_token FROM appointments WHERE id = ?",
                created.appointmentId());
        assertThat(appt.get("status")).isEqualTo("CONFIRMED");
        assertThat(appt.get("booking_source")).isEqualTo("LINK");
        assertThat(appt.get("client_id")).as("V126: a LINK visit header has no client").isNull();
        assertThat(appt.get("cancel_token")).as("the visit-level cancel token lives on the header").isNotNull();

        // ONE new-booking outbox row for the whole visit (never one per service).
        Integer newBookingRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'NEW_BOOKING'", Integer.class);
        assertThat(newBookingRows).as("exactly one new-visit notification").isEqualTo(1);
    }

    @Test
    @DisplayName("the visit cancel token cancels BOTH items and the header, freeing the slots")
    void should_cancelWholeVisit_when_visitCancelTokenPosted() throws Exception {
        String slug = "guest-cancel-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug("guest-cancel-master-" + System.nanoTime() + "@beautica.test", slug);
        seedOpenWeek(masterId);
        UUID svc1 = createService(masterId, 30, 0);
        UUID svc2 = createService(masterId, 30, 1);
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(12).withMinute(0).withSecond(0).withNano(0);

        GuestBookingResponse created = postVisit(slug, List.of(svc1, svc2), startsAt);
        String token = created.cancelUrl().substring(created.cancelUrl().lastIndexOf('/') + 1);

        ResponseEntity<Void> cancelResp = restTemplate.exchange(
                "/api/v1/book/cancel/" + token, HttpMethod.POST, new HttpEntity<>(xffHeaders()), Void.class);
        assertThat(cancelResp.getStatusCode())
                .as("the one visit token must cancel the whole visit → 204")
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT status, cancel_token FROM bookings WHERE appointment_id = ?", created.appointmentId());
        assertThat(items).as("both items were cancelled by the one token").hasSize(2);
        assertThat(items).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("CANCELLED");
            assertThat(row.get("cancel_token")).as("per-item tokens nulled on cancel").isNull();
        });

        Map<String, Object> appt = jdbcTemplate.queryForMap(
                "SELECT status, cancel_token FROM appointments WHERE id = ?", created.appointmentId());
        assertThat(appt.get("status")).isEqualTo("CANCELLED");
        assertThat(appt.get("cancel_token")).as("the visit token is consumed (nulled)").isNull();

        // A replayed POST (token already consumed / nulled) is an idempotent 404 — no second cancel.
        ResponseEntity<Void> replay = restTemplate.exchange(
                "/api/v1/book/cancel/" + token, HttpMethod.POST, new HttpEntity<>(xffHeaders()), Void.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a per-item child token on the legacy cancel path 404s and leaves the whole visit "
            + "CONFIRMED (no desync); the header token still cancels the whole visit")
    void should_rejectPerItemTokenAndKeepVisitConfirmed_when_childTokenPostedToLegacyPath() throws Exception {
        String slug = "guest-desync-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug("guest-desync-master-" + System.nanoTime() + "@beautica.test", slug);
        seedOpenWeek(masterId);
        UUID svc1 = createService(masterId, 30, 0);
        UUID svc2 = createService(masterId, 30, 1);
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);

        GuestBookingResponse created = postVisit(slug, List.of(svc1, svc2), startsAt);
        String headerToken = created.cancelUrl().substring(created.cancelUrl().lastIndexOf('/') + 1);

        // A visit child item carries its OWN per-item cancel_token (V91 mandates one on every CONFIRMED
        // LINK row). Pull one directly from the item row and present it to the SAME public cancel endpoint.
        UUID perItemToken = jdbcTemplate.queryForObject(
                "SELECT cancel_token FROM bookings WHERE appointment_id = ? ORDER BY starts_at LIMIT 1",
                UUID.class, created.appointmentId());
        assertThat(perItemToken).as("a child item has its own per-item token").isNotNull();
        assertThat(perItemToken.toString()).as("the per-item token is NOT the header token")
                .isNotEqualTo(headerToken);

        ResponseEntity<Void> perItemResp = restTemplate.exchange(
                "/api/v1/book/cancel/" + perItemToken, HttpMethod.POST,
                new HttpEntity<>(xffHeaders()), Void.class);
        assertThat(perItemResp.getStatusCode())
                .as("a per-item token must be indistinguishable from an unknown token → 404")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // No desync: the header and BOTH items stay CONFIRMED, every per-item token still present.
        List<Map<String, Object>> itemsAfterReject = jdbcTemplate.queryForList(
                "SELECT status, cancel_token FROM bookings WHERE appointment_id = ?", created.appointmentId());
        assertThat(itemsAfterReject).hasSize(2);
        assertThat(itemsAfterReject).allSatisfy(row -> {
            assertThat(row.get("status")).as("the legacy path must NOT cancel a single visit item").isEqualTo("CONFIRMED");
            assertThat(row.get("cancel_token")).as("per-item token untouched").isNotNull();
        });
        Map<String, Object> apptAfterReject = jdbcTemplate.queryForMap(
                "SELECT status, cancel_token FROM appointments WHERE id = ?", created.appointmentId());
        assertThat(apptAfterReject.get("status")).as("the visit header stays CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(apptAfterReject.get("cancel_token")).as("the header token is untouched").isNotNull();

        // The HEADER token still cancels the WHOLE visit → 204, both items + header CANCELLED.
        ResponseEntity<Void> headerResp = restTemplate.exchange(
                "/api/v1/book/cancel/" + headerToken, HttpMethod.POST,
                new HttpEntity<>(xffHeaders()), Void.class);
        assertThat(headerResp.getStatusCode())
                .as("the header token still cancels the whole visit → 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
        List<Map<String, Object>> itemsAfterCancel = jdbcTemplate.queryForList(
                "SELECT status FROM bookings WHERE appointment_id = ?", created.appointmentId());
        assertThat(itemsAfterCancel).hasSize(2);
        assertThat(itemsAfterCancel).allSatisfy(row ->
                assertThat(row.get("status")).isEqualTo("CANCELLED"));
        Map<String, Object> apptAfterCancel = jdbcTemplate.queryForMap(
                "SELECT status FROM appointments WHERE id = ?", created.appointmentId());
        assertThat(apptAfterCancel.get("status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("the reminder sweep sends exactly ONE reminder for a 2-service visit and marks every item")
    void should_sendOneReminder_when_multiServiceVisitDueForReminder() throws Exception {
        String slug = "guest-remind-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug("guest-remind-master-" + System.nanoTime() + "@beautica.test", slug);
        UUID svc1 = createService(masterId, 30, 0);
        UUID svc2 = createService(masterId, 30, 1);

        // Place item0 inside the sweep's [now+23h, now+25h] reminder window at now+23h30m (item1 follows
        // 30 min later). Uses the real clock the job reads. midnightSafeAnchor may push the pair up to
        // CHAIN_MINUTES later so the chain never straddles Kyiv midnight; +23h30m leaves 90 min of head-
        // room below the window's upper edge, so the shifted anchor is always still due in this sweep.
        ZonedDateTime startsAt = midnightSafeAnchor(ZonedDateTime.now(KYIV)
                .plusHours(23).plusMinutes(30).withSecond(0).withNano(0), CHAIN_MINUTES);
        // Schedule anchored at the derived (wall-clock-relative) start, so it is slot #0 — the create
        // path now requires the visit to fall on the master's schedule.
        seedWeekAnchoredAt(masterId, startsAt.toLocalTime(), CHAIN_MINUTES);
        GuestBookingResponse created = postVisit(slug, List.of(svc1, svc2), startsAt);

        // Ignore the confirmation SMS fired at create; measure only the reminder sweep.
        clearInvocations(smsService);

        bookingReminderJob.sendReminders();

        verify(smsService, times(1)).send(org.mockito.ArgumentMatchers.eq(GUEST_PHONE),
                org.mockito.ArgumentMatchers.anyString());

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT reminder_sent FROM bookings WHERE appointment_id = ?", created.appointmentId());
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(row ->
                assertThat(row.get("reminder_sent")).as("every visit item is marked reminded").isEqualTo(true));
    }

    @Test
    @DisplayName("legacy single-service guest booking + cancel-by-token still work (appointment_id NULL)")
    void should_keepLegacySingleServicePathIntact_when_guestBooksOneService() throws Exception {
        String slug = "guest-legacy-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug("guest-legacy-master-" + System.nanoTime() + "@beautica.test", slug);
        seedOpenWeek(masterId);
        UUID svc = createService(masterId, 60, 0);
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(4).withHour(9).withMinute(0).withSecond(0).withNano(0);

        String body = """
                {"serviceId":"%s","startsAt":"%s","name":"Ірина","surname":"Шевчук"}
                """.formatted(svc, startsAt.toOffsetDateTime());
        ResponseEntity<String> createResp = restTemplate.exchange(
                "/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, guestHeaders()), String.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        GuestBookingResponse created = objectMapper.readValue(createResp.getBody(), GuestBookingResponse.class);

        assertThat(created.appointmentId()).as("a legacy single-service booking has NO appointment").isNull();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, booking_source, appointment_id, cancel_token FROM bookings WHERE id = ?",
                created.bookingId());
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("booking_source")).isEqualTo("LINK");
        assertThat(row.get("appointment_id")).as("legacy path keeps appointment_id NULL").isNull();
        assertThat(row.get("cancel_token")).isNotNull();

        String token = created.cancelUrl().substring(created.cancelUrl().lastIndexOf('/') + 1);
        ResponseEntity<Void> cancelResp = restTemplate.exchange(
                "/api/v1/book/cancel/" + token, HttpMethod.POST, new HttpEntity<>(xffHeaders()), Void.class);
        assertThat(cancelResp.getStatusCode())
                .as("legacy single-booking cancel-by-token still returns 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT status, cancel_token FROM bookings WHERE id = ?", created.bookingId());
        assertThat(after.get("status")).isEqualTo("CANCELLED");
        assertThat(after.get("cancel_token")).isNull();
    }

    // ── point 2: multi-service availability sized to the summed back-to-back block ──

    @Test
    @DisplayName("GET /availability?serviceId=A&serviceId=B sizes slots to the Σ block (fewer than either "
            + "alone); a single ?serviceId=A stays the legacy single-service result")
    void should_sizeAvailabilityToSummedBlock_when_twoServicesRequested() throws Exception {
        String slug = "guest-avail-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug("guest-avail-master-" + System.nanoTime() + "@beautica.test", slug);
        UUID svcA = createService(masterId, 60, 0); // effective 60 (buffer 0)
        UUID svcB = createService(masterId, 30, 1); // effective 30
        LocalDate day = today().plusDays(7);
        seedWorkingDay(masterId, day, LocalTime.of(9, 0), LocalTime.of(12, 0));

        List<LocalTime> singleA = availabilityStarts(slug, day, svcA);
        List<LocalTime> singleB = availabilityStarts(slug, day, svcB);
        List<LocalTime> chain = availabilityStarts(slug, day, svcA, svcB);

        // Window 09:00–12:00, step 30. Single 60-min: last 60-min slot starts 11:00 (ends 12:00).
        assertThat(singleA)
                .as("a single ?serviceId=A returns the legacy single-service (60-min) slots, unchanged")
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0),
                        LocalTime.of(10, 30), LocalTime.of(11, 0));

        // Σ=90: only 09:00,09:30,10:00,10:30 leave room for a 90-min block before 12:00 (10:30+90=12:00).
        assertThat(chain)
                .as("the chain is sized to the Σ=90 back-to-back block → 4 starts, fewer than either alone")
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0),
                        LocalTime.of(10, 30));
        assertThat(chain.size())
                .as("the multi-service block offers fewer starts than either single service")
                .isLessThan(singleA.size())
                .isLessThan(singleB.size());
        for (LocalTime start : chain) {
            assertThat(start.plusMinutes(90))
                    .as("the Σ=90 block at %s must not overrun the 12:00 window close", start)
                    .isBeforeOrEqualTo(LocalTime.of(12, 0));
        }
    }

    // ── point 5: XOR of serviceId / masterServiceIds ───────────────────────────

    @Test
    @DisplayName("POST /booking with NEITHER serviceId nor masterServiceIds → 400 (the DTO cannot express "
            + "the XOR, so the service rejects it)")
    void should_return400_when_neitherServiceIdNorMasterServiceIds() {
        String slug = "guest-xor-" + Long.toString(System.nanoTime(), 36);
        createMasterWithSlug("guest-xor-master-" + System.nanoTime() + "@beautica.test", slug);
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0);
        String body = """
                {"startsAt":"%s","name":"Оксана","surname":"Мельник"}
                """.formatted(startsAt.toOffsetDateTime());

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, guestHeaders()), String.class);

        assertThat(resp.getStatusCode())
                .as("a body carrying neither serviceId nor masterServiceIds is a 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bookingCount()).as("nothing persisted on the rejected request").isZero();
        assertThat(appointmentCount()).isZero();
    }

    @Test
    @DisplayName("POST /booking with BOTH serviceId and masterServiceIds → masterServiceIds wins (a visit "
            + "is created; the lone serviceId is ignored)")
    void should_preferMasterServiceIds_when_bothPresent() throws Exception {
        String slug = "guest-both-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug("guest-both-master-" + System.nanoTime() + "@beautica.test", slug);
        seedOpenWeek(masterId);
        UUID svc1 = createService(masterId, 30, 0);
        UUID svc2 = createService(masterId, 30, 1);
        UUID svcLone = createService(masterId, 45, 2);
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(16).withMinute(0).withSecond(0).withNano(0);
        String body = """
                {"serviceId":"%s","masterServiceIds":["%s","%s"],"startsAt":"%s","name":"Оксана","surname":"Мельник"}
                """.formatted(svcLone, svc1, svc2, startsAt.toOffsetDateTime());

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, guestHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        GuestBookingResponse created = objectMapper.readValue(resp.getBody(), GuestBookingResponse.class);

        assertThat(created.appointmentId())
                .as("masterServiceIds takes precedence → a visit (appointment) is created")
                .isNotNull();
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT master_service_id FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                created.appointmentId());
        assertThat(items).as("exactly the two masterServiceIds items — the lone serviceId is ignored")
                .hasSize(2);
        assertThat(items).noneSatisfy(row ->
                assertThat(row.get("master_service_id")).isEqualTo(svcLone));
    }

    // ── point 6: guest identity validation parity on the multi-service path ────

    @Test
    @DisplayName("POST /booking (multi-service) with a control char in the guest name → 400 (same @Pattern "
            + "guard the legacy single path enforces), nothing persisted")
    void should_return400_when_multiServiceGuestNameHasControlChar() {
        String slug = "guest-ctrl-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug("guest-ctrl-master-" + System.nanoTime() + "@beautica.test", slug);
        UUID svc1 = createService(masterId, 30, 0);
        UUID svc2 = createService(masterId, 30, 1);
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(11).withMinute(0).withSecond(0).withNano(0);
        // Embedded NUL in the name — an SMS/log-injection surface the @Pattern rejects.
        String body = """
                {"masterServiceIds":["%s","%s"],"startsAt":"%s","name":"Окса\\u0000на","surname":"Мельник"}
                """.formatted(svc1, svc2, startsAt.toOffsetDateTime());

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, guestHeaders()), String.class);

        assertThat(resp.getStatusCode())
                .as("a control-char guest name is rejected on the multi-service path too → 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(appointmentCount()).as("no visit header persisted for the rejected request").isZero();
        assertThat(bookingCount()).as("no item rows persisted for the rejected request").isZero();
    }

    // ── point 8: whole-visit atomic rollback on a chain overlap ────────────────

    /**
     * <b>Why this test injects an interleaving instead of pre-seeding the blocker.</b>
     *
     * <p>The original arrangement pre-seeded a CONFIRMED booking at 10:30–11:00 and posted a 10:00
     * two-service chain, expecting {@code GuestBookingService}'s span {@code existsOverlap} to reject it.
     * Since the schedule-fit gate landed (2026-08-11) that arrangement is DEAD: the gate runs first
     * ({@code GuestBookingService:265}) and {@code getAvailableSlots} has already subtracted every
     * CONFIRMED booking on the master, so 10:00 is simply absent from the slot list and the gate 409s
     * before the lock is even taken. The request never reached the INSERT, so {@code appointmentCount()
     * == 0} / {@code bookingCount() == 1} were vacuously true and the atomic-rollback behaviour the test
     * exists to prove was not exercised at all.
     *
     * <p>Pre-seeding cannot be salvaged. For committed data the gate strictly DOMINATES
     * {@code existsOverlap}: the slot oracle subtracts exactly the {@code status = 'CONFIRMED'} rows that
     * {@code existsOverlap} scans (over an overlap-, not containment-, scoped day window), and the
     * candidate block it sizes is Σ of the chain's effective durations, i.e. a superset of
     * {@code [firstStart, lastEnd)}. So no blocker can be invisible to the oracle yet visible to the
     * overlap check, and neither can any blocker reach the GIST {@code no_overlapping_bookings} backstop
     * — {@code EXCLUDE fires ⟹ existsOverlap fires ⟹ the gate already rejected}.
     *
     * <p>What DOES reach the insert is the real-world race the backstop exists for: a competing booking
     * committing after both pre-checks have passed. This test reproduces exactly that window
     * deterministically. NOTHING is stubbed — {@code existsOverlap} really runs and really returns
     * {@code false}, because at that moment the blocker genuinely does not exist. It is committed from an
     * INDEPENDENT JDBC connection (its own transaction, so it survives the rollback under test) inside the
     * spy's answer, i.e. between the overlap check and the flush. The subsequent {@code flush()} then
     * trips the EXCLUDE constraint, {@code GuestBookingService} maps the
     * {@code DataIntegrityViolationException} to 409, and the WHOLE visit — header first, then both item
     * rows — rolls back.
     *
     * <p><b>Non-vacuity is asserted in-test, not just argued.</b> {@code verify(bookingRepository)
     * .saveAll(2 items)} proves the request reached the INSERT rather than being rejected early, so the
     * two count assertions can only be satisfied by a genuine insert-then-rollback.
     */
    @Test
    @DisplayName("a visit whose chain collides with a booking committed between the overlap check and the "
            + "flush → 409 from the GIST backstop, and the whole chain rolls back atomically (the INSERT "
            + "was reached: zero appointment header, zero item rows, only the blocker survives)")
    void should_rollbackWholeVisit_when_chainOverlapsExistingBooking() throws Exception {
        String slug = "guest-atomic-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug("guest-atomic-master-" + System.nanoTime() + "@beautica.test", slug);
        // On-schedule so the fit gate passes on its own merits — at gate time the master is genuinely free.
        seedOpenWeek(masterId);
        UUID svc1 = createService(masterId, 30, 0);
        UUID svc2 = createService(masterId, 30, 1);
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID clientId = seedClient();

        // The interleaving: after the REAL existsOverlap has run (and truthfully answered "free"), commit
        // a CONFIRMED booking at 10:30–11:00 — exactly where the visit's SECOND item lands — on its own
        // connection, so it is visible to the pending flush and outlives the rolled-back transaction.
        // The answer forwards to the GENUINE repository (see OverlapRaceSupport#forwardingAnswerOf), so
        // existsOverlap really executes its native query against live DB state — nothing is stubbed.
        Answer<?> forwardToRealRepository = OverlapRaceSupport.forwardingAnswerOf(bookingRepository);

        AtomicBoolean blockerCommitted = new AtomicBoolean(false);
        doAnswer(invocation -> {
            boolean overlapped = (boolean) forwardToRealRepository.answer(invocation);
            if (blockerCommitted.compareAndSet(false, true)) {
                commitBlockerOnItsOwnConnection(masterId, svc2, clientId,
                        startsAt.plusMinutes(30).toOffsetDateTime(),
                        startsAt.plusMinutes(60).toOffsetDateTime());
            }
            return overlapped;
        }).when(bookingRepository).existsOverlap(any(UUID.class), any(), any());

        String ids = "\"" + svc1 + "\",\"" + svc2 + "\"";
        String body = """
                {"masterServiceIds":[%s],"startsAt":"%s","name":"Оксана","surname":"Мельник"}
                """.formatted(ids, startsAt.toOffsetDateTime());
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, guestHeaders()), String.class);

        assertThat(blockerCommitted)
                .as("guard: the interleaving must actually have fired, otherwise everything below is "
                        + "vacuous. status=%s body=%s", resp.getStatusCode(), resp.getBody())
                .isTrue();
        assertThat(resp.getStatusCode())
                .as("the GIST no_overlapping_bookings backstop rejects the chain → 409, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        // NON-VACUITY: the request got past BOTH pre-checks and attempted the INSERT. Without this the
        // two count assertions below would also pass for an early rejection that never wrote a row.
        verify(bookingRepository).saveAll(argThat((Iterable<Booking> items) ->
                items != null && StreamSupport.stream(items.spliterator(), false).count() == 2));
        assertThat(appointmentCount())
                .as("atomic rollback: the appointment header was INSERTed before the failing flush and "
                        + "must not survive it")
                .isZero();
        assertThat(bookingCount())
                .as("atomic rollback: both item rows roll back — only the independently committed blocker "
                        + "survives")
                .isEqualTo(1);
    }

    // ── point 4 residual: reminder dedup — tail-marking + mixed sweep ──────────

    @Test
    @DisplayName("the reminder sweep marks EVERY item of a reminded visit — including a tail item whose own "
            + "startsAt falls OUTSIDE the sweep window — so a later sweep can never re-remind the tail")
    void should_markTailItemOutsideWindow_when_visitReminded() throws Exception {
        String slug = "guest-tail-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug("guest-tail-master-" + System.nanoTime() + "@beautica.test", slug);
        UUID svc1 = createService(masterId, 30, 0);
        UUID svc2 = createService(masterId, 30, 1);

        // Create the visit through the REAL create path at a fixed, midnight-safe anchor, then retime its
        // rows into the sweep window. This test — alone among the three reminder tests — cannot use
        // midnightSafeAnchor: it needs item0 INSIDE [now+23h, now+25h] with item1 (item0 + 30min) OUTSIDE,
        // which pins item0 to a 30-minute band, while the anchors a 60-minute chain must avoid near Kyiv
        // midnight span 60 minutes. The band is provably always narrower than the chain it must dodge
        // (freedom = Σ of the leading items' durations < Σ of ALL of them), so no wall-clock-derived anchor
        // can be both due-in-window and schedule-fittable at every hour of the day. Retiming decouples the
        // sweep — the actual subject here — from the create path's schedule gate entirely.
        ZonedDateTime createdAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0);
        seedOpenWeek(masterId);
        GuestBookingResponse created = postVisit(slug, List.of(svc1, svc2), createdAt);

        // item0 at now+24h45m is INSIDE the [now+23h, now+25h] window; item1 (30-min service) at
        // now+25h15m is OUTSIDE it. The query returns only item0, but the visit's dedup UPDATE must
        // still stamp item1.reminder_sent=true.
        ZonedDateTime item0 = ZonedDateTime.now(KYIV)
                .plusHours(24).plusMinutes(45).withSecond(0).withNano(0);
        retimeVisitItems(created.appointmentId(), item0, 30);
        clearInvocations(smsService);

        bookingReminderJob.sendReminders();

        verify(smsService, times(1)).send(org.mockito.ArgumentMatchers.eq(GUEST_PHONE),
                org.mockito.ArgumentMatchers.anyString());

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT reminder_sent, starts_at FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                created.appointmentId());
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(row ->
                assertThat(row.get("reminder_sent"))
                        .as("every item — incl. the tail outside the window — is marked reminded")
                        .isEqualTo(true));
    }

    @Test
    @DisplayName("a mixed sweep sends exactly ONE reminder for a multi-service visit AND one for a legacy "
            + "single guest booking — no cross-contamination")
    void should_remindVisitOnceAndSingleOnce_when_mixedSweep() throws Exception {
        String visitSlug = "guest-mix-v-" + Long.toString(System.nanoTime(), 36);
        UUID visitMaster = createMasterWithSlug("guest-mix-v-" + System.nanoTime() + "@beautica.test", visitSlug);
        UUID vSvc1 = createService(visitMaster, 30, 0);
        UUID vSvc2 = createService(visitMaster, 30, 1);
        // Same 90-min headroom below the window's upper edge as
        // should_sendOneReminder_when_multiServiceVisitDueForReminder, so midnightSafeAnchor's ≤60-min
        // shift can never push the visit out of this sweep.
        ZonedDateTime visitStart = midnightSafeAnchor(ZonedDateTime.now(KYIV)
                .plusHours(23).plusMinutes(30).withSecond(0).withNano(0), CHAIN_MINUTES);
        // Only the VISIT goes through the create path (and therefore the schedule-fit gate); the legacy
        // single booking below is inserted directly, so its master needs no schedule.
        seedWeekAnchoredAt(visitMaster, visitStart.toLocalTime(), CHAIN_MINUTES);
        GuestBookingResponse visit = postVisit(visitSlug, List.of(vSvc1, vSvc2), visitStart);

        // A legacy single guest booking (appointment_id NULL) with a DISTINCT phone, seeded directly and
        // due in the same sweep window.
        String legacyPhone = "+380671112233";
        String legacySlug = "guest-mix-s-" + Long.toString(System.nanoTime(), 36);
        UUID legacyMaster = createMasterWithSlug("guest-mix-s-" + System.nanoTime() + "@beautica.test", legacySlug);
        UUID lSvc = createService(legacyMaster, 60, 0);
        OffsetDateTime legacyStart = ZonedDateTime.now(KYIV)
                .plusHours(24).withSecond(0).withNano(0).toOffsetDateTime();
        UUID legacyBookingId = insertLegacyGuestBooking(legacyMaster, lSvc, legacyStart, legacyPhone);

        clearInvocations(smsService);
        bookingReminderJob.sendReminders();

        verify(smsService, times(1)).send(org.mockito.ArgumentMatchers.eq(GUEST_PHONE),
                org.mockito.ArgumentMatchers.anyString());
        verify(smsService, times(1)).send(org.mockito.ArgumentMatchers.eq(legacyPhone),
                org.mockito.ArgumentMatchers.anyString());
        verify(smsService, never()).send(org.mockito.ArgumentMatchers.eq(GUEST_PHONE),
                org.mockito.ArgumentMatchers.contains(" ")); // no malformed extra send

        List<Map<String, Object>> visitItems = jdbcTemplate.queryForList(
                "SELECT reminder_sent FROM bookings WHERE appointment_id = ?", visit.appointmentId());
        assertThat(visitItems).hasSize(2);
        assertThat(visitItems).allSatisfy(row ->
                assertThat(row.get("reminder_sent")).isEqualTo(true));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reminder_sent FROM bookings WHERE id = ?", Boolean.class, legacyBookingId))
                .as("the legacy single booking is marked reminded exactly as before BE-7")
                .isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private GuestBookingResponse postVisit(String slug, List<UUID> serviceIds, ZonedDateTime startsAt)
            throws Exception {
        String ids = serviceIds.stream().map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String body = """
                {"masterServiceIds":[%s],"startsAt":"%s","name":"Оксана","surname":"Мельник"}
                """.formatted(ids, startsAt.toOffsetDateTime());
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, guestHeaders()), String.class);
        assertThat(resp.getStatusCode()).as("guest visit creation must succeed").isEqualTo(HttpStatus.CREATED);
        return objectMapper.readValue(resp.getBody(), GuestBookingResponse.class);
    }

    private UUID createMasterWithSlug(String email, String slug) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, true, 'Наталія', 'Бойко')",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, ?, NOW(), NOW())",
                masterId, userId, slug);
        return masterId;
    }

    /** Seeds a master service with the given duration; {@code typeOffset} picks a distinct service_type. */
    private UUID createService(UUID masterId, int durationMinutes, int typeOffset) {
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, ?, ?, ?, 350.00, 0, true, NOW(), NOW())",
                serviceDefId, ownerId, "Послуга-" + typeOffset, resolveServiceTypeId(typeOffset), durationMinutes);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID resolveServiceTypeId(int offset) {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk OFFSET ? LIMIT 1",
                UUID.class, offset);
    }

    private HttpHeaders guestHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(guestTokenProvider.generate(GUEST_PHONE));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", clientIp);
        return headers;
    }

    private HttpHeaders xffHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        return headers;
    }

    /** Kyiv wall-clock start times of the public (no-auth) availability endpoint for one or more services. */
    private List<LocalTime> availabilityStarts(String slug, LocalDate date, UUID... serviceIds) throws Exception {
        StringBuilder url = new StringBuilder("/api/v1/book/" + slug + "/availability?date=" + date);
        for (UUID id : serviceIds) {
            url.append("&serviceId=").append(id);
        }
        ResponseEntity<String> resp = restTemplate.exchange(
                url.toString(), HttpMethod.GET, new HttpEntity<>(xffHeaders()), String.class);
        assertThat(resp.getStatusCode()).as("availability must be 200, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        AvailableSlotResponse[] slots = objectMapper.readValue(resp.getBody(), AvailableSlotResponse[].class);
        return java.util.Arrays.stream(slots)
                .map(s -> s.startsAt().withZoneSameInstant(KYIV).toLocalTime())
                .sorted()
                .toList();
    }

    private UUID seedSchedule(UUID masterId, LocalDate validFrom, LocalDate validTo) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                scheduleId, masterId, validFrom, validTo);
        return scheduleId;
    }

    private void addInterval(UUID scheduleId, int isoDow, LocalTime start, LocalTime end) {
        jdbcTemplate.update(
                "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), scheduleId, isoDow, start, end);
    }

    /** One open-ended working interval on {@code day}'s ISO weekday, valid from today. */
    private void seedWorkingDay(UUID masterId, LocalDate day, LocalTime start, LocalTime end) {
        UUID sched = seedSchedule(masterId, today(), null);
        addInterval(sched, day.getDayOfWeek().getValue(), start, end);
    }

    /**
     * Open-ended weekly schedule, all seven ISO weekdays 08:00–20:00 — required by every test that
     * expects a guest create to SUCCEED, now that all three create paths enforce schedule fit
     * (2026-08-11). Before that gate a master with no schedule at all could still be booked, so these
     * tests never seeded one.
     *
     * <p>Callers must place their {@code startsAt} on the 30-minute grid the slot walker anchors at the
     * interval start (08:00) — i.e. :00 or :30 — otherwise no candidate matches. Wall-clock-relative
     * tests use {@link #seedWeekAnchoredAt} instead.
     */
    private void seedOpenWeek(UUID masterId) {
        UUID sched = seedSchedule(masterId, today(), null);
        for (int isoDow = 1; isoDow <= 7; isoDow++) {
            addInterval(sched, isoDow, LocalTime.of(8, 0), LocalTime.of(20, 0));
        }
    }

    /**
     * Open-ended weekly schedule whose interval is EXACTLY the anchored chain's own window —
     * {@code [start, start + chainMinutes]} on every ISO weekday — for the reminder-sweep tests, whose
     * {@code startsAt} is derived from the real wall clock ({@code now + 23h30m} etc.) and therefore lands
     * on an arbitrary minute. The slot grid is anchored at the interval start, so anchoring the interval
     * at that exact minute makes the chain slot #0 whatever the clock reads.
     *
     * <p><b>End bound = {@code start + chainMinutes}, never a fixed {@code 23:59}.</b> The old fixed bound
     * silently coupled these tests to the time of day: a chain only fits when
     * {@code start + chainMinutes <= end}, so with {@code end = 23:59} every anchor later than
     * {@code 23:59 - chainMinutes} produced an empty slot list and a spurious 409 from the create-path fit
     * gate — roughly two hours of red per day across the three callers. Deriving the end from the chain
     * makes the seeded window fit the chain by construction, for every anchor the model can express.
     *
     * <p><b>The one anchor the model CANNOT express</b> is a chain that would run past Kyiv midnight:
     * {@code chk_interval_order} forbids a cross-midnight interval (a night shift is two single-day rows),
     * and {@code TimeSlotCalculator} bounds candidates by the civil day regardless. Callers must therefore
     * hand this a midnight-safe anchor — see {@link #midnightSafeAnchor}. A wrapping anchor fails FAST and
     * loudly here rather than surfacing as an inscrutable 409 from the create path.
     *
     * @param chainMinutes Σ of the visit's effective service durations — the block the slot walker sizes
     */
    private void seedWeekAnchoredAt(UUID masterId, LocalTime start, int chainMinutes) {
        LocalTime end = start.plusMinutes(chainMinutes);
        if (!end.isAfter(start)) {
            throw new IllegalStateException(
                    "Anchor " + start + " + " + chainMinutes + "min crosses Kyiv midnight; the schedule "
                            + "model cannot express it. Anchor via midnightSafeAnchor(...) first.");
        }
        UUID sched = seedSchedule(masterId, today(), null);
        for (int isoDow = 1; isoDow <= 7; isoDow++) {
            addInterval(sched, isoDow, start, end);
        }
    }

    /**
     * The companion to {@link #seedWeekAnchoredAt}: when a wall-clock-derived visit start would run its
     * chain past Kyiv midnight, pushes it forward ONTO that midnight — the start of the next civil day,
     * where the whole chain provably fits — and returns it unchanged otherwise. The shift is at most
     * {@code chainMinutes}, so a caller whose acceptance window has that much slack after the desired
     * anchor is time-of-day independent at every minute of the day.
     */
    private static ZonedDateTime midnightSafeAnchor(ZonedDateTime desired, int chainMinutes) {
        long secondOfDay = desired.toLocalTime().toSecondOfDay();
        long overflowSeconds = secondOfDay
                + Duration.ofMinutes(chainMinutes).toSeconds()
                - Duration.ofDays(1).toSeconds();
        if (overflowSeconds < 0) {
            return desired;
        }
        // Two details, each a bug the earlier revisions of this method shipped:
        //   * {@code >= 0}, not {@code > 0}: an end of EXACTLY midnight is as unrepresentable as one past
        //     it — LocalTime wraps 24:00 to 00:00, so seedWeekAnchoredAt's end.isAfter(start) is false.
        //   * The shift jumps to the NEXT midnight (a full day minus secondOfDay), NOT forward by the
        //     overflow. Adding only the overflow moves the anchor by old + chain - 86400, which clears
        //     midnight only once old >= 86400 - chain/2; anchors in [23:30, 24:00) landed still-wrapping
        //     and threw. Landing ON 00:00 makes the shifted anchor start the civil day, so it cannot wrap
        //     for any chain shorter than a day, at every one of the 1440 minutes of the clock.
        // The shift is at most chainMinutes, since the branch requires secondOfDay >= 86400 - chain — the
        // ≤60-min bound the two wall-clock callers size their acceptance headroom against. Exact seconds
        // are safe here: Kyiv's DST transitions are at 03:00/04:00 local, never between a late-evening
        // anchor and the midnight that follows it.
        return desired.plusSeconds(Duration.ofDays(1).toSeconds() - secondOfDay);
    }

    /**
     * Re-times an already-created visit's item rows onto a contiguous chain starting at {@code firstStart},
     * each {@code itemMinutes} long, preserving their order. Lets a test place a visit anywhere on the wall
     * clock — including spans the schedule model could not host — after the create path has done its real
     * work. Only {@code bookings} carries times; the {@code appointments} header holds none (V124).
     */
    private void retimeVisitItems(UUID appointmentId, ZonedDateTime firstStart, int itemMinutes) {
        List<UUID> itemIds = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                UUID.class, appointmentId);
        for (int i = 0; i < itemIds.size(); i++) {
            ZonedDateTime start = firstStart.plusMinutes((long) i * itemMinutes);
            jdbcTemplate.update(
                    "UPDATE bookings SET starts_at = ?, ends_at = ? WHERE id = ?",
                    start.toOffsetDateTime(), start.plusMinutes(itemMinutes).toOffsetDateTime(),
                    itemIds.get(i));
        }
    }

    private UUID seedClient() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'CLIENT', 'Кліент', 'Тест', true, true)",
                id, "cli-" + id + "@beautica.test");
        return id;
    }

    /**
     * Commits a CONFIRMED registered-client (APP) booking over the given span in its OWN transaction, so
     * it survives the rollback of the request that is racing it — see
     * {@link OverlapRaceSupport#commitOutsideTransaction}.
     */
    private void commitBlockerOnItsOwnConnection(UUID masterId, UUID masterServiceId, UUID clientId,
                                                 OffsetDateTime startsAt, OffsetDateTime endsAt)
            throws Exception {
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        OverlapRaceSupport.commitOutsideTransaction(dataSource,
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, 0, NOW(), NOW())",
                UUID.randomUUID(), clientId, masterId, masterServiceId, startsAt, endsAt,
                new BigDecimal("350.00"), minutes);
    }

    /** A legacy single guest (LINK) booking (appointment_id NULL, own cancel token), reminder not yet sent. */
    private UUID insertLegacyGuestBooking(UUID masterId, UUID masterServiceId,
                                          OffsetDateTime startsAt, String phone) {
        UUID id = UUID.randomUUID();
        OffsetDateTime endsAt = startsAt.plusMinutes(60);
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, status, booking_source, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "guest_name, guest_surname, guest_phone, cancel_token, reminder_sent, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CONFIRMED', 'LINK', ?, ?, ?, 60, 0, 'Гість', 'Одиночний', ?, ?, false, NOW(), NOW())",
                id, masterId, masterServiceId, startsAt, endsAt, new BigDecimal("350.00"),
                phone, UUID.randomUUID());
        return id;
    }

    private int bookingCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Integer.class);
    }

    private int appointmentCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM appointments", Integer.class);
    }
}
