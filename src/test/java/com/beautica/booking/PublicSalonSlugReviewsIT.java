package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.review.dto.SalonReviewResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.beautica.support.BookableMasterSeeder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 384 D4 — {@code GET /api/v1/book/s/{slug}/reviews}: the same matrix as the master route
 * ({@link PublicMasterSlugReviewsIT}) for {@code SalonReviewResponse}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /book/s/{slug}/reviews — sortable review page for the salon link (Phase 384)")
class PublicSalonSlugReviewsIT extends AbstractIntegrationTest {

    private static final String EXPECTED_CACHE_CONTROL = "max-age=0, public, s-maxage=30";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CacheManager cacheManager;

    private PublicBookingPageFixtures pages;
    private String clientIp;

    @BeforeEach
    void setUp() {
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        clientIp = "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
        for (String cache : List.of("salon-booking-slug-info", "reviews-by-salon")) {
            cacheManager.getCache(cache).clear();
        }
    }

    private ResponseEntity<String> get(String query, String slug, String ifNoneMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        if (ifNoneMatch != null) {
            headers.set(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        return restTemplate.exchange("/api/v1/book/s/" + slug + "/reviews" + query, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private static List<Integer> ratings(JsonNode page) {
        List<Integer> out = new ArrayList<>();
        page.path("data").forEach(r -> out.add(r.path("rating").asInt()));
        return out;
    }

    private record Fixture(PublicBookingPageFixtures.SalonPage salon, UUID masterId, UUID client) {
    }

    private Fixture salonWithMaster() throws Exception {
        PublicBookingPageFixtures.SalonPage salon = pages.salon("Reviews Salon " + System.nanoTime());
        UUID master = pages.salonMaster(salon.salonId(), "Anna", "Bila");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), master);
        return new Fixture(salon, master, pages.insertClient());
    }

    /** Ratings by age: 10 min -> 3, 20 min -> 5, 30 min -> 1, so every sort yields a different order. */
    private Fixture salonWithThreeReviews() throws Exception {
        Fixture f = salonWithMaster();
        pages.insertSalonReview(f.salon().salonId(), f.masterId(), f.client(), 3, 10);
        pages.insertSalonReview(f.salon().salonId(), f.masterId(), f.client(), 5, 20);
        pages.insertSalonReview(f.salon().salonId(), f.masterId(), f.client(), 1, 30);
        return f;
    }

    @Test
    @DisplayName("each of the four sorts returns its own order")
    void should_orderReviews_when_eachSortRequested() throws Exception {
        String slug = salonWithThreeReviews().salon().slug();

        assertThat(ratings(data(get("?sort=NEWEST", slug, null)))).containsExactly(3, 5, 1);
        assertThat(ratings(data(get("?sort=OLDEST", slug, null)))).containsExactly(1, 5, 3);
        assertThat(ratings(data(get("?sort=HIGHEST", slug, null)))).containsExactly(5, 3, 1);
        assertThat(ratings(data(get("?sort=LOWEST", slug, null)))).containsExactly(1, 3, 5);
        assertThat(ratings(data(get("", slug, null)))).as("default NEWEST").containsExactly(3, 5, 1);
    }

    @Test
    @DisplayName("paging: page 1 of 25 holds 5; size above 20 is clamped to 20")
    void should_pageAndClampSize_when_manyReviews() throws Exception {
        Fixture f = salonWithMaster();
        for (int i = 1; i <= 25; i++) {
            pages.insertSalonReview(f.salon().salonId(), f.masterId(), f.client(), 4, i);
        }

        JsonNode first = data(get("?page=0&size=500", f.salon().slug(), null));
        JsonNode second = data(get("?page=1&size=20", f.salon().slug(), null));

        assertThat(first.path("data")).hasSize(20);
        assertThat(first.path("size").asInt()).isEqualTo(20);
        assertThat(first.path("totalElements").asLong()).isEqualTo(25);
        assertThat(second.path("data")).hasSize(5);
    }

    @Test
    @DisplayName("bad sort is 400; unknown or inactive salon is 404 with no public cache header")
    void should_rejectBadSortAndUnknownSlug_when_requested() throws Exception {
        Fixture f = salonWithThreeReviews();
        PublicBookingPageFixtures.SalonPage inactive = pages.salon("Inactive Reviews Salon");
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", inactive.salonId());

        assertThat(get("?sort=BOGUS", f.salon().slug(), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> unknown = get("", "no-such-salon-zz99", null);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getHeaders().getCacheControl()).contains("no-store").doesNotContain("public");
        assertThat(get("", inactive.slug(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("exact Cache-Control + weak ETag; If-None-Match replay -> 304 with the same validator")
    void should_sendInfoCacheHeadersAndAnswer304_when_ifNoneMatchMatches() throws Exception {
        String slug = salonWithThreeReviews().salon().slug();

        ResponseEntity<String> first = get("?sort=HIGHEST", slug, null);
        String etag = first.getHeaders().getETag();
        ResponseEntity<String> replay = get("?sort=HIGHEST", slug, etag);

        assertThat(first.getHeaders().getCacheControl()).isEqualTo(EXPECTED_CACHE_CONTROL);
        assertThat(etag).matches("W/\"0[0-9a-f]{32}\"");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(replay.getBody()).isNullOrEmpty();
        assertThat(replay.getHeaders().getETag()).isEqualTo(etag);
    }

    @Test
    @DisplayName("a review item exposes exactly the app's SalonReviewResponse keys - no client id, phone, email or surname")
    void should_exposeOnlyAllowListedKeys_when_reviewItemRendered() throws Exception {
        Fixture f = salonWithThreeReviews();

        ResponseEntity<String> response = get("", f.salon().slug(), null);
        JsonNode item = data(response).path("data").get(0);

        Set<String> keys = new HashSet<>();
        item.fieldNames().forEachRemaining(keys::add);
        Set<String> expected = new HashSet<>();
        for (var component : SalonReviewResponse.class.getRecordComponents()) {
            expected.add(component.getName());
        }
        assertThat(keys).isEqualTo(expected);
        assertThat(item.path("clientDisplayName").asText()).isEqualTo("Ірина К.");
        assertThat(response.getBody()).doesNotContain("Кравчук").doesNotContain("@beautica.test")
                .doesNotContain(f.client().toString());
    }

    @Test
    @DisplayName("eviction: a new review is visible on the next /reviews read (reviews-by-salon eviction reaches the route)")
    void should_listNewReview_when_reviewCreatedEventCommits() throws Exception {
        Fixture f = salonWithMaster();
        assertThat(data(get("", f.salon().slug(), null)).path("data")).isEmpty();

        pages.insertSalonReview(f.salon().salonId(), f.masterId(), f.client(), 4, 1);
        UUID masterUserId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class,
                f.masterId());
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> eventPublisher.publishEvent(new com.beautica.review.event.ReviewCreatedEvent(
                f.masterId(), masterUserId, f.salon().salonId(), f.client())));

        assertThat(data(get("", f.salon().slug(), null)).path("data")).hasSize(1);
    }

    @Autowired private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    // ── phase 383/384 audit L1: bounded cache keys on the public reviews route ────────────────

    private static boolean hasKeyContaining(org.springframework.cache.Cache cache, String fragment) {
        @SuppressWarnings("unchecked")
        java.util.Map<Object, Object> entries =
                ((com.github.benmanes.caffeine.cache.Cache<Object, Object>) cache.getNativeCache()).asMap();
        return entries.keySet().stream().anyMatch(key -> String.valueOf(key).contains(fragment));
    }

    @Test
    @DisplayName("should_notCacheEmptyDeepPage_when_pageBeyondLastPage - 200 with the real total, and NO new cache key")
    void should_notCacheEmptyDeepPage_when_pageBeyondLastPage() throws Exception {
        var slug = salonWithThreeReviews().salon().slug();
        var cache = cacheManager.getCache("reviews-by-salon");

        JsonNode deep = data(get("?page=7&size=20", slug, null));
        data(get("?page=0&size=20", slug, null));

        assertThat(deep.path("data")).isEmpty();
        assertThat(deep.path("totalElements").asLong()).isEqualTo(3);
        assertThat(hasKeyContaining(cache, ":page:7:")).as("deep empty page must not be cached").isFalse();
        assertThat(hasKeyContaining(cache, ":page:0:")).as("non-vacuity: a real page IS cached").isTrue();
    }

    @Test
    @DisplayName("should_reject400_when_pageAboveTheCap - page 10 is allowed (empty), page 11 is a 400 that creates no cache key")
    void should_reject400_when_pageAboveTheCap() throws Exception {
        var slug = salonWithThreeReviews().salon().slug();
        var cache = cacheManager.getCache("reviews-by-salon");

        ResponseEntity<String> atCap = get("?page=10", slug, null);
        ResponseEntity<String> overCap = get("?page=11", slug, null);

        assertThat(atCap.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(overCap.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(hasKeyContaining(cache, ":page:11:")).isFalse();
    }
}
