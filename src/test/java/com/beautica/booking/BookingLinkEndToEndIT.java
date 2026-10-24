package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #168 final QA — the share flow end to end, over real HTTP, with the owner and admin created through
 * the production paths ({@code /auth/login}, {@code POST /salons}) rather than JDBC rows:
 * {@code GET /booking-links/mine} hands out a link, and that link's slug opens the public page.
 *
 * <p>Pins the 2026-10-20 decision "owner = salon links only" from the visitor's side: the owner is
 * reachable through the SALON page, listed there as a master once bookable, never through a personal link.
 *
 * <p>Every anonymous page read carries a per-test {@code X-Forwarded-For}: the {@code /book/**}{@code /info}
 * bucket is a hard-coded 60/min per IP, and every IT on the shared context otherwise reads from 127.0.0.1.
 */
@DisplayName("Booking links end to end — /booking-links/mine → public page (PR #168)")
class BookingLinkEndToEndIT extends AbstractIntegrationTest {

    private static final String MINE = "/api/v1/booking-links/mine";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private ServiceTestFixtures fixtures;
    private String visitorIp;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        visitorIp = "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private record SalonWithStaff(UUID salonId, UUID ownerMasterId, String ownerToken, String adminToken) {
    }

    /** Owner + salon + bound admin, all through the real HTTP paths; the owner is named "Ostap Vlasnyk". */
    private SalonWithStaff salonWithOwnerAndAdmin(String salonName) throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("e2e-owner-" + UUID.randomUUID() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, salonName);
        UUID ownerMasterId = jdbcTemplate.queryForObject(
                "SELECT id FROM masters WHERE salon_id = ? AND master_type = 'SALON_OWNER' AND is_active = true",
                UUID.class, salonId);
        jdbcTemplate.update(
                "UPDATE users SET first_name = 'Ostap', last_name = 'Vlasnyk' "
                        + "WHERE id = (SELECT owner_id FROM salons WHERE id = ?)", salonId);
        String adminToken = fixtures.createSalonAdminAndGetToken(
                salonId, "e2e-admin-" + UUID.randomUUID() + "@beautica.test");
        return new SalonWithStaff(salonId, ownerMasterId, ownerToken, adminToken);
    }

    private JsonNode links(String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.exchange(MINE, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data").path("links");
    }

    private ResponseEntity<String> anonymousGet(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", visitorIp);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode publicPage(String path) throws Exception {
        ResponseEntity<String> response = anonymousGet(path);
        assertThat(response.getStatusCode()).as("GET %s → %s", path, response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    /** "Last segment" of a share URL — what the website route passes to the info read. */
    private static String slugFromUrl(JsonNode link) {
        String url = link.path("url").asText();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static List<String> names(JsonNode masters) {
        List<String> names = new ArrayList<>();
        masters.forEach(master -> names.add(master.path("name").asText()));
        return names;
    }

    // ── salon link: owner == admin, page lists the owner as a master ─────────────────────────

    @Test
    @DisplayName("bookable owner: owner and admin get the identical SALON link, and its page lists the owner as a master")
    void should_listOwnerOnSalonPage_when_ownerAndAdminShareTheSalonLinkAndOwnerIsBookable() throws Exception {
        SalonWithStaff salon = salonWithOwnerAndAdmin("Spilna Studiia");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), salon.ownerMasterId());
        String ownerMasterSlug = jdbcTemplate.queryForObject(
                "SELECT booking_slug FROM masters WHERE id = ?", String.class, salon.ownerMasterId());

        JsonNode ownerLinks = links(salon.ownerToken());
        JsonNode adminLinks = links(salon.adminToken());

        assertThat(ownerLinks).as("owner and admin share one salon link (2026-10-20)").isEqualTo(adminLinks);
        assertThat(ownerLinks).hasSize(1);
        JsonNode link = ownerLinks.get(0);
        assertThat(link.path("kind").asText()).isEqualTo("SALON");
        assertThat(link.path("salonId").asText()).isEqualTo(salon.salonId().toString());
        assertThat(link.path("bookable").asBoolean()).isTrue();
        String salonSlug = link.path("slug").asText();
        assertThat(link.path("url").asText()).isEqualTo("https://beautica.app/book/s/" + salonSlug);
        assertThat(ownerLinks.toString())
                .as("the owner's personal master slug is never handed out as a link")
                .doesNotContain(ownerMasterSlug);

        JsonNode page = publicPage("/api/v1/book/s/" + slugFromUrl(link) + "/info");

        assertThat(page.path("salonId").asText()).isEqualTo(salon.salonId().toString());
        assertThat(page.path("name").asText()).isEqualTo("Spilna Studiia");
        assertThat(page.path("bookable").asBoolean()).isTrue();
        assertThat(names(page.path("masters"))).as("the owner is listed as the salon's master").containsExactly("Ostap Vlasnyk");
        assertThat(page.path("masters").get(0).path("masterSlug").asText())
                .as("owner master row created by POST /salons carries its slug, so the anonymous page can list it")
                .isEqualTo(ownerMasterSlug);
    }

    @Test
    @DisplayName("bookable owner: the masterSlug from the salon page opens the owner's master page (the onward booking flow)")
    void should_openOwnerMasterPage_when_followingMasterSlugFromSalonPage() throws Exception {
        SalonWithStaff salon = salonWithOwnerAndAdmin("Dali Studiia");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), salon.ownerMasterId());
        String salonSlug = links(salon.adminToken()).get(0).path("slug").asText();
        String masterSlug = publicPage("/api/v1/book/s/" + salonSlug + "/info").path("masters").get(0).path("masterSlug").asText();

        JsonNode masterPage = publicPage("/api/v1/book/" + masterSlug + "/info");

        assertThat(masterPage.path("masterName").asText()).isEqualTo("Ostap Vlasnyk");
        assertThat(masterPage.path("services")).isNotEmpty();
    }

    @Test
    @DisplayName("owner with no service/schedule: SALON link bookable=false, page 200 bookable=false with the owner NOT listed")
    void should_hideOwnerAndReportNotBookable_when_ownerNotBookable() throws Exception {
        SalonWithStaff salon = salonWithOwnerAndAdmin("Porozhnia Studiia");

        JsonNode link = links(salon.ownerToken()).get(0);

        assertThat(link.path("kind").asText()).isEqualTo("SALON");
        assertThat(link.path("bookable").asBoolean()).isFalse();

        JsonNode page = publicPage("/api/v1/book/s/" + link.path("slug").asText() + "/info");

        assertThat(page.path("bookable").asBoolean()).isFalse();
        assertThat(page.path("masters")).isEmpty();
    }

    // ── independent master link ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bookable independent master: one MASTER link whose slug opens /book/{slug}/info and NOT the salon page")
    void should_openMasterPageOnly_when_independentMasterSharesLink() throws Exception {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Nezalezhna");
        BookableMasterSeeder.makeIndependentBookable(jdbcTemplate, masterId);
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        String token = jwtTokenProvider.generateAccessToken(userId, "indep-" + userId + "@beautica.test",
                Role.INDEPENDENT_MASTER);

        JsonNode links = links(token);

        assertThat(links).hasSize(1);
        JsonNode link = links.get(0);
        assertThat(link.path("kind").asText()).isEqualTo("MASTER");
        assertThat(link.path("bookable").asBoolean()).isTrue();
        String slug = link.path("slug").asText();
        assertThat(link.path("url").asText()).isEqualTo("https://beautica.app/book/" + slug);

        JsonNode masterPage = publicPage("/api/v1/book/" + slugFromUrl(link) + "/info");

        assertThat(masterPage.path("masterName").asText()).isEqualTo("Nezalezhna Nezalezhna");
        assertThat(anonymousGet("/api/v1/book/s/" + slug + "/info").getStatusCode())
                .as("a MASTER slug is not a salon slug (separate namespace)")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
