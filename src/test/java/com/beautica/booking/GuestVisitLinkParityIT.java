package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.booking.job.BookingReminderJob;
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
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

        // Place both items inside the sweep's [now+23h, now+25h] reminder window: item0 at now+23h30m,
        // item1 at now+24h (30-min service). Uses the real clock the job reads.
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusHours(23).plusMinutes(30).withSecond(0).withNano(0);
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

    @Test
    @DisplayName("a visit whose chain overlaps an existing CONFIRMED booking → 409 and NOTHING persists "
            + "(zero appointment header, zero item rows) — the whole chain rolls back atomically")
    void should_rollbackWholeVisit_when_chainOverlapsExistingBooking() {
        String slug = "guest-atomic-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = createMasterWithSlug("guest-atomic-master-" + System.nanoTime() + "@beautica.test", slug);
        UUID svc1 = createService(masterId, 30, 0);
        UUID svc2 = createService(masterId, 30, 1);
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0);

        // A CONFIRMED client booking occupies 10:30–11:00 — exactly where the visit's SECOND item
        // (svc2, 10:30–11:00) would land. The whole-span overlap check must reject the visit.
        UUID clientId = seedClient();
        insertClientBooking(masterId, svc2, clientId,
                startsAt.plusMinutes(30).toOffsetDateTime(), startsAt.plusMinutes(60).toOffsetDateTime());

        String ids = "\"" + svc1 + "\",\"" + svc2 + "\"";
        String body = """
                {"masterServiceIds":[%s],"startsAt":"%s","name":"Оксана","surname":"Мельник"}
                """.formatted(ids, startsAt.toOffsetDateTime());
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, guestHeaders()), String.class);

        assertThat(resp.getStatusCode())
                .as("the chain collides with the existing booking → 409")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(appointmentCount())
                .as("atomic rollback: no visit header was persisted")
                .isZero();
        assertThat(bookingCount())
                .as("atomic rollback: only the pre-existing blocker survives — zero visit items persisted")
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

        // item0 at now+24h45m is INSIDE the [now+23h, now+25h] window; item1 (30-min service) at
        // now+25h15m is OUTSIDE it. The query returns only item0, but the visit's dedup UPDATE must
        // still stamp item1.reminder_sent=true.
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV)
                .plusHours(24).plusMinutes(45).withSecond(0).withNano(0);
        GuestBookingResponse created = postVisit(slug, List.of(svc1, svc2), startsAt);
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
        ZonedDateTime visitStart = ZonedDateTime.now(KYIV)
                .plusHours(23).plusMinutes(30).withSecond(0).withNano(0);
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

    private UUID seedClient() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'CLIENT', 'Кліент', 'Тест', true, true)",
                id, "cli-" + id + "@beautica.test");
        return id;
    }

    /** A CONFIRMED registered-client (APP) booking occupying the given span — the atomicity-test blocker. */
    private void insertClientBooking(UUID masterId, UUID masterServiceId, UUID clientId,
                                     OffsetDateTime startsAt, OffsetDateTime endsAt) {
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, created_at, updated_at) "
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
