package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.review.repository.ReviewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3's whole point, proven against a real render: a detached client's review SURVIVES with its
 * rating/comment intact, renders under the neutral sentinel on the master's PUBLIC review list,
 * and — the numerically falsifiable part — the master's {@code avgRating}/{@code reviewCount}
 * (as served by {@code GET /masters/{id}/reviews/summary}, backed by the persisted
 * {@code masters.avg_rating}/{@code review_count} columns, never a live re-aggregation) are
 * IDENTICAL before and after the delete. A fixture where the aggregate started at its zero
 * default would let this assertion pass vacuously against an untouched masters row — recalculating
 * before the delete (mirroring {@code SalonStaffHardDeleteIT} case 4) closes that hole.
 */
@DisplayName("GET /masters/{id}/reviews[/summary] — a detached review renders under the sentinel "
        + "with the rating aggregate untouched (Phase 300 D3)")
class DetachedReviewAuthorRenderIT extends AbstractIntegrationTest {

    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("avgRating/reviewCount are numerically unchanged, the review's rating/comment "
            + "survive, the sentinel author replaces the client, and the real name «Оксана» never "
            + "appears anywhere in the response body")
    void should_renderSentinelAuthor_and_leaveRatingAggregateUnchanged_when_reviewerSelfDeletes()
            throws Exception {
        UUID clientId = csd.createClient(); // real first name "Оксана" — see fixture javadoc
        String token = fixtures.tokenFor(emailOf(clientId));
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID bookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        String comment = "Чудовий майстер, дуже задоволена!";
        csd.insertReview(bookingId, clientId, salon.masterId(), salon.salonId(), 5, comment);
        tx.executeWithoutResult(s -> reviewRepository.recalculateMasterRating(salon.masterId()));

        // Fixture check — the aggregate genuinely MOVED off its zero default before the delete.
        JsonNode beforeSummary = getSummary(salon.masterId());
        assertThat(beforeSummary.path("reviewCount").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(beforeSummary.path("avgRating").asText()))
                .isEqualByComparingTo(new BigDecimal("5.00"));

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        ResponseEntity<String> listResponse = restTemplate.getForEntity(
                "/api/v1/masters/" + salon.masterId() + "/reviews", String.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody())
                .as("the real first name must appear NOWHERE in the public review payload")
                .doesNotContain("Оксана");
        JsonNode reviewRow = objectMapper.readTree(listResponse.getBody())
                .path("data").path("data").get(0);
        assertThat(reviewRow.path("clientDisplayName").asText()).isEqualTo("Видалений клієнт");
        assertThat(reviewRow.path("rating").asInt())
                .as("the rating itself survives untouched")
                .isEqualTo(5);
        assertThat(reviewRow.path("comment").asText())
                .as("the comment survives untouched")
                .isEqualTo(comment);

        JsonNode afterSummary = getSummary(salon.masterId());
        assertThat(afterSummary.path("reviewCount").asInt())
                .as("D3 — the detach must not change the review count")
                .isEqualTo(1);
        assertThat(new BigDecimal(afterSummary.path("avgRating").asText()))
                .as("D3 — the detach must not change the average rating")
                .isEqualByComparingTo(new BigDecimal("5.00"));
    }

    private JsonNode getSummary(UUID masterId) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/masters/" + masterId + "/reviews/summary", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
