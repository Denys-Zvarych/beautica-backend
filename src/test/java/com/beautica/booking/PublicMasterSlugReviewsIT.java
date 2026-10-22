package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.PublicBookingPageFixtures.IndependentMaster;
import com.beautica.config.TestSecurityConfig;
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
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 383 D6/D7 — {@code GET /api/v1/book/{slug}/reviews} over real HTTP: the four sort orders, paging, the size
 * clamp to 20, the error matrix, the info-identical cache headers with an ETag/304 round-trip, and a review item
 * that exposes nothing beyond the app's own {@code ReviewResponse}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /book/{slug}/reviews — sortable review page for the master link (Phase 383)")
class PublicMasterSlugReviewsIT extends AbstractIntegrationTest {

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
        for (String cache : List.of("booking-slug-info", "reviews-by-master")) {
            cacheManager.getCache(cache).clear();
        }
    }

    private ResponseEntity<String> get(String query, String slug, String ifNoneMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        if (ifNoneMatch != null) {
            headers.set(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        return restTemplate.exchange("/api/v1/book/" + slug + "/reviews" + query, HttpMethod.GET,
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

    /** Ratings by age: 10 min -> 3, 20 min -> 5, 30 min -> 1, so every sort yields a different order. */
    private IndependentMaster masterWithThreeReviews() {
        IndependentMaster master = pages.independentMaster(true);
        UUID client = pages.insertClient();
        pages.insertReview(master, client, 3, 10);
        pages.insertReview(master, client, 5, 20);
        pages.insertReview(master, client, 1, 30);
        return master;
    }

    @Test
    @DisplayName("each of the four sorts returns its own order")
    void should_orderReviews_when_eachSortRequested() throws Exception {
        IndependentMaster master = masterWithThreeReviews();

        assertThat(ratings(data(get("?sort=NEWEST", master.slug(), null)))).containsExactly(3, 5, 1);
        assertThat(ratings(data(get("?sort=OLDEST", master.slug(), null)))).containsExactly(1, 5, 3);
        assertThat(ratings(data(get("?sort=HIGHEST", master.slug(), null)))).containsExactly(5, 3, 1);
        assertThat(ratings(data(get("?sort=LOWEST", master.slug(), null)))).containsExactly(1, 3, 5);
        assertThat(ratings(data(get("", master.slug(), null)))).as("default sort is NEWEST").containsExactly(3, 5, 1);
    }

    @Test
    @DisplayName("paging: page 1 of 25 reviews holds the remaining 5; size above 20 is clamped to 20")
    void should_pageAndClampSize_when_manyReviews() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID client = pages.insertClient();
        for (int i = 1; i <= 25; i++) {
            pages.insertReview(master, client, 4, i);
        }

        JsonNode first = data(get("?page=0&size=500", master.slug(), null));
        JsonNode second = data(get("?page=1&size=20", master.slug(), null));

        assertThat(first.path("data")).hasSize(20);
        assertThat(first.path("size").asInt()).isEqualTo(20);
        assertThat(first.path("totalElements").asLong()).isEqualTo(25);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(second.path("data")).hasSize(5);
    }

    @Test
    @DisplayName("an unknown sort is 400; an unknown or inactive slug is 404 with no public cache header")
    void should_rejectBadSortAndUnknownSlug_when_requested() throws Exception {
        IndependentMaster master = masterWithThreeReviews();
        IndependentMaster inactive = pages.independentMaster(true);
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", inactive.masterId());

        ResponseEntity<String> badSort = get("?sort=BOGUS", master.slug(), null);
        ResponseEntity<String> unknown = get("", "no-such-master-zz99", null);
        ResponseEntity<String> inactiveReply = get("", inactive.slug(), null);

        assertThat(badSort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inactiveReply.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getHeaders().getCacheControl()).contains("no-store").doesNotContain("public");
    }

    @Test
    @DisplayName("exact Cache-Control + weak ETag; replay with If-None-Match -> 304 with the same validator")
    void should_sendInfoCacheHeadersAndAnswer304_when_ifNoneMatchMatches() throws Exception {
        IndependentMaster master = masterWithThreeReviews();

        ResponseEntity<String> first = get("?sort=HIGHEST", master.slug(), null);
        String etag = first.getHeaders().getETag();
        ResponseEntity<String> replay = get("?sort=HIGHEST", master.slug(), etag);
        ResponseEntity<String> otherSort = get("?sort=LOWEST", master.slug(), etag);

        assertThat(first.getHeaders().getCacheControl()).isEqualTo(EXPECTED_CACHE_CONTROL);
        assertThat(etag).matches("W/\"0[0-9a-f]{32}\"");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(replay.getBody()).isNullOrEmpty();
        assertThat(replay.getHeaders().getETag()).isEqualTo(etag);
        assertThat(otherSort.getStatusCode()).as("a different representation never matches the stale validator")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a review item exposes exactly the app's ReviewResponse keys - no client id, phone, email or surname")
    void should_exposeOnlyAllowListedKeys_when_reviewItemRendered() throws Exception {
        IndependentMaster master = masterWithThreeReviews();

        ResponseEntity<String> response = get("", master.slug(), null);
        JsonNode item = data(response).path("data").get(0);

        Set<String> keys = new HashSet<>();
        Iterator<String> names = item.fieldNames();
        names.forEachRemaining(keys::add);
        assertThat(keys).isEqualTo(Set.of("id", "masterId", "clientDisplayName", "serviceName", "rating", "comment",
                "createdAt"));
        assertThat(item.path("clientDisplayName").asText()).isEqualTo("Ірина К.");
        assertThat(response.getBody()).doesNotContain("Кравчук").doesNotContain("@beautica.test")
                .doesNotContain(PublicBookingPageFixtures.SEEDED_PHONE);
    }

    @Test
    @DisplayName("a master that is not bookable still returns its reviews (as /info renders them)")
    void should_returnReviews_when_masterNotBookable() throws Exception {
        IndependentMaster master = masterWithThreeReviews();
        jdbcTemplate.update("UPDATE master_services SET is_active = false WHERE master_id = ?", master.masterId());

        ResponseEntity<String> info = restTemplate.exchange("/api/v1/book/" + master.slug() + "/info", HttpMethod.GET,
                new HttpEntity<>(xff()), String.class);
        JsonNode reviews = data(get("", master.slug(), null));

        assertThat(objectMapper.readTree(info.getBody()).path("bookable").asBoolean()).isFalse();
        assertThat(reviews.path("data")).hasSize(3);
    }

    private HttpHeaders xff() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        return headers;
    }
}
