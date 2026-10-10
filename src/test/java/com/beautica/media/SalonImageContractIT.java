package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.beautica.support.R2DeleteLedger.purgedKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 343 QA — the HTTP contracts {@link SalonImageIT} does not pin: the seed-script wire contract
 * ({@code scripts/seed-demo-fleet.sh} → {@code seed_salon_image}), the CLIENT-visible public profile, strict
 * slot binding on BOTH verbs, invalid-file rejection on the salon route, cover-replace slot isolation and the
 * HTTP salon delete purging both images. Real Postgres, mocked R2 (purges are synchronous in the test profile).
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon logo/cover — wire contracts, public visibility, strict slot, invalid files (Phase 343 QA)")
class SalonImageContractIT extends AbstractMediaIntegrationTest {

    private static final String CDN = "https://cdn.example/";
    /** PNG signature + IHDR chunk start — what {@code seed-demo-fleet.sh} sends ({@code type=image/png}). */
    private static final byte[] PNG = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};
    private static final String SEEDED_LOGO = "https://cdn.example/salons/seed/logo/l.jpg";
    private static final String SEEDED_COVER = "https://cdn.example/salons/seed/cover/c.jpg";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private R2StorageService r2;

    @Override protected TestRestTemplate restTemplate() { return restTemplate; }
    @Override protected ObjectMapper objectMapper() { return objectMapper; }
    @Override protected PasswordEncoder passwordEncoder() { return passwordEncoder; }

    @BeforeEach
    void setUpR2() {
        reset(r2);
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> CDN + inv.getArgument(0));
        when(r2.extractKeyFromPublicUrl(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            return url.startsWith(CDN) ? Optional.of(url.substring(CDN.length())) : Optional.empty();
        });
        doNothing().when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    // ── seed-script contract ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("seed contract: owner POSTs a PNG part named `file` (type=image/png) to /media/cover then "
            + "/media/logo — both 200, stored as .png under the slot prefix (seed-demo-fleet.sh seed_salon_images)")
    void should_return200ForCoverThenLogo_when_ownerSendsSeedScriptMultipart() throws Exception {
        Owner o = owner("seed");

        ResponseEntity<String> cover = post(o.salonId(), "cover", o.token(), pngPart("file"));
        ResponseEntity<String> logo = post(o.salonId(), "logo", o.token(), pngPart("file"));

        assertThat(cover.getStatusCode()).as("cover body=%s", cover.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(logo.getStatusCode()).as("logo body=%s", logo.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(data(logo).path("coverImageUrl").asText())
                .startsWith(CDN + "salons/" + o.salonId() + "/cover/").endsWith(".png");
        assertThat(data(logo).path("avatarUrl").asText())
                .startsWith(CDN + "salons/" + o.salonId() + "/logo/").endsWith(".png");
        verify(r2).uploadFile(startsWith("salons/" + o.salonId() + "/cover/"), any(), eq((long) PNG.length),
                eq("image/png"));
        verify(r2).uploadFile(startsWith("salons/" + o.salonId() + "/logo/"), any(), eq((long) PNG.length),
                eq("image/png"));
    }

    @Test
    @DisplayName("seed contract: a part under any other name than `file` → 400 'Required file part is missing', "
            + "no R2 upload (renaming the field would silently break the seed)")
    void should_return400_when_multipartFieldIsNotNamedFile() throws Exception {
        Owner o = owner("seed-field");

        ResponseEntity<String> resp = post(o.salonId(), "cover", o.token(), pngPart("image"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(resp.getBody()).path("message").asText())
                .isEqualTo("Required file part is missing");
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    // ── public visibility ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("public profile: a logged-in CLIENT's GET /salons/{id} shows the owner's uploaded banner and "
            + "logo, and never the R2 keys")
    void should_showCoverAndLogoToClient_when_ownerUploadedBoth() throws Exception {
        Owner o = owner("public");
        String clientEmail = "p343-qa-client-" + System.nanoTime() + "@beautica.test";
        insertClient(clientEmail);
        String clientToken = loginAndGetToken(clientEmail);
        String coverUrl = data(post(o.salonId(), "cover", o.token(), jpegMultipartBody())).path("coverImageUrl").asText();
        String logoUrl = data(post(o.salonId(), "logo", o.token(), jpegMultipartBody())).path("avatarUrl").asText();

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/salons/" + o.salonId(), HttpMethod.GET,
                new HttpEntity<>(authHeaders(clientToken)), String.class);

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = data(resp);
        assertThat(body.path("coverImageUrl").asText()).as("client sees the banner").isEqualTo(coverUrl);
        assertThat(body.path("avatarUrl").asText()).as("client sees the logo").isEqualTo(logoUrl);
        assertThat(resp.getBody()).as("R2 keys never leave the server")
                .doesNotContain("r2Key").doesNotContain("R2Key").doesNotContain("\"salons/");
    }

    // ── strict slot on BOTH verbs (security INFO) ────────────────────────────────────────────

    static Stream<Arguments> invalidSlotsOnBothVerbs() {
        return Stream.of("banner", "LOGO", "Cover", "%20logo", "logo%20")
                .flatMap(slot -> Stream.of(Arguments.of(HttpMethod.POST, slot), Arguments.of(HttpMethod.DELETE, slot)));
    }

    @ParameterizedTest(name = "{0} /media/{1} → 400, constants not echoed, row + R2 untouched")
    @MethodSource("invalidSlotsOnBothVerbs")
    void should_return400AndTouchNothing_when_slotIsNotExactLowercase(HttpMethod verb, String slot) throws Exception {
        Owner o = owner("slot");
        jdbcTemplate.update("UPDATE salons SET avatar_url = ?, cover_image_url = ? WHERE id = ?",
                SEEDED_LOGO, SEEDED_COVER, o.salonId());
        URI uri = URI.create(restTemplate.getRootUri() + "/api/v1/salons/" + o.salonId() + "/media/" + slot);
        HttpEntity<?> request = verb == HttpMethod.POST
                ? new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(o.token()))
                : new HttpEntity<>(authHeaders(o.token()));

        ResponseEntity<String> resp = restTemplate.exchange(uri, verb, request, String.class);

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).doesNotContain("LOGO").doesNotContain("COVER");
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
        assertThat(purgedKeys(r2)).isEmpty();
        Map<String, Object> cols = columns(o.salonId());
        assertThat(cols.get("avatar_url")).isEqualTo(SEEDED_LOGO);
        assertThat(cols.get("cover_image_url")).isEqualTo(SEEDED_COVER);
    }

    // ── invalid files ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("invalid file: an SVG declared image/svg+xml on /media/cover → 400 (generic body), no R2 upload")
    void should_return400_when_coverIsSvg() throws Exception {
        Owner o = owner("svg");
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>x</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> resp = post(o.salonId(), "cover", o.token(), part("file", svg, "c.svg", "image/svg+xml"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertGenericBadRequest(resp);
        assertImageRejectedUntouched(o);
    }

    @Test
    @DisplayName("invalid file: an empty (0-byte) part on /media/logo → 400 (generic body), no R2 upload")
    void should_return400_when_logoIsEmpty() throws Exception {
        Owner o = owner("empty");

        ResponseEntity<String> resp = post(o.salonId(), "logo", o.token(), part("file", new byte[0], "e.jpg", "image/jpeg"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertGenericBadRequest(resp);
        assertImageRejectedUntouched(o);
    }

    @Test
    @DisplayName("invalid file: a 5 MB + 1 byte JPEG on /media/cover → 413 at the multipart layer, no R2 upload")
    void should_return413_when_coverExceedsFiveMegabytes() throws Exception {
        Owner o = owner("oversize");
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        Arrays.fill(big, (byte) 0x20);
        System.arraycopy(JPEG_HEADER, 0, big, 0, JPEG_HEADER.length);

        ResponseEntity<String> resp = post(o.salonId(), "cover", o.token(), part("file", big, "big.jpg", "image/jpeg"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertImageRejectedUntouched(o);
    }

    @Test
    @DisplayName("boundary: an exactly-5 MB JPEG on /media/cover passes the multipart layer (envelope overhead "
            + "no longer trips max-request-size) and is accepted by the service cap → 200, pointer written")
    void should_return200_when_coverIsExactlyFiveMegabytes() throws Exception {
        Owner o = owner("exact-5mb");

        ResponseEntity<String> resp = post(o.salonId(), "cover", o.token(), jpegMultipartBodyOfSize(FIVE_MB));

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        verify(r2).uploadFile(startsWith("salons/" + o.salonId() + "/cover/"), any(), eq((long) FIVE_MB), anyString());
        assertThat(columns(o.salonId()).get("cover_r2_key")).isNotNull();
    }

    // ── replace isolation + salon delete ─────────────────────────────────────────────────────

    @Test
    @DisplayName("replace cover: purges ONLY the first cover key after commit — the logo blob and pointer survive")
    void should_purgeOnlyFirstCoverKey_when_coverReplacedWhileLogoSet() throws Exception {
        Owner o = owner("cover-replace");
        String logoKey = key(data(post(o.salonId(), "logo", o.token(), jpegMultipartBody())).path("avatarUrl"));
        String firstCoverKey = key(data(post(o.salonId(), "cover", o.token(), jpegMultipartBody())).path("coverImageUrl"));
        setUpR2();

        ResponseEntity<String> resp = post(o.salonId(), "cover", o.token(), jpegMultipartBody());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondCoverKey = key(data(resp).path("coverImageUrl"));
        assertThat(purgedKeys(r2)).containsExactly(firstCoverKey);
        Map<String, Object> cols = columns(o.salonId());
        assertThat(cols.get("cover_r2_key")).isEqualTo(secondCoverKey).isNotEqualTo(firstCoverKey);
        assertThat(cols.get("avatar_r2_key")).as("logo untouched by a cover replace").isEqualTo(logoKey);
    }

    @Test
    @DisplayName("salon delete over HTTP: owner DELETE /salons/{id} after uploading logo + cover → 204 and both "
            + "uploaded blobs are purged after commit")
    void should_purgeLogoAndCover_when_ownerDeletesSalonOverHttp() throws Exception {
        Owner o = owner("salon-delete");
        String logoKey = key(data(post(o.salonId(), "logo", o.token(), jpegMultipartBody())).path("avatarUrl"));
        String coverKey = key(data(post(o.salonId(), "cover", o.token(), jpegMultipartBody())).path("coverImageUrl"));
        setUpR2();

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/salons/" + o.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(o.token())), String.class);

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(purgedKeys(r2)).containsExactlyInAnyOrder(logoKey, coverKey);
        assertThat(columns(o.salonId()).values()).as("every image pointer nulled").containsOnlyNulls();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private record Owner(UUID ownerId, UUID salonId, String token) {}

    private Owner owner(String tag) throws Exception {
        String email = "p343-qa-" + tag + "-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = insertSalonOwner(email);
        UUID salonId = insertSalon(ownerId, "P343 QA " + tag);
        return new Owner(ownerId, salonId, loginAndGetToken(email));
    }

    private static MultiValueMap<String, Object> pngPart(String fieldName) {
        return part(fieldName, PNG, "seed.png", "image/png");
    }

    /** One multipart part with an explicit part Content-Type — what curl's {@code -F 'x=@f;type=…'} sends. */
    private static MultiValueMap<String, Object> part(String fieldName, byte[] bytes, String filename, String type) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(type));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add(fieldName, new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        }, partHeaders));
        return body;
    }

    private ResponseEntity<String> post(UUID salonId, String slot, String token, MultiValueMap<String, Object> body) {
        return restTemplate.exchange("/api/v1/salons/" + salonId + "/media/" + slot, HttpMethod.POST,
                new HttpEntity<>(body, bearerMultipartHeaders(token)), String.class);
    }

    private void assertImageRejectedUntouched(Owner o) {
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
        assertThat(columns(o.salonId()).values()).as("no pointer written").containsOnlyNulls();
    }

    /** BusinessException 400s are genericised by GlobalExceptionHandler — the detail never reaches the client. */
    private void assertGenericBadRequest(ResponseEntity<String> resp) throws Exception {
        JsonNode root = objectMapper.readTree(resp.getBody());
        assertThat(root.path("success").asBoolean(true)).isFalse();
        assertThat(root.path("message").asText()).isEqualTo("Invalid request");
    }

    private JsonNode data(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private static String key(JsonNode url) {
        assertThat(url.asText()).startsWith(CDN);
        return url.asText().substring(CDN.length());
    }

    private Map<String, Object> columns(UUID salonId) {
        return jdbcTemplate.queryForMap(
                "SELECT avatar_url, avatar_r2_key, cover_image_url, cover_r2_key FROM salons WHERE id = ?", salonId);
    }
}
