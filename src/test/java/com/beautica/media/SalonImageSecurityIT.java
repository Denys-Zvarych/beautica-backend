package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 343 — the OWNER-only rule for salon logo/cover (locked product decision): every caller other than the
 * salon's own {@code SALON_OWNER} is refused on all four routes (POST/DELETE × logo/cover) BEFORE any storage
 * call, and the row is left untouched. The R2 mock is never stubbed, so ANY interaction (even
 * {@code isEnabled()}) fails {@code verifyNoInteractions}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon logo/cover — OWNER-only authorization (Phase 343)")
class SalonImageSecurityIT extends AbstractMediaIntegrationTest {

    private static final String SEEDED_LOGO = "https://cdn.example/salons/seed/logo/l.jpg";
    private static final String SEEDED_COVER = "https://cdn.example/salons/seed/cover/c.jpg";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private R2StorageService r2;

    @Override protected TestRestTemplate restTemplate() { return restTemplate; }
    @Override protected ObjectMapper objectMapper() { return objectMapper; }
    @Override protected PasswordEncoder passwordEncoder() { return passwordEncoder; }

    private UUID salonId;

    @BeforeEach
    void seedSalonWithImages() {
        reset(r2);
        UUID ownerId = insertSalonOwner("p343-sec-owner-" + System.nanoTime() + "@beautica.test");
        salonId = insertSalon(ownerId, "P343 Sec Salon");
        jdbcTemplate.update("UPDATE salons SET avatar_url = ?, cover_image_url = ? WHERE id = ?",
                SEEDED_LOGO, SEEDED_COVER, salonId);
    }

    @Test
    @DisplayName("TC-4: SALON_ADMIN of the SAME salon → 403 on POST logo, POST cover, DELETE logo, DELETE cover; "
            + "row unchanged, R2 never touched")
    void should_return403OnAllRoutes_when_callerIsAdminOfSameSalon() throws Exception {
        String email = "p343-sec-admin-" + System.nanoTime() + "@beautica.test";
        insertUser(email, "SALON_ADMIN", salonId);
        String token = loginAndGetToken(email);

        assertAllRoutesForbidden(token);
    }

    @Test
    @DisplayName("TC-5: the OWNER of another salon → 403 on all four routes; R2 never touched")
    void should_return403OnAllRoutes_when_callerOwnsADifferentSalon() throws Exception {
        String email = "p343-sec-other-owner-" + System.nanoTime() + "@beautica.test";
        UUID otherOwner = insertSalonOwner(email);
        insertSalon(otherOwner, "P343 Other Salon");
        String token = loginAndGetToken(email);

        assertAllRoutesForbidden(token);
    }

    @ParameterizedTest(name = "TC-5: {0} → 403 on all four routes")
    @ValueSource(strings = {"INDEPENDENT_MASTER", "SALON_MASTER", "CLIENT"})
    void should_return403OnAllRoutes_when_callerHasNonOwnerRole(String role) throws Exception {
        String email = "p343-sec-" + role.toLowerCase() + "-" + System.nanoTime() + "@beautica.test";
        insertUser(email, role, "SALON_MASTER".equals(role) ? salonId : null);
        String token = loginAndGetToken(email);

        assertAllRoutesForbidden(token);
    }

    @Test
    @DisplayName("TC-5: anonymous → 401 on all four routes; R2 never touched")
    void should_return401OnAllRoutes_when_anonymous() {
        for (String slot : new String[]{"logo", "cover"}) {
            HttpHeaders multipart = new HttpHeaders();
            multipart.setContentType(MediaType.MULTIPART_FORM_DATA);
            assertThat(restTemplate.exchange(url(slot), HttpMethod.POST,
                    new HttpEntity<>(jpegMultipartBody(), multipart), String.class).getStatusCode())
                    .as("anonymous POST %s", slot).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(restTemplate.exchange(url(slot), HttpMethod.DELETE,
                    new HttpEntity<>(new HttpHeaders()), String.class).getStatusCode())
                    .as("anonymous DELETE %s", slot).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertRowUnchanged();
        verifyNoInteractions(r2);
    }

    @ParameterizedTest(name = "TC-8 / D11: {0} PATCH /salons/<id> carrying avatarUrl + coverImageUrl leaves both unchanged")
    @ValueSource(strings = {"SALON_OWNER", "SALON_ADMIN"})
    void should_ignorePhotoFields_when_patchCarriesThem(String role) throws Exception {
        String token;
        if ("SALON_OWNER".equals(role)) {
            String email = "p343-tc8-owner-" + System.nanoTime() + "@beautica.test";
            UUID ownerId = insertSalonOwner(email);
            jdbcTemplate.update("UPDATE salons SET owner_id = ? WHERE id = ?", ownerId, salonId);
            token = loginAndGetToken(email);
        } else {
            String email = "p343-tc8-admin-" + System.nanoTime() + "@beautica.test";
            insertUser(email, "SALON_ADMIN", salonId);
            token = loginAndGetToken(email);
        }
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Renamed", "street", "Khreshchatyk St", "buildingNo", "22",
                "avatarUrl", "https://evil.example/x.jpg", "coverImageUrl", "https://evil.example/y.jpg"));
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/salons/" + salonId, HttpMethod.PATCH,
                new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM salons WHERE id = ?", String.class, salonId))
                .as("the PATCH itself applied").isEqualTo("Renamed");
        assertRowUnchanged();
        verifyNoInteractions(r2);
    }

    private void assertAllRoutesForbidden(String token) {
        for (String slot : new String[]{"logo", "cover"}) {
            assertThat(restTemplate.exchange(url(slot), HttpMethod.POST,
                    new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class)
                    .getStatusCode()).as("POST %s", slot).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(restTemplate.exchange(url(slot), HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(token)), String.class)
                    .getStatusCode()).as("DELETE %s", slot).isEqualTo(HttpStatus.FORBIDDEN);
        }

        assertRowUnchanged();
        verifyNoInteractions(r2);
    }

    private void assertRowUnchanged() {
        Map<String, Object> cols = jdbcTemplate.queryForMap(
                "SELECT avatar_url, avatar_r2_key, cover_image_url, cover_r2_key FROM salons WHERE id = ?", salonId);
        assertThat(cols.get("avatar_url")).isEqualTo(SEEDED_LOGO);
        assertThat(cols.get("cover_image_url")).isEqualTo(SEEDED_COVER);
        assertThat(cols.get("avatar_r2_key")).isNull();
        assertThat(cols.get("cover_r2_key")).isNull();
    }

    private String url(String slot) {
        return "/api/v1/salons/" + salonId + "/media/" + slot;
    }
}
