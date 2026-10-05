package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WorkIntervalDto;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.beautica.service.BookabilityHttp.ids;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The independent-master bookability journey end to end, every step through the real API with the
 * real caches warm between steps (locked rule 2026-10-05: listed only with ≥1 active service AND a
 * schedule; a direct profile link always loads; favourites hide but keep the row):
 * register → add a service → publish weekly hours → client favourites → master deletes the hours.
 */
@DisplayName("Independent master — bookability journey through the real API (full HTTP + real Postgres + real caches)")
class IndependentMasterBookabilityJourneyIT extends AbstractIntegrationTest {

    private static final String NAME = "Journeya";

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

    @Test
    @DisplayName("hidden until service AND hours exist; visible everywhere once both do; hidden again "
            + "(profile still loads, favourite row kept) once the hours are deleted")
    void should_followBookabilityRule_when_independentMasterConfiguresAndUnconfigures() throws Exception {
        String email = "journey-" + System.nanoTime() + "@beautica.test";
        String masterToken = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        jdbcTemplate.update("UPDATE users SET first_name = ?, last_name = ? WHERE email = ?", NAME, NAME, email);
        String clientToken = fixtures.createClientAndGetToken("journey-c-" + System.nanoTime() + "@beautica.test");

        // 1. freshly registered — nothing configured
        assertHidden(masterId, "fresh registration");

        // 2. a service, but no hours
        fixtures.createIndependentMasterService(masterToken, "Manicure");
        assertHidden(masterId, "service only, no hours");

        // 3. weekly hours published via the API
        UUID scheduleId = createWeeklySchedule(masterId, masterToken);
        assertThat(masterIds("/api/v1/search/masters?page=0&size=20")).as("browse").containsExactly(masterId.toString());
        assertThat(masterIds("/api/v1/search/masters?q=" + NAME)).as("by name").containsExactly(masterId.toString());
        assertThat(http.get("/api/v1/masters/" + masterId, null).path("bookable").asBoolean()).isTrue();
        assertThat(http.get("/api/v1/masters/" + masterId + "/services", null)).hasSize(1);
        http.addFavorite(clientToken, FavoriteTargetType.MASTER, masterId);
        assertThat(favouriteIds(clientToken)).containsExactly(masterId.toString());

        // 4. the master deletes their only hours via the API
        ResponseEntity<String> deleted = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/weekly-schedules/" + scheduleId, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(masterToken)), String.class);
        assertThat(deleted.getStatusCode()).as(deleted.getBody()).isEqualTo(HttpStatus.NO_CONTENT);

        assertHidden(masterId, "hours deleted");
        assertThat(favouriteIds(clientToken)).as("favourite hidden").isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorites WHERE target_id = ?", Long.class, masterId))
                .as("favourite row kept").isEqualTo(1L);
    }

    /** Not in browse, not by name, profile still 200 with bookable=false, empty client services tab. */
    private void assertHidden(UUID masterId, String step) throws Exception {
        JsonNode browse = http.get("/api/v1/search/masters?page=0&size=20", null);
        assertThat(ids(browse, "masterId")).as("%s: browse", step).isEmpty();
        assertThat(browse.path("totalElements").asLong()).as("%s: browse total", step).isZero();
        assertThat(masterIds("/api/v1/search/masters?q=" + NAME)).as("%s: by name", step).isEmpty();
        JsonNode profile = http.get("/api/v1/masters/" + masterId, null);
        assertThat(profile.path("masterId").asText()).as("%s: profile loads", step).isEqualTo(masterId.toString());
        assertThat(profile.path("bookable").isBoolean()).as("%s: flag present", step).isTrue();
        assertThat(profile.path("bookable").asBoolean()).as("%s: bookable", step).isFalse();
        assertThat(http.get("/api/v1/masters/" + masterId + "/services", null)).as("%s: services tab", step).isEmpty();
    }

    private UUID createWeeklySchedule(UUID masterId, String token) throws Exception {
        List<WeeklyScheduleDayRequest> days = IntStream.rangeClosed(1, 7)
                .mapToObj(d -> new WeeklyScheduleDayRequest(d,
                        List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))))
                .toList();
        WeeklyScheduleRequest request = new WeeklyScheduleRequest(LocalDate.now(ZoneId.of("Europe/Kyiv")), null, days);
        ResponseEntity<String> created = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/weekly-schedules", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(created.getBody()).path("data").path("id").asText());
    }

    private List<String> favouriteIds(String clientToken) throws Exception {
        return ids(http.get("/api/v1/favorites/masters", clientToken), "masterId");
    }

    private List<String> masterIds(String url) throws Exception {
        return ids(http.get(url, null), "masterId");
    }
}
