package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.PublicBookingPageFixtures.IndependentMaster;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.R2StorageService;
import com.beautica.review.event.ReviewCreatedEvent;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 380 QA — the HTTP caching contract of BOTH public booking-page routes ({@code /book/{slug}/info} and
 * {@code /book/s/{slug}/info}) and the write-path evictions the existing ITs do not drive over real HTTP,
 * observed the way the website's SSR/CDN observes them: through the {@code ETag} changing (or not) on the
 * next read.
 *
 * <p>Complements {@link PublicMasterSlugInfoEnrichedIT} (payload, caps, 4 eviction paths) and
 * {@link PublicSalonBookingInfoIT} (salon payload/eviction): pins the EXACT {@code Cache-Control} value, the
 * headers on a 304, a stale validator after a write, the filter's narrowing to the two info routes, and the
 * unbookable-200 / inactive-404 matrix on both routes.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /book/**/info — Cache-Control/ETag contract + write-path eviction visible via ETag (Phase 380 QA)")
class PublicBookingInfoHttpContractIT extends AbstractIntegrationTest {

    /** Spring's {@code CacheControl} renders max-age, public, s-maxage in this fixed order. */
    private static final String EXPECTED_CACHE_CONTROL = "max-age=0, public, s-maxage=30";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CacheManager cacheManager;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @MockBean private R2StorageService r2;

    private PublicBookingPageFixtures pages;
    private String clientIp;

    @BeforeEach
    void setUp() {
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // The /info bucket is 60/min per IP and shared by every IT on the cached context: own IP per test.
        clientIp = "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
        for (String cache : new String[] {"booking-slug-info", "salon-booking-slug-info"}) {
            cacheManager.getCache(cache).clear();
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private ResponseEntity<String> get(String path, String ifNoneMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        if (ifNoneMatch != null) {
            headers.set(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static String masterPath(String slug) {
        return "/api/v1/book/" + slug + "/info";
    }

    private static String salonPath(String slug) {
        return "/api/v1/book/s/" + slug + "/info";
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private static HttpHeaders bearerJson(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Asserts a 200 whose ETag differs from {@code staleEtag}, and returns the response. */
    private ResponseEntity<String> assertChangedSince(String path, String staleEtag) {
        ResponseEntity<String> after = get(path, staleEtag);
        assertThat(after.getStatusCode()).as("stale validator after a write -> full 200, body=%s", after.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(after.getHeaders().getETag()).as("ETag moved on").isNotEqualTo(staleEtag);
        return after;
    }

    // ── exact headers, both routes ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("master /info answers the EXACT Cache-Control 'max-age=0, public, s-maxage=30' + weak ETag")
    void should_sendExactCacheControl_when_masterInfoRead() {
        IndependentMaster master = pages.independentMaster(true);

        ResponseEntity<String> response = get(masterPath(master.slug()), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo(EXPECTED_CACHE_CONTROL);
        assertThat(response.getHeaders().getETag()).matches("W/\"0[0-9a-f]{32}\"");
        assertThat(response.getHeaders().get(HttpHeaders.CACHE_CONTROL)).as("one Cache-Control header").hasSize(1);
    }

    @Test
    @DisplayName("salon /info answers the EXACT Cache-Control 'max-age=0, public, s-maxage=30' + weak ETag")
    void should_sendExactCacheControl_when_salonInfoRead() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Exact Header Salon");

        ResponseEntity<String> response = get(salonPath(salon.slug()), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo(EXPECTED_CACHE_CONTROL);
        assertThat(response.getHeaders().getETag()).matches("W/\"0[0-9a-f]{32}\"");
        assertThat(response.getHeaders().get(HttpHeaders.CACHE_CONTROL)).as("one Cache-Control header").hasSize(1);
    }

    @Test
    @DisplayName("304 keeps the same ETag and Cache-Control and carries no body — master route")
    void should_repeatValidatorAndCacheControl_when_master304() {
        IndependentMaster master = pages.independentMaster(true);
        String etag = get(masterPath(master.slug()), null).getHeaders().getETag();

        ResponseEntity<String> replay = get(masterPath(master.slug()), etag);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(replay.getHeaders().getETag()).isEqualTo(etag);
        assertThat(replay.getHeaders().getCacheControl()).isEqualTo(EXPECTED_CACHE_CONTROL);
        assertThat(replay.getBody()).isNullOrEmpty();
    }

    @Test
    @DisplayName("304 keeps the same ETag and Cache-Control and carries no body — salon route")
    void should_repeatValidatorAndCacheControl_when_salon304() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Salon 304");
        String etag = get(salonPath(salon.slug()), null).getHeaders().getETag();

        ResponseEntity<String> replay = get(salonPath(salon.slug()), etag);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(replay.getHeaders().getETag()).isEqualTo(etag);
        assertThat(replay.getHeaders().getCacheControl()).isEqualTo(EXPECTED_CACHE_CONTROL);
        assertThat(replay.getBody()).isNullOrEmpty();
    }

    @Test
    @DisplayName("If-None-Match listing several validators incl. the current one -> 304; a foreign one -> 200")
    void should_matchOneOfManyValidators_when_ifNoneMatchIsAList() {
        IndependentMaster master = pages.independentMaster(true);
        String etag = get(masterPath(master.slug()), null).getHeaders().getETag();

        ResponseEntity<String> list = get(masterPath(master.slug()), "W/\"deadbeef\", " + etag);
        ResponseEntity<String> foreign = get(masterPath(master.slug()), "W/\"0123456789abcdef0123456789abcdef\"");

        assertThat(list.getStatusCode()).as("list containing current").isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(foreign.getStatusCode()).as("foreign validator").isEqualTo(HttpStatus.OK);
        assertThat(foreign.getBody()).isNotBlank();
    }

    @Test
    @DisplayName("the ETag filter is scoped to the two info routes: availability and cancel-info carry no ETag")
    void should_notEtagOtherBookRoutes_when_availabilityOrCancelInfoRead() {
        IndependentMaster master = pages.independentMaster(true);
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? LIMIT 1", UUID.class, master.masterId());
        String tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Kyiv")).plusDays(1).toString();

        ResponseEntity<String> availability = get(
                "/api/v1/book/" + master.slug() + "/availability?date=" + tomorrow + "&serviceId=" + masterServiceId, null);
        ResponseEntity<String> cancelInfo = get("/api/v1/book/cancel/" + UUID.randomUUID(), null);

        assertThat(availability.getStatusCode()).as("a 2xx, so an unscoped filter WOULD tag it; body=%s",
                availability.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(availability.getHeaders().getETag()).isNull();
        assertThat(cancelInfo.getHeaders().getETag()).as("cancel-info status=%s", cancelInfo.getStatusCode())
                .isNull();
        String infoCacheControl = availability.getHeaders().getCacheControl();
        assertThat(infoCacheControl == null || !infoCacheControl.contains("s-maxage"))
                .as("no CDN caching on non-info routes, got %s", infoCacheControl).isTrue();
    }

    // ── matrix: unbookable 200 / inactive 404, both routes ───────────────────────────────────

    @Test
    @DisplayName("master route: unbookable active master -> 200 bookable:false (cacheable); inactive -> 404 with no ETag/public cache")
    void should_distinguishUnbookable200FromInactive404_when_masterInfoRead() throws Exception {
        IndependentMaster unbookable = pages.independentMaster(false);
        IndependentMaster inactive = pages.independentMaster(true);
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", inactive.masterId());

        ResponseEntity<String> ok = get(masterPath(unbookable.slug()), null);
        ResponseEntity<String> gone = get(masterPath(inactive.slug()), null);

        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(ok).path("bookable").asBoolean(true)).isFalse();
        assertThat(ok.getHeaders().getCacheControl()).isEqualTo(EXPECTED_CACHE_CONTROL);
        assertThat(ok.getHeaders().getETag()).startsWith("W/\"");
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(gone.getHeaders().getETag()).isNull();
        assertThat(String.valueOf(gone.getHeaders().getCacheControl())).doesNotContain("public").doesNotContain("s-maxage");
    }

    @Test
    @DisplayName("salon route: active salon without a bookable master -> 200 bookable:false (cacheable); inactive salon -> 404 with no ETag/public cache")
    void should_distinguishUnbookable200FromInactive404_when_salonInfoRead() throws Exception {
        PublicBookingPageFixtures.SalonPage empty = pages.salon("Empty Salon");
        PublicBookingPageFixtures.SalonPage closed = pages.salon("Closed Salon");
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", closed.salonId());

        ResponseEntity<String> ok = get(salonPath(empty.slug()), null);
        ResponseEntity<String> gone = get(salonPath(closed.slug()), null);

        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(ok);
        assertThat(body.path("bookable").asBoolean(true)).isFalse();
        assertThat(body.path("masters")).isEmpty();
        assertThat(ok.getHeaders().getCacheControl()).isEqualTo(EXPECTED_CACHE_CONTROL);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(gone.getHeaders().getETag()).isNull();
        assertThat(String.valueOf(gone.getHeaders().getCacheControl())).doesNotContain("public").doesNotContain("s-maxage");
    }

    // ── stale validator after a write: 200 with a new ETag, then 304 on the new one ──────────

    @Test
    @DisplayName("master: review committed -> a replayed old ETag gets 200 + new ETag; the new ETag then gets 304")
    void should_answer200ThenNew304_when_reviewCommittedAfterValidatorIssued() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID client = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', 'Ірина', 'Кравчук', true, true)",
                client, "c380q-" + client + "@beautica.test", passwordEncoder.encode(PublicBookingPageFixtures.PASSWORD));
        String oldEtag = get(masterPath(master.slug()), null).getHeaders().getETag();
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? LIMIT 1", UUID.class, master.masterId());
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) VALUES (?, ?, ?, ?, NULL, 'COMPLETED', "
                        + "NOW() - interval '2 hours', NOW() - interval '1 hour', 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, client, master.masterId(), masterServiceId);
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, NULL, 4, 'nice', NOW(), NOW())",
                UUID.randomUUID(), bookingId, client, master.masterId());

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(
                new ReviewCreatedEvent(master.masterId(), master.userId(), null, client)));

        ResponseEntity<String> fresh = assertChangedSince(masterPath(master.slug()), oldEtag);
        assertThat(json(fresh).path("reviewCount").asInt()).isEqualTo(1);
        assertThat(get(masterPath(master.slug()), fresh.getHeaders().getETag()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    @DisplayName("salon: owner renames the salon -> a replayed old ETag gets 200 + new ETag; the new ETag then gets 304")
    void should_answer200ThenNew304_when_salonRenamedAfterValidatorIssued() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Before Rename");
        String oldEtag = get(salonPath(salon.slug()), null).getHeaders().getETag();

        ResponseEntity<String> patch = restTemplate.exchange("/api/v1/salons/" + salon.salonId(), HttpMethod.PATCH,
                new HttpEntity<>("{\"name\":\"After Rename\",\"street\":\"вул. Хрещатик\",\"buildingNo\":\"1\"}",
                        bearerJson(salon.ownerToken())), String.class);
        assertThat(patch.getStatusCode()).as(patch.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> fresh = assertChangedSince(salonPath(salon.slug()), oldEtag);
        assertThat(json(fresh).path("name").asText()).isEqualTo("After Rename");
        assertThat(get(salonPath(salon.slug()), fresh.getHeaders().getETag()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    // ── write paths driven over HTTP that the existing ITs skip ──────────────────────────────

    @Test
    @DisplayName("eviction: master renames a service definition (PATCH /services/{id}) -> new name on the next read")
    void should_showNewServiceName_when_serviceDefinitionRenamed() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        String token = pages.tokenOf(master);
        String oldEtag = get(masterPath(master.slug()), null).getHeaders().getETag();

        ResponseEntity<String> patch = restTemplate.exchange("/api/v1/services/" + master.serviceDefId(),
                HttpMethod.PATCH, new HttpEntity<>("{\"name\":\"Новий манікюр\"}", bearerJson(token)), String.class);
        assertThat(patch.getStatusCode()).as(patch.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> fresh = assertChangedSince(masterPath(master.slug()), oldEtag);
        assertThat(json(fresh).path("services").get(0).path("name").asText()).isEqualTo("Новий манікюр");
    }

    @Test
    @DisplayName("eviction: master edits own bio (PATCH /independent-masters/me/profile) -> new bio on the next read")
    void should_showNewBio_when_masterEditsProfile() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        String token = pages.tokenOf(master);
        String oldEtag = get(masterPath(master.slug()), null).getHeaders().getETag();

        ResponseEntity<String> patch = restTemplate.exchange("/api/v1/independent-masters/me/profile", HttpMethod.PATCH,
                new HttpEntity<>("{\"bio\":\"Нова біографія\"}", bearerJson(token)), String.class);
        assertThat(patch.getStatusCode()).as(patch.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> fresh = assertChangedSince(masterPath(master.slug()), oldEtag);
        assertThat(json(fresh).path("bio").asText()).isEqualTo("Нова біографія");
    }

    @Test
        @DisplayName("eviction: master deactivated (DELETE /masters/{id}) -> the cached page turns 404 on the next read")
    void should_return404_when_masterDeactivatedAfterPageCached() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        String token = pages.tokenOf(master);
        assertThat(get(masterPath(master.slug()), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> delete = restTemplate.exchange("/api/v1/masters/" + master.masterId(),
                HttpMethod.DELETE, new HttpEntity<>(bearerJson(token)), String.class);
        assertThat(delete.getStatusCode().is2xxSuccessful()).as(delete.getBody()).isTrue();

        assertThat(get(masterPath(master.slug()), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("eviction: salon deleted (DELETE /salons/{id}) -> the cached salon page AND its master's cached page turn 410 Gone (phase 379 D7 tombstones)")
    void should_return410OnBothPages_when_salonDeletedAfterPagesCached() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Deleted Salon");
        UUID masterId = pages.salonMaster(salon.salonId(), "Марія", "Тестова");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), masterId);
        String masterSlug = pages.masterSlugOf(masterId);
        assertThat(get(salonPath(salon.slug()), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(masterPath(masterSlug), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> delete = restTemplate.exchange("/api/v1/salons/" + salon.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(bearerJson(salon.ownerToken())), String.class);
        assertThat(delete.getStatusCode().is2xxSuccessful()).as(delete.getBody()).isTrue();

        assertThat(get(salonPath(salon.slug()), null).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(get(masterPath(masterSlug), null).getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    @DisplayName("eviction: master removed from the salon (DELETE /salons/{id}/masters/{id}) -> its cached page turns 410 Gone (phase 379 D7) "
            + "and the cached salon page stops listing it")
    void should_return410AndDelistMaster_when_masterRemovedFromSalonAfterPagesCached() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Removal Salon");
        UUID leaving = pages.salonMaster(salon.salonId(), "Ide", "Het");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), leaving);
        UUID staying = pages.salonMaster(salon.salonId(), "Zalyshaietsia", "Tut");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), staying);
        String leavingSlug = pages.masterSlugOf(leaving);
        assertThat(json(get(salonPath(salon.slug()), null)).path("masters")).hasSize(2);
        assertThat(get(masterPath(leavingSlug), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> remove = restTemplate.exchange(
                "/api/v1/salons/" + salon.salonId() + "/masters/" + leaving, HttpMethod.DELETE,
                new HttpEntity<>(bearerJson(salon.ownerToken())), String.class);
        assertThat(remove.getStatusCode().is2xxSuccessful()).as(remove.getBody()).isTrue();

        assertThat(get(masterPath(leavingSlug), null).getStatusCode()).isEqualTo(HttpStatus.GONE);
        JsonNode masters = json(get(salonPath(salon.slug()), null)).path("masters");
        assertThat(masters).hasSize(1);
        assertThat(masters.get(0).path("masterSlug").asText()).isEqualTo(pages.masterSlugOf(staying));
    }

    @Test
    @DisplayName("eviction: independent master deletes their own account (DELETE /users/me) -> the cached page turns 410 Gone (phase 379 D7)")
    void should_return410_when_independentMasterSelfDeletedAfterPageCached() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        String token = pages.tokenOf(master);
        assertThat(get(masterPath(master.slug()), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> delete = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(bearerJson(token)), String.class);
        assertThat(delete.getStatusCode().is2xxSuccessful()).as(delete.getBody()).isTrue();

        assertThat(get(masterPath(master.slug()), null).getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    @DisplayName("eviction: master rotated to another salon (PATCH /masters/{id}/salon) -> the cached page shows the new salon")
    void should_showNewSalon_when_masterRotatedAfterPageCached() throws Exception {
        PublicBookingPageFixtures.SalonPage from = pages.salon("Rotate From");
        PublicBookingPageFixtures.SalonPage to = pages.additionalSalon(from, "Rotate To");
        UUID masterId = pages.salonMaster(from.salonId(), "Марія", "Тестова");
        String slug = pages.masterSlugOf(masterId);
        ResponseEntity<String> before = get(masterPath(slug), null);
        assertThat(json(before).path("salon").path("name").asText()).isEqualTo("Rotate From");

        ResponseEntity<String> rotate = restTemplate.exchange("/api/v1/masters/" + masterId + "/salon",
                HttpMethod.PATCH, new HttpEntity<>("{\"destinationSalonId\":\"" + to.salonId() + "\"}",
                        bearerJson(from.ownerToken())), String.class);
        assertThat(rotate.getStatusCode().is2xxSuccessful()).as(rotate.getBody()).isTrue();

        ResponseEntity<String> fresh = assertChangedSince(masterPath(slug), before.getHeaders().getETag());
        assertThat(json(fresh).path("salon").path("name").asText()).isEqualTo("Rotate To");
        assertThat(json(fresh).path("salon").path("bookingSlug").asText()).isEqualTo(to.slug());
    }

    // ── If-None-Match: * (RFC 9110 §13.1.2) ──────────────────────────────────────────────────

    @Test
    @DisplayName("If-None-Match: * on an EXISTING page answers 304 with no body, on both routes")
    void should_answer304_when_ifNoneMatchIsWildcardAndPageExists() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Wildcard Salon");

        String masterEtag = get(masterPath(master.slug()), null).getHeaders().getETag();
        String salonEtag = get(salonPath(salon.slug()), null).getHeaders().getETag();

        ResponseEntity<String> masterReply = get(masterPath(master.slug()), "*");
        ResponseEntity<String> salonReply = get(salonPath(salon.slug()), " * ");

        assertThat(masterReply.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(masterReply.getBody()).isNullOrEmpty();
        assertThat(masterReply.getHeaders().getCacheControl()).isEqualTo(EXPECTED_CACHE_CONTROL);
        assertThat(masterReply.getHeaders().getETag()).as("a 304 always carries the validator of the 200")
                .isNotNull().isEqualTo(masterEtag);
        assertThat(salonReply.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(salonReply.getBody()).isNullOrEmpty();
        assertThat(salonReply.getHeaders().getETag()).isNotNull().isEqualTo(salonEtag);
    }

    @Test
    @DisplayName("If-None-Match: * on an UNKNOWN or inactive page is still 404 (the precondition never masks a missing page)")
    void should_return404_when_ifNoneMatchIsWildcardButPageMissing() {
        IndependentMaster inactive = pages.independentMaster(true);
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", inactive.masterId());

        assertThat(get(masterPath("no-such-master-zz99"), "*").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(masterPath(inactive.slug()), "*").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(salonPath("no-such-salon-zz99"), "*").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("eviction: portfolio photo deleted (DELETE /media/portfolio/{id}) -> photo gone on the next read")
    void should_dropPhoto_when_portfolioPhotoDeleted() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        String token = pages.tokenOf(master);
        UUID mediaId = UUID.randomUUID();
        String key = "portfolio/independent/" + master.masterId() + "/seed-" + mediaId + ".jpg";
        jdbcTemplate.update(
                "INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, r2_url, "
                        + "created_at, updated_at) VALUES (?, ?, 'MASTER', ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                mediaId, master.userId(), master.masterId(), key, "https://cdn.example/" + key);
        ResponseEntity<String> before = get(masterPath(master.slug()), null);
        assertThat(json(before).path("portfolio")).hasSize(1);

        ResponseEntity<String> delete = restTemplate.exchange("/api/v1/media/portfolio/" + mediaId,
                HttpMethod.DELETE, new HttpEntity<>(bearerJson(token)), String.class);
        assertThat(delete.getStatusCode().is2xxSuccessful()).as(delete.getBody()).isTrue();

        ResponseEntity<String> fresh = assertChangedSince(masterPath(master.slug()), before.getHeaders().getETag());
        assertThat(json(fresh).path("portfolio")).isEmpty();
    }
}
