package com.beautica.moderation;

import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 396 D3 — a hidden review leaves every public list and summary (including the CACHED list pages, which the
 * moderation event evicts), but stays in its author's own {@code GET /reviews/me}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Hidden reviews are invisible publicly, visible to the author (phase 396 D3)")
class ReviewVisibilityFilterIT extends AbstractModerationIT {

    @Test
    @DisplayName("auto-hidden review: absent from master + salon lists and summaries; present in the author's /reviews/me")
    void should_excludeHiddenReviewPublicly_and_keepItForAuthor() throws Exception {
        SalonFixture salon = createSalon();
        UUID keptId = insertReview(createUser(unique("happy"), "CLIENT", null), salon, "Чудово", 5);
        String authorEmail = unique("author");
        UUID hiddenId = insertReview(createUser(authorEmail, "CLIENT", null), salon, "Погано", 1);
        String masterList = "/api/v1/masters/" + salon.masterId() + "/reviews";
        String salonList = "/api/v1/salons/" + salon.salonId() + "/reviews";
        assertThat(reviewIds(getAnonymous(masterList))).as("precondition — and caches the page")
                .containsExactlyInAnyOrder(keptId, hiddenId);
        assertThat(reviewIds(getAnonymous(salonList))).as("precondition — and caches the page")
                .containsExactlyInAnyOrder(keptId, hiddenId);

        for (int i = 0; i < 3; i++) {
            reportReview(eligibleClient(salon).email(), hiddenId);
        }

        assertThat(moderationStatus(hiddenId)).isEqualTo("HIDDEN_PENDING");
        assertThat(reviewIds(getAnonymous(masterList))).containsExactly(keptId);
        assertThat(data(getAnonymous(masterList)).path("totalElements").asInt()).isEqualTo(1);
        assertThat(reviewIds(getAnonymous(salonList))).containsExactly(keptId);
        for (String sort : List.of("OLDEST", "HIGHEST", "LOWEST")) {
            assertThat(reviewIds(getAnonymous(masterList + "?sort=" + sort))).as("master %s", sort).containsExactly(keptId);
            assertThat(reviewIds(getAnonymous(salonList + "?sort=" + sort))).as("salon %s", sort).containsExactly(keptId);
        }
        assertSummaryWithoutOneStar(data(getAnonymous(masterList + "/summary")));
        assertSummaryWithoutOneStar(data(getAnonymous("/api/v1/salons/" + salon.salonId() + "/reviews/summary")));
        assertThat(myReviewIds(tokenFor(authorEmail))).containsExactly(hiddenId);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"HIDDEN_PENDING", "REMOVED"})
    @DisplayName("hidden or removed review (D23): the author's GET /bookings/{id} drops reviewByClient (no body) and "
            + "canReview stays false — the row still occupies the booking")
    void should_omitReviewBody_when_reviewNotVisible(String moderationStatus) throws Exception {
        SalonFixture salon = createSalon();
        String authorEmail = unique("author");
        UUID reviewId = insertReview(createUser(authorEmail, "CLIENT", null), salon, "Образливий текст", 1);
        UUID bookingId = jdbcTemplate.queryForObject("SELECT booking_id FROM reviews WHERE id = ?", UUID.class, reviewId);
        String token = tokenFor(authorEmail);
        JsonNode visible = bookingDetail(token, bookingId);

        jdbcTemplate.update("UPDATE reviews SET moderation_status = ? WHERE id = ?", moderationStatus, reviewId);
        JsonNode moderated = bookingDetail(token, bookingId);

        assertThat(visible.path("reviewByClient").path("comment").asText()).as("precondition").isEqualTo("Образливий текст");
        assertThat(visible.path("canReview").asBoolean()).isFalse();
        assertThat(moderated.path("reviewByClient").isNull() || moderated.path("reviewByClient").isMissingNode())
                .as("no rating, no comment: %s", moderated.path("reviewByClient")).isTrue();
        assertThat(moderated.toString()).doesNotContain("Образливий текст");
        assertThat(moderated.path("canReview").asBoolean()).as("a moderated review can never be rewritten").isFalse();
    }

    private JsonNode bookingDetail(String token, UUID bookingId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return data(resp);
    }

    private static void assertSummaryWithoutOneStar(JsonNode summary) {
        assertThat(summary.path("reviewCount").asInt()).isEqualTo(1);
        assertThat(summary.path("avgRating").decimalValue()).isEqualByComparingTo("5");
        for (JsonNode bucket : summary.path("ratingDistribution")) {
            long expected = bucket.path("rating").asInt() == 5 ? 1 : 0;
            assertThat(bucket.path("count").asLong()).as("bucket %s", bucket.path("rating").asInt()).isEqualTo(expected);
        }
    }

    private List<UUID> myReviewIds(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/reviews/me", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return reviewIds(resp);
    }

    private List<UUID> reviewIds(ResponseEntity<String> resp) {
        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.OK);
        List<UUID> ids = new ArrayList<>();
        data(resp).path("data").forEach(row -> ids.add(UUID.fromString(row.path("id").asText())));
        return ids;
    }
}
