package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The R8/(c) risk from the phase 301 plan: after an {@code INDEPENDENT_MASTER} self-deletes, are
 * they genuinely absent from every public discovery surface — search, the public profile route,
 * favourites — and does the public {@code booking_slug} LINK route (the one route whose backing
 * query the plan flagged as "unverified — treat as a real risk", R8) actually refuse a detached
 * master rather than silently continuing to serve their booking page?
 */
@DisplayName("Public surfaces after an INDEPENDENT_MASTER self-deletes (Phase 301 §3c, R8)")
class IndependentMasterPublicSurfaceAfterSelfDeleteIT extends AbstractIntegrationTest {

    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(3);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    @Test
    @DisplayName("GET /masters/{id} returns 404, not 500 or a stale profile, for a master DELETED "
            + "outright (no history)")
    void should_return404_when_masterWasDeletedOutright() throws Exception {
        String email = "im-surface-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        String token = fixtures.tokenFor(email);
        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/masters/" + masterId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /masters/{id} returns 404, not 500, for a master DETACHED (with history) — "
            + "requireAttached must 404 BEFORE any master.getUser() dereference")
    void should_return404_when_masterWasDetached() throws Exception {
        String email = "im-surface-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        UUID clientId = csd.createClient();
        csd.insertBooking(clientId, masterId, masterServiceId, null, "COMPLETED", PAST);
        String token = fixtures.tokenFor(email);
        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);
        // Fixture check — the detach branch was genuinely taken, not the delete branch.
        assertThat(csd.masterExists(masterId)).isTrue();

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/masters/" + masterId, String.class);

        assertThat(response.getStatusCode())
                .as("no NPE from dereferencing the now-null master.user — 404, not 500")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /search/masters no longer lists the departed master — absent on BOTH the "
            + "delete branch and the detach branch")
    void should_disappearFromSearch_afterSelfDelete() throws Exception {
        String email = "im-surface-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        String token = fixtures.tokenFor(email);

        // Fixture check — present BEFORE the delete.
        assertThat(searchResultContainsMasterId(masterId)).isTrue();

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);
        // search:masters:browse/:q/:total are short-TTL Caffeine caches (CacheConfig) — the
        // "before" call above cached the page this master appeared on. Evict explicitly so the
        // "after" call measures the real query, not a 60s-stale cache entry.
        clearSearchCaches();

        assertThat(searchResultContainsMasterId(masterId))
                .as("gone from search — the users join alone drops a role-less/absent account, and "
                        + "is_active=false drops a detached stub")
                .isFalse();
    }

    private void clearSearchCaches() {
        for (String name : java.util.List.of(
                "search:masters:browse", "search:masters:q", "search:masters:total")) {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    @Test
    @DisplayName("a client's favourite pointing at the departed master is silently filtered out of "
            + "GET /favorites/masters — no error, just absent (INNER JOIN masters, is_active=true)")
    void should_filterFavoriteOut_afterMasterSelfDeletes() throws Exception {
        String masterEmail = "im-surface-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        UUID clientId = csd.createClient();
        // give the master history so the detach (not delete) branch is exercised — the harder case,
        // since the row still physically exists behind the INNER JOIN.
        csd.insertBooking(clientId, masterId, masterServiceId, null, "COMPLETED", PAST);
        csd.insertFavorite(clientId, "MASTER", masterId);
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        String masterToken = fixtures.tokenFor(masterEmail);

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(masterToken)), Void.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/favorites/masters", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = objectMapper.readTree(response.getBody()).path("data").path("data");
        boolean stillListed = false;
        for (JsonNode row : content) {
            if (masterId.toString().equals(row.path("masterId").asText())) {
                stillListed = true;
            }
        }
        assertThat(stillListed)
                .as("the favourite ROW itself survives forever (R7, no FK) but must never render")
                .isFalse();
        // The orphaned favorite row itself is the R7 residual — still present in the DB, invisible
        // behind the INNER JOIN — recorded, not fixed here.
        assertThat(csd.count("SELECT COUNT(*) FROM favorites WHERE target_id = ?", masterId))
                .as("R7 — the orphan favourite row survives, invisible; not fixed in this phase")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("R8 — the public booking_slug LINK route refuses a DETACHED master: 404, not a "
            + "stale booking page for a provider who no longer has an account")
    void should_refuseBookingSlugRoute_forDetachedMaster() throws Exception {
        String email = "im-surface-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        UUID clientId = csd.createClient();
        csd.insertBooking(clientId, masterId, masterServiceId, null, "COMPLETED", PAST);
        String slug = "im-surface-slug-" + System.nanoTime();
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, masterId);
        String token = fixtures.tokenFor(email);

        // Fixture check — the slug resolves BEFORE the delete.
        ResponseEntity<String> before = restTemplate.getForEntity("/api/v1/book/" + slug + "/info", String.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);
        assertThat(csd.masterExists(masterId))
                .as("the detach branch, not the delete branch, is what R8 is actually about — the "
                        + "row still physically exists")
                .isTrue();
        // findBySlug is @Cacheable(sync = true) — the pre-delete fixture check above cached the
        // 200 OK response under this exact slug key. Evict it explicitly: without this, the "after"
        // call below would trivially return the STALE cached response regardless of whether the
        // query itself refuses a detached row, silently defanging the whole assertion.
        var slugCache = cacheManager.getCache("booking-slug-info");
        assertThat(slugCache).as("cache bean must exist for this eviction to mean anything").isNotNull();
        slugCache.evict(slug);

        ResponseEntity<String> after = restTemplate.getForEntity("/api/v1/book/" + slug + "/info", String.class);

        assertThat(after.getStatusCode())
                .as("R8 — findByBookingSlugWithUser's is_active=true predicate AND its INNER JOIN "
                        + "m.user must both refuse a detached row; either alone would already do it")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private boolean searchResultContainsMasterId(UUID masterId) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/search/masters?size=50", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = objectMapper.readTree(response.getBody()).path("data").path("data");
        for (JsonNode row : content) {
            if (masterId.toString().equals(row.path("masterId").asText())) {
                return true;
            }
        }
        return false;
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
