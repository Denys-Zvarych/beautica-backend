package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.service.BookabilityHttp;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.entity.PriceType;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 361 — a {@code SALON_OWNER} self-edits their master profile through the existing
 * {@code PATCH /independent-masters/me/profile}. Full chain (real security filter, Bean Validation,
 * {@code UserService} union backstop, Postgres, caches). The role matrix is the gate this phase exists for.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH /independent-masters/me/profile — SALON_OWNER self-edit (full stack, real Postgres)")
class OwnerMasterProfileUpdateIT extends AbstractIntegrationTest {

    private static final String PROFILE_URL = "/api/v1/independent-masters/me/profile";
    private static final String LOCALITY_URL = "/api/v1/independent-masters/me";
    private static final String TITLE = "Майстер манікюру";
    private static final String BIO = "Десять років досвіду";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

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
    @DisplayName("#1 SALON_OWNER sets title + bio: 200 and the response echoes both")
    void should_return200AndEcho_when_ownerPatchesProfile() throws Exception {
        Owner owner = registerOwner("echo");

        ResponseEntity<String> response = patch(PROFILE_URL, Map.of("professionalTitle", TITLE, "bio", BIO), owner.token());

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("professionalTitle").asText()).isEqualTo(TITLE);
        assertThat(data.path("bio").asText()).isEqualTo(BIO);
    }

    @Test
    @DisplayName("#2 GET /masters/me reads title + bio back (warm cache evicted by the write)")
    void should_readBackOnMastersMe_when_ownerPatchesProfile() throws Exception {
        Owner owner = registerOwner("readback");
        http.get("/api/v1/masters/me", owner.token());

        patch(PROFILE_URL, Map.of("professionalTitle", TITLE, "bio", BIO), owner.token());

        JsonNode me = http.get("/api/v1/masters/me", owner.token());
        assertThat(me.path("professionalTitle").asText()).isEqualTo(TITLE);
        assertThat(me.path("bio").asText()).isEqualTo(BIO);
    }

    @Test
    @DisplayName("#3 anonymous GET /masters/{ownerMasterId} shows the title (warm public cache evicted)")
    void should_showTitleOnPublicDetail_when_ownerPatchesProfile() throws Exception {
        Owner owner = registerOwner("public");
        http.get("/api/v1/masters/" + owner.masterId());

        patch(PROFILE_URL, Map.of("professionalTitle", TITLE), owner.token());

        assertThat(http.get("/api/v1/masters/" + owner.masterId()).path("professionalTitle").asText())
                .isEqualTo(TITLE);
    }

    @Test
    @DisplayName("#4 anonymous public salon team listing carries the owner's title")
    void should_showTitleOnPublicSalonTeam_when_ownerPatchesProfile() throws Exception {
        Owner owner = registerOwner("team");
        configureOwnerMaster(owner);
        assertThat(http.rosterIds(owner.salonId())).containsExactly(owner.masterId().toString());
        http.get("/api/v1/salons/" + owner.salonId());

        patch(PROFILE_URL, Map.of("professionalTitle", TITLE), owner.token());

        JsonNode roster = http.get("/api/v1/salons/" + owner.salonId() + "/masters");
        assertThat(BookabilityHttp.ids(roster, "professionalTitle")).containsExactly(TITLE);
        JsonNode bySalon = http.get("/api/v1/masters/by-salon/" + owner.salonId(), owner.token());
        assertThat(BookabilityHttp.ids(bySalon, "professionalTitle")).containsExactly(TITLE);
        JsonNode staff = http.get("/api/v1/salons/" + owner.salonId() + "/staff", owner.token());
        assertThat(staff.toString()).contains(TITLE);
    }

    @Test
    @DisplayName("#5 \"\" clears the title to null; an empty body leaves values unchanged")
    void should_clearOnEmptyString_and_keepOnNullFields_when_ownerPatches() throws Exception {
        Owner owner = registerOwner("clear");
        patch(PROFILE_URL, Map.of("professionalTitle", TITLE, "bio", BIO), owner.token());

        ResponseEntity<String> noop = patch(PROFILE_URL, Map.of(), owner.token());
        assertThat(noop.getStatusCode()).as(noop.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(titleInDb(owner.userId())).isEqualTo(TITLE);
        assertThat(bioInDb(owner.userId())).isEqualTo(BIO);

        ResponseEntity<String> cleared = patch(PROFILE_URL, Map.of("professionalTitle", ""), owner.token());
        assertThat(cleared.getStatusCode()).as(cleared.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(titleInDb(owner.userId())).isNull();
        assertThat(bioInDb(owner.userId())).isEqualTo(BIO);
    }

    @Test
    @DisplayName("#6 bidi override char / 101 chars in the title: 400 and nothing persisted")
    void should_return400_when_titleHasBidiOrTooLong() throws Exception {
        Owner owner = registerOwner("validate");

        ResponseEntity<String> bidi = patch(PROFILE_URL, Map.of("professionalTitle", "x‮"), owner.token());
        ResponseEntity<String> tooLong = patch(PROFILE_URL, Map.of("professionalTitle", "a".repeat(101)), owner.token());

        assertThat(bidi.getStatusCode()).as(bidi.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getStatusCode()).as(tooLong.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(titleInDb(owner.userId())).isNull();
    }

    @Test
    @DisplayName("#7 SALON_MASTER stays 403 on /independent-masters/me/profile")
    void should_return403_when_salonMasterPatches() throws Exception {
        Owner owner = registerOwner("sm");
        String token = seedAndLogin("SALON_MASTER", owner.salonId(), uniqueEmail("sm"));

        assertForbiddenAndUnchanged(patch(PROFILE_URL, Map.of("professionalTitle", TITLE), token), owner);
    }

    @Test
    @DisplayName("#8 SALON_ADMIN stays 403")
    void should_return403_when_salonAdminPatches() throws Exception {
        Owner owner = registerOwner("sa");
        String token = fixtures.createSalonAdminAndGetToken(owner.salonId(), uniqueEmail("sa"));

        assertForbiddenAndUnchanged(patch(PROFILE_URL, Map.of("professionalTitle", TITLE), token), owner);
    }

    @Test
    @DisplayName("#9 CLIENT stays 403")
    void should_return403_when_clientPatches() throws Exception {
        Owner owner = registerOwner("cl");
        String token = fixtures.createClientAndGetToken(uniqueEmail("cl"));

        assertForbiddenAndUnchanged(patch(PROFILE_URL, Map.of("professionalTitle", TITLE), token), owner);
    }

    @Test
    @DisplayName("#10 no token: 401")
    void should_return401_when_unauthenticated() {
        ResponseEntity<String> response = restTemplate.exchange(PROFILE_URL, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("professionalTitle", TITLE)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("#11 SALON_OWNER stays 403 on the locality endpoint PATCH /independent-masters/me")
    void should_return403_when_ownerPatchesLocality() throws Exception {
        Owner owner = registerOwner("loc");

        ResponseEntity<String> response = patch(LOCALITY_URL, Map.of("cityId", UUID.randomUUID().toString(), "street", "Khreshchatyk", "buildingNo", "1"), owner.token());

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("#12 owner A writes only their own user row; owner B is untouched")
    void should_writeOnlyOwnRow_when_twoOwnersExist() throws Exception {
        Owner a = registerOwner("iso-a");
        Owner b = registerOwner("iso-b");
        patch(PROFILE_URL, Map.of("professionalTitle", "B-title"), b.token());

        patch(PROFILE_URL, Map.of("professionalTitle", TITLE, "bio", BIO), a.token());

        assertThat(titleInDb(a.userId())).isEqualTo(TITLE);
        assertThat(titleInDb(b.userId())).isEqualTo("B-title");
        assertThat(bioInDb(b.userId())).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void assertForbiddenAndUnchanged(ResponseEntity<String> response, Owner owner) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(titleInDb(owner.userId())).as("owner row untouched").isNull();
    }

    private record Owner(UUID userId, UUID salonId, UUID masterId, String token) {}

    private Owner registerOwner(String tag) throws Exception {
        String email = uniqueEmail("owner-" + tag);
        String token = fixtures.createSalonOwnerAndGetToken(email);
        UUID salonId = fixtures.createSalon(token, "Owner Profile " + tag);
        UUID userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        return new Owner(userId, salonId, fixtures.resolveMasterIdForUserEmail(email), token);
    }

    /** Service + assignment + weekly hours as the owner, so the owner-master is on the public bookable roster. */
    private void configureOwnerMaster(Owner owner) throws Exception {
        UUID defId = fixtures.createServiceDefinition(owner.token(), owner.salonId(), "Owner Service");
        ResponseEntity<String> assigned = restTemplate.exchange(
                "/api/v1/salons/" + owner.salonId() + "/masters/" + owner.masterId() + "/services",
                HttpMethod.POST,
                new HttpEntity<>(new AssignServiceToMasterRequest(defId, PriceType.FIXED, new BigDecimal("500.00"), null, null),
                        fixtures.bearerHeaders(owner.token())),
                String.class);
        assertThat(assigned.getStatusCode()).as(assigned.getBody()).isEqualTo(HttpStatus.CREATED);
        List<WeeklyScheduleDayRequest> days = IntStream.rangeClosed(1, 7)
                .mapToObj(d -> new WeeklyScheduleDayRequest(d,
                        List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))))
                .toList();
        ResponseEntity<String> schedule = restTemplate.exchange(
                "/api/v1/masters/" + owner.masterId() + "/weekly-schedules", HttpMethod.POST,
                new HttpEntity<>(new WeeklyScheduleRequest(LocalDate.now(TimeZones.KYIV), null, days),
                        fixtures.bearerHeaders(owner.token())),
                String.class);
        assertThat(schedule.getStatusCode()).as(schedule.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    private String seedAndLogin(String role, UUID salonId, String email) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                UUID.randomUUID(), email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    private ResponseEntity<String> patch(String url, Map<String, ?> body, String token) {
        return restTemplate.exchange(url, HttpMethod.PATCH,
                new HttpEntity<>(new LinkedHashMap<>(body), fixtures.bearerHeaders(token)), String.class);
    }

    private String titleInDb(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT professional_title FROM users WHERE id = ?", String.class, userId);
    }

    private String bioInDb(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT bio FROM users WHERE id = ?", String.class, userId);
    }

    private static String uniqueEmail(String tag) {
        return tag + "-" + UUID.randomUUID() + "@beautica.test";
    }
}
