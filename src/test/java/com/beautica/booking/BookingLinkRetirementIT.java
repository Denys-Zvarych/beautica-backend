package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.service.BookingSlugService;
import com.beautica.booking.service.RetiredSlugHasher;
import com.beautica.common.cache.MasterProfileCacheEvictor;
import com.beautica.common.cache.SalonBookingInfoCacheEvictor;
import com.beautica.config.BookingLinksProperties;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 378 — regenerate / revoke booking links end to end (real Postgres, real security chain, real rate
 * limit filter): MASTER (independent master) and SALON (owner of ANY of their salons, the admin bound to
 * it) happy paths, the D6 403 matrix, D7 409, D8 idempotent revoke, D9 kill-switch, D10 429, D4 "revoked is
 * never re-issued by /mine", D5 internal issue, D13 cache eviction, and the V204 CHECK.
 */
@DisplayName("Booking links — regenerate / revoke (phase 378)")
class BookingLinkRetirementIT extends AbstractIntegrationTest {

    private static final String MINE = "/api/v1/booking-links/mine";
    private static final String MASTER_REGENERATE = "/api/v1/booking-links/master/regenerate";
    private static final String MASTER_REVOKE = "/api/v1/booking-links/master";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private BookingLinksProperties bookingLinksProperties;
    @Autowired private BookingSlugService bookingSlugService;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void enableSharing() {
        bookingLinksProperties.setShareEnabled(true);
    }

    @AfterEach
    void restoreSwitch() {
        // Context singleton shared with every IT on this cached context.
        bookingLinksProperties.setShareEnabled(true);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private UUID independentMasterWithSlug(String name, String slug) {
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, name);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, masterId);
        return masterId;
    }

    private UUID salonWithSlug(String name, String slug) {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, name);
        jdbcTemplate.update("UPDATE salons SET booking_slug = ? WHERE id = ?", slug, salonId);
        return salonId;
    }

    /** A further active salon of the same owner. */
    private UUID siblingSalonWithSlug(UUID ownerUserId, String name, String slug) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city_id, is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, ?, NOW(), NOW())",
                salonId, ownerUserId, name, testCityId(), slug);
        return salonId;
    }

    private UUID insertUser(Role role, UUID salonId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                userId, "user-" + userId + "@beautica.test", PASSWORD_HASH, role.name(), salonId);
        return userId;
    }

    private UUID userOfMaster(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private UUID ownerOf(UUID salonId) {
        return jdbcTemplate.queryForObject("SELECT owner_id FROM salons WHERE id = ?", UUID.class, salonId);
    }

    private String masterSlug(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT booking_slug FROM masters WHERE id = ?", String.class, masterId);
    }

    private boolean masterRevoked(UUID masterId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT booking_link_revoked_at IS NOT NULL FROM masters WHERE id = ?", Boolean.class, masterId));
    }

    private String salonSlug(UUID salonId) {
        return jdbcTemplate.queryForObject("SELECT booking_slug FROM salons WHERE id = ?", String.class, salonId);
    }

    private boolean salonRevoked(UUID salonId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT booking_link_revoked_at IS NOT NULL FROM salons WHERE id = ?", Boolean.class, salonId));
    }

    private int tombstoneCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM retired_booking_slugs", Integer.class);
    }

    /** The tombstone of {@code slug}, found by its hash (V205: no plaintext slug is stored). */
    private Map<String, Object> tombstone(String kind, String slug) {
        return jdbcTemplate.queryForMap(
                "SELECT master_id, salon_id, retired_by_user_id, reason FROM retired_booking_slugs "
                        + "WHERE kind = ? AND slug_hash = ?", kind, RetiredSlugHasher.hash(BookingLinkKind.valueOf(kind), slug));
    }

    private String token(UUID userId, Role role) {
        return jwtTokenProvider.generateAccessToken(userId, "user-" + userId + "@beautica.test", role);
    }

    private static String salonRegenerate(UUID salonId) {
        return "/api/v1/booking-links/salons/" + salonId + "/regenerate";
    }

    private static String salonRevoke(UUID salonId) {
        return "/api/v1/booking-links/salons/" + salonId;
    }

    private ResponseEntity<String> call(HttpMethod method, String url, String token, String currentSlug) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("currentSlug", currentSlug);
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode okData(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private JsonNode mine(String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.exchange(MINE, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return okData(response).path("links");
    }

    // ── MASTER ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("master regenerate → new slug ≠ old, stored, /mine shows it, tombstone (MASTER, old, REGENERATED, retired_by)")
    void should_issueNewSlugAndTombstoneOld_when_independentMasterRegenerates() throws Exception {
        UUID masterId = independentMasterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        UUID userId = userOfMaster(masterId);
        String token = token(userId, Role.INDEPENDENT_MASTER);

        JsonNode link = okData(call(HttpMethod.POST, MASTER_REGENERATE, token, "kalyna-kalyna-ab12"));

        String fresh = link.path("slug").asText();
        assertThat(fresh).isNotEqualTo("kalyna-kalyna-ab12").matches("^kalyna-kalyna-[a-z0-9]{4}$");
        assertThat(link.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(link.path("kind").asText()).isEqualTo("MASTER");
        assertThat(link.path("url").asText()).isEqualTo("https://beautica.app/book/" + fresh);
        assertThat(masterSlug(masterId)).isEqualTo(fresh);
        assertThat(mine(token).get(0).path("slug").asText()).isEqualTo(fresh);
        Map<String, Object> row = tombstone("MASTER", "kalyna-kalyna-ab12");
        assertThat(row.get("master_id")).isEqualTo(masterId);
        assertThat(row.get("salon_id")).isNull();
        assertThat(row.get("retired_by_user_id")).isEqualTo(userId);
        assertThat(row.get("reason")).isEqualTo("REGENERATED");
    }

    @Test
    @DisplayName("378 audit security MEDIUM (V205): the stored tombstone holds only SHA-256(kind:slug) — no slug column, no plaintext anywhere in the row; Java hash == V205 SQL backfill preimage")
    void should_storeNoPlaintextSlug_when_tombstoneWrittenInDb() throws Exception {
        UUID masterId = independentMasterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        String token = token(userOfMaster(masterId), Role.INDEPENDENT_MASTER);

        okData(call(HttpMethod.POST, MASTER_REGENERATE, token, "kalyna-kalyna-ab12"));

        assertThat(jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'retired_booking_slugs'",
                String.class)).doesNotContain("slug").contains("slug_hash");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retired_booking_slugs r WHERE r::text LIKE '%kalyna%'", Integer.class)).isZero();
        String sqlHash = jdbcTemplate.queryForObject(
                "SELECT encode(sha256(convert_to('MASTER' || ':' || 'kalyna-kalyna-ab12', 'UTF8')), 'hex')", String.class);
        assertThat(jdbcTemplate.queryForObject("SELECT slug_hash FROM retired_booking_slugs", String.class))
                .isEqualTo(sqlHash)
                .isEqualTo(RetiredSlugHasher.hash(BookingLinkKind.MASTER, "kalyna-kalyna-ab12"));
    }

    @Test
    @DisplayName("master revoke → REVOKED, slug/url null; /mine twice stays REVOKED (D4, no re-issue); revoke again → 200, still ONE tombstone (D8)")
    void should_revokeAndNeverReissue_when_independentMasterRevokes() throws Exception {
        UUID masterId = independentMasterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        String token = token(userOfMaster(masterId), Role.INDEPENDENT_MASTER);

        JsonNode revoked = okData(call(HttpMethod.DELETE, MASTER_REVOKE, token, "kalyna-kalyna-ab12"));

        assertThat(revoked.path("status").asText()).isEqualTo("REVOKED");
        assertThat(revoked.path("slug").isNull()).isTrue();
        assertThat(revoked.path("url").isNull()).isTrue();
        assertThat(revoked.path("bookable").asBoolean()).isFalse();
        for (int i = 0; i < 2; i++) {
            JsonNode links = mine(token);
            assertThat(links).hasSize(1);
            assertThat(links.get(0).path("status").asText()).isEqualTo("REVOKED");
            assertThat(links.get(0).path("slug").isNull()).isTrue();
        }
        assertThat(masterSlug(masterId)).isNull();
        assertThat(masterRevoked(masterId)).isTrue();

        JsonNode again = okData(call(HttpMethod.DELETE, MASTER_REVOKE, token, null));

        assertThat(again.path("status").asText()).isEqualTo("REVOKED");
        assertThat(tombstoneCount()).isEqualTo(1);
        assertThat(tombstone("MASTER", "kalyna-kalyna-ab12").get("reason")).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("regenerate from REVOKED (currentSlug null) → ACTIVE, flag cleared, the retired slug not reused")
    void should_reactivateWithFreshSlug_when_regeneratingRevokedLink() throws Exception {
        UUID masterId = independentMasterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        String token = token(userOfMaster(masterId), Role.INDEPENDENT_MASTER);
        okData(call(HttpMethod.DELETE, MASTER_REVOKE, token, "kalyna-kalyna-ab12"));

        JsonNode link = okData(call(HttpMethod.POST, MASTER_REGENERATE, token, null));

        assertThat(link.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(link.path("slug").asText()).isNotEqualTo("kalyna-kalyna-ab12");
        assertThat(masterRevoked(masterId)).isFalse();
        assertThat(masterSlug(masterId)).isEqualTo(link.path("slug").asText());
        assertThat(tombstoneCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("D7: stale currentSlug → 409, slug unchanged, no tombstone")
    void should_return409AndChangeNothing_when_currentSlugStale() {
        UUID masterId = independentMasterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        String token = token(userOfMaster(masterId), Role.INDEPENDENT_MASTER);

        ResponseEntity<String> regenerate = call(HttpMethod.POST, MASTER_REGENERATE, token, "kalyna-kalyna-zz99");
        ResponseEntity<String> revoke = call(HttpMethod.DELETE, MASTER_REVOKE, token, null);

        assertThat(regenerate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(revoke.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(masterSlug(masterId)).isEqualTo("kalyna-kalyna-ab12");
        assertThat(masterRevoked(masterId)).isFalse();
        assertThat(tombstoneCount()).isZero();
    }

    @Test
    @DisplayName("salon owner on /master/* (owner has no personal MASTER link) and salon master / client → 403, nothing written")
    void should_return403_when_nonIndependentMasterUsesMasterRoutes() {
        UUID salonId = salonWithSlug("Alfa", "alfa-ab12");
        UUID ownerId = ownerOf(salonId);
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, 'ostap-owner-ab12', NOW(), NOW())",
                UUID.randomUUID(), ownerId, salonId);
        UUID salonMasterUser = userOfMaster(BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salonId));
        UUID clientUser = insertUser(Role.CLIENT, null);

        for (String token : List.of(token(ownerId, Role.SALON_OWNER), token(salonMasterUser, Role.SALON_MASTER),
                token(clientUser, Role.CLIENT))) {
            assertThat(call(HttpMethod.POST, MASTER_REGENERATE, token, "ostap-owner-ab12").getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(HttpMethod.DELETE, MASTER_REVOKE, token, "ostap-owner-ab12").getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(tombstoneCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT booking_slug FROM masters WHERE user_id = ?", String.class, ownerId)).isEqualTo("ostap-owner-ab12");
    }

    // ── SALON (D6) ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("owner with TWO salons regenerates + revokes EACH; one link per salon; tombstones record the owner")
    void should_letOwnerManageEveryOwnedSalon_when_ownerHasTwoSalons() throws Exception {
        UUID alfa = salonWithSlug("Alfa", "alfa-ab12");
        UUID ownerId = ownerOf(alfa);
        UUID beta = siblingSalonWithSlug(ownerId, "Beta", "beta-cd34");
        String token = token(ownerId, Role.SALON_OWNER);

        JsonNode alfaLink = okData(call(HttpMethod.POST, salonRegenerate(alfa), token, "alfa-ab12"));
        JsonNode betaLink = okData(call(HttpMethod.POST, salonRegenerate(beta), token, "beta-cd34"));

        assertThat(alfaLink.path("slug").asText()).matches("^alfa-[a-z0-9]{4}$").isNotEqualTo("alfa-ab12");
        assertThat(alfaLink.path("url").asText()).isEqualTo("https://beautica.app/book/s/" + alfaLink.path("slug").asText());
        assertThat(alfaLink.path("salonId").asText()).isEqualTo(alfa.toString());
        assertThat(betaLink.path("slug").asText()).matches("^beta-[a-z0-9]{4}$").isNotEqualTo("beta-cd34");
        assertThat(salonSlug(alfa)).isEqualTo(alfaLink.path("slug").asText());
        assertThat(salonSlug(beta)).isEqualTo(betaLink.path("slug").asText());

        okData(call(HttpMethod.DELETE, salonRevoke(alfa), token, alfaLink.path("slug").asText()));
        okData(call(HttpMethod.DELETE, salonRevoke(beta), token, betaLink.path("slug").asText()));

        JsonNode links = mine(token);
        assertThat(links).hasSize(2);
        links.forEach(link -> {
            assertThat(link.path("kind").asText()).isEqualTo("SALON");
            assertThat(link.path("status").asText()).isEqualTo("REVOKED");
            assertThat(link.path("slug").isNull()).isTrue();
        });
        assertThat(salonRevoked(alfa)).isTrue();
        assertThat(salonRevoked(beta)).isTrue();
        assertThat(tombstoneCount()).isEqualTo(4);
        assertThat(tombstone("SALON", "alfa-ab12").get("retired_by_user_id")).isEqualTo(ownerId);
        assertThat(tombstone("SALON", "alfa-ab12").get("master_id")).isNull();
        assertThat(tombstone("SALON", "alfa-ab12").get("salon_id")).isEqualTo(alfa);
    }

    @Test
    @DisplayName("bound admin regenerates + revokes its salon; owner then sees REVOKED and /mine never re-issues it")
    void should_letBoundAdminManageItsSalon_when_adminBound() throws Exception {
        UUID salonId = salonWithSlug("Alfa", "alfa-ab12");
        UUID adminId = insertUser(Role.SALON_ADMIN, salonId);
        String adminToken = token(adminId, Role.SALON_ADMIN);

        JsonNode link = okData(call(HttpMethod.POST, salonRegenerate(salonId), adminToken, "alfa-ab12"));
        okData(call(HttpMethod.DELETE, salonRevoke(salonId), adminToken, link.path("slug").asText()));

        assertThat(tombstone("SALON", "alfa-ab12").get("retired_by_user_id")).isEqualTo(adminId);
        String ownerToken = token(ownerOf(salonId), Role.SALON_OWNER);
        for (int i = 0; i < 2; i++) {
            assertThat(mine(ownerToken).get(0).path("status").asText()).isEqualTo("REVOKED");
        }
        assertThat(salonSlug(salonId)).isNull();
    }

    @Test
    @DisplayName("D6 403 matrix: admin of ANOTHER salon, owner of ANOTHER salon, salon master, client, independent master, unknown salon — nothing written")
    void should_return403AndWriteNothing_when_callerNotOwnerOrBoundAdmin() {
        UUID salonId = salonWithSlug("Alfa", "alfa-ab12");
        UUID otherSalon = salonWithSlug("Beta", "beta-cd34");
        UUID foreignAdmin = insertUser(Role.SALON_ADMIN, otherSalon);
        UUID foreignOwner = ownerOf(otherSalon);
        UUID salonMasterUser = userOfMaster(BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salonId));
        UUID client = insertUser(Role.CLIENT, null);
        UUID indie = userOfMaster(independentMasterWithSlug("Kalyna", "kalyna-kalyna-ab12"));

        Map<String, String> callers = Map.of(
                "foreign admin", token(foreignAdmin, Role.SALON_ADMIN),
                "foreign owner", token(foreignOwner, Role.SALON_OWNER),
                "salon master", token(salonMasterUser, Role.SALON_MASTER),
                "client", token(client, Role.CLIENT),
                "independent master", token(indie, Role.INDEPENDENT_MASTER));
        callers.forEach((who, token) -> {
            assertThat(call(HttpMethod.POST, salonRegenerate(salonId), token, "alfa-ab12").getStatusCode())
                    .as(who + " regenerate").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(HttpMethod.DELETE, salonRevoke(salonId), token, "alfa-ab12").getStatusCode())
                    .as(who + " revoke").isEqualTo(HttpStatus.FORBIDDEN);
        });
        assertThat(call(HttpMethod.POST, salonRegenerate(UUID.randomUUID()), token(foreignOwner, Role.SALON_OWNER),
                "alfa-ab12").getStatusCode()).as("unknown salon").isEqualTo(HttpStatus.FORBIDDEN);
        // 378 audit (security INFO): an ADMIN token on an unknown salon is the same 403 — no existence oracle.
        UUID unknownSalon = UUID.randomUUID();
        assertThat(call(HttpMethod.POST, salonRegenerate(unknownSalon), token(foreignAdmin, Role.SALON_ADMIN),
                "alfa-ab12").getStatusCode()).as("admin, unknown salon, regenerate").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.DELETE, salonRevoke(unknownSalon), token(foreignAdmin, Role.SALON_ADMIN),
                "alfa-ab12").getStatusCode()).as("admin, unknown salon, revoke").isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(salonSlug(salonId)).isEqualTo("alfa-ab12");
        assertThat(salonRevoked(salonId)).isFalse();
        assertThat(tombstoneCount()).isZero();
    }

    @Test
    @DisplayName("own INACTIVE salon → 404")
    void should_return404_when_ownSalonInactive() {
        UUID salonId = salonWithSlug("Alfa", "alfa-ab12");
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);

        ResponseEntity<String> response = call(
                HttpMethod.POST, salonRegenerate(salonId), token(ownerOf(salonId), Role.SALON_OWNER), "alfa-ab12");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tombstoneCount()).isZero();
    }

    // ── kill-switch (D9) + rate limit (D10) ──────────────────────────────────────────────────

    @Test
    @DisplayName("D9: switch off → 404 on all four routes, zero writes")
    void should_return404AndWriteNothing_when_shareSwitchedOff() {
        UUID masterId = independentMasterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        UUID salonId = salonWithSlug("Alfa", "alfa-ab12");
        String masterToken = token(userOfMaster(masterId), Role.INDEPENDENT_MASTER);
        String ownerToken = token(ownerOf(salonId), Role.SALON_OWNER);
        bookingLinksProperties.setShareEnabled(false);

        assertThat(call(HttpMethod.POST, MASTER_REGENERATE, masterToken, "kalyna-kalyna-ab12").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, MASTER_REVOKE, masterToken, "kalyna-kalyna-ab12").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, salonRegenerate(salonId), ownerToken, "alfa-ab12").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, salonRevoke(salonId), ownerToken, "alfa-ab12").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(masterSlug(masterId)).isEqualTo("kalyna-kalyna-ab12");
        assertThat(salonSlug(salonId)).isEqualTo("alfa-ab12");
        assertThat(masterRevoked(masterId)).isFalse();
        assertThat(salonRevoked(salonId)).isFalse();
        assertThat(tombstoneCount()).isZero();
    }

    @Test
    @DisplayName("D10: 11th mutation in an hour → 429 with Retry-After, budget shared across MASTER and SALON routes")
    void should_return429_when_eleventhMutationInAnHour() {
        UUID masterId = independentMasterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        String token = token(userOfMaster(masterId), Role.INDEPENDENT_MASTER);
        UUID salonId = salonWithSlug("Alfa", "alfa-ab12");
        for (int i = 0; i < 5; i++) {
            assertThat(call(HttpMethod.POST, MASTER_REGENERATE, token, "kalyna-kalyna-zz99").getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(call(HttpMethod.DELETE, salonRevoke(salonId), token, "alfa-ab12").getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        ResponseEntity<String> eleventh = call(HttpMethod.DELETE, MASTER_REVOKE, token, "kalyna-kalyna-ab12");

        assertThat(eleventh.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(eleventh.getHeaders().getFirst("Retry-After")).isEqualTo("3600");
        assertThat(masterSlug(masterId)).isEqualTo("kalyna-kalyna-ab12");
    }

    // ── cache eviction (D13) ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D13: master page cached under the old slug → evicted after regenerate commits; a 409 (rollback) evicts nothing")
    void should_evictOldMasterPage_when_regenerateCommits() throws Exception {
        UUID masterId = independentMasterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        String token = token(userOfMaster(masterId), Role.INDEPENDENT_MASTER);
        assertThat(restTemplate.getForEntity("/api/v1/book/kalyna-kalyna-ab12/info", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var cache = cacheManager.getCache(MasterProfileCacheEvictor.BOOKING_SLUG_INFO_CACHE);
        assertThat(cache.get("kalyna-kalyna-ab12")).isNotNull();

        call(HttpMethod.POST, MASTER_REGENERATE, token, "kalyna-kalyna-zz99");          // 409 → rolled back
        assertThat(cache.get("kalyna-kalyna-ab12")).as("rollback must not evict").isNotNull();
        JsonNode link = okData(call(HttpMethod.POST, MASTER_REGENERATE, token, "kalyna-kalyna-ab12"));

        assertThat(cache.get("kalyna-kalyna-ab12")).isNull();
        assertThat(restTemplate.getForEntity("/api/v1/book/kalyna-kalyna-ab12/info", String.class).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
        assertThat(restTemplate.getForEntity("/api/v1/book/" + link.path("slug").asText() + "/info", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("D13: salon page cached under the old slug → evicted after salon regenerate; the old page 410s (phase 379)")
    void should_evictOldSalonPage_when_salonRegenerateCommits() throws Exception {
        UUID salonId = salonWithSlug("Alfa", "alfa-ab12");
        assertThat(restTemplate.getForEntity("/api/v1/book/s/alfa-ab12/info", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var cache = cacheManager.getCache(SalonBookingInfoCacheEvictor.CACHE);
        assertThat(cache.get("alfa-ab12")).isNotNull();

        JsonNode link = okData(call(
                HttpMethod.POST, salonRegenerate(salonId), token(ownerOf(salonId), Role.SALON_OWNER), "alfa-ab12"));

        assertThat(cache.get("alfa-ab12")).isNull();
        assertThat(restTemplate.getForEntity("/api/v1/book/s/alfa-ab12/info", String.class).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
        assertThat(restTemplate.getForEntity("/api/v1/book/s/" + link.path("slug").asText() + "/info", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── QA 378: user-stated rules end to end over HTTP (public page follows the stored slug) ─────

    private HttpStatus masterPage(String slug) {
        return HttpStatus.valueOf(restTemplate.getForEntity("/api/v1/book/" + slug + "/info", String.class)
                .getStatusCode().value());
    }

    private HttpStatus salonPage(String slug) {
        return HttpStatus.valueOf(restTemplate.getForEntity("/api/v1/book/s/" + slug + "/info", String.class)
                .getStatusCode().value());
    }

    @Test
    @DisplayName("QA 378: owner with TWO salons regenerates both → both old public salon pages 410 (phase 379), both new ones serve, /mine lists both new ACTIVE slugs")
    void should_serveOnlyNewSalonPages_when_ownerRegeneratesBothSalons() throws Exception {
        UUID alfa = salonWithSlug("Alfa", "alfa-ab12");
        UUID ownerId = ownerOf(alfa);
        UUID beta = siblingSalonWithSlug(ownerId, "Beta", "beta-cd34");
        String token = token(ownerId, Role.SALON_OWNER);
        assertThat(salonPage("alfa-ab12")).as("warm alfa page").isEqualTo(HttpStatus.OK);
        assertThat(salonPage("beta-cd34")).as("warm beta page").isEqualTo(HttpStatus.OK);

        String alfaNew = okData(call(HttpMethod.POST, salonRegenerate(alfa), token, "alfa-ab12")).path("slug").asText();
        String betaNew = okData(call(HttpMethod.POST, salonRegenerate(beta), token, "beta-cd34")).path("slug").asText();

        assertThat(salonPage("alfa-ab12")).as("old alfa page").isEqualTo(HttpStatus.GONE);
        assertThat(salonPage("beta-cd34")).as("old beta page").isEqualTo(HttpStatus.GONE);
        assertThat(salonPage(alfaNew)).as("new alfa page %s", alfaNew).isEqualTo(HttpStatus.OK);
        assertThat(salonPage(betaNew)).as("new beta page %s", betaNew).isEqualTo(HttpStatus.OK);
        JsonNode links = mine(token);
        assertThat(links).hasSize(2);
        Map<String, String> slugBySalon = new HashMap<>();
        links.forEach(link -> {
            assertThat(link.path("status").asText()).isEqualTo("ACTIVE");
            slugBySalon.put(link.path("salonId").asText(), link.path("slug").asText());
        });
        assertThat(slugBySalon).containsExactlyInAnyOrderEntriesOf(
                Map.of(alfa.toString(), alfaNew, beta.toString(), betaNew));
    }

    @Test
    @DisplayName("QA 378: admin bound to ONE of the owner's two salons → 403 on the sibling salon (both routes, untouched); its own salon regenerates and the old page stops serving")
    void should_limitBoundAdminToItsOwnSalon_when_ownerHasTwoSalons() throws Exception {
        UUID alfa = salonWithSlug("Alfa", "alfa-ab12");
        UUID ownerId = ownerOf(alfa);
        UUID beta = siblingSalonWithSlug(ownerId, "Beta", "beta-cd34");
        String adminToken = token(insertUser(Role.SALON_ADMIN, alfa), Role.SALON_ADMIN);

        ResponseEntity<String> siblingRegenerate = call(HttpMethod.POST, salonRegenerate(beta), adminToken, "beta-cd34");
        ResponseEntity<String> siblingRevoke = call(HttpMethod.DELETE, salonRevoke(beta), adminToken, "beta-cd34");
        JsonNode own = okData(call(HttpMethod.POST, salonRegenerate(alfa), adminToken, "alfa-ab12"));

        assertThat(siblingRegenerate.getStatusCode()).as("sibling regenerate").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(siblingRevoke.getStatusCode()).as("sibling revoke").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(salonSlug(beta)).isEqualTo("beta-cd34");
        assertThat(salonRevoked(beta)).isFalse();
        assertThat(salonPage("beta-cd34")).as("sibling page still serves").isEqualTo(HttpStatus.OK);
        String alfaNew = own.path("slug").asText();
        assertThat(own.path("salonId").asText()).isEqualTo(alfa.toString());
        assertThat(salonSlug(alfa)).isEqualTo(alfaNew);
        assertThat(salonPage("alfa-ab12")).as("old own page").isEqualTo(HttpStatus.GONE);
        assertThat(salonPage(alfaNew)).as("new own page").isEqualTo(HttpStatus.OK);
        assertThat(tombstoneCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("QA 378: salon revoke → public salon page gone, /mine REVOKED for owner AND bound admin (twice, never re-issued); revoke again (null) → 200, ONE tombstone")
    void should_takeSalonPageDownAndStayRevoked_when_salonRevoked() throws Exception {
        UUID salonId = salonWithSlug("Alfa", "alfa-ab12");
        String ownerToken = token(ownerOf(salonId), Role.SALON_OWNER);
        String adminToken = token(insertUser(Role.SALON_ADMIN, salonId), Role.SALON_ADMIN);
        assertThat(salonPage("alfa-ab12")).as("warm page").isEqualTo(HttpStatus.OK);

        JsonNode revoked = okData(call(HttpMethod.DELETE, salonRevoke(salonId), ownerToken, "alfa-ab12"));

        assertThat(revoked.path("status").asText()).isEqualTo("REVOKED");
        assertThat(revoked.path("slug").isNull()).isTrue();
        assertThat(revoked.path("url").isNull()).isTrue();
        assertThat(salonPage("alfa-ab12")).as("revoked page").isEqualTo(HttpStatus.GONE);
        for (int i = 0; i < 2; i++) {
            for (String token : List.of(ownerToken, adminToken)) {
                JsonNode links = mine(token);
                assertThat(links).hasSize(1);
                assertThat(links.get(0).path("status").asText()).isEqualTo("REVOKED");
                assertThat(links.get(0).path("slug").isNull()).isTrue();
            }
        }
        assertThat(salonSlug(salonId)).isNull();

        JsonNode again = okData(call(HttpMethod.DELETE, salonRevoke(salonId), adminToken, null));

        assertThat(again.path("status").asText()).isEqualTo("REVOKED");
        assertThat(tombstoneCount()).isEqualTo(1);
        assertThat(tombstone("SALON", "alfa-ab12").get("reason")).isEqualTo("REVOKED");
        assertThat(salonPage("alfa-ab12")).as("still gone").isEqualTo(HttpStatus.GONE);
    }

    @Test
    @DisplayName("QA 378: salon regenerate from REVOKED (currentSlug null) → ACTIVE, new public page serves, the revoked slug stays dead, /mine ACTIVE")
    void should_reactivateSalonWithFreshServingSlug_when_regeneratingRevokedSalonLink() throws Exception {
        UUID salonId = salonWithSlug("Alfa", "alfa-ab12");
        String ownerToken = token(ownerOf(salonId), Role.SALON_OWNER);
        okData(call(HttpMethod.DELETE, salonRevoke(salonId), ownerToken, "alfa-ab12"));

        JsonNode link = okData(call(HttpMethod.POST, salonRegenerate(salonId), ownerToken, null));

        String fresh = link.path("slug").asText();
        assertThat(link.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(fresh).matches("^alfa-[a-z0-9]{4}$").isNotEqualTo("alfa-ab12");
        assertThat(salonRevoked(salonId)).isFalse();
        assertThat(salonSlug(salonId)).isEqualTo(fresh);
        assertThat(salonPage(fresh)).as("new page").isEqualTo(HttpStatus.OK);
        assertThat(salonPage("alfa-ab12")).as("revoked slug").isEqualTo(HttpStatus.GONE);
        JsonNode mine = mine(ownerToken).get(0);
        assertThat(mine.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(mine.path("slug").asText()).isEqualTo(fresh);
        assertThat(tombstoneCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("QA 378: master revoke → public master page gone; regenerate from REVOKED → new master page serves, the revoked slug stays dead")
    void should_takeMasterPageDownThenServeNewSlug_when_revokedThenRegenerated() throws Exception {
        UUID masterId = independentMasterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        String token = token(userOfMaster(masterId), Role.INDEPENDENT_MASTER);
        assertThat(masterPage("kalyna-kalyna-ab12")).as("warm page").isEqualTo(HttpStatus.OK);

        okData(call(HttpMethod.DELETE, MASTER_REVOKE, token, "kalyna-kalyna-ab12"));
        HttpStatus afterRevoke = masterPage("kalyna-kalyna-ab12");
        String fresh = okData(call(HttpMethod.POST, MASTER_REGENERATE, token, null)).path("slug").asText();

        assertThat(afterRevoke).as("revoked page").isEqualTo(HttpStatus.GONE);
        assertThat(masterPage(fresh)).as("new page %s", fresh).isEqualTo(HttpStatus.OK);
        assertThat(masterPage("kalyna-kalyna-ab12")).as("revoked slug after regenerate").isEqualTo(HttpStatus.GONE);
        assertThat(mine(token).get(0).path("slug").asText()).isEqualTo(fresh);
    }

    @Test
    @DisplayName("QA 378: salon D7 over HTTP — stale currentSlug from owner (regenerate) and admin (revoke) → 409, salon slug + page unchanged, no tombstone")
    void should_return409AndChangeNothing_when_salonCurrentSlugStale() {
        UUID salonId = salonWithSlug("Alfa", "alfa-ab12");
        String ownerToken = token(ownerOf(salonId), Role.SALON_OWNER);
        String adminToken = token(insertUser(Role.SALON_ADMIN, salonId), Role.SALON_ADMIN);

        ResponseEntity<String> regenerate = call(HttpMethod.POST, salonRegenerate(salonId), ownerToken, "alfa-zz99");
        ResponseEntity<String> revoke = call(HttpMethod.DELETE, salonRevoke(salonId), adminToken, null);

        assertThat(regenerate.getStatusCode()).as("owner regenerate stale").isEqualTo(HttpStatus.CONFLICT);
        assertThat(revoke.getStatusCode()).as("admin revoke with null while live").isEqualTo(HttpStatus.CONFLICT);
        assertThat(salonSlug(salonId)).isEqualTo("alfa-ab12");
        assertThat(salonRevoked(salonId)).isFalse();
        assertThat(salonPage("alfa-ab12")).isEqualTo(HttpStatus.OK);
        assertThat(tombstoneCount()).isZero();
    }

    // ── D5 internal issue + V204 CHECK ───────────────────────────────────────────────────────

    @Test
    @DisplayName("D5: internal getOrCreateSlug on a REVOKED master issues a slug and clears the flag (never a retired one)")
    void should_issueAndClearFlag_when_internalIssueOnRevokedMaster() throws Exception {
        UUID masterId = independentMasterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        okData(call(HttpMethod.DELETE, MASTER_REVOKE,
                token(userOfMaster(masterId), Role.INDEPENDENT_MASTER), "kalyna-kalyna-ab12"));

        String issued = bookingSlugService.getOrCreateSlug(masterId);

        assertThat(issued).matches("^kalyna-kalyna-[a-z0-9]{4}$").isNotEqualTo("kalyna-kalyna-ab12");
        assertThat(masterSlug(masterId)).isEqualTo(issued);
        assertThat(masterRevoked(masterId)).isFalse();
    }

    @Test
    @DisplayName("V204 CHECK: a row with BOTH a live slug and a revoke flag is rejected (masters and salons)")
    void should_rejectSlugAndRevokeFlagTogether_when_writtenDirectly() {
        UUID masterId = independentMasterWithSlug("Kalyna", "kalyna-kalyna-ab12");
        UUID salonId = salonWithSlug("Alfa", "alfa-ab12");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE masters SET booking_link_revoked_at = now() WHERE id = ?", masterId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_masters_booking_link_revoked_no_slug");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE salons SET booking_link_revoked_at = now() WHERE id = ?", salonId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_salons_booking_link_revoked_no_slug");
    }
}
