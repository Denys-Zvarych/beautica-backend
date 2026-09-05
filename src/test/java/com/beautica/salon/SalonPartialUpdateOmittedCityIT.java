package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real HTTP-level (Testcontainers) regression coverage for the user-reported bug that started
 * the V150/V151 "a salon must always have a city" track: {@code PATCH /salons/{id}} changing
 * ONLY the description returned a 400 {@code BusinessException("City is required")} because the
 * mobile client's PATCH payload builder does not resend {@code cityId} — {@code SalonService}
 * fed the raw (null) request cityId into {@code LocalityWriteValidator.validateProviderLocality}
 * unconditionally, even though the salon being patched already had a perfectly valid city.
 *
 * <p>Deliberately a new, narrowly-scoped class rather than adding to
 * {@code SalonPublicProfileIntegrationTest} or {@code SalonMutationPersistenceIT}: this is THE
 * regression the whole locality-NOT-NULL track exists to fix, so it deserves its own
 * discoverable, single-purpose file (small-file convention, see {@code CLAUDE.md} § phase docs)
 * rather than being buried as one more case in an unrelated class's javadoc scope.
 *
 * <p>Fixes {@code SalonService#updateSalon}: {@code cityId}/{@code districtId} are now only
 * (re)validated and (re)written when the request actually supplies a {@code cityId} — PATCH
 * semantics, mirroring the CLIENT/INDEPENDENT_MASTER fix already shipped for {@code UserService}
 * (commit 9ed0559). An omitted {@code cityId} means "locality not included in this update", not
 * "clear my city" — a salon's city is mandatory and, per V150/V151, always already non-null on
 * any persisted row, so there is always a valid existing value to keep.
 *
 * <p>Extended (same fix pass, sibling MEDIUM finding) to cover {@code locationNote}: it is
 * OPTIONAL on {@code UpdateSalonRequest} (unlike {@code street}/{@code buildingNo}, which are
 * {@code @NotBlank} and therefore always resent), so the same unconditional-write bug shape
 * silently wiped a saved note on any PATCH that omitted it — a strictly worse failure mode than
 * the cityId bug above, since it lost data with a 200 rather than rejecting the request with a
 * loud 400.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH /salons/{id} — description-only update must not require resending cityId")
class SalonPartialUpdateOmittedCityIT extends AbstractIntegrationTest {

    private static final String SALONS_URL = "/api/v1/salons";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("PATCH with only description set (cityId/districtId omitted) succeeds and keeps the salon's existing city")
    void should_updateDescriptionOnly_when_cityIdOmittedFromPatch() throws Exception {
        // Arrange — a real owner + a real salon that already HAS a city (every persisted salon
        // does, per V150/V151), created through the actual createSalon HTTP path so the fixture
        // is not hand-rolled around the bug under test.
        String ownerEmail = "owner-desc-only-" + UUID.randomUUID() + "@beautica.test";
        createOwner(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID cityId = testCityId();

        UUID salonId = registerSalon(ownerToken, cityId);

        // Act — PATCH with ONLY description changed. cityId/districtId are omitted (null in the
        // JSON body, exactly like the mobile notifier's payload) — name/street/buildingNo are
        // resent unchanged because UpdateSalonRequest still requires them (@NotBlank), but this
        // is NOT a locality-changing request: the bug reproduces specifically on the omitted
        // cityId, independent of what else is (or isn't) in the payload.
        var descriptionOnlyPatch = new UpdateSalonRequest(
                "Velvet Studio", "Freshly renovated interior", null, null, null,
                null, null, "Khreshchatyk St", "22", null, null, null);

        ResponseEntity<String> patchResponse = restTemplate.exchange(
                SALONS_URL + "/" + salonId, HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(descriptionOnlyPatch), bearerHeaders(ownerToken)),
                String.class);

        // Assert — the ORIGINAL bug: this returned 400 "City is required". Must now be 200, and
        // the salon must still carry the SAME cityId it already had — proving the backend
        // preserved, rather than merely tolerated, the existing locality.
        assertThat(patchResponse.getStatusCode())
                .as("description-only PATCH omitting cityId must succeed — actual body: %s",
                        patchResponse.getBody())
                .isEqualTo(HttpStatus.OK);

        var wrapper = objectMapper.readValue(
                patchResponse.getBody(), new TypeReference<ApiResponse<SalonResponse>>() { });
        SalonResponse patched = wrapper.data();

        assertThat(patched.description()).isEqualTo("Freshly renovated interior");
        assertThat(patched.cityId())
                .as("cityId must be UNCHANGED (kept from the existing row) when omitted from the PATCH")
                .isEqualTo(cityId);

        // Re-fetch independently to prove the value was actually committed, not just echoed
        // back in the PATCH response.
        ResponseEntity<String> getResponse =
                restTemplate.getForEntity(SALONS_URL + "/" + salonId, String.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT city_id FROM salons WHERE id = ?", UUID.class, salonId))
                .as("the committed DB row must still carry the original cityId")
                .isEqualTo(cityId);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT description FROM salons WHERE id = ?", String.class, salonId))
                .isEqualTo("Freshly renovated interior");
    }

    @Test
    @DisplayName("PATCH with only name changed (locationNote omitted) must not wipe a previously saved location note")
    void should_keepLocationNote_when_nameOnlyPatchOmitsIt() throws Exception {
        // Arrange — a real salon, then a first PATCH that actually sets a locationNote (the
        // user-visible symptom the MEDIUM finding is about: this note must survive an unrelated
        // later edit, exactly like an address-based landmark note a client relies on to find the
        // salon).
        String ownerEmail = "owner-note-survive-" + UUID.randomUUID() + "@beautica.test";
        createOwner(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID cityId = testCityId();
        UUID salonId = registerSalon(ownerToken, cityId);

        var setNotePatch = new UpdateSalonRequest(
                "Velvet Studio", null, null, null, null,
                null, null, "Khreshchatyk St", "22", "2nd floor, blue door", null, null);
        ResponseEntity<String> setNoteResponse = restTemplate.exchange(
                SALONS_URL + "/" + salonId, HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(setNotePatch), bearerHeaders(ownerToken)),
                String.class);
        assertThat(setNoteResponse.getStatusCode())
                .as("setting the locationNote must succeed — actual body: %s", setNoteResponse.getBody())
                .isEqualTo(HttpStatus.OK);

        // Act — a name-only PATCH that omits locationNote entirely (null on the wire), just like
        // the mobile client's general profile-save path (name/description/phone/Instagram) which
        // never sends locationNote at all.
        var nameOnlyPatch = new UpdateSalonRequest(
                "Velvet Studio Renamed", null, null, null, null,
                null, null, "Khreshchatyk St", "22", null, null, null);
        ResponseEntity<String> patchResponse = restTemplate.exchange(
                SALONS_URL + "/" + salonId, HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(nameOnlyPatch), bearerHeaders(ownerToken)),
                String.class);

        // Assert — the MEDIUM bug: this used to silently null out locationNote in the DB.
        assertThat(patchResponse.getStatusCode())
                .as("name-only PATCH omitting locationNote must succeed — actual body: %s",
                        patchResponse.getBody())
                .isEqualTo(HttpStatus.OK);

        var wrapper = objectMapper.readValue(
                patchResponse.getBody(), new TypeReference<ApiResponse<SalonResponse>>() { });
        assertThat(wrapper.data().locationNote())
                .as("locationNote must survive an unrelated PATCH that omits it")
                .isEqualTo("2nd floor, blue door");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT location_note FROM salons WHERE id = ?", String.class, salonId))
                .as("the committed DB row must still carry the original locationNote")
                .isEqualTo("2nd floor, blue door");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private UUID createOwner(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));
        return userId;
    }

    /** Registers a real salon (with a real city + required street/buildingNo) via the actual create endpoint. */
    private UUID registerSalon(String ownerToken, UUID cityId) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "name", "Velvet Studio",
                "cityId", cityId,
                "street", "Khreshchatyk St",
                "buildingNo", "22"
        ));
        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL, HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(ownerToken)),
                String.class);
        assertThat(response.getStatusCode())
                .as("salon fixture creation must succeed — actual body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        var wrapper = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<SalonResponse>>() { });
        return wrapper.data().id();
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode())
                .as("login must succeed for %s — actual body: %s", email, resp.getBody())
                .isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() { });
        return body.data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
