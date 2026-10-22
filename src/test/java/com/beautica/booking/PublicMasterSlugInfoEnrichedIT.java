package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.dto.BookingSlugInfoResponse;
import com.beautica.booking.service.BookingSlugService;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.R2StorageService;
import com.beautica.review.event.ReviewCreatedEvent;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.support.BookableMasterSeeder;
import com.beautica.support.HibernateStatistics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Phase 380 — the enriched public {@code GET /api/v1/book/{slug}/info} end to end (real Postgres, real
 * security chain, anonymous caller): full payload, exact key-set allow-list (no PII), the bookable matrix,
 * caps (5 reviews / 12 photos), post-commit eviction by every real write path, HTTP caching
 * ({@code Cache-Control} + ETag + 304) and the statement budget.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /book/{slug}/info — enriched master page (Phase 380)")
class PublicMasterSlugInfoEnrichedIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Str0ngP@ss1!";
    private static final String SEEDED_PHONE = "+380671119999";
    private static final String SEEDED_INSTAGRAM = "secret_insta_handle";

    private static final Set<String> ROOT_KEYS = Set.of(
            "masterName", "avatarUrl", "bio", "services", "masterId", "masterType", "professionalTitle", "city",
            "region", "salon", "avgRating", "reviewCount", "categories", "portfolio", "latestReviews", "bookable",
            "updatedAt");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CacheManager cacheManager;
    @Autowired private BookingSlugService bookingSlugService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManagerFactory emf;
    @MockBean private R2StorageService r2;

    private ServiceTestFixtures fixtures;
    private String clientIp;

    @BeforeEach
    void setUp() {
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // The /info bucket is 60/min per IP and shared by every IT on the cached context: own IP per test.
        clientIp = "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
        reset(r2);
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
        doNothing().when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
        for (String cache : List.of("booking-slug-info", "portfolio", "reviews-by-master")) {
            cacheManager.getCache(cache).clear();
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private record Master(UUID userId, UUID masterId, String slug, String email) {
    }

    private Master independentMaster() {
        String email = "m380-" + UUID.randomUUID() + "@beautica.test";
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, phone_number, bio, "
                        + "instagram, professional_title, city, region, avatar_url, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', 'Олена', 'Коваль', ?, 'Манікюр 8 років', ?, "
                        + "'Майстер манікюру', 'Львів', 'Львівська область', 'https://cdn.example/a.webp', true, true)",
                userId, email, passwordEncoder.encode(PASSWORD), SEEDED_PHONE, SEEDED_INSTAGRAM);
        UUID masterId = UUID.randomUUID();
        String slug = "olena-k-" + masterId.toString().substring(0, 4);
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, "
                        + "booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, NULL, 'INDEPENDENT_MASTER', 0, 0, true, ?, NOW(), NOW())",
                masterId, userId, slug);
        return new Master(userId, masterId, slug, email);
    }

    private Master bookableMaster() {
        Master master = independentMaster();
        BookableMasterSeeder.makeIndependentBookable(jdbcTemplate, master.masterId());
        return master;
    }

    private String tokenOf(Master master) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(master.email(), PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    private UUID insertClient() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', 'Ірина', 'Кравчук', true, true)",
                id, "c380-" + id + "@beautica.test", passwordEncoder.encode(PASSWORD));
        return id;
    }

    /** One COMPLETED booking plus its review, {@code minutesAgo} old; a fresh booking per review (UNIQUE). */
    private void insertReview(Master master, UUID clientId, int rating, int minutesAgo) {
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? LIMIT 1", UUID.class, master.masterId());
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'COMPLETED', NOW() - interval '2 hours', "
                        + "NOW() - interval '1 hour', 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, master.masterId(), masterServiceId);
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, NULL, ?, ?, "
                        + "NOW() - make_interval(mins => ?), NOW())",
                UUID.randomUUID(), bookingId, clientId, master.masterId(), rating, "review-" + minutesAgo, minutesAgo);
    }

    private void insertPortfolioPhoto(Master master, int minutesAgo) {
        UUID id = UUID.randomUUID();
        String key = "portfolio/independent/" + master.masterId() + "/seed-" + id + ".jpg";
        jdbcTemplate.update(
                "INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, r2_url, "
                        + "created_at, updated_at) VALUES (?, ?, 'MASTER', ?, 'PORTFOLIO', ?, ?, "
                        + "NOW() - make_interval(mins => ?), NOW())",
                id, master.userId(), master.masterId(), key, "https://cdn.example/" + key, minutesAgo);
    }

    private ResponseEntity<String> get(String slug, HttpHeaders extra) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        headers.addAll(extra);
        return restTemplate.exchange("/api/v1/book/" + slug + "/info", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private JsonNode page(String slug) throws Exception {
        ResponseEntity<String> response = get(slug, new HttpHeaders());
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> out = new HashSet<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(out::add);
        return out;
    }

    private static void collectKeys(JsonNode node, List<String> sink) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                sink.add(name);
                collectKeys(node.get(name), sink);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, sink));
        }
    }

    private void evictSlugCacheEntry(String slug) {
        // Not used to simulate a write — only to prove a read is served from the cache in the eviction tests.
        assertThat(cacheManager.getCache("booking-slug-info").get(slug)).as("slug entry cached").isNotNull();
    }

    // ── full payload + allow-list ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("full payload for an independent master with services, schedule, 2 reviews, 2 portfolio photos")
    void should_returnFullPayload_when_masterHasEverything() throws Exception {
        Master master = bookableMaster();
        UUID client = insertClient();
        insertReview(master, client, 5, 10);
        insertReview(master, client, 4, 20);
        jdbcTemplate.update("UPDATE masters SET review_count = 2, avg_rating = 4.50 WHERE id = ?", master.masterId());
        insertPortfolioPhoto(master, 5);
        insertPortfolioPhoto(master, 6);

        JsonNode body = page(master.slug());

        assertThat(body.path("masterName").asText()).isEqualTo("Олена Коваль");
        assertThat(body.path("bio").asText()).isEqualTo("Манікюр 8 років");
        assertThat(body.path("masterId").asText()).isEqualTo(master.masterId().toString());
        assertThat(body.path("masterType").asText()).isEqualTo("INDEPENDENT_MASTER");
        assertThat(body.path("professionalTitle").asText()).isEqualTo("Майстер манікюру");
        assertThat(body.path("city").asText()).isEqualTo("Львів");
        assertThat(body.path("region").asText()).isEqualTo("Львівська область");
        assertThat(body.path("salon").isNull()).isTrue();
        assertThat(body.path("avgRating").decimalValue()).isEqualByComparingTo("4.50");
        assertThat(body.path("reviewCount").asInt()).isEqualTo(2);
        assertThat(body.path("services")).hasSize(1);
        assertThat(body.path("categories")).hasSize(1);
        assertThat(body.path("portfolio")).hasSize(2);
        assertThat(body.path("latestReviews")).hasSize(2);
        assertThat(body.path("latestReviews").get(0).path("clientDisplayName").asText()).isEqualTo("Ірина К.");
        assertThat(body.path("bookable").asBoolean()).isTrue();
        assertThat(body.path("updatedAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("exact key-set allow-list at every level; no phone/email/instagram/street anywhere in the body")
    void should_exposeOnlyAllowListedKeys_when_pageRendered() throws Exception {
        Master master = bookableMaster();
        UUID client = insertClient();
        insertReview(master, client, 5, 10);
        insertPortfolioPhoto(master, 5);
        jdbcTemplate.update("UPDATE masters SET review_count = 1, avg_rating = 5.00 WHERE id = ?", master.masterId());

        ResponseEntity<String> response = get(master.slug(), new HttpHeaders());
        JsonNode body = objectMapper.readTree(response.getBody());

        assertThat(keys(body)).isEqualTo(ROOT_KEYS);
        assertThat(keys(body.path("services").get(0)))
                .isEqualTo(Set.of("id", "name", "durationMinutes", "priceFrom"));
        assertThat(keys(body.path("categories").get(0))).isEqualTo(Set.of("id", "name"));
        assertThat(keys(body.path("portfolio").get(0)))
                .isEqualTo(Set.of("id", "entityType", "entityId", "mediaType", "url", "createdAt"));
        assertThat(keys(body.path("latestReviews").get(0)))
                .isEqualTo(Set.of("id", "masterId", "clientDisplayName", "serviceName", "rating", "comment",
                        "createdAt"));
        List<String> allKeys = new ArrayList<>();
        collectKeys(body, allKeys);
        assertThat(allKeys).noneMatch(k -> k.toLowerCase().matches(".*(street|instagram|email|phone|building).*"));
        assertThat(response.getBody()).doesNotContain(SEEDED_PHONE, SEEDED_INSTAGRAM, master.email());
    }

    @Test
    @DisplayName("salon master -> salon.name + salon.bookingSlug; masterType SALON_MASTER")
    void should_returnSalonRef_when_masterBelongsToSalon() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken("owner-380-" + UUID.randomUUID() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Studio Velvet");
        UUID masterId = fixtures.createSalonMaster(salonId);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?",
                "sm-" + masterId.toString().substring(0, 8), masterId);
        BookableMasterSeeder.makeBookable(jdbcTemplate, salonId, masterId);
        String salonSlug = jdbcTemplate.queryForObject("SELECT booking_slug FROM salons WHERE id = ?", String.class, salonId);

        JsonNode body = page("sm-" + masterId.toString().substring(0, 8));

        assertThat(body.path("masterType").asText()).isEqualTo("SALON_MASTER");
        assertThat(keys(body.path("salon"))).isEqualTo(Set.of("name", "bookingSlug"));
        assertThat(body.path("salon").path("name").asText()).isEqualTo("Studio Velvet");
        assertThat(body.path("salon").path("bookingSlug").asText()).isEqualTo(salonSlug);
        assertThat(body.path("bookable").asBoolean()).isTrue();
    }

    // ── bookable matrix + 404 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bookable matrix: no services -> false; services without schedule -> false; both -> true")
    void should_flipBookable_when_servicesAndScheduleArePresent() throws Exception {
        Master master = independentMaster();
        assertThat(page(master.slug()).path("bookable").asBoolean()).as("no services").isFalse();

        cacheManager.getCache("booking-slug-info").clear();
        BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master.masterId());
        assertThat(page(master.slug()).path("bookable").asBoolean()).as("services, no schedule").isFalse();

        cacheManager.getCache("booking-slug-info").clear();
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master.masterId());
        assertThat(page(master.slug()).path("bookable").asBoolean()).as("services + schedule").isTrue();
    }

    @Test
    @DisplayName("inactive master -> 404; master of an inactive salon -> 404; unbookable active master -> 200")
    void should_return404_when_masterOrSalonInactive() throws Exception {
        Master inactive = bookableMaster();
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", inactive.masterId());
        String ownerToken = fixtures.createSalonOwnerAndGetToken("owner-380b-" + UUID.randomUUID() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Closed Studio");
        UUID salonMaster = fixtures.createSalonMaster(salonId);
        String salonMasterSlug = "cs-" + salonMaster.toString().substring(0, 8);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", salonMasterSlug, salonMaster);
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);

        assertThat(get(inactive.slug(), new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(salonMasterSlug, new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(independentMaster().slug(), new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── caps ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("latestReviews capped at 5 newest first; portfolio capped at 12")
    void should_capReviewsAtFiveAndPortfolioAtTwelve_when_manyExist() throws Exception {
        Master master = bookableMaster();
        UUID client = insertClient();
        for (int i = 1; i <= 7; i++) {
            insertReview(master, client, 5, i * 10);
        }
        for (int i = 1; i <= 13; i++) {
            insertPortfolioPhoto(master, i);
        }

        JsonNode body = page(master.slug());

        assertThat(body.path("latestReviews")).hasSize(5);
        List<String> comments = new ArrayList<>();
        body.path("latestReviews").forEach(r -> comments.add(r.path("comment").asText()));
        assertThat(comments).containsExactly("review-10", "review-20", "review-30", "review-40", "review-50");
        assertThat(body.path("portfolio")).hasSize(12);
    }

    // ── eviction (read -> write -> read within the TTL) ───────────────────────────────────────

    @Test
    @DisplayName("eviction: a new review is visible on the next read (rating, count, latestReviews)")
    void should_showNewReview_when_reviewCreatedEventCommits() throws Exception {
        Master master = bookableMaster();
        UUID client = insertClient();
        assertThat(page(master.slug()).path("reviewCount").asInt()).isZero();
        evictSlugCacheEntry(master.slug());

        insertReview(master, client, 5, 1);
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(
                new ReviewCreatedEvent(master.masterId(), master.userId(), null, client)));

        JsonNode after = page(master.slug());
        assertThat(after.path("reviewCount").asInt()).isEqualTo(1);
        assertThat(after.path("latestReviews")).hasSize(1);
    }

    @Test
    @DisplayName("eviction: a portfolio upload is visible on the next read")
    void should_showNewPhoto_when_portfolioUploaded() throws Exception {
        Master master = bookableMaster();
        String token = tokenOf(master);
        assertThat(page(master.slug()).path("portfolio")).isEmpty();
        evictSlugCacheEntry(master.slug());

        ResponseEntity<String> upload = restTemplate.exchange("/api/v1/media/portfolio", HttpMethod.POST,
                new HttpEntity<>(jpegBody(), multipartHeaders(token)), String.class);

        assertThat(upload.getStatusCode()).as("body=%s", upload.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(page(master.slug()).path("portfolio")).hasSize(1);
    }

    @Test
    @DisplayName("eviction: deactivating the service flips bookable on the next read")
    void should_flipBookable_when_serviceDeactivated() throws Exception {
        Master master = independentMaster();
        UUID definitionId = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master.masterId());
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, master.masterId());
        String token = tokenOf(master);
        assertThat(page(master.slug()).path("bookable").asBoolean()).isTrue();
        evictSlugCacheEntry(master.slug());

        ResponseEntity<String> deactivate = restTemplate.exchange("/api/v1/services/" + definitionId,
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), String.class);

        assertThat(deactivate.getStatusCode().is2xxSuccessful()).as("body=%s", deactivate.getBody()).isTrue();
        JsonNode after = page(master.slug());
        assertThat(after.path("bookable").asBoolean()).isFalse();
        assertThat(after.path("services")).isEmpty();
    }

    @Test
    @DisplayName("eviction: clearing the schedule flips bookable on the next read")
    void should_flipBookable_when_scheduleCleared() throws Exception {
        Master master = bookableMaster();
        String token = tokenOf(master);
        UUID scheduleId = jdbcTemplate.queryForObject(
                "SELECT id FROM weekly_schedules WHERE master_id = ? LIMIT 1", UUID.class, master.masterId());
        assertThat(page(master.slug()).path("bookable").asBoolean()).isTrue();
        evictSlugCacheEntry(master.slug());

        ResponseEntity<String> delete = restTemplate.exchange(
                "/api/v1/masters/" + master.masterId() + "/weekly-schedules/" + scheduleId,
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), String.class);

        assertThat(delete.getStatusCode().is2xxSuccessful()).as("body=%s", delete.getBody()).isTrue();
        assertThat(page(master.slug()).path("bookable").asBoolean()).isFalse();
    }

    // ── HTTP caching ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cache-Control public max-age=60 + weak ETag; If-None-Match replay -> 304 with empty body")
    void should_answer304_when_ifNoneMatchMatches() throws Exception {
        Master master = bookableMaster();

        ResponseEntity<String> first = get(master.slug(), new HttpHeaders());
        String etag = first.getHeaders().getETag();
        HttpHeaders conditional = new HttpHeaders();
        conditional.setIfNoneMatch(etag);
        ResponseEntity<String> replay = get(master.slug(), conditional);

        assertThat(first.getHeaders().getCacheControl()).contains("public").contains("max-age=60");
        assertThat(etag).startsWith("W/\"");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(replay.getBody()).isNullOrEmpty();
    }

    @Test
    @DisplayName("404 carries no public cache header")
    void should_notSendPublicCacheHeader_when_slugUnknown() {
        ResponseEntity<String> response = get("no-such-master-zz99", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String cacheControl = response.getHeaders().getCacheControl();
        assertThat(cacheControl == null || !cacheControl.contains("public")).isTrue();
    }

    // ── statement budget ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a cache miss costs at most 7 statements (D9)")
    void should_useAtMostSevenStatements_when_cacheMiss() {
        Master master = bookableMaster();
        UUID client = insertClient();
        insertReview(master, client, 5, 10);
        insertPortfolioPhoto(master, 5);
        Statistics statistics = HibernateStatistics.enabledOn(emf);
        long before = statistics.getPrepareStatementCount();

        BookingSlugInfoResponse info = bookingSlugService.findBySlug(master.slug());

        long statements = statistics.getPrepareStatementCount() - before;
        assertThat(info.latestReviews()).hasSize(1);
        assertThat(info.portfolio()).hasSize(1);
        assertThat(statements).as("statements on a cache miss").isBetween(1L, 7L);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static HttpHeaders multipartHeaders(String token) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        return headers;
    }

    private static MultiValueMap<String, Object> jpegBody() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}) {
            @Override
            public String getFilename() {
                return "a.jpg";
            }
        });
        return body;
    }
}
