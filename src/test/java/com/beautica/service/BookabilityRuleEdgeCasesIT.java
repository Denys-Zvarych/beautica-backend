package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.beautica.service.BookabilityHttp.ids;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge cases of the locked bookability rule (product decision 2026-10-05), written from the RULE,
 * not the SQL: a master is shown to clients only with ≥1 active service AND a schedule; a salon only
 * with ≥1 such ACTIVE master. Every case asserts the two halves of the contract agree — the
 * structural search gate ({@code GET /search/masters|salons}) and the strict profile flag
 * ({@code GET /masters/{id}.bookable}, {@code GET /salons/{id}.bookable}) — so a master can never be
 * listed in search while its own profile says "not bookable", or vice versa.
 *
 * <p>Schedule shapes are seeded by JDBC where the API cannot produce them (an expired template, a
 * past override); the deactivation cases go through the real API so the cache-eviction path runs.
 */
@DisplayName("Bookability rule edge cases — search membership and profile flag agree (full HTTP + real Postgres)")
class BookabilityRuleEdgeCasesIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final int HORIZON_DAYS = 180;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;
    private BookabilityHttp http;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        http = new BookabilityHttp(restTemplate, objectMapper);
    }

    // ── schedule shapes: overrides ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("an independent master whose ONLY schedule is a future CUSTOM_HOURS override (set via "
            + "the API) is bookable: listed in search (cache primed first), profile bookable=true")
    void should_listMaster_when_onlyScheduleIsCustomHoursOverride() throws Exception {
        String email = "custom-only-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        rename(masterId, "Zoriana");
        BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, masterId);
        assertThat(masterSearch("Zoriana")).as("precondition: no schedule yet — hidden, page cached").isEmpty();
        LocalDate day = today().plusDays(3);

        ResponseEntity<String> put = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/overrides/" + day, HttpMethod.PUT,
                new HttpEntity<>(new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        List.of(new WorkIntervalDto(LocalTime.of(10, 0), LocalTime.of(12, 0)))),
                        fixtures.bearerHeaders(token)),
                String.class);
        assertThat(put.getStatusCode()).as(put.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(masterSearch("Zoriana")).containsExactly(masterId.toString());
        assertThat(masterBookable(masterId)).isTrue();
    }

    @Test
    @DisplayName("a salon whose only master has just a future CUSTOM_HOURS override is listed, and its "
            + "profile is bookable=true")
    void should_listSalon_when_onlyMasterHasCustomHoursOverride() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Orion Custom Hours");
        UUID masterId = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salonId);
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salonId, masterId);
        BookableMasterSeeder.seedCustomHoursOverride(jdbcTemplate, masterId, today().plusDays(5));

        assertThat(salonSearch("Orion")).containsExactly(salonId.toString());
        assertThat(salonBookable(salonId)).isTrue();
    }

    @Test
    @DisplayName("a master whose only override is a DAY_OFF has no schedule: hidden, bookable=false")
    void should_hideMaster_when_onlyOverrideIsDayOff() throws Exception {
        UUID masterId = independentWithService("Dayoffa");
        BookableMasterSeeder.seedDayOffOverride(jdbcTemplate, masterId, today().plusDays(2));

        assertThat(masterSearch("Dayoffa")).isEmpty();
        assertThat(masterBookable(masterId)).isFalse();
    }

    @Test
    @DisplayName("a master whose only CUSTOM_HOURS override is in the PAST has no schedule: hidden, "
            + "bookable=false")
    void should_hideMaster_when_onlyCustomHoursOverrideIsPast() throws Exception {
        UUID masterId = independentWithService("Pastella");
        BookableMasterSeeder.seedCustomHoursOverride(jdbcTemplate, masterId, today().minusDays(1));

        assertThat(masterSearch("Pastella")).isEmpty();
        assertThat(masterBookable(masterId)).isFalse();
    }

    // ── schedule shapes: weekly templates ───────────────────────────────────────────────────

    @Test
    @DisplayName("an EXPIRED weekly template (valid_to = yesterday) does not count: hidden, bookable=false, "
            + "favourite hidden but kept")
    void should_hideMaster_when_weeklyTemplateExpired() throws Exception {
        UUID masterId = independentWithService("Expira");
        BookableMasterSeeder.seedWeeklyTemplate(jdbcTemplate, masterId,
                today().minusDays(30), today().minusDays(1), true);
        String clientToken = fixtures.createClientAndGetToken("exp-" + System.nanoTime() + "@beautica.test");
        http.addFavorite(clientToken, FavoriteTargetType.MASTER, masterId);

        assertThat(masterSearch("Expira")).isEmpty();
        assertThat(masterBookable(masterId)).isFalse();
        assertThat(ids(http.get("/api/v1/favorites/masters", clientToken), "masterId")).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorites WHERE target_id = ?", Long.class, masterId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("a weekly template ending TODAY still counts (boundary): listed, bookable=true")
    void should_listMaster_when_weeklyTemplateEndsToday() throws Exception {
        UUID masterId = independentWithService("Ultima");
        BookableMasterSeeder.seedWeeklyTemplate(jdbcTemplate, masterId, today().minusDays(30), today(), true);
        // Structural half only: the strict free-slot half legitimately turns false once today's
        // 17:00 has passed ("fully booked right now" stays listed by design).
        assertThat(masterSearch("Ultima")).containsExactly(masterId.toString());
    }

    @Test
    @DisplayName("a template starting exactly at the 180-day horizon counts (listed, bookable=true); one "
            + "starting a day later does not (hidden, bookable=false)")
    void should_respectHorizon_when_templateStartsAtOrPastHorizon() throws Exception {
        UUID atHorizon = independentWithService("Horizonta");
        BookableMasterSeeder.seedWeeklyTemplate(jdbcTemplate, atHorizon,
                today().plusDays(HORIZON_DAYS), null, true);
        UUID pastHorizon = independentWithService("Beyondia");
        BookableMasterSeeder.seedWeeklyTemplate(jdbcTemplate, pastHorizon,
                today().plusDays(HORIZON_DAYS + 1), null, true);

        assertThat(masterSearch("Horizonta")).containsExactly(atHorizon.toString());
        assertThat(masterBookable(atHorizon)).isTrue();
        assertThat(masterSearch("Beyondia")).isEmpty();
        assertThat(masterBookable(pastHorizon)).isFalse();
    }

    @Test
    @DisplayName("an EMPTY weekly template (no working hours on any day) is not a schedule: hidden, "
            + "bookable=false")
    void should_hideMaster_when_weeklyTemplateHasNoHours() throws Exception {
        UUID masterId = independentWithService("Vacua");
        BookableMasterSeeder.seedWeeklyTemplate(jdbcTemplate, masterId, today(), null, false);

        assertThat(masterSearch("Vacua")).isEmpty();
        assertThat(masterBookable(masterId)).isFalse();
    }

    @Test
    @DisplayName("an EXPLICIT_TIMES weekly template (discrete start times, no intervals) counts: listed, "
            + "bookable=true")
    void should_listMaster_when_templateUsesExplicitTimes() throws Exception {
        UUID masterId = independentWithService("Discreta");
        BookableMasterSeeder.seedExplicitTimesTemplate(jdbcTemplate, masterId);

        assertThat(masterSearch("Discreta")).containsExactly(masterId.toString());
        assertThat(masterBookable(masterId)).isTrue();
    }

    // ── services ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an independent master deactivating their ONLY service definition via DELETE "
            + "/services/{id} drops out of the (already cached) search, profile bookable=false, "
            + "services tab empty, favourite hidden")
    void should_hideMaster_when_onlyServiceDefinitionDeactivated() throws Exception {
        String email = "deact-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        rename(masterId, "Deactiva");
        UUID defId = fixtures.createIndependentMasterService(token, "Manicure");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);
        String clientToken = fixtures.createClientAndGetToken("deact-c-" + System.nanoTime() + "@beautica.test");
        http.addFavorite(clientToken, FavoriteTargetType.MASTER, masterId);
        assertThat(masterSearch("Deactiva")).as("precondition: listed, page cached").containsExactly(masterId.toString());
        assertThat(masterBookable(masterId)).as("precondition: bookable, verdict cached").isTrue();

        ResponseEntity<String> deleted = restTemplate.exchange("/api/v1/services/" + defId, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(deleted.getStatusCode()).as(deleted.getBody()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(masterSearch("Deactiva")).isEmpty();
        assertThat(masterBookable(masterId)).isFalse();
        assertThat(http.get("/api/v1/masters/" + masterId + "/services", null)).isEmpty();
        assertThat(ids(http.get("/api/v1/favorites/masters", clientToken), "masterId")).isEmpty();
    }

    @Test
    @DisplayName("a salon owner deactivating the only bookable master's SALON definition drops the salon "
            + "from the (already cached) salon search and flips GET /salons/{id}.bookable to false")
    void should_hideSalon_when_onlyBookableServiceDefinitionDeactivated() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("owner-deact-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Deactivation Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID defId = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salonId, masterId);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);
        assertThat(salonSearch("Deactivation")).as("precondition: listed, page cached").containsExactly(salonId.toString());
        assertThat(salonBookable(salonId)).as("precondition: bookable, verdict cached").isTrue();

        ResponseEntity<String> deleted = restTemplate.exchange("/api/v1/services/" + defId, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), String.class);
        assertThat(deleted.getStatusCode()).as(deleted.getBody()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(salonSearch("Deactivation")).isEmpty();
        assertThat(salonBookable(salonId)).isFalse();
    }

    @Test
    @DisplayName("a salon master holding only a STALE independent-owned assignment (from a former solo "
            + "practice) does not make the salon bookable")
    void should_hideSalon_when_masterOnlyHasCrossOwnerAssignment() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Staleowner Studio");
        UUID masterId = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salonId);
        BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, masterId);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);

        assertThat(salonSearch("Staleowner")).isEmpty();
        assertThat(salonBookable(salonId)).isFalse();
    }

    // ── salon membership ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a salon whose only fully-configured master is DEACTIVATED is hidden and bookable=false")
    void should_hideSalon_when_onlyConfiguredMasterInactive() throws Exception {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Dormant Studio");
        UUID masterId = BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, salonId);
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", masterId);

        assertThat(salonSearch("Dormant")).isEmpty();
        assertThat(salonBookable(salonId)).isFalse();
    }

    // ── category chips are reference data — never filtered by bookability ─────────────────

    @Test
    @DisplayName("category chips (GET /service-categories/approved) list every approved category even when "
            + "NO provider is bookable, and the list is identical once one is")
    void should_keepCategoryChipsUnfiltered_when_noProviderIsBookable() throws Exception {
        List<String> expected = jdbcTemplate.queryForList(
                "SELECT name FROM platform_categories WHERE active = TRUE AND status = 'APPROVED'", String.class);
        assertThat(expected).as("seeded reference data").isNotEmpty();

        String clientToken = fixtures.createClientAndGetToken("chips-" + System.nanoTime() + "@beautica.test");
        List<String> withNobody = names(http.get("/api/v1/service-categories/approved", clientToken));
        BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate,
                BookableMasterSeeder.insertSalon(jdbcTemplate, "Chip Studio"));
        List<String> withOne = names(http.get("/api/v1/service-categories/approved", clientToken));

        assertThat(withNobody).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(withOne).containsExactlyElementsOf(withNobody);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private UUID independentWithService(String name) {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, name);
        BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, masterId);
        return masterId;
    }

    private void rename(UUID masterId, String name) {
        jdbcTemplate.update(
                "UPDATE users SET first_name = ?, last_name = ? WHERE id = (SELECT user_id FROM masters WHERE id = ?)",
                name, name, masterId);
    }

    private static LocalDate today() {
        return LocalDate.now(KYIV);
    }

    private List<String> masterSearch(String q) throws Exception {
        return ids(http.get("/api/v1/search/masters?q=" + q, null), "masterId");
    }

    private List<String> salonSearch(String q) throws Exception {
        return ids(http.get("/api/v1/search/salons?q=" + q, null), "salonId");
    }

    private boolean masterBookable(UUID masterId) throws Exception {
        JsonNode node = http.get("/api/v1/masters/" + masterId, null);
        assertThat(node.path("bookable").isBoolean()).as("bookable present on %s", node).isTrue();
        return node.path("bookable").asBoolean();
    }

    private boolean salonBookable(UUID salonId) throws Exception {
        JsonNode node = http.get("/api/v1/salons/" + salonId, null);
        assertThat(node.path("bookable").isBoolean()).as("bookable present on %s", node).isTrue();
        return node.path("bookable").asBoolean();
    }

    private static List<String> names(JsonNode list) {
        List<String> names = new ArrayList<>();
        list.forEach(row -> names.add(row.path("name").asText()));
        return names;
    }
}
