package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.SalonBookingSlugInfoResponse;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.service.SalonBookingSlugService;
import com.beautica.common.TimeZones;
import com.beautica.common.cache.SalonBookingInfoCacheEvictor;
import com.beautica.config.CacheConfig;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.dto.MasterProfileUpdateRequest;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.UserService;
import com.beautica.support.BookableMasterSeeder;
import com.beautica.support.ThreadJdbcFootprint;
import com.beautica.support.ThreadJdbcFootprint.Footprint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 377 — public {@code GET /api/v1/book/s/{slug}/info} end to end (real Postgres, real security chain,
 * anonymous caller): only bookable masters listed, {@code bookable=false} instead of 404 for a salon with
 * none, 404 for unknown/inactive slugs, the slug lookup cached ({@link ThreadJdbcFootprint}: zero DB work
 * on a hit) and evicted after commit by the real write paths — salon rename/deactivation, a master's
 * schedule, service unassign and master deactivation.
 *
 * <p>Salons are created through {@code POST /salons} (slug issued at creation, phase 376); salon masters
 * are inserted with a stored slug (as every master since 13.1 is), except where a test needs a LEGACY row
 * ({@code booking_slug NULL}) — which the page omits and never writes (phase 377 audit, supersedes D4's
 * lazy issue on this path).
 */
@Import(ThreadJdbcFootprint.Config.class)
@DisplayName("GET /book/s/{slug}/info — bookable masters only, 404/bookable=false, cached + evicted after commit (Phase 377)")
class PublicSalonBookingInfoIT extends AbstractIntegrationTest {

    private static final String SLUG_CHECK = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CacheManager cacheManager;
    @Autowired private SalonBookingSlugService salonBookingSlugService;
    @Autowired private SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    @Autowired private UserService userService;
    @Autowired private TransactionTemplate transactionTemplate;

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private record OwnedSalon(String ownerToken, UUID salonId, String slug) {
    }

    private OwnedSalon ownedSalon(String name) throws Exception {
        String token = fixtures.createSalonOwnerAndGetToken("owner-377-" + UUID.randomUUID() + "@beautica.test");
        UUID salonId = fixtures.createSalon(token, name);
        return new OwnedSalon(token, salonId, salonSlugOf(salonId));
    }

    /** A salon master with a stored slug — the steady state for every master created since phase 13.1. */
    private UUID namedSalonMaster(UUID salonId, String firstName, String lastName) {
        UUID masterId = legacySalonMaster(salonId, firstName, lastName);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?",
                "m-" + masterId.toString().substring(0, 8) + "-s377", masterId);
        return masterId;
    }

    /** A pre-V86 LEGACY salon master: {@code booking_slug NULL}. */
    private UUID legacySalonMaster(UUID salonId, String firstName, String lastName) {
        UUID masterId = fixtures.createSalonMaster(salonId);
        jdbcTemplate.update(
                "UPDATE users SET first_name = ?, last_name = ?, professional_title = 'Стиліст', "
                        + "avatar_url = 'https://cdn.beautica.test/a.jpg' WHERE id = ?",
                firstName, lastName, userIdOf(masterId));
        return masterId;
    }

    private String salonSlugOf(UUID salonId) {
        return jdbcTemplate.queryForObject("SELECT booking_slug FROM salons WHERE id = ?", String.class, salonId);
    }

    private String masterSlugOf(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT booking_slug FROM masters WHERE id = ?", String.class, masterId);
    }

    private UUID userIdOf(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private ResponseEntity<String> anonymousGet(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
    }

    private JsonNode page(String slug) throws Exception {
        ResponseEntity<String> response = anonymousGet("/api/v1/book/s/" + slug + "/info");
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private static List<String> names(JsonNode masters) {
        List<String> names = new ArrayList<>();
        masters.forEach(master -> names.add(master.path("name").asText()));
        return names;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    private Cache pageCache() {
        return cacheManager.getCache(SalonBookingInfoCacheEvictor.CACHE);
    }

    private static LocalDate kyivToday() {
        return LocalDate.now(TimeZones.KYIV);
    }

    // ── listing (D3/D4) ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("2 bookable + 1 service-only + 1 inactive bookable master → only the 2 bookable listed, bookable=true, salon fields")
    void should_listOnlyBookableMasters_when_salonHasMixedMasters() throws Exception {
        OwnedSalon salon = ownedSalon("Mix Salon");
        UUID anna = namedSalonMaster(salon.salonId(), "Anna", "Bila");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), anna);
        UUID olha = namedSalonMaster(salon.salonId(), "Olha", "Chorna");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), olha);
        UUID serviceOnly = namedSalonMaster(salon.salonId(), "Bez", "Hrafika");
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon.salonId(), serviceOnly);
        UUID inactive = namedSalonMaster(salon.salonId(), "Ne", "Aktyvna");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), inactive);
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", inactive);

        JsonNode page = page(salon.slug());

        assertThat(page.path("salonId").asText()).isEqualTo(salon.salonId().toString());
        assertThat(page.path("name").asText()).isEqualTo("Mix Salon");
        assertThat(page.path("city").asText()).isEqualTo("Вінниця");
        assertThat(page.path("avgRating").isNull()).as("no reviews → null, never 0").isTrue();
        assertThat(page.path("reviewCount").asInt()).isZero();
        assertThat(page.path("bookable").asBoolean()).isTrue();
        assertThat(names(page.path("masters"))).containsExactlyInAnyOrder("Anna Bila", "Olha Chorna");
        JsonNode first = page.path("masters").get(0);
        assertThat(first.path("professionalTitle").asText()).isEqualTo("Стиліст");
        assertThat(first.path("avatarUrl").asText()).isEqualTo("https://cdn.beautica.test/a.jpg");
    }

    @Test
    @DisplayName("legacy master (booking_slug NULL) is omitted, never issued a slug, and the GET does zero writes / transactions")
    void should_omitLegacyMasterWithoutWriting_when_masterHasNoSlug() throws Exception {
        OwnedSalon salon = ownedSalon("Slug Salon");
        UUID legacy = legacySalonMaster(salon.salonId(), "Iryna", "Lehasi");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), legacy);
        UUID stored = namedSalonMaster(salon.salonId(), "Maria", "Stara");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), stored);
        jdbcTemplate.update("UPDATE masters SET booking_slug = 'maria-stara-zz11' WHERE id = ?", stored);

        ThreadJdbcFootprint.Measured<SalonBookingSlugInfoResponse> measured =
                ThreadJdbcFootprint.measure(() -> salonBookingSlugService.findBySlug(salon.slug()));

        assertThat(measured.footprint().transactions()).as("anonymous read never writes — %s", measured.footprint())
                .isZero();
        assertThat(masterSlugOf(legacy)).as("no slug issued by the public page").isNull();
        assertThat(masterSlugOf(stored)).as("stored slug never rewritten").isEqualTo("maria-stara-zz11");
        assertThat(measured.result().masters()).extracting("masterSlug").containsExactly("maria-stara-zz11");
        assertThat(anonymousGet("/api/v1/book/maria-stara-zz11/info").getStatusCode())
                .as("the listed masterSlug opens the master booking flow").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("only slug-less bookable masters → 200, bookable=false (omission never 404s the page)")
    void should_returnNotBookable_when_onlyLegacyMastersAreBookable() throws Exception {
        OwnedSalon salon = ownedSalon("Legacy Only Salon");
        UUID legacy = legacySalonMaster(salon.salonId(), "Tilky", "Lehasi");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), legacy);

        JsonNode page = page(salon.slug());

        assertThat(page.path("bookable").asBoolean()).isFalse();
        assertThat(page.path("masters")).isEmpty();
        assertThat(masterSlugOf(legacy)).isNull();
    }

    @Test
    @DisplayName("active salon with no bookable master → 200, bookable=false, masters: [] (not 404)")
    void should_returnNotBookable_when_noMasterBookable() throws Exception {
        OwnedSalon salon = ownedSalon("Empty Salon");
        UUID master = namedSalonMaster(salon.salonId(), "Tilky", "Posluha");
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon.salonId(), master);

        JsonNode page = page(salon.slug());

        assertThat(page.path("bookable").asBoolean()).isFalse();
        assertThat(page.path("masters").isArray()).isTrue();
        assertThat(page.path("masters")).isEmpty();
        assertThat(page.path("name").asText()).isEqualTo("Empty Salon");
    }

    // ── 404 / 400 (D2/D6) ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("inactive salon → 404")
    void should_return404_when_salonInactive() throws Exception {
        OwnedSalon salon = ownedSalon("Closed Salon");
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salon.salonId());

        ResponseEntity<String> response = anonymousGet("/api/v1/book/s/" + salon.slug() + "/info");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("unknown slug → 404; a MASTER slug is not a salon slug (separate namespace) → 404")
    void should_return404_when_slugUnknownOrMasterSlug() {
        UUID independent = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Okrema");
        jdbcTemplate.update("UPDATE masters SET booking_slug = 'okrema-okrema-x9y8' WHERE id = ?", independent);

        ResponseEntity<String> unknown = anonymousGet("/api/v1/book/s/no-such-salon-0000/info");
        ResponseEntity<String> masterSlug = anonymousGet("/api/v1/book/s/okrema-okrema-x9y8/info");

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(masterSlug.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("malformed slug → 400 before any lookup; /book/s/info (slug 's') stays on the master route → 400")
    void should_return400_when_slugMalformed() {
        assertThat(anonymousGet("/api/v1/book/s/Bad_Slug!/info").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(anonymousGet("/api/v1/book/s/info").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── public surface (§I) ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("anonymous 200 carries exactly the D7 fields — no owner id/email/phone, no master/user ids")
    void should_exposeOnlyPublicFields_when_anonymous() throws Exception {
        OwnedSalon salon = ownedSalon("Public Salon");
        jdbcTemplate.update("UPDATE salons SET phone = '+380501112233' WHERE id = ?", salon.salonId());
        UUID master = namedSalonMaster(salon.salonId(), "Publichna", "Maisterka");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), master);
        String ownerEmail = jdbcTemplate.queryForObject(
                "SELECT u.email FROM users u JOIN salons s ON s.owner_id = u.id WHERE s.id = ?",
                String.class, salon.salonId());

        ResponseEntity<String> response = anonymousGet("/api/v1/book/s/" + salon.slug() + "/info");
        JsonNode page = objectMapper.readTree(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fieldNames(page)).containsExactlyInAnyOrder(
                "salonId", "name", "city", "avatarUrl", "coverImageUrl", "avgRating", "reviewCount",
                "bookable", "masters");
        assertThat(fieldNames(page.path("masters").get(0)))
                .containsExactlyInAnyOrder("masterSlug", "name", "avatarUrl", "professionalTitle");
        assertThat(response.getBody())
                .doesNotContain(ownerEmail)
                .doesNotContain("+380501112233")
                .doesNotContain(master.toString())
                .doesNotContain(userIdOf(master).toString());
    }

    // ── cache (D5) ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("second lookup of a slug is a cache hit: zero connections and statements (the probe sees the miss's work)")
    void should_touchNoDatabase_when_slugCached() throws Exception {
        OwnedSalon salon = ownedSalon("Cached Salon");
        UUID master = namedSalonMaster(salon.salonId(), "Keshova", "Maisterka");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), master);
        jdbcTemplate.update("UPDATE masters SET booking_slug = 'keshova-m-c4c4' WHERE id = ?", master);

        Footprint miss = ThreadJdbcFootprint.footprintOf(() -> salonBookingSlugService.findBySlug(salon.slug()));
        ThreadJdbcFootprint.Measured<SalonBookingSlugInfoResponse> hit =
                ThreadJdbcFootprint.measure(() -> salonBookingSlugService.findBySlug(salon.slug()));

        assertThat(miss.statements()).as("miss: salon row + ONE master batch — %s", miss).isEqualTo(2);
        assertThat(miss.transactions()).as("not transactional — %s", miss).isZero();
        assertThat(hit.footprint().connections()).as("%s", hit.footprint()).isZero();
        assertThat(hit.footprint().statements()).as("%s", hit.footprint()).isZero();
        assertThat(hit.result().masters()).hasSize(1);
    }

    @Test
    @DisplayName("statements on a miss are constant in the number of bookable masters (one batch, never per master)")
    void should_useConstantStatements_when_salonHasManyBookableMasters() throws Exception {
        OwnedSalon one = ownedSalon("One Master Salon");
        UUID solo = namedSalonMaster(one.salonId(), "Odna", "Maisterka");
        BookableMasterSeeder.makeBookable(jdbcTemplate, one.salonId(), solo);
        jdbcTemplate.update("UPDATE masters SET booking_slug = 'odna-m-o1o1' WHERE id = ?", solo);
        OwnedSalon three = ownedSalon("Three Master Salon");
        for (int i = 0; i < 3; i++) {
            UUID master = namedSalonMaster(three.salonId(), "Tr" + i, "Maisterka");
            BookableMasterSeeder.makeBookable(jdbcTemplate, three.salonId(), master);
            jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", "tr" + i + "-m-t3t3", master);
        }

        Footprint oneFootprint = ThreadJdbcFootprint.footprintOf(() -> salonBookingSlugService.findBySlug(one.slug()));
        ThreadJdbcFootprint.Measured<SalonBookingSlugInfoResponse> threeMeasured =
                ThreadJdbcFootprint.measure(() -> salonBookingSlugService.findBySlug(three.slug()));

        assertThat(threeMeasured.result().masters()).hasSize(3);
        assertThat(threeMeasured.footprint().statements())
                .as("one=%s three=%s", oneFootprint, threeMeasured.footprint())
                .isEqualTo(oneFootprint.statements());
    }

    // ── eviction after commit, through the real write paths (D5) ─────────────────────────────

    @Test
    @DisplayName("salon master gains working hours (owner PUT override) → cached bookable=false page evicted, master now listed")
    void should_listMaster_when_masterGainsSchedule() throws Exception {
        OwnedSalon salon = ownedSalon("Schedule Salon");
        UUID master = namedSalonMaster(salon.salonId(), "Novyi", "Hrafik");
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon.salonId(), master);
        assertThat(page(salon.slug()).path("bookable").asBoolean()).isFalse();
        assertThat(pageCache().get(salon.slug())).as("arrange: the page is cached").isNotNull();
        LocalDate day = kyivToday().plusDays(2);

        ResponseEntity<String> put = restTemplate.exchange(
                "/api/v1/masters/" + master + "/overrides/" + day, HttpMethod.PUT,
                new HttpEntity<>(new ScheduleOverrideRequest(day, ScheduleExceptionKind.CUSTOM_HOURS,
                        List.of(new WorkIntervalDto(LocalTime.of(10, 0), LocalTime.of(12, 0)))),
                        fixtures.bearerHeaders(salon.ownerToken())),
                String.class);
        assertThat(put.getStatusCode()).as(put.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(pageCache().get(salon.slug())).as("evicted after commit, not by TTL").isNull();
        JsonNode after = page(salon.slug());
        assertThat(after.path("bookable").asBoolean()).isTrue();
        assertThat(names(after.path("masters"))).containsExactly("Novyi Hrafik");
    }

    @Test
    @DisplayName("owner unassigns the master's only service → page evicted, master gone, bookable=false")
    void should_dropMaster_when_serviceUnassigned() throws Exception {
        OwnedSalon salon = ownedSalon("Unassign Salon");
        UUID master = namedSalonMaster(salon.salonId(), "Bez", "Posluhy");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master);
        UUID defId = BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon.salonId(), master);
        assertThat(names(page(salon.slug()).path("masters"))).containsExactly("Bez Posluhy");

        ResponseEntity<String> delete = restTemplate.exchange(
                "/api/v1/salons/" + salon.salonId() + "/masters/" + master + "/services/" + defId,
                HttpMethod.DELETE, new HttpEntity<>(fixtures.bearerHeaders(salon.ownerToken())), String.class);
        assertThat(delete.getStatusCode()).as(delete.getBody()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(pageCache().get(salon.slug())).isNull();
        JsonNode after = page(salon.slug());
        assertThat(after.path("bookable").asBoolean()).isFalse();
        assertThat(after.path("masters")).isEmpty();
    }

    @Test
    @DisplayName("owner deactivates a bookable master → page evicted, master gone")
    void should_dropMaster_when_masterDeactivated() throws Exception {
        OwnedSalon salon = ownedSalon("Deactivate Master Salon");
        UUID staying = namedSalonMaster(salon.salonId(), "Zalyshaietsia", "Tut");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), staying);
        UUID leaving = namedSalonMaster(salon.salonId(), "Ide", "Het");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), leaving);
        assertThat(names(page(salon.slug()).path("masters"))).hasSize(2);

        ResponseEntity<String> delete = restTemplate.exchange("/api/v1/masters/" + leaving, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(salon.ownerToken())), String.class);
        assertThat(delete.getStatusCode().is2xxSuccessful()).as(delete.getBody()).isTrue();

        assertThat(pageCache().get(salon.slug())).isNull();
        assertThat(names(page(salon.slug()).path("masters"))).containsExactly("Zalyshaietsia Tut");
    }

    @Test
    @DisplayName("owner renames the salon → page evicted, new name served")
    void should_serveNewName_when_salonRenamed() throws Exception {
        OwnedSalon salon = ownedSalon("Stara Nazva");
        assertThat(page(salon.slug()).path("name").asText()).isEqualTo("Stara Nazva");

        ResponseEntity<String> patch = restTemplate.exchange("/api/v1/salons/" + salon.salonId(), HttpMethod.PATCH,
                new HttpEntity<>("{\"name\":\"Nova Nazva\",\"street\":\"вул. Хрещатик\",\"buildingNo\":\"1\"}",
                        fixtures.bearerHeaders(salon.ownerToken())),
                String.class);
        assertThat(patch.getStatusCode()).as(patch.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(pageCache().get(salon.slug())).isNull();
        assertThat(page(salon.slug()).path("name").asText()).isEqualTo("Nova Nazva");
        assertThat(salonSlugOf(salon.salonId())).as("slug permanent across rename").isEqualTo(salon.slug());
    }

    @Test
    @DisplayName("owner deactivates (deletes) the salon → cached page evicted, slug now 404")
    void should_return404_when_salonDeactivatedAfterCaching() throws Exception {
        OwnedSalon salon = ownedSalon("Zakryiemo Salon");
        UUID master = namedSalonMaster(salon.salonId(), "Ostannia", "Zmina");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), master);
        assertThat(page(salon.slug()).path("bookable").asBoolean()).isTrue();

        ResponseEntity<String> delete = restTemplate.exchange("/api/v1/salons/" + salon.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(salon.ownerToken())), String.class);
        assertThat(delete.getStatusCode()).as(delete.getBody()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(pageCache().get(salon.slug())).isNull();
        assertThat(anonymousGet("/api/v1/book/s/" + salon.slug() + "/info").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("master renames itself → both its own page and the salon page evicted after commit, new name served")
    void should_serveNewName_when_masterRenamed() throws Exception {
        OwnedSalon salon = ownedSalon("Rename Master Salon");
        UUID master = namedSalonMaster(salon.salonId(), "Stare", "Imia");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), master);
        String masterSlug = masterSlugOf(master);
        assertThat(names(page(salon.slug()).path("masters"))).containsExactly("Stare Imia");
        ResponseEntity<String> masterPageBefore = anonymousGet("/api/v1/book/" + masterSlug + "/info");
        assertThat(masterPageBefore.getBody()).contains("Stare");
        assertThat(cacheManager.getCache("booking-slug-info").get(masterSlug)).as("arrange: master page cached").isNotNull();
        assertThat(pageCache().get(salon.slug())).as("arrange: salon page cached").isNotNull();

        transactionTemplate.executeWithoutResult(status -> userService.updateMasterProfile(
                userIdOf(master), new MasterProfileUpdateRequest("Nove", null, null, null, null, null)));

        assertThat(cacheManager.getCache("booking-slug-info").get(masterSlug)).as("master page evicted").isNull();
        assertThat(pageCache().get(salon.slug())).as("salon page evicted").isNull();
        assertThat(names(page(salon.slug()).path("masters"))).containsExactly("Nove Imia");
        assertThat(anonymousGet("/api/v1/book/" + masterSlug + "/info").getBody())
                .contains("Nove").doesNotContain("Stare");
    }

    @Test
    @DisplayName("booking writers' catalogue-only evict leaves the salon page cached; bookability writers' full evict drops it")
    void should_keepSalonPageCached_when_bookingWriteEvictsCatalog() throws Exception {
        OwnedSalon salon = ownedSalon("Booking Write Salon");
        UUID master = namedSalonMaster(salon.salonId(), "Zaniata", "Maisterka");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), master);
        page(salon.slug());
        assertThat(pageCache().get(salon.slug())).as("arrange: the page is cached").isNotNull();

        salonCatalogCacheEvictor.evictCatalogOnly(salon.salonId());

        assertThat(pageCache().get(salon.slug())).as("a booking write must not evict salon-booking-slug-info")
                .isNotNull();
        salonCatalogCacheEvictor.evict(salon.salonId());
        assertThat(pageCache().get(salon.slug())).as("non-vacuity: the full evict does drop it").isNull();
    }

    @Test
    @DisplayName("real client booking create + cancel over HTTP → salon page stays cached (same entry), "
            + "while the catalogue entry IS evicted after commit (the hook ran)")
    void should_keepSalonPageCached_when_clientBooksAndCancelsOverHttp() throws Exception {
        OwnedSalon salon = ownedSalon("Real Booking Salon");
        UUID master = namedSalonMaster(salon.salonId(), "Realna", "Maisterka");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), master);
        UUID assignmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? AND is_active = true", UUID.class, master);
        page(salon.slug());
        Object cachedBefore = pageCache().get(salon.slug()).get();
        Cache catalogue = cacheManager.getCache("salon-service-catalog");
        catalogue.put(salon.salonId(), "sentinel-before-booking");
        String clientToken = fixtures.createClientAndGetToken("client-377-" + UUID.randomUUID() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.of(kyivToday().plusDays(2), LocalTime.of(10, 0), TimeZones.KYIV);

        ResponseEntity<String> booked = restTemplate.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(new CreateBookingRequest(master, assignmentId, startsAt, null, null, false),
                        fixtures.bearerHeaders(clientToken)),
                String.class);

        assertThat(booked.getStatusCode()).as(booked.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(catalogue.get(salon.salonId()))
                .as("non-vacuity: the booking's after-commit catalogue eviction really ran").isNull();
        assertThat(pageCache().get(salon.slug()))
                .as("a booking never changes the bookable-master list — the salon page must stay cached")
                .isNotNull();
        assertThat(pageCache().get(salon.slug()).get()).as("same cached instance, not a re-load")
                .isSameAs(cachedBefore);

        UUID bookingId = UUID.fromString(objectMapper.readTree(booked.getBody()).path("data").path("id").asText());
        catalogue.put(salon.salonId(), "sentinel-before-cancel");
        ResponseEntity<String> cancelled = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId + "/cancel", HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null)),
                        fixtures.bearerHeaders(clientToken)),
                String.class);

        assertThat(cancelled.getStatusCode()).as(cancelled.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(catalogue.get(salon.salonId()))
                .as("non-vacuity: the cancel's after-commit catalogue eviction really ran").isNull();
        assertThat(pageCache().get(salon.slug()).get())
                .as("a cancel must not evict salon-booking-slug-info either").isSameAs(cachedBefore);
        assertThat(names(page(salon.slug()).path("masters"))).containsExactly("Realna Maisterka");
    }

    @Test
    @DisplayName("salon-booking-slug-info expires after 30 s — the bound on a page lagging a write the value scan missed")
    void should_expireAfterThirtySeconds_when_salonPageCacheIsLive() {
        Cache springCache = pageCache();

        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                ((CaffeineCache) springCache).getNativeCache();

        assertThat(nativeCache.policy().expireAfterWrite())
                .isPresent()
                .get()
                .extracting(p -> p.getExpiresAfter(TimeUnit.SECONDS))
                .isEqualTo(CacheConfig.SALON_BOOKING_SLUG_INFO_TTL_SECONDS)
                .isEqualTo(30L);
    }
}
