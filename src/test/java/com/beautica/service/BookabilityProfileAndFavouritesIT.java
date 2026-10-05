package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static com.beautica.service.BookabilityHttp.ids;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bookability on the client profile surfaces (locked product decision 2026-10-05):
 * <ul>
 *   <li>{@code GET /masters/{id}} and {@code GET /salons/{id}} still load for a non-bookable
 *       provider (a direct link must not 404) and carry {@code bookable} from the strict verdict;</li>
 *   <li>{@code GET /favorites/masters|salons} (also the home favourites rail) HIDE a non-bookable
 *       favourite, keeping the row so it reappears once the provider is configured again;</li>
 *   <li>{@code GET /masters/{id}/services} shows clients only bookable services, while the master
 *       themself and their salon owner see the full configured list.</li>
 * </ul>
 * Fixture: a salon created through the real {@code POST /salons} path (auto-enrolled owner-master
 * with a service but NO hours) plus one configured SALON_MASTER; an independent master registered
 * through the real API with a service but NO hours; one configured independent master.
 */
@DisplayName("Profiles, favourites and the master services tab respect bookability (full HTTP + real Postgres)")
class BookabilityProfileAndFavouritesIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    private ServiceTestFixtures fixtures;
    private BookabilityHttp http;
    private Fixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        http = new BookabilityHttp(restTemplate, objectMapper);
        fx = seed();
    }

    // ── detail flags ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /masters/{id}: configured masters bookable=true; unscheduled masters still load "
            + "with bookable=false (salon owner-master AND independent)")
    void should_exposeBookableFlag_when_masterDetailRead() throws Exception {
        assertThat(http.get("/api/v1/masters/" + fx.salonConfigured(), null).path("bookable").asBoolean()).isTrue();
        assertThat(http.get("/api/v1/masters/" + fx.indepConfigured(), null).path("bookable").asBoolean()).isTrue();

        JsonNode owner = http.get("/api/v1/masters/" + fx.ownerMaster(), null);
        JsonNode indep = http.get("/api/v1/masters/" + fx.indepUnscheduled(), null);

        assertThat(owner.path("masterId").asText()).isEqualTo(fx.ownerMaster().toString());
        assertThat(owner.path("bookable").isBoolean()).isTrue();
        assertThat(owner.path("bookable").asBoolean()).isFalse();
        assertThat(indep.path("bookable").isBoolean()).isTrue();
        assertThat(indep.path("bookable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("GET /salons/{id}: true with one configured master; false (profile still 200) once "
            + "that master loses their schedule")
    void should_exposeBookableFlag_when_salonDetailRead() throws Exception {
        assertThat(http.get("/api/v1/salons/" + fx.salonId(), null).path("bookable").asBoolean()).isTrue();

        BookableMasterSeeder.removeSchedule(jdbcTemplate, fx.salonConfigured());
        evictSalonVerdict();
        JsonNode salon = http.get("/api/v1/salons/" + fx.salonId(), null);

        assertThat(salon.path("id").asText()).isEqualTo(fx.salonId().toString());
        assertThat(salon.path("bookable").isBoolean()).isTrue();
        assertThat(salon.path("bookable").asBoolean()).isFalse();
    }

    // ── favourites (also the home favourites rail) ─────────────────────────────────────────

    @Test
    @DisplayName("favourited master + salon disappear when their schedule is removed and reappear "
            + "when it is restored; the favourite rows themselves are kept")
    void should_hideAndRestoreFavourites_when_scheduleRemovedAndRestored() throws Exception {
        String clientToken = fixtures.createClientAndGetToken("fav-bk-" + System.nanoTime() + "@beautica.test");
        http.addFavorite(clientToken, FavoriteTargetType.MASTER, fx.indepConfigured());
        http.addFavorite(clientToken, FavoriteTargetType.MASTER, fx.salonConfigured());
        http.addFavorite(clientToken, FavoriteTargetType.SALON, fx.salonId());
        assertThat(ids(http.get("/api/v1/favorites/masters", clientToken), "masterId"))
                .containsExactlyInAnyOrder(fx.indepConfigured().toString(), fx.salonConfigured().toString());
        assertThat(ids(http.get("/api/v1/favorites/salons", clientToken), "salonId"))
                .containsExactly(fx.salonId().toString());

        BookableMasterSeeder.removeSchedule(jdbcTemplate, fx.indepConfigured());
        BookableMasterSeeder.removeSchedule(jdbcTemplate, fx.salonConfigured());
        JsonNode hiddenMasters = http.get("/api/v1/favorites/masters", clientToken);
        JsonNode hiddenSalons = http.get("/api/v1/favorites/salons", clientToken);

        assertThat(ids(hiddenMasters, "masterId")).isEmpty();
        assertThat(hiddenMasters.path("totalElements").asLong()).isZero();
        assertThat(ids(hiddenSalons, "salonId")).isEmpty();
        assertThat(hiddenSalons.path("totalElements").asLong()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM favorites", Long.class)).isEqualTo(3L);

        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, fx.indepConfigured());
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, fx.salonConfigured());

        assertThat(ids(http.get("/api/v1/favorites/masters", clientToken), "masterId"))
                .containsExactlyInAnyOrder(fx.indepConfigured().toString(), fx.salonConfigured().toString());
        assertThat(ids(http.get("/api/v1/favorites/salons", clientToken), "salonId"))
                .containsExactly(fx.salonId().toString());
    }

    @Test
    @DisplayName("favourited master with a schedule but no active service is hidden too")
    void should_hideFavouriteMaster_when_noActiveService() throws Exception {
        String clientToken = fixtures.createClientAndGetToken("fav-svc-" + System.nanoTime() + "@beautica.test");
        http.addFavorite(clientToken, FavoriteTargetType.MASTER, fx.indepConfigured());

        jdbcTemplate.update("UPDATE master_services SET is_active = false WHERE master_id = ?", fx.indepConfigured());

        assertThat(ids(http.get("/api/v1/favorites/masters", clientToken), "masterId")).isEmpty();
    }

    // ── master services tab ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /masters/{id}/services: anonymous and CLIENT see no services of an unscheduled "
            + "master but see a configured master's; the master themself and the salon owner see all")
    void should_filterServicesTabForClients_when_masterIsUnscheduled() throws Exception {
        String clientToken = fixtures.createClientAndGetToken("tab-" + System.nanoTime() + "@beautica.test");

        assertThat(http.get("/api/v1/masters/" + fx.ownerMaster() + "/services", null)).isEmpty();
        assertThat(http.get("/api/v1/masters/" + fx.ownerMaster() + "/services", clientToken)).isEmpty();
        assertThat(http.get("/api/v1/masters/" + fx.indepUnscheduled() + "/services", clientToken)).isEmpty();
        assertThat(http.get("/api/v1/masters/" + fx.salonConfigured() + "/services", null)).hasSize(1);

        assertThat(http.get("/api/v1/masters/" + fx.ownerMaster() + "/services", fx.ownerToken())).hasSize(1);
        assertThat(http.get("/api/v1/masters/" + fx.indepUnscheduled() + "/services", fx.indepToken())).hasSize(1);
    }

    // ── services tab: other staff get the CLIENT view (audit 2026-10-05, finding 6) ─────────
    //
    // Target: the salon's auto-enrolled owner-master — one configured service, no hours, so the
    // management view lists 1 and the filtered client view lists 0. Only the master themself and
    // THEIR salon's owner/admin may see the unfiltered list (should_filterServicesTabForClients…
    // pins those two); every other authenticated role must get the filtered list.

    @Test
    @DisplayName("GET /masters/{id}/services: an ADMIN of ANOTHER salon gets the filtered (empty) list")
    void should_filterServicesTab_when_callerIsAdminOfAnotherSalon() throws Exception {
        UUID otherSalon = fixtures.createSalon(
                fixtures.createSalonOwnerAndGetToken("owner-x-" + System.nanoTime() + "@beautica.test"), "Other Salon");
        String adminToken = fixtures.createSalonAdminAndGetToken(otherSalon, "admin-x-" + System.nanoTime() + "@beautica.test");

        assertFilteredFor(adminToken);
    }

    @Test
    @DisplayName("GET /masters/{id}/services: an OWNER of ANOTHER salon gets the filtered (empty) list")
    void should_filterServicesTab_when_callerIsOwnerOfAnotherSalon() throws Exception {
        String otherOwnerToken = fixtures.createSalonOwnerAndGetToken("owner-y-" + System.nanoTime() + "@beautica.test");
        fixtures.createSalon(otherOwnerToken, "Another Salon");

        assertFilteredFor(otherOwnerToken);
    }

    @Test
    @DisplayName("GET /masters/{id}/services: a PEER SALON_MASTER of the SAME salon gets the filtered (empty) list")
    void should_filterServicesTab_when_callerIsPeerSalonMaster() throws Exception {
        ServiceTestFixtures.SalonMasterFixture peer = fixtures.createSalonMasterWithRowAndGetToken(
                fx.salonId(), "peer-" + System.nanoTime() + "@beautica.test");

        assertFilteredFor(peer.token());
    }

    @Test
    @DisplayName("GET /masters/{id}/services: an UNRELATED INDEPENDENT_MASTER gets the filtered (empty) list")
    void should_filterServicesTab_when_callerIsUnrelatedIndependentMaster() throws Exception {
        assertFilteredFor(fx.indepToken());
    }

    /** Non-vacuous: the owner still sees the configured row, the caller under test sees none. */
    private void assertFilteredFor(String callerToken) throws Exception {
        String url = "/api/v1/masters/" + fx.ownerMaster() + "/services";
        assertThat(http.get(url, fx.ownerToken())).as("management view (salon owner)").hasSize(1);

        assertThat(http.get(url, callerToken)).as("caller must get the client (filtered) view").isEmpty();
    }

    // ── fixture ─────────────────────────────────────────────────────────────────────────────

    private Fixture seed() throws Exception {
        String ownerEmail = "owner-bk-" + System.nanoTime() + "@beautica.test";
        String ownerToken = fixtures.createSalonOwnerAndGetToken(ownerEmail);
        UUID salonId = fixtures.createSalon(ownerToken, "Bookability Salon");
        UUID ownerMaster = fixtures.resolveMasterIdForUserEmail(ownerEmail);
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salonId, ownerMaster);
        UUID salonConfigured = fixtures.createSalonMaster(salonId);
        BookableMasterSeeder.makeBookable(jdbcTemplate, salonId, salonConfigured);

        String indepEmail = "indep-bk-" + System.nanoTime() + "@beautica.test";
        String indepToken = fixtures.createIndependentMasterAndGetToken(indepEmail);
        UUID indepUnscheduled = fixtures.resolveMasterIdForUserEmail(indepEmail);
        BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, indepUnscheduled);

        String configuredEmail = "indep-ok-" + System.nanoTime() + "@beautica.test";
        fixtures.createIndependentMasterAndGetToken(configuredEmail);
        UUID indepConfigured = fixtures.resolveMasterIdForUserEmail(configuredEmail);
        BookableMasterSeeder.makeIndependentBookable(jdbcTemplate, indepConfigured);

        return new Fixture(salonId, ownerToken, ownerMaster, salonConfigured,
                indepUnscheduled, indepToken, indepConfigured);
    }

    /** The salon verdict is cached 60 s; the JDBC schedule delete bypasses the write-path eviction. */
    private void evictSalonVerdict() {
        Cache cache = cacheManager.getCache("salon-bookable-masters");
        assertThat(cache).isNotNull();
        cache.evict(fx.salonId());
    }

    private record Fixture(UUID salonId, String ownerToken, UUID ownerMaster, UUID salonConfigured,
                           UUID indepUnscheduled, String indepToken, UUID indepConfigured) {
    }
}
