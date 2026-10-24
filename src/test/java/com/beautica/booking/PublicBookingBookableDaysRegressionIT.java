package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.PublicBookingPageFixtures.IndependentMaster;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.SmsService;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.beautica.booking.PublicBookingPageFixtures.randomIp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 387 QA regression net — the {@code GET /api/v1/book/{slug}/bookable-days} contracts that
 * {@link PublicBookingBookableDaysIT} does not pin, all over real Postgres and the real security chain:
 *
 * <ul>
 *   <li>multi-service (N&gt;1) day-gate agrees with multi-service {@code /availability} date by date (D1/D9);</li>
 *   <li>the (cached, D9) day-gate flips to {@code working = false} right after a guest booking takes the last
 *       fitting slot — single- AND multi-service keys are evicted by the booking write;</li>
 *   <li>the span-weighted charge (D8) is enforced by the REAL filter chain: 12 full-window reads exhaust the
 *       shared 60-token bucket, the 13th — and any {@code /availability} from that IP — is 429;</li>
 *   <li>an inactive salon 404s deterministically even when its master's day-gate is already cached;</li>
 *   <li>an empty {@code serviceId=} value is a client error, never a 500.</li>
 * </ul>
 *
 * <p>Same context shape as {@code GuestBookingCommentIT} ({@link TestSecurityConfig} + mocked {@link SmsService})
 * so the Spring context is reused. Every request carries a fresh random {@code X-Forwarded-For} unless the test
 * is about the per-IP bucket, so the shared guest buckets never leak budget across tests.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /book/{slug}/bookable-days — QA regression net (Phase 387, Testcontainers)")
class PublicBookingBookableDaysRegressionIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final int GUEST_WINDOW_DAYS = 60;
    /** Production capacity of guestAvailabilityBuckets (not overridable — built inside the filter). */
    private static final int BUCKET_CAPACITY = 60;
    /** 1 + 61/14 = 5 tokens for one service over the whole guest window (D8). */
    private static final int FULL_WINDOW_COST = 5;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GuestTokenProvider guestTokenProvider;
    @MockBean private SmsService smsService;

    private PublicBookingPageFixtures pages;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
        today = LocalDate.now(KYIV);
    }

    // ── multi-service ⇔ /availability ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("two services (60+60): bookable-days agrees with multi-service /availability on every date, "
            + "including days where a booking leaves only 60-minute gaps")
    void should_agreeWithMultiServiceAvailability_when_visitOfTwoServices() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID first = masterServiceOf(master.serviceDefId());
        UUID second = pages.assignService(master.masterId());
        BookableMasterSeeder.removeSchedule(jdbcTemplate, master.masterId());
        UUID scheduleId = BookableMasterSeeder.seedWeeklyTemplate(jdbcTemplate, master.masterId(), today, null, false);
        LocalDate longDay = today.plusDays(1);     // 09-17 free             → visit fits
        LocalDate shortDay = today.plusDays(2);    // 09-10 only             → visit does not fit
        LocalDate splitDay = today.plusDays(3);    // 09-12, booked 10-11    → only 60-min gaps
        LocalDate exactDay = today.plusDays(4);    // 09-11 free             → visit fits exactly
        pages.addInterval(scheduleId, longDay, LocalTime.of(9, 0), LocalTime.of(17, 0));
        pages.addInterval(scheduleId, shortDay, LocalTime.of(9, 0), LocalTime.of(10, 0));
        pages.addInterval(scheduleId, splitDay, LocalTime.of(9, 0), LocalTime.of(12, 0));
        pages.addInterval(scheduleId, exactDay, LocalTime.of(9, 0), LocalTime.of(11, 0));
        assertCreated(guestBook(master, first, splitDay, LocalTime.of(10, 0)));
        String services = "&serviceId=" + first + "&serviceId=" + second;

        Map<LocalDate, Boolean> visit = bookableDays(master.slug(), longDay, today.plusDays(6), services, randomIp());

        assertThat(visit)
                .as("non-vacuous fixture: both verdicts must occur")
                .containsEntry(longDay, true).containsEntry(exactDay, true)
                .containsEntry(shortDay, false).containsEntry(splitDay, false);
        for (Map.Entry<LocalDate, Boolean> day : visit.entrySet()) {
            ResponseEntity<String> slots = anonymousGet("/api/v1/book/" + master.slug() + "/availability?date="
                    + day.getKey() + services, randomIp());
            assertThat(slots.getStatusCode()).as("body=%s", slots.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(objectMapper.readTree(slots.getBody()).size() > 0)
                    .as("multi-service bookable-days and /availability disagree on %s", day.getKey())
                    .isEqualTo(day.getValue());
        }
    }

    // ── cache eviction on a guest booking ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a guest booking taking the last fitting slot flips the CACHED day-gate to non-working "
            + "(single-service key and multi-service key both evicted)")
    void should_flipDayToNonWorking_when_guestBookingTakesLastFittingSlot() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID first = masterServiceOf(master.serviceDefId());
        UUID second = pages.assignService(master.masterId());
        BookableMasterSeeder.removeSchedule(jdbcTemplate, master.masterId());
        UUID scheduleId = BookableMasterSeeder.seedWeeklyTemplate(jdbcTemplate, master.masterId(), today, null, false);
        LocalDate day = today.plusDays(2);
        // 09:00-11:00: one 60-min service fits at 09:00 and 10:00; the 120-min visit only at 09:00.
        pages.addInterval(scheduleId, day, LocalTime.of(9, 0), LocalTime.of(11, 0));
        String single = "&serviceId=" + first;
        String visit = "&serviceId=" + first + "&serviceId=" + second;
        assertThat(bookableDays(master.slug(), day, day, single, randomIp())).containsEntry(day, true);
        assertThat(bookableDays(master.slug(), day, day, visit, randomIp())).containsEntry(day, true);

        assertCreated(guestBook(master, first, day, LocalTime.of(9, 0)));
        Map<LocalDate, Boolean> visitAfterFirst = bookableDays(master.slug(), day, day, visit, randomIp());
        Map<LocalDate, Boolean> singleAfterFirst = bookableDays(master.slug(), day, day, single, randomIp());
        assertCreated(guestBook(master, first, day, LocalTime.of(10, 0)));
        Map<LocalDate, Boolean> singleAfterSecond = bookableDays(master.slug(), day, day, single, randomIp());

        assertThat(visitAfterFirst)
                .as("multi-service key must be evicted by the 09:00 booking (120 min no longer fits)")
                .containsEntry(day, false);
        assertThat(singleAfterFirst).as("10:00-11:00 still fits one service").containsEntry(day, true);
        assertThat(singleAfterSecond)
                .as("single-service key must be evicted by the 10:00 booking (no slot left)")
                .containsEntry(day, false);
    }

    // ── span-weighted throttle through the real filter chain ────────────────────────────────────

    @Test
    @DisplayName("12 full-window reads (5 tokens each) drain the shared 60-token bucket: the 13th read and an "
            + "/availability from the same IP are 429 with Retry-After; another IP is untouched")
    void should_return429_when_spanWeightedBudgetExhaustedThroughRealFilterChain() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID svc = masterServiceOf(master.serviceDefId());
        String ip = randomIp();
        String fullWindow = "/api/v1/book/" + master.slug() + "/bookable-days?from=" + today
                + "&to=" + today.plusDays(GUEST_WINDOW_DAYS) + "&serviceId=" + svc;
        int affordable = BUCKET_CAPACITY / FULL_WINDOW_COST;

        for (int i = 1; i <= affordable; i++) {
            ResponseEntity<String> ok = anonymousGet(fullWindow, ip);
            assertThat(ok.getStatusCode()).as("full-window read %d of %d is within budget", i, affordable)
                    .isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> overBudget = anonymousGet(fullWindow, ip);
        ResponseEntity<String> availability = anonymousGet("/api/v1/book/" + master.slug()
                + "/availability?date=" + today.plusDays(1) + "&serviceId=" + svc, ip);
        ResponseEntity<String> otherIp = anonymousGet(fullWindow, randomIp());

        assertThat(overBudget.getStatusCode().value())
                .as("a flat 1-token charge would still serve read %d", affordable + 1).isEqualTo(429);
        assertThat(overBudget.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(availability.getStatusCode().value())
                .as("/availability shares the drained bucket").isEqualTo(429);
        assertThat(otherIp.getStatusCode()).as("the bucket is per IP").isEqualTo(HttpStatus.OK);
    }

    // ── inactive salon determinism ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("salon deactivated AFTER its master's day-gate was cached → every read is 404 (slug lookup is "
            + "never served from cache); reactivated → 200 again")
    void should_return404Deterministically_when_salonDeactivatedAfterDayGateCached() throws Exception {
        var salon = pages.salon("Кеш 387");
        UUID masterId = pages.salonMaster(salon.salonId(), "Ольга", "Тиха");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), masterId);
        UUID svc = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? LIMIT 1", UUID.class, masterId);
        String url = "/api/v1/book/" + pages.masterSlugOf(masterId) + "/bookable-days?from=" + today.plusDays(1)
                + "&to=" + today.plusDays(3) + "&serviceId=" + svc;
        assertThat(anonymousGet(url, randomIp()).getStatusCode()).as("warm the cache").isEqualTo(HttpStatus.OK);

        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salon.salonId());
        List<HttpStatus> whileClosed = List.of(
                (HttpStatus) anonymousGet(url, randomIp()).getStatusCode(),
                (HttpStatus) anonymousGet(url, randomIp()).getStatusCode(),
                (HttpStatus) anonymousGet(url, randomIp()).getStatusCode());
        jdbcTemplate.update("UPDATE salons SET is_active = true WHERE id = ?", salon.salonId());
        HttpStatus reopened = (HttpStatus) anonymousGet(url, randomIp()).getStatusCode();

        assertThat(whileClosed).containsOnly(HttpStatus.NOT_FOUND);
        assertThat(reopened).isEqualTo(HttpStatus.OK);
    }

    // ── empty serviceId value ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an empty serviceId= value is a 4xx client error, never a 500")
    void should_return4xx_when_serviceIdValueEmpty() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        LocalDate from = today.plusDays(1);

        ResponseEntity<String> resp = anonymousGet("/api/v1/book/" + master.slug() + "/bookable-days?from=" + from
                + "&to=" + from + "&serviceId=", randomIp());

        assertThat(resp.getStatusCode().is4xxClientError()).as("status=%s body=%s",
                resp.getStatusCode(), resp.getBody()).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private static String randomPhone() {
        return "+38050" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
    }

    private UUID masterServiceOf(UUID serviceDefId) {
        return jdbcTemplate.queryForObject("SELECT id FROM master_services WHERE service_def_id = ?", UUID.class,
                serviceDefId);
    }

    private ResponseEntity<String> guestBook(IndependentMaster master, UUID masterServiceId, LocalDate day,
                                             LocalTime time) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(guestTokenProvider.generate(randomPhone()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", randomIp());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceId", masterServiceId.toString());
        body.put("startsAt", ZonedDateTime.of(day, time, KYIV).toOffsetDateTime().toString());
        body.put("name", "Олена");
        body.put("surname", "Коваль");
        body.put("privacyAccepted", true);
        body.put("privacyPolicyVersion", "2025-02-03");
        return restTemplate.exchange("/api/v1/book/" + master.slug() + "/booking", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
    }

    private static void assertCreated(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as("guest booking body=%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    /** No Authorization header — the route is permitAll. */
    private ResponseEntity<String> anonymousGet(String url, String ip) {
        return pages.anonymousGet(url, ip);
    }

    private Map<LocalDate, Boolean> bookableDays(String slug, LocalDate from, LocalDate to, String serviceParams,
                                                 String ip) throws Exception {
        ResponseEntity<String> resp = anonymousGet("/api/v1/book/" + slug + "/bookable-days?from=" + from
                + "&to=" + to + serviceParams, ip);
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        Map<LocalDate, Boolean> out = new LinkedHashMap<>();
        for (JsonNode day : objectMapper.readTree(resp.getBody())) {
            out.put(LocalDate.parse(day.get("date").asText()), day.get("working").asBoolean());
        }
        return out;
    }
}
