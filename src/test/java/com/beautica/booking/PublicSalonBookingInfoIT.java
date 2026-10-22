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
import java.util.concurrent.ThreadLocalRandom;
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

    /** Salon row + masters batch (377) + portfolio, services (+bookability batch), summary, newest reviews (384). */
    private static final int MISS_STATEMENT_BUDGET = 12;
    private static final String SLUG_CHECK = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CacheManager cacheManager;
    @Autowired private SalonBookingSlugService salonBookingSlugService;
    @Autowired private SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    @Autowired private UserService userService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @org.springframework.boot.test.mock.mockito.MockBean private com.beautica.media.service.R2StorageService r2;

    private ServiceTestFixtures fixtures;
    private PublicBookingPageFixtures pages;
    private String clientIp;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        clientIp = "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private String salonSlugOf(UUID salonId) {
        return jdbcTemplate.queryForObject("SELECT booking_slug FROM salons WHERE id = ?", String.class, salonId);
    }

    private String masterSlugOf(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT booking_slug FROM masters WHERE id = ?", String.class, masterId);
    }

    private UUID userIdOf(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    /**
     * Anonymous GET from this test's own client IP. The {@code /book/**}{@code /info} bucket is a hard-coded
     * 60/min per IP shared by every IT on the cached context (all of which call from 127.0.0.1); this class
     * alone spends ~30 of it, so without its own IP a suite run could 429 here or in a sibling IT.
     */
    private ResponseEntity<String> anonymousGet(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
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
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Mix Salon");
        UUID anna = pages.salonMaster(salon.salonId(), "Anna", "Bila");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), anna);
        UUID olha = pages.salonMaster(salon.salonId(), "Olha", "Chorna");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), olha);
        UUID serviceOnly = pages.salonMaster(salon.salonId(), "Bez", "Hrafika");
        BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon.salonId(), serviceOnly);
        UUID inactive = pages.salonMaster(salon.salonId(), "Ne", "Aktyvna");
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
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Slug Salon");
        UUID legacy = pages.legacySalonMaster(salon.salonId(), "Iryna", "Lehasi");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), legacy);
        UUID stored = pages.salonMaster(salon.salonId(), "Maria", "Stara");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), stored);
        jdbcTemplate.update("UPDATE masters SET booking_slug = 'maria-stara-zz11' WHERE id = ?", stored);

        ThreadJdbcFootprint.Measured<SalonBookingSlugInfoResponse> measured =
                ThreadJdbcFootprint.measure(() -> salonBookingSlugService.findBySlug(salon.slug()));

        // Phase 384: the page delegates to read-only services, so the read runs in ONE read-only transaction
        // (it was transaction-free before). It still never writes: the asserts on the slug columns below prove it.
        assertThat(measured.footprint().transactions()).as("one read-only transaction — %s", measured.footprint())
                .isEqualTo(1);
        assertThat(masterSlugOf(legacy)).as("no slug issued by the public page").isNull();
        assertThat(masterSlugOf(stored)).as("stored slug never rewritten").isEqualTo("maria-stara-zz11");
        assertThat(measured.result().masters()).extracting("masterSlug").containsExactly("maria-stara-zz11");
        assertThat(anonymousGet("/api/v1/book/maria-stara-zz11/info").getStatusCode())
                .as("the listed masterSlug opens the master booking flow").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("only slug-less bookable masters → 200, bookable=false (omission never 404s the page)")
    void should_returnNotBookable_when_onlyLegacyMastersAreBookable() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Legacy Only Salon");
        UUID legacy = pages.legacySalonMaster(salon.salonId(), "Tilky", "Lehasi");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), legacy);

        JsonNode page = page(salon.slug());

        assertThat(page.path("bookable").asBoolean()).isFalse();
        assertThat(page.path("masters")).isEmpty();
        assertThat(masterSlugOf(legacy)).isNull();
    }

    @Test
    @DisplayName("active salon with no bookable master → 200, bookable=false, masters: [] (not 404)")
    void should_returnNotBookable_when_noMasterBookable() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Empty Salon");
        UUID master = pages.salonMaster(salon.salonId(), "Tilky", "Posluha");
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
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Closed Salon");
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
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Public Salon");
        jdbcTemplate.update("UPDATE salons SET phone = '+380501112233' WHERE id = ?", salon.salonId());
        UUID master = pages.salonMaster(salon.salonId(), "Publichna", "Maisterka");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), master);
        String ownerEmail = jdbcTemplate.queryForObject(
                "SELECT u.email FROM users u JOIN salons s ON s.owner_id = u.id WHERE s.id = ?",
                String.class, salon.salonId());

        ResponseEntity<String> response = anonymousGet("/api/v1/book/s/" + salon.slug() + "/info");
        JsonNode page = objectMapper.readTree(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fieldNames(page)).containsExactlyInAnyOrder(
                "salonId", "name", "city", "avatarUrl", "coverImageUrl", "avgRating", "reviewCount",
                "bookable", "masters", "region", "street", "buildingNo", "updatedAt", "description", "locationNote",
                "portfolio", "serviceCategories", "ratingDistribution", "latestReviews");
        assertThat(fieldNames(page.path("masters").get(0)))
                .containsExactlyInAnyOrder("masterSlug", "name", "avatarUrl", "professionalTitle");
        assertThat(response.getBody())
                .doesNotContain(ownerEmail)
                .doesNotContain("+380501112233")
                .doesNotContain(master.toString())
                .doesNotContain(userIdOf(master).toString());
    }

    // ── phase 384: tabs ──────────────────────────────────────────────────────────────────────

    private record RichSalon(PublicBookingPageFixtures.SalonPage salon, UUID masterId, UUID client) {
    }

    /** A salon with a bookable categorised service, one portfolio photo and one review. */
    private RichSalon richSalon(String name) throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon(name);
        UUID master = pages.salonMaster(salon.salonId(), "Anna", "Bila");
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master);
        pages.categorise(BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon.salonId(), master));
        UUID client = pages.insertClient();
        pages.insertSalonReview(salon.salonId(), master, client, 5, 10);
        jdbcTemplate.update("UPDATE salons SET description = 'Студія краси', location_note = 'Другий вхід', "
                + "phone = '+380501112233' WHERE id = ?", salon.salonId());
        insertSalonPhoto(salon.salonId(), 5);
        return new RichSalon(salon, master, client);
    }

    private void insertSalonPhoto(UUID salonId, int minutesAgo) {
        UUID id = UUID.randomUUID();
        UUID uploader = jdbcTemplate.queryForObject("SELECT owner_id FROM salons WHERE id = ?", UUID.class, salonId);
        String key = "portfolio/salons/" + salonId + "/seed-" + id + ".jpg";
        jdbcTemplate.update("INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, "
                + "r2_url, created_at, updated_at) VALUES (?, ?, 'SALON', ?, 'PORTFOLIO', ?, ?, "
                + "NOW() - make_interval(mins => ?), NOW())", id, uploader, salonId, key, "https://cdn.example/" + key,
                minutesAgo);
    }

    private static java.util.Set<String> componentNames(Class<? extends Record> type) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var component : type.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    private static java.util.Set<String> keySet(JsonNode node) {
        return new java.util.HashSet<>(fieldNames(node));
    }

    @Test
    @DisplayName("should_exposeOnlyAllowListedKeys_when_salonPageRendered - recursive: groups, services, photos, reviews")
    void should_exposeOnlyAllowListedKeys_when_salonPageRendered() throws Exception {
        RichSalon rich = richSalon("Allow List Salon");
        String ownerEmail = jdbcTemplate.queryForObject(
                "SELECT u.email FROM users u JOIN salons s ON s.owner_id = u.id WHERE s.id = ?", String.class,
                rich.salon().salonId());

        ResponseEntity<String> response = anonymousGet("/api/v1/book/s/" + rich.salon().slug() + "/info");
        JsonNode page = objectMapper.readTree(response.getBody());

        assertThat(keySet(page)).isEqualTo(java.util.Set.of(
                "salonId", "name", "city", "avatarUrl", "coverImageUrl", "avgRating", "reviewCount", "bookable",
                "masters", "region", "street", "buildingNo", "updatedAt", "description", "locationNote", "portfolio",
                "serviceCategories", "ratingDistribution", "latestReviews"));
        assertThat(keySet(page.path("portfolio").get(0)))
                .isEqualTo(java.util.Set.of("id", "entityType", "entityId", "mediaType", "url", "createdAt"));
        JsonNode group = page.path("serviceCategories").get(0);
        assertThat(keySet(group)).isEqualTo(java.util.Set.of("category", "displayName", "count", "services"));
        assertThat(keySet(group.path("services").get(0)))
                .isEqualTo(componentNames(com.beautica.service.dto.ServiceDefinitionResponse.class));
        assertThat(keySet(page.path("ratingDistribution").get(0))).isEqualTo(java.util.Set.of("rating", "count"));
        assertThat(keySet(page.path("latestReviews").get(0)))
                .isEqualTo(componentNames(com.beautica.review.dto.SalonReviewResponse.class));
        assertThat(response.getBody()).doesNotContain(ownerEmail).doesNotContain("+380501112233")
                .doesNotContain(rich.client().toString());
    }

    @Test
    @DisplayName("parity: serviceCategories, portfolio and ratingDistribution equal the app's public salon endpoints")
    void should_matchAppPublicEndpoints_when_salonPageRendered() throws Exception {
        RichSalon rich = richSalon("Parity Salon");
        JsonNode page = page(rich.salon().slug());
        UUID salonId = rich.salon().salonId();

        JsonNode appServices = appData("/api/v1/salons/" + salonId + "/services").path("categories");
        JsonNode appPortfolio = appData("/api/v1/salons/" + salonId + "/portfolio").path("content");
        JsonNode appSummary = appData("/api/v1/salons/" + salonId + "/reviews/summary");
        JsonNode appReviews = appData("/api/v1/salons/" + salonId + "/reviews").path("data");

        assertThat(page.path("serviceCategories")).isEqualTo(appServices);
        assertThat(page.path("portfolio")).isEqualTo(appPortfolio);
        assertThat(page.path("ratingDistribution")).isEqualTo(appSummary.path("ratingDistribution"));
        assertThat(page.path("latestReviews")).isEqualTo(appReviews);
        assertThat(page.path("description").asText()).isEqualTo("Студія краси");
        assertThat(page.path("locationNote").asText()).isEqualTo("Другий вхід");
    }

    private JsonNode appData(String path) throws Exception {
        ResponseEntity<String> response = anonymousGet(path);
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    @Test
    @DisplayName("eviction (D5): a new salon review shows in ratingDistribution and latestReviews on the next read")
    void should_showNewSalonReview_when_reviewCreatedEventCommits() throws Exception {
        RichSalon rich = richSalon("Review Evict Salon");
        assertThat(page(rich.salon().slug()).path("latestReviews")).hasSize(1);
        assertThat(pageCache().get(rich.salon().slug())).as("page cached").isNotNull();

        pages.insertSalonReview(rich.salon().salonId(), rich.masterId(), rich.client(), 3, 1);
        UUID masterUserId = userIdOf(rich.masterId());
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(
                new com.beautica.review.event.ReviewCreatedEvent(rich.masterId(), masterUserId,
                        rich.salon().salonId(), rich.client())));

        JsonNode after = page(rich.salon().slug());
        assertThat(after.path("latestReviews")).hasSize(2);
        assertThat(after.path("ratingDistribution").get(2).path("rating").asInt()).isEqualTo(3);
        assertThat(after.path("ratingDistribution").get(2).path("count").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("eviction (D5): a salon portfolio upload and then delete are visible on the next read")
    void should_showPhotoThenDropIt_when_salonPortfolioUploadedAndDeleted() throws Exception {
        org.mockito.Mockito.when(r2.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(r2.buildPublicUrl(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Photo Evict Salon");
        UUID master = pages.salonMaster(salon.salonId(), "Anna", "Bila");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), master);
        assertThat(page(salon.slug()).path("portfolio")).isEmpty();

        org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}) {
            @Override
            public String getFilename() {
                return "a.jpg";
            }
        });
        HttpHeaders headers = fixtures.bearerHeaders(salon.ownerToken());
        headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> upload = restTemplate.exchange("/api/v1/salons/" + salon.salonId() + "/portfolio",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(upload.getStatusCode()).as(upload.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode afterUpload = page(salon.slug());
        assertThat(afterUpload.path("portfolio")).hasSize(1);

        String mediaId = afterUpload.path("portfolio").get(0).path("id").asText();
        ResponseEntity<String> delete = restTemplate.exchange(
                "/api/v1/salons/" + salon.salonId() + "/portfolio/" + mediaId, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(salon.ownerToken())), String.class);
        assertThat(delete.getStatusCode().is2xxSuccessful()).as(delete.getBody()).isTrue();

        assertThat(page(salon.slug()).path("portfolio")).isEmpty();
    }

    // ── cache (D5) ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("second lookup of a slug is a cache hit: zero connections and statements (the probe sees the miss's work)")
    void should_touchNoDatabase_when_slugCached() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Cached Salon");
        UUID master = pages.salonMaster(salon.salonId(), "Keshova", "Maisterka");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), master);
        jdbcTemplate.update("UPDATE masters SET booking_slug = 'keshova-m-c4c4' WHERE id = ?", master);

        Footprint miss = ThreadJdbcFootprint.footprintOf(() -> salonBookingSlugService.findBySlug(salon.slug()));
        ThreadJdbcFootprint.Measured<SalonBookingSlugInfoResponse> hit =
                ThreadJdbcFootprint.measure(() -> salonBookingSlugService.findBySlug(salon.slug()));

        assertThat(miss.statements()).as("miss: salon row + ONE master batch + the tab blocks — %s", miss)
                .isLessThanOrEqualTo(MISS_STATEMENT_BUDGET);
        assertThat(miss.transactions()).as("one read-only transaction — %s", miss).isEqualTo(1);
        assertThat(hit.footprint().connections()).as("%s", hit.footprint()).isZero();
        assertThat(hit.footprint().statements()).as("%s", hit.footprint()).isZero();
        assertThat(hit.result().masters()).hasSize(1);
    }

    @Test
    @DisplayName("statements on a miss are constant in the number of bookable masters (one batch, never per master)")
    void should_useConstantStatements_when_salonHasManyBookableMasters() throws Exception {
        PublicBookingPageFixtures.SalonPage one = pages.salon("One Master Salon");
        UUID solo = pages.salonMaster(one.salonId(), "Odna", "Maisterka");
        BookableMasterSeeder.makeBookable(jdbcTemplate, one.salonId(), solo);
        jdbcTemplate.update("UPDATE masters SET booking_slug = 'odna-m-o1o1' WHERE id = ?", solo);
        PublicBookingPageFixtures.SalonPage three = pages.salon("Three Master Salon");
        for (int i = 0; i < 3; i++) {
            UUID master = pages.salonMaster(three.salonId(), "Tr" + i, "Maisterka");
            BookableMasterSeeder.makeBookable(jdbcTemplate, three.salonId(), master);
            jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", "tr" + i + "-m-t3t3", master);
        }

        Footprint oneFootprint = ThreadJdbcFootprint.footprintOf(() -> salonBookingSlugService.findBySlug(one.slug()));
        ThreadJdbcFootprint.Measured<SalonBookingSlugInfoResponse> threeMeasured =
                ThreadJdbcFootprint.measure(() -> salonBookingSlugService.findBySlug(three.slug()));

        assertThat(threeMeasured.result().masters()).hasSize(3);
        assertThat(threeMeasured.footprint().statements())
                .as("one=%s three=%s", oneFootprint, threeMeasured.footprint())
                .isLessThanOrEqualTo(oneFootprint.statements());
    }

    // ── eviction after commit, through the real write paths (D5) ─────────────────────────────

    @Test
    @DisplayName("salon master gains working hours (owner PUT override) → cached bookable=false page evicted, master now listed")
    void should_listMaster_when_masterGainsSchedule() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Schedule Salon");
        UUID master = pages.salonMaster(salon.salonId(), "Novyi", "Hrafik");
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
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Unassign Salon");
        UUID master = pages.salonMaster(salon.salonId(), "Bez", "Posluhy");
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
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Deactivate Master Salon");
        UUID staying = pages.salonMaster(salon.salonId(), "Zalyshaietsia", "Tut");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), staying);
        UUID leaving = pages.salonMaster(salon.salonId(), "Ide", "Het");
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
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Stara Nazva");
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
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Zakryiemo Salon");
        UUID master = pages.salonMaster(salon.salonId(), "Ostannia", "Zmina");
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
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Rename Master Salon");
        UUID master = pages.salonMaster(salon.salonId(), "Stare", "Imia");
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
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Booking Write Salon");
        UUID master = pages.salonMaster(salon.salonId(), "Zaniata", "Maisterka");
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
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Real Booking Salon");
        UUID master = pages.salonMaster(salon.salonId(), "Realna", "Maisterka");
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

    // ── phase 380: address fields + HTTP caching ─────────────────────────────────────────────

    @Test
    @DisplayName("phase 380 D7: region, street, buildingNo, updatedAt are appended and the old fields keep their values")
    void should_appendAddressFields_when_salonHasStructuredAddress() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Address Salon");
        jdbcTemplate.update("UPDATE salons SET region = 'Київська область', street = 'вул. Хрещатик', "
                + "building_no = '12' WHERE id = ?", salon.salonId());

        JsonNode page = page(salon.slug());

        assertThat(page.path("region").asText()).isEqualTo("Київська область");
        assertThat(page.path("street").asText()).isEqualTo("вул. Хрещатик");
        assertThat(page.path("buildingNo").asText()).isEqualTo("12");
        assertThat(page.path("updatedAt").asText()).isNotBlank();
        assertThat(page.path("name").asText()).isEqualTo("Address Salon");
        assertThat(fieldNames(page)).containsExactlyInAnyOrder("salonId", "name", "city", "avatarUrl",
                "coverImageUrl", "avgRating", "reviewCount", "bookable", "masters", "region", "street",
                "buildingNo", "updatedAt", "description", "locationNote", "portfolio", "serviceCategories",
                "ratingDistribution", "latestReviews");
    }

    @Test
    @DisplayName("phase 380 D7: blank street falls back to the legacy address")
    void should_fallBackToLegacyAddress_when_streetBlank() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Legacy Address Salon");
        jdbcTemplate.update("UPDATE salons SET street = NULL, address = 'вул. Стара, 5' WHERE id = ?",
                salon.salonId());

        JsonNode page = page(salon.slug());

        assertThat(page.path("street").asText()).isEqualTo("вул. Стара, 5");
    }

    @Test
    @DisplayName("phase 380 D5: salon /info sends Cache-Control public, max-age=0, s-maxage=30 + weak ETag, 304 on match, no public header on 404")
    void should_sendCacheHeadersAndAnswer304_when_ifNoneMatchMatches() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Etag Salon");

        ResponseEntity<String> first = anonymousGet("/api/v1/book/s/" + salon.slug() + "/info");
        HttpHeaders conditional = new HttpHeaders();
        conditional.set("X-Forwarded-For", clientIp);
        conditional.setIfNoneMatch(first.getHeaders().getETag());
        ResponseEntity<String> replay = restTemplate.exchange("/api/v1/book/s/" + salon.slug() + "/info",
                HttpMethod.GET, new HttpEntity<>(conditional), String.class);
        ResponseEntity<String> missing = anonymousGet("/api/v1/book/s/no-such-salon-zz99/info");

        assertThat(first.getHeaders().getCacheControl())
                .contains("public").contains("max-age=0").contains("s-maxage=30");
        assertThat(first.getHeaders().getETag()).startsWith("W/\"");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(replay.getBody()).isNullOrEmpty();
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String missingCacheControl = missing.getHeaders().getCacheControl();
        assertThat(missingCacheControl == null || !missingCacheControl.contains("public")).isTrue();
    }
}
