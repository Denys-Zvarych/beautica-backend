package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.crypto.GuestCancelTokenCipher;
import com.beautica.booking.dto.GuestBookingResponse;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review-trust phase 1 (V206) end-to-end over real HTTP + Postgres: a guest cancel token reaches the
 * guest (create response → cancelUrl) but is stored ONLY as its SHA-256. The hash oracle here is
 * Postgres's own {@code sha256()}, NOT {@code CancelTokenHasher}, so an application hasher that drifts
 * from the DB's (backfill / legacy trigger) preimage turns this red instead of agreeing with itself.
 *
 * <p>Each test then drives the public cancel page: GET info → POST cancel (once) → replayed POST and
 * post-consume GET answer a 404 byte-identical (minus the per-request {@code requestId}) to a never-issued
 * token — so the hash-only storage did not open a token-state oracle.
 */
@DisplayName("Guest cancel token hash-only storage (V206) — public API end-to-end, single booking + visit")
class GuestCancelTokenHashEndToEndIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String GUEST_PHONE = "+380509998866";
    private static final String CANCEL_PATH = "/api/v1/book/cancel/";
    /** {@code app.booking.terminal-view-days} default (30) — the test profile does not override it. */
    private static final Duration TERMINAL_VIEW = Duration.ofDays(30);

    // Own per-IP bucket per test (RFC 5737 TEST-NET-3): the guest-booking, cancel-POST and the new
    // cancel-info caps are per IP and shared across the cached context.
    private static final AtomicInteger IP_SEQ = new AtomicInteger(151);
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
    private Clock clock;

    @Autowired
    private GuestCancelTokenCipher guestCancelTokenCipher;

    @MockBean
    private SmsService smsService;

    private GuestLinkFixtures fixtures;

    @BeforeEach
    void initFixtures() {
        fixtures = new GuestLinkFixtures(jdbcTemplate, passwordEncoder, clock);
    }

    @Test
    @DisplayName("single LINK booking: DB holds only sha256(token); GET info 200; POST cancels once; "
            + "replayed POST and later GET return the unknown-token 404 body")
    void should_storeOnlyHashAndCancelOnce_when_guestSingleBookingCancelledByLink() throws Exception {
        String slug = "hash-e2e-single-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = fixtures.createMasterWithSlug(slug + "@beautica.test", slug);
        fixtures.seedOpenWeek(masterId);
        UUID svc = fixtures.createService(masterId, "Манікюр-хеш", 60, 0);
        ZonedDateTime startsAt = ZonedDateTime.now(clock.withZone(KYIV)).plusDays(3)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);

        GuestBookingResponse created = postBooking(slug, Map.of("serviceId", svc), startsAt);
        String token = tokenOf(created);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT cancel_token, cancel_token_hash, status FROM bookings WHERE id = ?", created.bookingId());
        assertThat(row.get("cancel_token")).as("no plaintext token at rest").isNull();
        assertThat(row.get("cancel_token_hash"))
                .as("the stored form is Postgres sha256 of the token handed to the guest")
                .isEqualTo(pgSha256(token));
        assertThat(rowText("bookings", created.bookingId()))
                .as("the raw token appears in no column of the booking row")
                .doesNotContain(token);
        // Phase 435 (V230): the reversible copy is stored sealed and opens to the guest's token.
        String sealed = jdbcTemplate.queryForObject(
                "SELECT cancel_token_sealed FROM bookings WHERE id = ?", String.class, created.bookingId());
        // Audit-fix: v2:<kid>: format, AES-GCM bound to this row ("bookings:<id>") — sealed after the persist.
        assertThat(sealed).matches("^v2:[0-9a-f]{8}:[A-Za-z0-9+/]+={0,2}$");
        assertThat(guestCancelTokenCipher.open(sealed,
                com.beautica.booking.crypto.GuestCancelTokenCipher.Row.booking(created.bookingId()))).extracting(com.beautica.booking.domain.GuestCancelToken::value).isEqualTo(token);

        setMasterStreet(masterId, "Сагайдачного", "3А");

        ResponseEntity<String> info = get(token);
        assertThat(info.getStatusCode()).as(info.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode infoBody = objectMapper.readTree(info.getBody());
        assertThat(infoBody.path("serviceName").asText()).isEqualTo("Манікюр-хеш");
        assertThat(infoBody.path("cancellable").asBoolean()).isTrue();
        // Phase 435: independent master — no salon name; the master's own street + building is the address.
        assertThat(infoBody.has("salonName")).isTrue();
        assertThat(infoBody.get("salonName").isNull()).isTrue();
        assertThat(infoBody.path("address").asText()).isEqualTo("Сагайдачного 3А");
        // Phase 435 follow-up: additive `locationNote`, null when the master has none.
        assertThat(infoBody.has("locationNote")).isTrue();
        assertThat(infoBody.get("locationNote").isNull()).isTrue();
        assertNoGuestPii(info.getBody());

        assertThat(post(token).getStatusCode()).as("first POST consumes the token").isEqualTo(HttpStatus.NO_CONTENT);
        assertReplayIndistinguishableFromUnknownToken(token);

        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT status, cancel_token_hash FROM bookings WHERE id = ?", created.bookingId());
        assertThat(after.get("status")).isEqualTo("CANCELLED");
        assertThat(after.get("cancel_token_hash")).as("consumed: hash nulled").isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT cancel_token_sealed FROM bookings WHERE id = ?",
                String.class, created.bookingId())).as("consumed: sealed copy nulled with the hash").isNull();
    }

    @Test
    @DisplayName("phase 435: a provider-declined LINK booking — GET 200 read-only view (status DECLINED, not "
            + "cancellable); POST still 404 and changes nothing")
    void should_returnReadOnlyViewAndRefuseCancel_when_bookingDeclined() throws Exception {
        String slug = "hash-e2e-declined-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = fixtures.createMasterWithSlug(slug + "@beautica.test", slug);
        fixtures.seedOpenWeek(masterId);
        UUID svc = fixtures.createService(masterId, "Манікюр-відхилено", 60, 0);
        ZonedDateTime startsAt = ZonedDateTime.now(clock.withZone(KYIV)).plusDays(3)
                .withHour(11).withMinute(0).withSecond(0).withNano(0);
        GuestBookingResponse created = postBooking(slug, Map.of("serviceId", svc), startsAt);
        String token = tokenOf(created);
        jdbcTemplate.update("UPDATE bookings SET status = 'DECLINED' WHERE id = ?", created.bookingId());

        ResponseEntity<String> info = get(token);

        assertThat(info.getStatusCode()).as(info.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(info.getBody());
        assertThat(body.path("status").asText()).isEqualTo("DECLINED");
        assertThat(body.path("cancellable").asBoolean(true)).isFalse();
        assertThat(body.path("serviceName").asText()).isEqualTo("Манікюр-відхилено");
        // Phase 435: the venue fields ride on the read-only view too — this master has no street → null.
        assertThat(body.has("address")).isTrue();
        assertThat(body.get("address").isNull()).isTrue();
        assertThat(body.get("salonName").isNull()).isTrue();
        assertNoGuestPii(info.getBody());
        assertThat(post(token).getStatusCode()).as("no cancel of a declined booking").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class,
                created.bookingId())).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("phase 435: V230 trigger — a writer that nulls only the hash (previous app image) nulls the sealed copy too")
    void should_nullSealedCopy_when_legacyWriterNullsOnlyTheHash() throws Exception {
        String slug = "hash-e2e-trigger-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = fixtures.createMasterWithSlug(slug + "@beautica.test", slug);
        fixtures.seedOpenWeek(masterId);
        UUID svc = fixtures.createService(masterId, "Манікюр-тригер", 60, 0);
        ZonedDateTime startsAt = ZonedDateTime.now(clock.withZone(KYIV)).plusDays(3)
                .withHour(15).withMinute(0).withSecond(0).withNano(0);
        GuestBookingResponse created = postBooking(slug, Map.of("serviceId", svc), startsAt);

        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED', cancel_token_hash = NULL WHERE id = ?",
                created.bookingId());

        assertThat(jdbcTemplate.queryForObject("SELECT cancel_token_sealed FROM bookings WHERE id = ?",
                String.class, created.bookingId())).isNull();
    }

    @Test
    @DisplayName("phase 435 audit-fix: V231 leaves all four cancel_token_sealed CHECKs VALIDATED")
    void should_haveValidatedSealedChecks_when_migrated() {
        Integer validated = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                 WHERE conname IN ('chk_bookings_cancel_token_sealed_format', 'chk_bookings_cancel_token_sealed_pair',
                                   'chk_appointments_cancel_token_sealed_format',
                                   'chk_appointments_cancel_token_sealed_pair')
                   AND convalidated
                """, Integer.class);

        assertThat(validated).isEqualTo(4);
    }

    @Test
    @DisplayName("phase 435 audit-fix: a DECLINED booking's page 404s once it started > terminal-view-days ago")
    void should_return404_when_terminalBookingStartedLongAgo() throws Exception {
        String slug = "hash-e2e-window-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = fixtures.createMasterWithSlug(slug + "@beautica.test", slug);
        fixtures.seedOpenWeek(masterId);
        UUID svc = fixtures.createService(masterId, "Манікюр-вікно", 60, 0);
        ZonedDateTime startsAt = ZonedDateTime.now(clock.withZone(KYIV)).plusDays(3)
                .withHour(16).withMinute(0).withSecond(0).withNano(0);
        GuestBookingResponse created = postBooking(slug, Map.of("serviceId", svc), startsAt);
        String token = tokenOf(created);
        jdbcTemplate.update("UPDATE bookings SET status = 'DECLINED', starts_at = starts_at - interval '40 days', "
                + "ends_at = ends_at - interval '40 days' WHERE id = ?", created.bookingId());

        ResponseEntity<String> info = get(token);

        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("multi-service LINK visit: header holds only sha256(token), items carry their own distinct "
            + "hashes and no plaintext; GET info 200; POST cancels once; replay 404 = unknown-token 404")
    void should_storeOnlyHashAndCancelOnce_when_guestVisitCancelledByLink() throws Exception {
        String slug = "hash-e2e-visit-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = fixtures.createMasterWithSlug(slug + "@beautica.test", slug);
        fixtures.seedOpenWeek(masterId);
        UUID svc1 = fixtures.createService(masterId, "Візит-хеш-1", 30, 0);
        UUID svc2 = fixtures.createService(masterId, "Візит-хеш-2", 30, 1);
        ZonedDateTime startsAt = ZonedDateTime.now(clock.withZone(KYIV)).plusDays(4)
                .withHour(13).withMinute(0).withSecond(0).withNano(0);

        GuestBookingResponse created = postBooking(slug, Map.of("masterServiceIds", List.of(svc1, svc2)), startsAt);
        assertThat(created.appointmentId()).as("two services → one visit header").isNotNull();
        String token = tokenOf(created);
        String expectedHash = pgSha256(token);

        Map<String, Object> header = jdbcTemplate.queryForMap(
                "SELECT cancel_token, cancel_token_hash FROM appointments WHERE id = ?", created.appointmentId());
        assertThat(header.get("cancel_token")).as("no plaintext token on the header").isNull();
        assertThat(header.get("cancel_token_hash")).isEqualTo(expectedHash);
        assertThat(rowText("appointments", created.appointmentId())).doesNotContain(token);
        // Phase 435 (V230): only the header carries the sealed copy (the per-item tokens are never linked).
        assertThat(guestCancelTokenCipher.open(jdbcTemplate.queryForObject(
                "SELECT cancel_token_sealed FROM appointments WHERE id = ?", String.class, created.appointmentId()),
                com.beautica.booking.crypto.GuestCancelTokenCipher.Row.appointment(created.appointmentId())))
                .extracting(com.beautica.booking.domain.GuestCancelToken::value).isEqualTo(token);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE appointment_id = ? AND cancel_token_sealed IS NOT NULL",
                Integer.class, created.appointmentId())).isZero();

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, cancel_token, cancel_token_hash FROM bookings WHERE appointment_id = ?",
                created.appointmentId());
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.get("cancel_token")).as("no plaintext per-item token").isNull();
            assertThat((String) item.get("cancel_token_hash")).matches("^[0-9a-f]{64}$").isNotEqualTo(expectedHash);
            assertThat(rowText("bookings", (UUID) item.get("id"))).doesNotContain(token);
        });
        assertThat(items.get(0).get("cancel_token_hash"))
                .as("each item mints its own token")
                .isNotEqualTo(items.get(1).get("cancel_token_hash"));

        setMasterStreet(masterId, "Хрещатик", "22");

        ResponseEntity<String> info = get(token);
        assertThat(info.getStatusCode()).as(info.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode infoBody = objectMapper.readTree(info.getBody());
        assertThat(infoBody.path("cancellable").asBoolean()).isTrue();
        assertThat(infoBody.get("salonName").isNull()).isTrue();
        assertThat(infoBody.path("address").asText()).isEqualTo("Хрещатик 22");
        assertNoGuestPii(info.getBody());

        assertThat(post(token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertReplayIndistinguishableFromUnknownToken(token);

        Map<String, Object> headerAfter = jdbcTemplate.queryForMap(
                "SELECT status, cancel_token_hash FROM appointments WHERE id = ?", created.appointmentId());
        assertThat(headerAfter.get("status")).isEqualTo("CANCELLED");
        assertThat(headerAfter.get("cancel_token_hash")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT cancel_token_sealed FROM appointments WHERE id = ?",
                String.class, created.appointmentId())).as("consumed: sealed copy nulled").isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE appointment_id = ? AND status = 'CANCELLED' "
                        + "AND cancel_token_hash IS NULL", Integer.class, created.appointmentId()))
                .as("both items cancelled and their hashes nulled")
                .isEqualTo(2);
    }

    // ── phase 435 QA: salon variant ─────────────────────────────────────────────

    @Test
    @DisplayName("phase 435: salon LINK booking — sealed copy bound to its row; GET carries the salon name and the "
            + "SALON's street address (not the master's own)")
    void should_returnSalonNameAndSalonAddress_when_salonBookingOpened() throws Exception {
        String slug = "hash-e2e-salon-" + Long.toString(System.nanoTime(), 36);
        GuestLinkFixtures.SalonMaster salon = fixtures.createSalonOwnerMasterWithSlug(
                slug + "@beautica.test", slug, "Студія Лілія", testCityId());
        fixtures.seedOpenWeek(salon.masterId());
        UUID svc = fixtures.createSalonService(salon.masterId(), salon.salonId(), "Манікюр-салон", 60, 0);
        ZonedDateTime startsAt = ZonedDateTime.now(clock.withZone(KYIV)).plusDays(3)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        GuestBookingResponse created = postBooking(slug, Map.of("serviceId", svc), startsAt);
        String token = tokenOf(created);
        setSalonAddress(salon.salonId(), "Хрещатик", "10", null);
        setMasterStreet(salon.masterId(), "Сагайдачного", "3А");
        jdbcTemplate.update("UPDATE salons SET location_note = ? WHERE id = ?", "  вхід   з двору ", salon.salonId());
        jdbcTemplate.update("UPDATE users SET location_note = 'master note' "
                + "WHERE id = (SELECT user_id FROM masters WHERE id = ?)", salon.masterId());

        ResponseEntity<String> info = get(token);

        assertThat(info.getStatusCode()).as(info.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT salon_id FROM bookings WHERE id = ?", UUID.class,
                created.bookingId())).as("the public route created a SALON booking").isEqualTo(salon.salonId());
        assertThat(guestCancelTokenCipher.open(jdbcTemplate.queryForObject(
                "SELECT cancel_token_sealed FROM bookings WHERE id = ?", String.class, created.bookingId()),
                GuestCancelTokenCipher.Row.booking(created.bookingId()))).extracting(com.beautica.booking.domain.GuestCancelToken::value).isEqualTo(token);
        JsonNode body = objectMapper.readTree(info.getBody());
        assertThat(body.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(body.path("cancellable").asBoolean()).isTrue();
        assertThat(body.path("salonName").asText()).isEqualTo("Студія Лілія");
        assertThat(body.path("address").asText()).as("salon address wins over the master's own").isEqualTo("Хрещатик 10");
        assertThat(body.path("locationNote").asText()).as("the SALON's note (not the master's), whitespace collapsed")
                .isEqualTo("вхід з двору");
        assertNoGuestPii(info.getBody());
    }

    @Test
    @DisplayName("phase 435 follow-up: a LEGACY UUID token (issued before the short format) still opens and cancels")
    void should_acceptLegacyUuidToken_when_issuedBeforeShortFormat() throws Exception {
        String slug = "hash-e2e-legacy-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = fixtures.createMasterWithSlug(slug + "@beautica.test", slug);
        fixtures.seedOpenWeek(masterId);
        UUID svc = fixtures.createService(masterId, "Манікюр-легасі", 60, 0);
        ZonedDateTime startsAt = ZonedDateTime.now(clock.withZone(KYIV)).plusDays(3)
                .withHour(11).withMinute(0).withSecond(0).withNano(0);
        GuestBookingResponse created = postBooking(slug, Map.of("serviceId", svc), startsAt);
        String legacy = UUID.randomUUID().toString();
        // A pre-follow-up row: the hash of a UUID token (the V230 trigger nulls the sealed copy with the new hash).
        jdbcTemplate.update("UPDATE bookings SET cancel_token_hash = ? WHERE id = ?", pgSha256(legacy),
                created.bookingId());

        ResponseEntity<String> info = get(legacy);
        ResponseEntity<String> upperCase = get(legacy.toUpperCase());

        assertThat(info.getStatusCode()).as(info.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(info.getBody()).path("serviceName").asText()).isEqualTo("Манікюр-легасі");
        assertThat(upperCase.getStatusCode()).as("UUID spelling normalised as before").isEqualTo(HttpStatus.OK);
        assertThat(post(legacy).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("phase 435 follow-up: a malformed token is still a 400 before any lookup (both formats validated)")
    void should_return400_when_tokenMalformed() {
        assertThat(get("Ab3dE5gH7jK9mN1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("not-a-token").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("Ab3dE5gH7jK9mN1pq").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("phase 435: salon LINK visit declined by the provider — GET 200 read-only (DECLINED, not cancellable) "
            + "with the salon name and the salon's LEGACY free-text address; POST 404")
    void should_returnReadOnlySalonViewWithLegacyAddress_when_salonVisitDeclined() throws Exception {
        String slug = "hash-e2e-salon-visit-" + Long.toString(System.nanoTime(), 36);
        GuestLinkFixtures.SalonMaster salon = fixtures.createSalonOwnerMasterWithSlug(
                slug + "@beautica.test", slug, "Beauty Room", testCityId());
        fixtures.seedOpenWeek(salon.masterId());
        UUID svc1 = fixtures.createSalonService(salon.masterId(), salon.salonId(), "Салон-візит-1", 30, 0);
        UUID svc2 = fixtures.createSalonService(salon.masterId(), salon.salonId(), "Салон-візит-2", 30, 1);
        ZonedDateTime startsAt = ZonedDateTime.now(clock.withZone(KYIV)).plusDays(4)
                .withHour(11).withMinute(0).withSecond(0).withNano(0);
        GuestBookingResponse created = postBooking(slug, Map.of("masterServiceIds", List.of(svc1, svc2)), startsAt);
        String token = tokenOf(created);
        setSalonAddress(salon.salonId(), null, null, "вул. Велика Васильківська, 5");
        declineVisit(created.appointmentId());

        ResponseEntity<String> info = get(token);

        assertThat(info.getStatusCode()).as(info.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(info.getBody());
        assertThat(body.path("status").asText()).isEqualTo("DECLINED");
        assertThat(body.path("cancellable").asBoolean(true)).isFalse();
        assertThat(body.path("salonName").asText()).isEqualTo("Beauty Room");
        assertThat(body.path("address").asText()).isEqualTo("вул. Велика Васильківська, 5");
        assertNoGuestPii(info.getBody());
        assertThat(post(token).getStatusCode()).as("no cancel of a declined visit").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class,
                created.appointmentId())).isEqualTo("DECLINED");
    }

    // ── phase 435 QA: terminal-view-days (30) boundary ─────────────────────────

    @Test
    @DisplayName("phase 435: a DECLINED booking that started 30 days minus 5 minutes ago is still shown read-only (200)")
    void should_showReadOnlyView_when_terminalBookingStartedJustInsideWindow() throws Exception {
        String token = declinedBookingStartedAgo(TERMINAL_VIEW.minusMinutes(5), "inside");

        ResponseEntity<String> info = get(token);

        assertThat(info.getStatusCode()).as(info.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(info.getBody());
        assertThat(body.path("status").asText()).isEqualTo("DECLINED");
        assertThat(body.path("cancellable").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("phase 435: a DECLINED booking that started 30 days plus 5 minutes ago 404s byte-identical to an "
            + "unknown token")
    void should_return404LikeUnknownToken_when_terminalBookingStartedJustOutsideWindow() throws Exception {
        String token = declinedBookingStartedAgo(TERMINAL_VIEW.plusMinutes(5), "outside");

        ResponseEntity<String> info = get(token);
        ResponseEntity<String> unknown = get(UUID.randomUUID().toString());

        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stableBody(info)).as("no expired-vs-unknown oracle").isEqualTo(stableBody(unknown));
    }

    @Test
    @DisplayName("phase 435: a CONFIRMED booking is never cut off by the terminal window — started 40 days ago, "
            + "GET still 200 (not cancellable: already started)")
    void should_showBooking_when_confirmedBookingStartedLongAgo() throws Exception {
        String slug = "hash-e2e-old-confirmed-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = fixtures.createMasterWithSlug(slug + "@beautica.test", slug);
        fixtures.seedOpenWeek(masterId);
        UUID svc = fixtures.createService(masterId, "Манікюр-старий", 60, 0);
        GuestBookingResponse created = postBooking(slug, Map.of("serviceId", svc),
                ZonedDateTime.now(clock.withZone(KYIV)).plusDays(3).withHour(12).withMinute(0).withSecond(0).withNano(0));
        String token = tokenOf(created);
        moveBookingStart(created.bookingId(), "CONFIRMED", Duration.ofDays(40));

        ResponseEntity<String> info = get(token);

        assertThat(info.getStatusCode()).as(info.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(info.getBody());
        assertThat(body.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(body.path("cancellable").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("phase 435: a DECLINED visit whose items started 31 days ago 404s like an unknown token "
            + "(the single-booking path is not consulted)")
    void should_return404_when_terminalVisitStartedOutsideWindow() throws Exception {
        String slug = "hash-e2e-visit-window-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = fixtures.createMasterWithSlug(slug + "@beautica.test", slug);
        fixtures.seedOpenWeek(masterId);
        UUID svc1 = fixtures.createService(masterId, "Візит-вікно-1", 30, 0);
        UUID svc2 = fixtures.createService(masterId, "Візит-вікно-2", 30, 1);
        GuestBookingResponse created = postBooking(slug, Map.of("masterServiceIds", List.of(svc1, svc2)),
                ZonedDateTime.now(clock.withZone(KYIV)).plusDays(4).withHour(14).withMinute(0).withSecond(0).withNano(0));
        String token = tokenOf(created);
        declineVisit(created.appointmentId());
        jdbcTemplate.update("UPDATE bookings SET starts_at = starts_at - interval '35 days', "
                + "ends_at = ends_at - interval '35 days' WHERE appointment_id = ?", created.appointmentId());

        ResponseEntity<String> info = get(token);
        ResponseEntity<String> unknown = get(UUID.randomUUID().toString());

        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stableBody(info)).isEqualTo(stableBody(unknown));
    }

    /** A LINK booking made through the public route, then DECLINED with its start moved to {@code now - ago}. */
    private String declinedBookingStartedAgo(Duration ago, String tag) throws Exception {
        String slug = "hash-e2e-bound-" + tag + "-" + Long.toString(System.nanoTime(), 36);
        UUID masterId = fixtures.createMasterWithSlug(slug + "@beautica.test", slug);
        fixtures.seedOpenWeek(masterId);
        UUID svc = fixtures.createService(masterId, "Манікюр-межа", 60, 0);
        GuestBookingResponse created = postBooking(slug, Map.of("serviceId", svc),
                ZonedDateTime.now(clock.withZone(KYIV)).plusDays(3).withHour(9).withMinute(0).withSecond(0).withNano(0));
        String token = tokenOf(created);
        moveBookingStart(created.bookingId(), "DECLINED", ago);
        return token;
    }

    /** Sets {@code status} and moves the booking so it started {@code ago} before the application clock's now. */
    private void moveBookingStart(UUID bookingId, String status, Duration ago) {
        OffsetDateTime start = OffsetDateTime.now(clock).minus(ago);
        jdbcTemplate.update("UPDATE bookings SET status = ?, starts_at = ?, ends_at = ? WHERE id = ?",
                status, start, start.plusHours(1), bookingId);
    }

    private void declineVisit(UUID appointmentId) {
        jdbcTemplate.update("UPDATE appointments SET status = 'DECLINED' WHERE id = ?", appointmentId);
        jdbcTemplate.update("UPDATE bookings SET status = 'DECLINED' WHERE appointment_id = ?", appointmentId);
    }

    private void setSalonAddress(UUID salonId, String street, String buildingNo, String legacyAddress) {
        jdbcTemplate.update("UPDATE salons SET street = ?, building_no = ?, address = ? WHERE id = ?",
                street, buildingNo, legacyAddress, salonId);
    }

    // ── assertions ─────────────────────────────────────────────────────────────

    /** Phase 435: the public page carries no guest PII — neither the guest's phone nor their name. */
    private static void assertNoGuestPii(String body) {
        assertThat(body).doesNotContain(GUEST_PHONE).doesNotContain("Оксана").doesNotContain("Мельник");
    }

    private void assertReplayIndistinguishableFromUnknownToken(String consumedToken) throws Exception {
        String unknown = UUID.randomUUID().toString();

        ResponseEntity<String> replayPost = post(consumedToken);
        ResponseEntity<String> unknownPost = post(unknown);
        assertThat(replayPost.getStatusCode()).as("replayed POST").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknownPost.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stableBody(replayPost))
                .as("a consumed token's POST 404 must be identical to a never-issued token's")
                .isEqualTo(stableBody(unknownPost));

        ResponseEntity<String> replayGet = get(consumedToken);
        ResponseEntity<String> unknownGet = get(unknown);
        assertThat(replayGet.getStatusCode()).as("GET after consume").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stableBody(replayGet))
                .as("a consumed token's GET 404 must be identical to a never-issued token's")
                .isEqualTo(stableBody(unknownGet));
    }

    /** The 404 envelope minus {@code requestId}, which legitimately differs per request. */
    private JsonNode stableBody(ResponseEntity<String> response) throws Exception {
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("success").asBoolean(true)).as("an error envelope").isFalse();
        ((ObjectNode) body).remove("requestId");
        return body;
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private GuestBookingResponse postBooking(String slug, Map<String, Object> services, ZonedDateTime startsAt)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(services);
        body.put("startsAt", startsAt.toOffsetDateTime().toString());
        body.put("name", "Оксана");
        body.put("surname", "Мельник");
        body.put("privacyAccepted", true);
        body.put("privacyPolicyVersion", "2026-10-20");
        HttpHeaders headers = xffHeaders();
        headers.setBearerAuth(guestTokenProvider.generate(GUEST_PHONE));
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

        assertThat(resp.getStatusCode()).as("guest create, body=%s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readValue(resp.getBody(), GuestBookingResponse.class);
    }

    private ResponseEntity<String> get(String token) {
        return restTemplate.exchange(CANCEL_PATH + token, HttpMethod.GET, new HttpEntity<>(xffHeaders()), String.class);
    }

    private ResponseEntity<String> post(String token) {
        return restTemplate.exchange(CANCEL_PATH + token, HttpMethod.POST, new HttpEntity<>(xffHeaders()), String.class);
    }

    private HttpHeaders xffHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        return headers;
    }

    private static String tokenOf(GuestBookingResponse created) {
        String url = created.cancelUrl();
        // Phase 435 follow-up: the short link {base}/c/{token}, a NEW token being 16 base62 chars.
        assertThat(url).contains("/c/");
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertThat(token).as("the link carries the raw short token").matches("[0-9A-Za-z]{16}");
        return token;
    }

    private void setMasterStreet(UUID masterId, String street, String buildingNo) {
        jdbcTemplate.update("UPDATE users SET street = ?, building_no = ? "
                + "WHERE id = (SELECT user_id FROM masters WHERE id = ?)", street, buildingNo, masterId);
    }

    // ── DB oracles ─────────────────────────────────────────────────────────────

    private String pgSha256(String token) {
        return jdbcTemplate.queryForObject(
                "SELECT encode(sha256(convert_to(?, 'UTF8')), 'hex')", String.class, token);
    }

    /** The whole row rendered as text — catches the raw token in ANY column, present or future. */
    private String rowText(String table, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT t::text FROM " + table + " t WHERE t.id = ?", String.class, id);
    }
}
