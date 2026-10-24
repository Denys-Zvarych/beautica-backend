package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phases 391–393 QA — the full app-like guest SALON booking story over HTTP only (real Postgres, real security
 * chain), the way {@code beautica.app/book/s/{slug}} drives it:
 *
 * <ol>
 *   <li>{@code GET /book/s/{slug}/info} → {@code serviceCoverage} says who performs what (A: services 1+2, B: 3);</li>
 *   <li>the guest picks services 1 and 3 → the page routes 1 to A and 3 to B from the coverage refs;</li>
 *   <li>{@code bookable-days} / {@code availability} per master reflect each master's OWN schedule
 *       (A works every day 08–20, B only on {@code day}'s weekday 10:00–11:00 = exactly one slot);</li>
 *   <li>ONE guest OTP token → POST A then POST B → two bookings, two cancel links; cancelling A leaves B;</li>
 *   <li>phone visibility (386): owner sees the guest phone on both, the performing salon master never.</li>
 * </ol>
 *
 * <p>Every id a POST uses is read back from the public responses (coverage refs), never from the fixtures, so the
 * test fails if the page's contract stops being sufficient to book. Same context shape as
 * {@code PublicSalonGuestBookingIT} (cached context reused); every request carries a fresh random
 * {@code X-Forwarded-For} so the per-IP guest buckets never leak between tests.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon guest booking — full app-like story over HTTP (phases 391–393, Testcontainers)")
class PublicSalonGuestAppStoryIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String BOOK = "/api/v1/book/";
    private static final String POLICY = "2026-10-20";
    private static final LocalTime B_ONLY_SLOT = LocalTime.of(10, 0);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GuestTokenProvider guestTokenProvider;
    @MockBean private SmsService smsService;

    private BookingTestFixtures fx;
    private PublicBookingPageFixtures pages;
    private BookingTestFixtures.SalonFixture salon;
    private String salonSlug;
    private UUID masterA;
    private UUID masterB;
    private String masterBEmail;
    /** Service DEFINITION ids (what serviceCoverage keys by). */
    private UUID def1;
    private UUID def2;
    private UUID def3;
    private LocalDate day;
    private String guestPhone;

    @BeforeEach
    void setUp() {
        fx = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
        salon = fx.createSalon("story-owner-" + System.nanoTime() + "@beautica.test");
        salonSlug = "st-" + salon.salonId().toString().substring(0, 8) + "-s1";
        jdbcTemplate.update("UPDATE salons SET booking_slug = ? WHERE id = ?", salonSlug, salon.salonId());
        day = LocalDate.now(KYIV).plusDays(2);
        guestPhone = "+38063" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);

        masterA = salon.masterId();
        def1 = defOf(fx.createSalonService(salon.salonId(), masterA));
        def2 = defOf(fx.createSalonService(salon.salonId(), masterA));
        fx.addWorkingHoursForEveryDay(masterA);

        masterB = fx.createExtraSalonMaster(salon.salonId(), "story");
        masterBEmail = jdbcTemplate.queryForObject(
                "SELECT u.email FROM users u JOIN masters m ON m.user_id = u.id WHERE m.id = ?", String.class, masterB);
        def3 = defOf(fx.createSalonService(salon.salonId(), masterB));
        UUID bTemplate = BookableMasterSeeder.seedWeeklyTemplate(jdbcTemplate, masterB, LocalDate.now(KYIV), null, false);
        pages.addInterval(bTemplate, day, B_ONLY_SLOT, B_ONLY_SLOT.plusHours(1));

        for (UUID def : List.of(def1, def2, def3)) {
            pages.categorise(def);
        }
    }

    // ── 1. info: coverage ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("info: serviceCoverage maps services 1+2 → A and 3 → B with their own assignment ids; both masters "
            + "listed by id, the slug-less ones with masterSlug null; parity with the app's per-service master list")
    void should_mapEachServiceToItsPerformer_when_infoFetched() throws Exception {
        JsonNode page = info();

        assertThat(coverageMasters(page, def1)).as("service 1").containsExactly(masterA);
        assertThat(coverageMasters(page, def2)).as("service 2").containsExactly(masterA);
        assertThat(coverageMasters(page, def3)).as("service 3").containsExactly(masterB);
        assertThat(coverageRef(page, def3, masterB)).isEqualTo(assignmentOf(masterB, def3));
        List<UUID> roster = new ArrayList<>();
        page.path("masters").forEach(m -> {
            roster.add(UUID.fromString(m.path("masterId").asText()));
            assertThat(m.path("masterSlug").isNull()).as("slug-less salon master listed with null slug").isTrue();
        });
        assertThat(roster).containsExactlyInAnyOrder(masterA, masterB);
        for (UUID def : List.of(def1, def2, def3)) {
            JsonNode app = objectMapper.readTree(get("/api/v1/salons/" + salon.salonId() + "/services/" + def
                    + "/masters").getBody()).path("data");
            List<String> appPairs = new ArrayList<>();
            app.forEach(m -> appPairs.add(m.path("masterId").asText() + "/" + m.path("masterServiceId").asText()));
            List<String> pagePairs = new ArrayList<>();
            for (UUID m : coverageMasters(page, def)) {
                pagePairs.add(m + "/" + coverageRef(page, def, m));
            }
            assertThat(pagePairs).as("app parity for service %s", def).isNotEmpty()
                    .containsExactlyInAnyOrderElementsOf(appPairs);
        }
    }

    // ── 2. bookable-days / availability per master ──────────────────────────────────────────────

    @Test
    @DisplayName("bookable-days + availability follow each master's OWN schedule: A works all 7 days, B only on "
            + "one weekday with exactly one slot")
    void should_differPerMaster_when_bookableDaysAndAvailabilityQueried() throws Exception {
        JsonNode page = info();
        UUID ref1 = coverageRef(page, def1, masterA);
        UUID ref3 = coverageRef(page, def3, masterB);
        String window = "?from=" + day + "&to=" + day.plusDays(6);

        JsonNode daysA = okJson(get(salonRoute(masterA, "bookable-days") + window + "&serviceId=" + ref1));
        JsonNode daysB = okJson(get(salonRoute(masterB, "bookable-days") + window + "&serviceId=" + ref3));
        JsonNode slotsA = okJson(get(salonRoute(masterA, "availability") + "?date=" + day + "&serviceId=" + ref1));
        JsonNode slotsB = okJson(get(salonRoute(masterB, "availability") + "?date=" + day + "&serviceId=" + ref3));

        assertThat(workingDates(daysA)).as("A: every day of the week").hasSize(7);
        assertThat(workingDates(daysB)).as("B: only its one weekday").containsExactly(day.toString());
        assertThat(slotsA.size()).as("A 08–20 → many slots").isGreaterThan(1);
        assertThat(slotStarts(slotsB)).as("B: exactly one slot").containsExactly(B_ONLY_SLOT);
    }

    // ── 3. one token, two masters, two cancel links ─────────────────────────────────────────────

    @Test
    @DisplayName("guest picks services 1 and 3 → one OTP token, POST A (svc 1) then POST B (svc 3) → 2 bookings, "
            + "2 cancel links; cancelling A leaves B CONFIRMED with a live cancel link")
    void should_bookEachMasterAndCancelIndependently_when_oneTokenSplitsVisitAcrossMasters() throws Exception {
        JsonNode page = info();
        UUID ref1 = coverageRef(page, def1, masterA);
        UUID ref3 = coverageRef(page, def3, masterB);
        String token = guestTokenProvider.generate(guestPhone);

        JsonNode a = created(book(masterA, ref1, LocalTime.of(12, 0), token));
        JsonNode b = created(book(masterB, ref3, B_ONLY_SLOT, token));

        assertThat(serviceDefOfGuestBooking(masterA)).as("A performs service 1").isEqualTo(def1);
        assertThat(serviceDefOfGuestBooking(masterB)).as("B performs service 3").isEqualTo(def3);
        String cancelA = cancelToken(a);
        String cancelB = cancelToken(b);
        assertThat(cancelA).isNotBlank().isNotEqualTo(cancelB);

        ResponseEntity<String> cancelled = post(BOOK + "cancel/" + cancelA, null, null);

        assertThat(cancelled.getStatusCode()).as(cancelled.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(statusOfGuestBooking(masterA)).isEqualTo("CANCELLED");
        assertThat(statusOfGuestBooking(masterB)).as("B untouched by A's cancel").isEqualTo("CONFIRMED");
        assertThat(get(BOOK + "cancel/" + cancelA).getStatusCode()).as("A's link consumed")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(BOOK + "cancel/" + cancelB).getStatusCode()).as("B's link still live")
                .isEqualTo(HttpStatus.OK);
    }

    // ── 4. phone visibility (386) ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("phase 386: owner sees the guest phone on BOTH masters' bookings; each performing salon master gets "
            + "null and the digits never appear in the body")
    void should_showPhoneToOwnerButNotSalonMasters_when_guestBookedTwoMasters() throws Exception {
        JsonNode page = info();
        String token = guestTokenProvider.generate(guestPhone);
        UUID bookingA = bookingId(created(book(masterA, coverageRef(page, def1, masterA), LocalTime.of(12, 0), token)));
        UUID bookingB = bookingId(created(book(masterB, coverageRef(page, def3, masterB), B_ONLY_SLOT, token)));
        String ownerToken = fx.tokenFor(salon.ownerEmail());

        Map<UUID, String> performerToken = Map.of(bookingA, fx.tokenFor(salon.masterEmail()),
                bookingB, fx.tokenFor(masterBEmail));
        for (UUID bookingId : List.of(bookingA, bookingB)) {
            ResponseEntity<String> owner = detail(bookingId, ownerToken);
            ResponseEntity<String> master = detail(bookingId, performerToken.get(bookingId));

            assertThat(okJson(owner).path("data").path("guestPhone").asText()).as("owner, booking %s", bookingId)
                    .isEqualTo(guestPhone);
            assertThat(okJson(master).path("data").path("guestPhone").isNull()).as("master, booking %s", bookingId)
                    .isTrue();
            assertThat(master.getBody()).doesNotContain(guestPhone.substring(4));
        }
    }

    // ── 5. service not performed by the chosen master ───────────────────────────────────────────

    @Test
    @DisplayName("service 3 (B's) sent to master A → 404 on availability AND booking, zero rows")
    void should_return404_when_serviceNotPerformedByChosenMaster() throws Exception {
        UUID ref3 = coverageRef(info(), def3, masterB);
        String token = guestTokenProvider.generate(guestPhone);

        ResponseEntity<String> slots = get(salonRoute(masterA, "availability") + "?date=" + day + "&serviceId=" + ref3);
        ResponseEntity<String> booking = book(masterA, ref3, LocalTime.of(12, 0), token);

        assertThat(slots.getStatusCode()).as(slots.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(booking.getStatusCode()).as(booking.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(guestRows()).isZero();
    }

    // ── 6. B's only slot taken in between → 409 for B, A stays ──────────────────────────────────

    @Test
    @DisplayName("B's only free slot is taken by another guest after our guest saw it → POST A 201, POST B 409; "
            + "A's booking stays CONFIRMED, B's availability is now empty")
    void should_return409ForThatMasterOnly_when_itsOnlyFreeSlotWasJustTaken() throws Exception {
        JsonNode page = info();
        UUID ref1 = coverageRef(page, def1, masterA);
        UUID ref3 = coverageRef(page, def3, masterB);
        String slotsBUrl = salonRoute(masterB, "availability") + "?date=" + day + "&serviceId=" + ref3;
        assertThat(slotStarts(okJson(get(slotsBUrl)))).as("seen free by our guest").containsExactly(B_ONLY_SLOT);
        created(book(masterB, ref3, B_ONLY_SLOT, guestTokenProvider.generate("+380501112299")));
        String token = guestTokenProvider.generate(guestPhone);

        ResponseEntity<String> first = book(masterA, ref1, LocalTime.of(12, 0), token);
        ResponseEntity<String> second = book(masterB, ref3, B_ONLY_SLOT, token);

        assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).as(second.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(guestRows()).as("only A's booking carries our guest").isEqualTo(1);
        assertThat(statusOfGuestBooking(masterA)).isEqualTo("CONFIRMED");
        assertThat(okJson(get(slotsBUrl))).as("B fully booked").isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private UUID defOf(UUID masterServiceId) {
        return jdbcTemplate.queryForObject("SELECT service_def_id FROM master_services WHERE id = ?", UUID.class,
                masterServiceId);
    }

    private UUID assignmentOf(UUID masterId, UUID defId) {
        return jdbcTemplate.queryForObject("SELECT id FROM master_services WHERE master_id = ? AND service_def_id = ?",
                UUID.class, masterId, defId);
    }

    private JsonNode info() throws Exception {
        return okJson(get(BOOK + "s/" + salonSlug + "/info"));
    }

    private static List<UUID> coverageMasters(JsonNode page, UUID defId) {
        List<UUID> ids = new ArrayList<>();
        page.path("serviceCoverage").forEach(entry -> {
            if (entry.path("serviceId").asText().equals(defId.toString())) {
                entry.path("masters").forEach(ref -> ids.add(UUID.fromString(ref.path("masterId").asText())));
            }
        });
        return ids;
    }

    /** The {@code masterServiceId} the page would send for (service, master) — read from the public coverage. */
    private static UUID coverageRef(JsonNode page, UUID defId, UUID masterId) {
        for (JsonNode entry : page.path("serviceCoverage")) {
            if (entry.path("serviceId").asText().equals(defId.toString())) {
                for (JsonNode ref : entry.path("masters")) {
                    if (ref.path("masterId").asText().equals(masterId.toString())) {
                        return UUID.fromString(ref.path("masterServiceId").asText());
                    }
                }
            }
        }
        throw new AssertionError("no coverage ref for service " + defId + " / master " + masterId + " in " + page);
    }

    private static List<String> workingDates(JsonNode days) {
        List<String> dates = new ArrayList<>();
        days.forEach(d -> {
            if (d.path("working").asBoolean()) {
                dates.add(d.path("date").asText());
            }
        });
        return dates;
    }

    private static List<LocalTime> slotStarts(JsonNode slots) {
        List<LocalTime> starts = new ArrayList<>();
        slots.forEach(s -> starts.add(ZonedDateTime.parse(s.path("startsAt").asText())
                .withZoneSameInstant(KYIV).toLocalTime()));
        return starts;
    }

    private UUID serviceDefOfGuestBooking(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT ms.service_def_id FROM bookings b "
                + "JOIN master_services ms ON ms.id = b.master_service_id "
                + "WHERE b.master_id = ? AND b.guest_phone = ?", UUID.class, masterId, guestPhone);
    }

    private String statusOfGuestBooking(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE master_id = ? AND guest_phone = ?",
                String.class, masterId, guestPhone);
    }

    private int guestRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM bookings WHERE guest_phone = ?", Integer.class,
                guestPhone);
    }

    private static String cancelToken(JsonNode created) {
        return created.path("cancelUrl").asText().replaceAll(".*/book/cancel/", "");
    }

    private static UUID bookingId(JsonNode created) {
        return UUID.fromString(created.path("bookingId").asText());
    }

    private JsonNode created(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode okJson(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private String salonRoute(UUID masterId, String leaf) {
        return BOOK + "s/" + salonSlug + "/masters/" + masterId + "/" + leaf;
    }

    private ResponseEntity<String> book(UUID masterId, UUID masterServiceId, LocalTime time, String token)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("masterServiceIds", List.of(masterServiceId.toString()));
        body.put("startsAt", ZonedDateTime.of(day, time, KYIV).toOffsetDateTime().toString());
        body.put("name", "Олена");
        body.put("surname", "Коваль");
        body.put("privacyAccepted", true);
        body.put("privacyPolicyVersion", POLICY);
        return post(salonRoute(masterId, "booking"), body, token);
    }

    private ResponseEntity<String> post(String url, Map<String, Object> body, String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", PublicBookingPageFixtures.randomIp());
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        String json = body == null ? null : objectMapper.writeValueAsString(body);
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private ResponseEntity<String> get(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", PublicBookingPageFixtures.randomIp());
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> detail(UUID bookingId, String token) {
        return restTemplate.exchange("/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(fx.bearerHeaders(token)), String.class);
    }
}
