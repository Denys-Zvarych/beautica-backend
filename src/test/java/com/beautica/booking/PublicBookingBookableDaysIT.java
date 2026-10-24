package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.beautica.booking.PublicBookingPageFixtures.randomIp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 387 — {@code GET /api/v1/book/{slug}/bookable-days} end to end (real Security chain, real Postgres, no
 * Authorization header): the public calendar day-gate answers from the app's own day-gate core, so only dates
 * with a free slot long enough for the (summed) service duration are {@code working = true}.
 *
 * <p>The window always starts at {@code today + 1} so the lead-time cutoff (which can legitimately empty TODAY late
 * in the day) never makes an assertion clock-dependent; 6 consecutive dates have 6 distinct ISO weekdays.
 */
@DisplayName("GET /book/{slug}/bookable-days — public calendar day-gate (Phase 387, Testcontainers)")
class PublicBookingBookableDaysIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final int GUEST_WINDOW_DAYS = 60;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private PublicBookingPageFixtures pages;
    private LocalDate today;
    /** A fresh source IP per test, as PublicMasterSlugInfoEnrichedIT does, so the shared per-IP availability bucket never trips. */
    private String clientIp;

    @BeforeEach
    void setUp() {
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
        today = LocalDate.now(KYIV);
        clientIp = randomIp();
    }

    @Test
    @DisplayName("schedule on ONE weekday only → only that date in the window is bookable")
    void should_markOnlyScheduledWeekdayBookable_when_masterWorksOneWeekday() throws Exception {
        var master = pages.independentMaster(false);
        UUID svc = pages.assignService(master.masterId());
        LocalDate workDay = today.plusDays(3);
        seedSingleWeekday(master.masterId(), workDay, LocalTime.of(9, 0), LocalTime.of(17, 0));

        Map<LocalDate, Boolean> days = bookableDays(master.slug(), today.plusDays(1), today.plusDays(6), svc);

        assertThat(days).hasSize(6);
        days.forEach((date, working) -> assertThat(working)
                .as("date %s (only %s is scheduled)", date, workDay)
                .isEqualTo(date.equals(workDay)));
    }

    @Test
    @DisplayName("two services (60+60 min) → a day with only a 60-minute window is NOT bookable; one service → it is")
    void should_sumServiceDurations_when_serviceIdRepeated() throws Exception {
        var master = pages.independentMaster(false);
        UUID first = pages.assignService(master.masterId());
        UUID second = pages.assignService(master.masterId());
        LocalDate longDay = today.plusDays(2);
        LocalDate shortDay = today.plusDays(3);
        UUID scheduleId = BookableMasterSeeder.seedWeeklyTemplate(jdbcTemplate, master.masterId(), today, null, false);
        pages.addInterval(scheduleId, longDay, LocalTime.of(9, 0), LocalTime.of(17, 0));
        pages.addInterval(scheduleId, shortDay, LocalTime.of(9, 0), LocalTime.of(10, 0));

        Map<LocalDate, Boolean> single = bookableDays(master.slug(), longDay, shortDay, first);
        Map<LocalDate, Boolean> visit = fetch("/api/v1/book/" + master.slug() + "/bookable-days?from=" + longDay
                + "&to=" + shortDay + "&serviceId=" + first + "&serviceId=" + second);

        assertThat(single).containsEntry(longDay, true).containsEntry(shortDay, true);
        assertThat(visit).containsEntry(longDay, true).containsEntry(shortDay, false);
    }

    @Test
    @DisplayName("a day is bookable iff /availability returns ≥1 slot for it (same oracle)")
    void should_agreeWithAvailability_when_sameMasterAndService() throws Exception {
        var master = pages.independentMaster(false);
        UUID svc = pages.assignService(master.masterId());
        LocalDate workDay = today.plusDays(4);
        seedSingleWeekday(master.masterId(), workDay, LocalTime.of(9, 0), LocalTime.of(12, 0));

        Map<LocalDate, Boolean> days = bookableDays(master.slug(), today.plusDays(1), today.plusDays(6), svc);

        for (Map.Entry<LocalDate, Boolean> e : days.entrySet()) {
            ResponseEntity<String> slots = anonymousGet("/api/v1/book/" + master.slug()
                    + "/availability?date=" + e.getKey() + "&serviceId=" + svc);
            assertThat(slots.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(objectMapper.readTree(slots.getBody()).size() > 0)
                    .as("bookable-days and /availability disagree on %s", e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    @Test
    @DisplayName("salon link: a salon master's slug (as listed by /book/s/{slug}/info) serves its bookable days")
    void should_serveSalonMasterDays_when_masterSlugFromSalonPage() throws Exception {
        var salon = pages.salon("Студія 387");
        UUID masterId = pages.salonMaster(salon.salonId(), "Анна", "Біла");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), masterId);
        UUID svc = masterServiceIdOf(masterId);
        String masterSlug = pages.masterSlugOf(masterId);

        Map<LocalDate, Boolean> days = bookableDays(masterSlug, today.plusDays(1), today.plusDays(3), svc);

        assertThat(days).hasSize(3).allSatisfy((date, working) -> assertThat(working).isTrue());
    }

    @Test
    @DisplayName("salon deactivated → its master's bookable-days is 404, like /availability")
    void should_return404_when_salonInactive() throws Exception {
        var salon = pages.salon("Закрита 387");
        UUID masterId = pages.salonMaster(salon.salonId(), "Ірина", "Сіра");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), masterId);
        UUID svc = masterServiceIdOf(masterId);
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salon.salonId());

        ResponseEntity<String> resp = get(pages.masterSlugOf(masterId), today.plusDays(1), today.plusDays(2), svc);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("unknown slug, foreign service and inactive service → 404 (no oracle without a valid pair)")
    void should_return404_when_slugUnknownOrServiceForeignOrInactive() throws Exception {
        var master = pages.independentMaster(true);
        var other = pages.independentMaster(true);
        UUID ownSvc = masterServiceIdOf(master.masterId());
        UUID foreignSvc = masterServiceIdOf(other.masterId());
        LocalDate from = today.plusDays(1);

        ResponseEntity<String> unknownSlug = get("no-such-slug-387", from, from, ownSvc);
        ResponseEntity<String> foreign = get(master.slug(), from, from, foreignSvc);
        ResponseEntity<String> random = get(master.slug(), from, from, UUID.randomUUID());
        jdbcTemplate.update("UPDATE master_services SET is_active = false WHERE id = ?", ownSvc);
        ResponseEntity<String> inactive = get(master.slug(), from, from, ownSvc);

        assertThat(unknownSlug.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(random.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inactive.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("from in the past, to beyond the guest window, from > to → 400")
    void should_return400_when_rangeOutsideGuestWindowOrInverted() throws Exception {
        var master = pages.independentMaster(true);
        UUID svc = masterServiceIdOf(master.masterId());

        ResponseEntity<String> past = get(master.slug(), today.minusDays(1), today.plusDays(1), svc);
        ResponseEntity<String> tooFar = get(master.slug(), today, today.plusDays(GUEST_WINDOW_DAYS + 1), svc);
        ResponseEntity<String> inverted = get(master.slug(), today.plusDays(2), today.plusDays(1), svc);
        ResponseEntity<String> lastDay = get(master.slug(), today.plusDays(GUEST_WINDOW_DAYS),
                today.plusDays(GUEST_WINDOW_DAYS), svc);

        assertThat(past.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooFar.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inverted.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(lastDay.getStatusCode()).as("the guest window's last day is inclusive").isEqualTo(HttpStatus.OK);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private UUID masterServiceIdOf(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? LIMIT 1", UUID.class, masterId);
    }

    private void seedSingleWeekday(UUID masterId, LocalDate day, LocalTime start, LocalTime end) {
        UUID scheduleId = BookableMasterSeeder.seedWeeklyTemplate(jdbcTemplate, masterId, today, null, false);
        pages.addInterval(scheduleId, day, start, end);
    }

    private ResponseEntity<String> get(String slug, LocalDate from, LocalDate to, UUID svc) {
        return anonymousGet("/api/v1/book/" + slug + "/bookable-days?from=" + from + "&to=" + to
                + "&serviceId=" + svc);
    }

    private Map<LocalDate, Boolean> bookableDays(String slug, LocalDate from, LocalDate to, UUID svc) throws Exception {
        return fetch("/api/v1/book/" + slug + "/bookable-days?from=" + from + "&to=" + to + "&serviceId=" + svc);
    }

    /** No Authorization header — the route is permitAll. */
    private ResponseEntity<String> anonymousGet(String url) {
        return pages.anonymousGet(url, clientIp);
    }

    private Map<LocalDate, Boolean> fetch(String url) throws Exception {
        ResponseEntity<String> resp = anonymousGet(url);
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        Map<LocalDate, Boolean> out = new LinkedHashMap<>();
        for (JsonNode day : objectMapper.readTree(resp.getBody())) {
            assertThat(day.size()).as("only {date, working} — no hours, no ids").isEqualTo(2);
            out.put(LocalDate.parse(day.get("date").asText()), day.get("working").asBoolean());
        }
        return out;
    }
}
