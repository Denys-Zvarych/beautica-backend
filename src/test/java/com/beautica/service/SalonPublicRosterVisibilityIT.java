package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.entity.PriceType;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public salon roster ({@code GET /salons/{id}/masters}, {@code GET /masters/by-salon/{id}})
 * lists only client-visible masters — the CHEAP structural rule ({@code MasterBookabilitySql}: ≥1
 * active, correctly-owned service AND working hours within the 180-day horizon), the same predicate
 * discovery search applies. It never walks the free-slot calendar: a FULLY BOOKED master stays
 * listed. User report: an auto-enrolled owner-master with no services and no hours appeared in a
 * client's «Майстри» tab. The management roster {@code GET /salons/{id}/staff} stays unfiltered.
 *
 * <p>Fixture per salon: an owner-master auto-enrolled by the real {@code POST /salons} path (no
 * services, no hours), one configured SALON_MASTER (service + weekly hours), and one SALON_MASTER
 * with a service but no hours. The roster is read live (uncached) — no manual eviction anywhere.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Public salon roster — only masters with services + hours are listed (full HTTP + real Postgres)")
class SalonPublicRosterVisibilityIT extends AbstractIntegrationTest {

    /** {@code BookingWindow.MAX_DAYS_AHEAD} — the schedule horizon the cheap rule looks across. */
    private static final int HORIZON_DAYS = 180;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("GET /salons/{id}/masters (anonymous) lists only the configured master; totalElements == 1")
    void should_listOnlyBookableMaster_when_salonRosterReadAnonymously() throws Exception {
        SalonRoster roster = seedRoster("anon");

        JsonNode page = getPage("/api/v1/salons/" + roster.salonId() + "/masters", null);

        assertThat(masterIds(page)).containsExactly(roster.configuredMasterId().toString());
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        assertThat(page.path("totalPages").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /masters/by-salon/{id} (CLIENT) returns the same single bookable master")
    void should_listOnlyBookableMaster_when_bySalonReadByClient() throws Exception {
        SalonRoster roster = seedRoster("byid");
        String clientToken = fixtures.createClientAndGetToken("client-roster-" + System.nanoTime() + "@beautica.test");

        JsonNode page = getPage("/api/v1/masters/by-salon/" + roster.salonId(), clientToken);

        assertThat(masterIds(page)).containsExactly(roster.configuredMasterId().toString());
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /salons/{id}/staff («Команда») for the OWNER and for an ADMIN returns every master "
            + "unfiltered — incl. the unconfigured owner-master and a master with no services — while "
            + "the same salon's public /masters roster excludes both")
    void should_returnAllMasters_when_ownerOrAdminReadsStaffRoster() throws Exception {
        SalonRoster roster = seedRoster("staff");
        UUID noServices = fixtures.createSalonMaster(roster.salonId());
        fixtures.seedUsableSchedule(noServices);
        String adminToken = fixtures.createSalonAdminAndGetToken(
                roster.salonId(), "admin-roster-" + System.nanoTime() + "@beautica.test");
        List<String> everyMaster = List.of(
                roster.ownerMasterId().toString(),
                roster.configuredMasterId().toString(),
                roster.noHoursMasterId().toString(),
                noServices.toString());

        List<String> ownerView = staffMasterIds(roster.salonId(), roster.ownerToken());
        List<String> adminView = staffMasterIds(roster.salonId(), adminToken);
        List<String> publicView = masterIds(getPage("/api/v1/salons/" + roster.salonId() + "/masters", null));

        assertThat(ownerView).as("owner «Команда»").containsExactlyInAnyOrderElementsOf(everyMaster);
        assertThat(adminView).as("admin «Команда»").containsExactlyInAnyOrderElementsOf(everyMaster);
        assertThat(publicView).as("public roster")
                .containsExactly(roster.configuredMasterId().toString())
                .doesNotContain(roster.ownerMasterId().toString(), noServices.toString());
    }

    @Test
    @DisplayName("Paging is applied AFTER the gate, in SQL — size=2 over 3 bookable + 2 unbookable "
            + "gives pages of 2 and 1, totalElements 3, totalPages 2")
    void should_pageOverBookableMastersOnly_when_rosterExceedsPageSize() throws Exception {
        SalonRoster roster = seedRoster("paging");
        UUID second = fixtures.createSalonMaster(roster.salonId());
        BookableMasterSeeder.makeBookable(jdbcTemplate, roster.salonId(), second);
        UUID third = fixtures.createSalonMaster(roster.salonId());
        BookableMasterSeeder.makeBookable(jdbcTemplate, roster.salonId(), third);
        String base = "/api/v1/salons/" + roster.salonId() + "/masters?size=2&page=";

        JsonNode first = getPage(base + 0, null);
        JsonNode last = getPage(base + 1, null);

        assertThat(first.path("totalElements").asLong()).isEqualTo(3);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(masterIds(first)).hasSize(2);
        assertThat(last.path("totalElements").asLong()).isEqualTo(3);
        assertThat(masterIds(last)).hasSize(1);
        List<String> union = new ArrayList<>(masterIds(first));
        union.addAll(masterIds(last));
        assertThat(union).containsExactlyInAnyOrder(
                roster.configuredMasterId().toString(), second.toString(), third.toString());
    }

    @Test
    @DisplayName("A master who becomes bookable (service assigned via the API) appears on the very "
            + "next read — no manual eviction")
    void should_listMaster_when_serviceAssignedAfterRosterWasCached() throws Exception {
        SalonRoster roster = seedRoster("evict");
        String url = "/api/v1/salons/" + roster.salonId() + "/masters";
        assertThat(masterIds(getPage(url, null)))
                .as("precondition: the service-only master is not listed")
                .doesNotContain(roster.noHoursMasterId().toString());
        UUID hoursOnlyMaster = fixtures.createSalonMaster(roster.salonId());
        fixtures.seedUsableSchedule(hoursOnlyMaster);
        assertThat(masterIds(getPage(url, null))).doesNotContain(hoursOnlyMaster.toString());
        UUID defId = fixtures.createServiceDefinition(roster.ownerToken(), roster.salonId(),
                "Roster Evict " + System.nanoTime());

        ResponseEntity<String> assign = restTemplate.exchange(
                "/api/v1/salons/" + roster.salonId() + "/masters/" + hoursOnlyMaster + "/services",
                HttpMethod.POST,
                new HttpEntity<>(new AssignServiceToMasterRequest(defId, PriceType.FIXED,
                        new BigDecimal("500.00"), null, null),
                        fixtures.bearerHeaders(roster.ownerToken())),
                String.class);

        assertThat(assign.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(masterIds(getPage(url, null))).containsExactlyInAnyOrder(
                roster.configuredMasterId().toString(), hoursOnlyMaster.toString());
    }

    @Test
    @DisplayName("A FULLY BOOKED master (service + hours, but every slot taken) IS listed — the roster "
            + "uses the cheap rule, never the free-slot calendar, matching search")
    void should_listMaster_when_masterIsFullyBooked() throws Exception {
        SalonRoster roster = seedRoster("full");
        UUID fullyBooked = fixtures.createSalonMaster(roster.salonId());
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, roster.salonId(), fullyBooked);
        LocalDate day = LocalDate.now(TimeZones.KYIV).plusDays(2);
        // Its ONLY hours: one 60-min window for a 60-min service — exactly one slot.
        BookableMasterSeeder.seedCustomHoursOverride(jdbcTemplate, fullyBooked, day,
                LocalTime.of(10, 0), LocalTime.of(11, 0));
        UUID assignmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? AND is_active = true", UUID.class, fullyBooked);
        assertThat(servicesTab(fullyBooked)).as("precondition: one free slot — service bookable").hasSize(1);
        String clientToken = fixtures.createClientAndGetToken("client-full-" + System.nanoTime() + "@beautica.test");

        ResponseEntity<String> booked = restTemplate.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(new CreateBookingRequest(fullyBooked, assignmentId,
                        ZonedDateTime.of(day, LocalTime.of(10, 0), TimeZones.KYIV), null, null, false),
                        fixtures.bearerHeaders(clientToken)),
                String.class);

        assertThat(booked.getStatusCode()).as(booked.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(servicesTab(fullyBooked))
                .as("the only slot is taken — the strict free-slot verdict now says unbookable").isEmpty();
        assertThat(masterIds(getPage("/api/v1/salons/" + roster.salonId() + "/masters", null)))
                .containsExactlyInAnyOrder(roster.configuredMasterId().toString(), fullyBooked.toString());
        assertThat(masterIds(getPage("/api/v1/masters/by-salon/" + roster.salonId(), clientToken)))
                .containsExactlyInAnyOrder(roster.configuredMasterId().toString(), fullyBooked.toString());
    }

    @Test
    @DisplayName("Masters with hours but NO service, or a service but hours only BEYOND the 180-day "
            + "horizon, are NOT listed; hours starting exactly AT the horizon are")
    void should_hideMasters_when_noServiceOrScheduleBeyondHorizon() throws Exception {
        SalonRoster roster = seedRoster("rule");
        LocalDate today = LocalDate.now(TimeZones.KYIV);
        UUID hoursOnly = fixtures.createSalonMaster(roster.salonId());
        fixtures.seedUsableSchedule(hoursOnly);
        UUID beyondHorizon = fixtures.createSalonMaster(roster.salonId());
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, roster.salonId(), beyondHorizon);
        BookableMasterSeeder.seedWeeklyTemplate(jdbcTemplate, beyondHorizon,
                today.plusDays(HORIZON_DAYS + 1), null, true);
        UUID atHorizon = fixtures.createSalonMaster(roster.salonId());
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, roster.salonId(), atHorizon);
        BookableMasterSeeder.seedWeeklyTemplate(jdbcTemplate, atHorizon, today.plusDays(HORIZON_DAYS), null, true);

        JsonNode page = getPage("/api/v1/salons/" + roster.salonId() + "/masters", null);

        assertThat(masterIds(page)).containsExactlyInAnyOrder(
                roster.configuredMasterId().toString(), atHorizon.toString());
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("The auto-enrolled OWNER-MASTER, once given a service + hours, IS on the client roster "
            + "of both public endpoints — the owner is a master like any other, no switcher")
    void should_listOwnerMaster_when_ownerMasterHasServiceAndHours() throws Exception {
        SalonRoster roster = seedRoster("owner");
        BookableMasterSeeder.makeBookable(jdbcTemplate, roster.salonId(), roster.ownerMasterId());
        String clientToken = fixtures.createClientAndGetToken("client-owner-" + System.nanoTime() + "@beautica.test");

        JsonNode salonsPage = getPage("/api/v1/salons/" + roster.salonId() + "/masters", null);
        JsonNode bySalonPage = getPage("/api/v1/masters/by-salon/" + roster.salonId(), clientToken);

        assertThat(masterIds(salonsPage)).as("/salons/{id}/masters").containsExactlyInAnyOrder(
                roster.ownerMasterId().toString(), roster.configuredMasterId().toString());
        assertThat(salonsPage.path("totalElements").asLong()).isEqualTo(2);
        assertThat(masterIds(bySalonPage)).as("/masters/by-salon/{id}").containsExactlyInAnyOrder(
                roster.ownerMasterId().toString(), roster.configuredMasterId().toString());
        assertThat(bySalonPage.path("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("A salon with ZERO client-visible masters (unconfigured owner-master + a service-only "
            + "master) returns 200 with an empty page and totalElements 0 on both public endpoints")
    void should_returnEmptyPage_when_noMasterIsClientVisible() throws Exception {
        String ownerEmail = "owner-roster-empty-" + System.nanoTime() + "@beautica.test";
        String ownerToken = fixtures.createSalonOwnerAndGetToken(ownerEmail);
        UUID salonId = fixtures.createSalon(ownerToken, "Public Roster empty");
        UUID serviceOnly = fixtures.createSalonMaster(salonId);
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salonId, serviceOnly);
        String clientToken = fixtures.createClientAndGetToken("client-empty-" + System.nanoTime() + "@beautica.test");
        assertThat(staffMasterIds(salonId, ownerToken))
                .as("precondition: «Команда» still has both masters")
                .hasSize(2)
                .contains(serviceOnly.toString(), fixtures.resolveMasterIdForUserEmail(ownerEmail).toString());

        JsonNode salonsPage = getPage("/api/v1/salons/" + salonId + "/masters", null);
        JsonNode bySalonPage = getPage("/api/v1/masters/by-salon/" + salonId, clientToken);

        assertThat(masterIds(salonsPage)).as("/salons/{id}/masters rows").isEmpty();
        assertThat(salonsPage.path("totalElements").asLong()).as("/salons/{id}/masters total").isZero();
        assertThat(masterIds(bySalonPage)).as("/masters/by-salon/{id} rows").isEmpty();
        assertThat(bySalonPage.path("totalElements").asLong()).as("/masters/by-salon/{id} total").isZero();
    }

    @Test
    @DisplayName("/masters/by-salon/{id} and /salons/{id}/masters return IDENTICAL pages (rows, order, "
            + "totals) under paging + sort when some masters are filtered out")
    void should_returnIdenticalPages_when_bothPublicRosterEndpointsReadWithPagingAndSort() throws Exception {
        SalonRoster roster = seedRoster("parity");
        UUID second = fixtures.createSalonMaster(roster.salonId());
        BookableMasterSeeder.makeBookable(jdbcTemplate, roster.salonId(), second);
        UUID third = fixtures.createSalonMaster(roster.salonId());
        BookableMasterSeeder.makeBookable(jdbcTemplate, roster.salonId(), third);
        String clientToken = fixtures.createClientAndGetToken("client-parity-" + System.nanoTime() + "@beautica.test");
        String query = "?size=2&sort=createdAt,asc&page=";
        List<String> seen = new ArrayList<>();

        for (int page = 0; page < 2; page++) {
            JsonNode salons = getPage("/api/v1/salons/" + roster.salonId() + "/masters" + query + page, null);
            JsonNode bySalon = getPage("/api/v1/masters/by-salon/" + roster.salonId() + query + page, clientToken);

            assertThat(bySalon.path("data")).as("page %d rows (incl. order)", page).isEqualTo(salons.path("data"));
            assertThat(bySalon.path("totalElements").asLong()).as("page %d total", page)
                    .isEqualTo(salons.path("totalElements").asLong()).isEqualTo(3);
            assertThat(bySalon.path("totalPages").asInt()).as("page %d totalPages", page)
                    .isEqualTo(salons.path("totalPages").asInt()).isEqualTo(2);
            seen.addAll(masterIds(salons));
        }

        assertThat(seen).as("union of both pages — no duplicate, no skip, no filtered master")
                .containsExactlyInAnyOrder(
                        roster.configuredMasterId().toString(), second.toString(), third.toString());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    /** Master ids (rows with a masterId) of the management roster {@code GET /salons/{id}/staff}. */
    private List<String> staffMasterIds(UUID salonId, String token) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/staff", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        objectMapper.readTree(response.getBody()).path("data").forEach(member -> {
            if (!member.path("masterId").isNull() && !member.path("masterId").isMissingNode()) {
                ids.add(member.path("masterId").asText());
            }
        });
        return ids;
    }

    /** Anonymous client view of a master's services tab — the strict free-slot verdict. */
    private JsonNode servicesTab(UUID masterId) throws Exception {
        return getPage("/api/v1/masters/" + masterId + "/services", null);
    }

    private SalonRoster seedRoster(String tag) throws Exception {
        String ownerEmail = "owner-roster-" + tag + "-" + System.nanoTime() + "@beautica.test";
        String ownerToken = fixtures.createSalonOwnerAndGetToken(ownerEmail);
        UUID salonId = fixtures.createSalon(ownerToken, "Public Roster " + tag);
        UUID ownerMasterId = fixtures.resolveMasterIdForUserEmail(ownerEmail);

        UUID configured = fixtures.createSalonMaster(salonId);
        BookableMasterSeeder.makeBookable(jdbcTemplate, salonId, configured);

        UUID noHours = fixtures.createSalonMaster(salonId);
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salonId, noHours);

        return new SalonRoster(salonId, ownerToken, ownerMasterId, configured, noHours);
    }

    private JsonNode getPage(String url, String token) throws Exception {
        HttpEntity<?> entity = token == null
                ? HttpEntity.EMPTY
                : new HttpEntity<>(fixtures.bearerHeaders(token));
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        assertThat(response.getStatusCode()).as("GET %s", url).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private static List<String> masterIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("data").forEach(row -> ids.add(row.path("masterId").asText()));
        return ids;
    }

    private record SalonRoster(
            UUID salonId, String ownerToken, UUID ownerMasterId, UUID configuredMasterId, UUID noHoursMasterId) {
    }
}
