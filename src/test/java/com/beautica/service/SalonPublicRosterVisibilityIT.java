package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public salon roster ({@code GET /salons/{id}/masters}, {@code GET /masters/by-salon/{id}})
 * lists only masters a client can actually book — the shared free-slot verdict
 * ({@code BookingMasterService#getBookableMasterIds}, the same one the salon catalogue applies).
 * User report: an auto-enrolled owner-master with no services and no hours appeared in a client's
 * «Майстри» tab. The management roster {@code GET /salons/{id}/staff} stays deliberately unfiltered.
 *
 * <p>Fixture per salon: an owner-master auto-enrolled by the real {@code POST /salons} path (no
 * services, no hours), one configured SALON_MASTER (service + weekly hours), and one SALON_MASTER
 * with a service but no hours. Reads go through the real cache — no manual eviction anywhere.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Public salon roster — only bookable masters are listed (full HTTP + real Postgres + real cache)")
class SalonPublicRosterVisibilityIT extends AbstractIntegrationTest {

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
    @DisplayName("GET /salons/{id}/staff (owner) still returns all three masters — management roster is ungated")
    void should_returnAllMasters_when_ownerReadsStaffRoster() throws Exception {
        SalonRoster roster = seedRoster("staff");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + roster.salonId() + "/staff", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(roster.ownerToken())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> staffMasterIds = new ArrayList<>();
        objectMapper.readTree(response.getBody()).path("data").forEach(member -> {
            if (!member.path("masterId").isNull() && !member.path("masterId").isMissingNode()) {
                staffMasterIds.add(member.path("masterId").asText());
            }
        });
        assertThat(staffMasterIds).containsExactlyInAnyOrder(
                roster.ownerMasterId().toString(),
                roster.configuredMasterId().toString(),
                roster.noHoursMasterId().toString());
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
            + "next read — the roster cache is evicted with the catalogue, no manual eviction")
    void should_listMaster_when_serviceAssignedAfterRosterWasCached() throws Exception {
        SalonRoster roster = seedRoster("evict");
        String url = "/api/v1/salons/" + roster.salonId() + "/masters";
        assertThat(masterIds(getPage(url, null)))
                .as("precondition: the hours-only master is not listed and the result is now cached")
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

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

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
