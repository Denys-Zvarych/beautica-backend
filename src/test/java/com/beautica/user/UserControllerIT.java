package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("User endpoints — integration")
class UserControllerIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(UserControllerIT.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Дніпро — confirmed by V53 seed to have urban districts (city_districts
    // rows). Resolved by its stable KATOTTH business key, never by name or a
    // hardcoded UUID (the seed assigns ids via gen_random_uuid()).
    private static final String CITY_WITH_DISTRICTS_KATOTTH = "UA12020010010037010";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    // ── setup ─────────────────────────────────────────────────────────────────
    // JDK HttpURLConnection rejects PATCH as an invalid method. Replace the
    // default SimpleClientHttpRequestFactory with Apache HttpClient 5, which
    // supports all HTTP methods including PATCH.
    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    // ── cleanup ───────────────────────────────────────────────────────────────
    // @Transactional is silently ignored on lifecycle callbacks in RANDOM_PORT tests.
    // Use JdbcTemplate to delete rows in dependency order.
    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    // ── GET /api/v1/users/me ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /me — 401 when no Authorization header")
    void should_return401_when_noTokenProvided() {
        log.debug("Arrange: no Authorization header prepared");

        log.debug("Act: GET /api/v1/users/me without credentials — unauthenticated request must be rejected");
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/users/me", String.class);

        assertThat(response.getStatusCode())
                .as("status must be 401 when no Authorization header is present on GET /me")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /me — 200 with correct profile when valid token provided")
    void should_return200WithProfile_when_validTokenProvided() throws Exception {
        log.debug("Arrange: register user and obtain access token");
        String accessToken = registerAndGetToken(
                "getme@beautica.com", "Str0ngP@ss1!", "Olena", "Koval", null);

        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: GET /api/v1/users/me with valid token for getme@beautica.com");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 when a valid token is used to access own profile")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().email()).isEqualTo("getme@beautica.com");
        assertThat(apiResponse.data().role()).isEqualTo("CLIENT");
        assertThat(apiResponse.data().firstName()).isEqualTo("Olena");
        assertThat(apiResponse.data().lastName()).isEqualTo("Koval");
        assertThat(apiResponse.data().isActive()).isTrue();
        assertThat(apiResponse.data().id()).isNotNull();
    }

    @Test
    @DisplayName("GET /me — 200 for any authenticated role (no role restriction on own profile)")
    void should_return200_when_anyAuthenticatedUserAccessesOwnProfile() throws Exception {
        log.debug("Arrange: register as CLIENT — only role auto-assigned on register");
        String accessToken = registerAndGetToken(
                "anyrole@beautica.com", "Str0ngP@ss1!", null, null, null);

        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: GET /api/v1/users/me with CLIENT token — any authenticated role must be accepted");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 — GET /me has no role restriction")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().role()).isNotBlank();
    }

    // ── PATCH /api/v1/users/me ────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /me — 401 when no Authorization header")
    void should_return401_when_noTokenOnPatch() {
        log.debug("Arrange: no Authorization header prepared");
        var request = new UpdateProfileRequest("Ivan", "Petrenko", null,
                null, null, null, null, null);

        log.debug("Act: PATCH /api/v1/users/me without credentials — unauthenticated request must be rejected");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(request),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 401 when no Authorization header is present on PATCH /me")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("PATCH /me — 200 and fields updated when valid patch applied")
    void should_return200AndUpdateFields_when_patchApplied() throws Exception {
        log.debug("Arrange: register user with initial name, obtain token");
        String accessToken = registerAndGetToken(
                "patch@beautica.com", "Str0ngP@ss1!", "Stara", "Familiya", "+380671111111");

        var patchRequest = new UpdateProfileRequest("Nova", "Familiya", "+380672222222",
                null, null, null, null, null);
        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: PATCH /api/v1/users/me changing firstName='Nova', lastName, and phoneNumber");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 when valid patch fields are applied")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().firstName()).isEqualTo("Nova");
        assertThat(apiResponse.data().lastName()).isEqualTo("Familiya");
        assertThat(apiResponse.data().phoneNumber()).isEqualTo("+380672222222");
        assertThat(apiResponse.data().email()).isEqualTo("patch@beautica.com");
    }

    @Test
    @DisplayName("PATCH /me — 200 and fields unchanged when all patch fields are null")
    void should_return200AndLeaveFieldsUnchanged_when_allNullPatch() throws Exception {
        log.debug("Arrange: register user with known name, obtain token");
        String accessToken = registerAndGetToken(
                "nullpatch@beautica.com", "Str0ngP@ss1!", "Kept", "Name", "+380633333333");

        var patchRequest = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null);
        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: PATCH /api/v1/users/me with all-null fields — existing values must be preserved");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 200 even when all patch fields are null")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.success()).isTrue();
        assertThat(apiResponse.data().firstName()).isEqualTo("Kept");
        assertThat(apiResponse.data().lastName()).isEqualTo("Name");
        assertThat(apiResponse.data().phoneNumber()).isEqualTo("+380633333333");
    }

    // ── PATCH /api/v1/users/me — validation ──────────────────────────────────

    @Test
    @DisplayName("PATCH /me — 400 when firstName is blank (empty string erases name)")
    void should_return400_when_firstNameIsBlank() throws Exception {
        log.debug("Arrange: register user and obtain access token");
        String accessToken = registerAndGetToken(
                "blank-fn@beautica.com", "Str0ngP@ss1!", "Valid", "Name", null);

        var patchRequest = new UpdateProfileRequest("", null, null,
                null, null, null, null, null);
        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: PATCH /api/v1/users/me with empty string firstName — must be rejected");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when firstName is an empty string")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PATCH /me — 400 when firstName exceeds 100 characters")
    void should_return400_when_firstNameTooLong() throws Exception {
        log.debug("Arrange: register user and obtain access token");
        String accessToken = registerAndGetToken(
                "long-fn@beautica.com", "Str0ngP@ss1!", "Valid", "Name", null);

        String tooLong = "A".repeat(101);
        var patchRequest = new UpdateProfileRequest(tooLong, null, null,
                null, null, null, null, null);
        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: PATCH /api/v1/users/me with 101-character firstName — must exceed max length and be rejected");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when firstName exceeds 100 characters")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PATCH /me — 400 when phoneNumber contains invalid characters")
    void should_return400_when_phoneNumberInvalidFormat() throws Exception {
        log.debug("Arrange: register user and obtain access token");
        String accessToken = registerAndGetToken(
                "bad-phone@beautica.com", "Str0ngP@ss1!", "Valid", "Name", null);

        var patchRequest = new UpdateProfileRequest(null, null, "not-a-phone!@#",
                null, null, null, null, null);
        HttpHeaders headers = bearerHeaders(accessToken);

        log.debug("Act: PATCH /api/v1/users/me with phoneNumber='not-a-phone!@#' — must fail validation");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("status must be 400 when phoneNumber contains invalid characters")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Phase 10.6 — CLIENT discovery-default locality (AC 4) ────────────────

    @Test
    @DisplayName("Register CLIENT — 200 with no locality (registration is never blocked on locality, AC 4)")
    void should_registerClient_when_noLocalityProvided() {
        log.debug("Arrange: a CLIENT registration payload that carries no city/district");
        var request = new RegisterRequest(
                "noloc-client@beautica.com", "Str0ngP@ss1!",
                SelfRegistrationRole.CLIENT, "Olha", "Bez", null, null);

        log.debug("Act: POST /api/v1/auth/register — locality is absent and must not block");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT registration must succeed with no locality (AC 4 — never block on locality)")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("PATCH /me (CLIENT) — 200 and city_id persisted as a discovery default (AC 4)")
    void should_setCityIdDiscoveryDefault_when_clientPatchesProfile() throws Exception {
        log.debug("Arrange: register a CLIENT (no locality), resolve a real seeded city id");
        String accessToken = registerAndGetToken(
                "client-disc@beautica.com", "Str0ngP@ss1!", "Dasha", "Klient", null);
        UUID cityId = cityIdByKatotth(CITY_WITH_DISTRICTS_KATOTTH);

        // CLIENT discovery default is city-only — district is optional and NOT
        // required even when the city defines urban districts (most-specific-
        // node rule applies to providers, not to the client filter).
        var patchRequest = new UpdateProfileRequest(null, null, null,
                cityId, null, null, null, null);

        log.debug("Act: PATCH /api/v1/users/me with cityId={} as a discovery default", cityId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, bearerHeaders(accessToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT may set city_id alone as a discovery default (AC 4)")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.data().cityId())
                .as("city_id must be persisted and read back for the CLIENT")
                .isEqualTo(cityId);
        assertThat(apiResponse.data().districtId()).isNull();
        assertThat(apiResponse.data().street())
                .as("CLIENT has no physical address — street is never persisted")
                .isNull();
    }

    // ── Phase 10.6 — INDEPENDENT_MASTER most-specific-node rule (AC 1/2/3) ────

    @Test
    @DisplayName("PATCH /me (INDEPENDENT_MASTER) — 400 when city omitted (city is mandatory for providers, AC 1)")
    void should_reject_when_independentMasterOmitsCity() throws Exception {
        log.debug("Arrange: seed + log in an INDEPENDENT_MASTER (cannot self-register)");
        String token = createIndependentMasterAndGetToken("im-nocity@beautica.com");

        var patchRequest = new UpdateProfileRequest(null, null, null,
                null, null, "Some St", "1", null);

        log.debug("Act: PATCH /api/v1/users/me with no city — provider save must be rejected");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("INDEPENDENT_MASTER save without city_id must be 400 (AC 1)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PATCH /me (INDEPENDENT_MASTER) — 400 when city has districts but district omitted (AC 2)")
    void should_reject_when_independentMasterOmitsRequiredDistrict() throws Exception {
        log.debug("Arrange: seed + log in an INDEPENDENT_MASTER; resolve a city WITH districts");
        String token = createIndependentMasterAndGetToken("im-nodistrict@beautica.com");
        UUID cityWithDistricts = cityIdByKatotth(CITY_WITH_DISTRICTS_KATOTTH);

        var patchRequest = new UpdateProfileRequest(null, null, null,
                cityWithDistricts, null, "Some St", "1", null);

        log.debug("Act: PATCH /api/v1/users/me — city defines districts, district omitted → reject");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("provider save with a districted city but no district must be 400 (AC 2)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PATCH /me (INDEPENDENT_MASTER) — 400 when district is not a child of the city (AC 3)")
    void should_reject_when_independentMasterDistrictNotChildOfCity() throws Exception {
        log.debug("Arrange: seed + log in an INDEPENDENT_MASTER; mismatched city/district pair");
        String token = createIndependentMasterAndGetToken("im-mismatch@beautica.com");
        UUID cityWithDistricts = cityIdByKatotth(CITY_WITH_DISTRICTS_KATOTTH);
        UUID alienDistrictId = UUID.randomUUID(); // not a child of the city

        var patchRequest = new UpdateProfileRequest(null, null, null,
                cityWithDistricts, alienDistrictId, "Some St", "1", null);

        log.debug("Act: PATCH /api/v1/users/me — district is not a child of the supplied city → reject");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("provider save with a district not belonging to the city must be 400 (AC 3)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PATCH /me (INDEPENDENT_MASTER) — 200 when city has no districts and district is null (AC 2)")
    void should_accept_when_independentMasterUsesDistrictlessCity() throws Exception {
        log.debug("Arrange: seed + log in an INDEPENDENT_MASTER; resolve a city WITHOUT districts");
        String token = createIndependentMasterAndGetToken("im-leafcity@beautica.com");
        UUID districtlessCity = anyCityWithoutDistricts();

        var patchRequest = new UpdateProfileRequest(null, null, null,
                districtlessCity, null, "Lesi Ukrainky", "7", "Blue door");

        log.debug("Act: PATCH /api/v1/users/me — districtless city is the leaf, null district is valid");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("provider save with a districtless city and null district must be 200 (AC 2)")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.data().cityId()).isEqualTo(districtlessCity);
        assertThat(apiResponse.data().districtId()).isNull();
        assertThat(apiResponse.data().street())
                .as("INDEPENDENT_MASTER persists the full structured address")
                .isEqualTo("Lesi Ukrainky");
    }

    // ── Phase 10.7 — write-path security properties (AC 2/3) ─────────────────

    @Test
    @DisplayName("PATCH /me (CLIENT) — provider-only fields supplied are silently dropped, never persisted (AC 2)")
    void should_dropProviderOnlyFields_when_clientSuppliesThem() throws Exception {
        log.debug("Arrange: register a CLIENT and resolve a real seeded city id");
        String accessToken = registerAndGetToken(
                "client-provfields@beautica.com", "Str0ngP@ss1!", "Kli", "Ent", null);
        UUID cityId = cityIdByKatotth(CITY_WITH_DISTRICTS_KATOTTH);

        // A CLIENT explicitly supplies the provider-only structured address.
        // The per-role write routing must NOT persist street/buildingNo/
        // locationNote for a CLIENT (they are a physical-address concept that
        // only providers own); only the discovery-default city_id is kept.
        var patchRequest = new UpdateProfileRequest(null, null, null,
                cityId, null, "Provider Street", "42", "Hidden entrance");

        log.debug("Act: PATCH /api/v1/users/me as CLIENT supplying provider-only address fields");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, bearerHeaders(accessToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT save must still succeed — extra fields are dropped, not an error")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.data().cityId()).isEqualTo(cityId);
        assertThat(apiResponse.data().street())
                .as("CLIENT must NOT be able to write the provider-only street field")
                .isNull();
        assertThat(apiResponse.data().buildingNo())
                .as("CLIENT must NOT be able to write the provider-only building_no field")
                .isNull();
        assertThat(apiResponse.data().locationNote())
                .as("CLIENT must NOT be able to write the provider-only location_note field")
                .isNull();
    }

    @Test
    @DisplayName("PATCH /me (SALON_MASTER) — has no personal-locality write path; locality fields ignored (AC 2)")
    void should_notWritePersonalLocality_when_salonMasterPatchesProfile() throws Exception {
        log.debug("Arrange: seed + log in a SALON_MASTER; resolve a real seeded city id");
        String token = createUserWithRoleAndGetToken("sm-noloc@beautica.com", "SALON_MASTER");
        UUID cityId = cityIdByKatotth(CITY_WITH_DISTRICTS_KATOTTH);

        var patchRequest = new UpdateProfileRequest("Sal", "Master", null,
                cityId, null, "Some St", "1", "note");

        log.debug("Act: PATCH /api/v1/users/me as SALON_MASTER supplying locality — must be ignored, not persisted");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("SALON_MASTER profile save succeeds but writes no personal locality")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.data().firstName()).isEqualTo("Sal");
        assertThat(apiResponse.data().cityId())
                .as("SALON_MASTER has NO personal-locality write path — city_id stays null")
                .isNull();
        assertThat(apiResponse.data().street())
                .as("SALON_MASTER has NO personal-locality write path — street stays null")
                .isNull();
    }

    @Test
    @DisplayName("PATCH /me (SALON_ADMIN) — has no personal-locality write path; locality fields ignored (AC 2)")
    void should_notWritePersonalLocality_when_salonAdminPatchesProfile() throws Exception {
        log.debug("Arrange: seed + log in a SALON_ADMIN; resolve a real seeded city id");
        String token = createUserWithRoleAndGetToken("sa-noloc@beautica.com", "SALON_ADMIN");
        UUID cityId = cityIdByKatotth(CITY_WITH_DISTRICTS_KATOTTH);

        var patchRequest = new UpdateProfileRequest("Adm", "In", null,
                cityId, null, "Admin St", "2", "note");

        log.debug("Act: PATCH /api/v1/users/me as SALON_ADMIN supplying locality — must be ignored");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("SALON_ADMIN profile save succeeds but writes no personal locality")
                .isEqualTo(HttpStatus.OK);

        var apiResponse = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {});
        assertThat(apiResponse.data().cityId())
                .as("SALON_ADMIN has NO personal-locality write path — city_id stays null")
                .isNull();
        assertThat(apiResponse.data().street())
                .as("SALON_ADMIN has NO personal-locality write path — street stays null")
                .isNull();
    }

    @Test
    @DisplayName("PATCH /me — 400 when street exceeds 255 chars (length bound enforced at the boundary, AC 3)")
    void should_return400_when_streetExceedsLengthBound() throws Exception {
        log.debug("Arrange: seed + log in an INDEPENDENT_MASTER");
        String token = createUserWithRoleAndGetToken("im-longstreet@beautica.com", "INDEPENDENT_MASTER");

        String tooLongStreet = "S".repeat(256);
        var patchRequest = new UpdateProfileRequest(null, null, null,
                null, null, tooLongStreet, null, null);

        log.debug("Act: PATCH /api/v1/users/me with a 256-char street — must be a clean 400, not a 500");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("street over the 255 @Size/@Column bound must be rejected at the boundary (AC 3)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PATCH /me — 400 when buildingNo exceeds 50 chars (length bound enforced, AC 3)")
    void should_return400_when_buildingNoExceedsLengthBound() throws Exception {
        log.debug("Arrange: seed + log in an INDEPENDENT_MASTER");
        String token = createUserWithRoleAndGetToken("im-longbno@beautica.com", "INDEPENDENT_MASTER");

        String tooLongBuildingNo = "9".repeat(51);
        var patchRequest = new UpdateProfileRequest(null, null, null,
                null, null, null, tooLongBuildingNo, null);

        log.debug("Act: PATCH /api/v1/users/me with a 51-char buildingNo — must be a clean 400");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("buildingNo over the 50 @Size/@Column bound must be rejected (AC 3)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PATCH /me — 400 when locationNote exceeds 1000 chars (length bound enforced, AC 3)")
    void should_return400_when_locationNoteExceedsLengthBound() throws Exception {
        log.debug("Arrange: seed + log in an INDEPENDENT_MASTER");
        String token = createUserWithRoleAndGetToken("im-longnote@beautica.com", "INDEPENDENT_MASTER");

        String tooLongNote = "N".repeat(1001);
        var patchRequest = new UpdateProfileRequest(null, null, null,
                null, null, null, null, tooLongNote);

        log.debug("Act: PATCH /api/v1/users/me with a 1001-char locationNote — must be a clean 400");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, bearerHeaders(token)),
                String.class);

        assertThat(response.getStatusCode())
                .as("locationNote over the 1000 @Size bound must be rejected (AC 3 — TEXT column, "
                        + "so the DTO @Size is the only enforced bound)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Resolves a seeded city id by its stable KATOTTH business key. */
    private UUID cityIdByKatotth(String katotthCode) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE katotth_code = ?", UUID.class, katotthCode);
    }

    /**
     * Returns any seeded city that has no urban districts (the city is the
     * locality leaf). Derived from the live seed so the test stays correct if
     * the KATOTTH snapshot changes — never a hardcoded id.
     */
    private UUID anyCityWithoutDistricts() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE id NOT IN (SELECT city_id FROM city_districts) LIMIT 1",
                UUID.class);
    }

    /**
     * Seeds an email-verified INDEPENDENT_MASTER directly (the role cannot
     * self-register — {@code SelfRegistrationRole} only allows CLIENT /
     * SALON_OWNER) and returns a fresh access token via login. Real BCrypt via
     * the production {@link PasswordEncoder} (§M: no fake hashes).
     */
    private String createIndependentMasterAndGetToken(String email) throws Exception {
        return createUserWithRoleAndGetToken(email, "INDEPENDENT_MASTER");
    }

    /**
     * Seeds an email-verified user with the given role directly (roles other
     * than CLIENT / SALON_OWNER cannot self-register) and returns a fresh
     * access token via login. Real BCrypt via the production
     * {@link PasswordEncoder} (§M: no fake hashes). {@code role} is a fixed
     * enum constant supplied by the test, never user input — safe to inline.
     */
    private String createUserWithRoleAndGetToken(String email, String role) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, true, true)",
                UUID.randomUUID(), email, hash, role);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    /**
     * Registers a new user, bypasses the email-verification gate via a direct DB update,
     * then logs in and returns the access token.
     */
    private String registerAndGetToken(
            String email, String password,
            String firstName, String lastName, String phoneNumber) throws Exception {
        var request = new RegisterRequest(email, password, SelfRegistrationRole.CLIENT, firstName, lastName, phoneNumber, null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Phase 1.7: registration no longer issues tokens; mark email verified then login.
        jdbcTemplate.update("UPDATE users SET email_verified = true WHERE email = ?", email);

        ResponseEntity<String> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, password), String.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var body = objectMapper.readValue(
                loginResp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    /** Builds HttpHeaders with a Bearer token and JSON content-type. */
    private HttpHeaders bearerHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
