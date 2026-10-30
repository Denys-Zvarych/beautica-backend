package com.beautica.moderation;

import com.beautica.config.TestSecurityConfig;
import com.beautica.review.service.RatingRecalculationService;
import com.beautica.review.repository.ClientReviewRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 396 D3 — every moderation transition recalculates the VISIBLE-only aggregates after commit: master + salon on
 * a client&rarr;provider review, the client's own rating on a provider&rarr;client review. The public master summary
 * (uncached, reads the persisted counters) is the observable surface.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Ratings drop on hide / remove and come back on restore (phase 396 D3)")
class RatingRecalculationOnModerationIT extends AbstractModerationIT {

    @Autowired
    private RatingRecalculationService ratingRecalculationService;

    @Autowired
    private ClientReviewRepository clientReviewRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("5★ + 1★: auto-hide the 1★ → master/salon 5.00 (1 review); RESTORE → 3.00 (2 reviews)")
    void should_dropAndRestoreRatings_when_reviewHiddenThenRestored() throws Exception {
        SalonFixture salon = createSalon();
        insertReview(createUser(unique("happy"), "CLIENT", null), salon, "Чудово", 5);
        UUID badId = insertReview(createUser(unique("author"), "CLIENT", null), salon, "Погано", 1);
        ratingRecalculationService.recalculateMasterRating(salon.masterId());
        ratingRecalculationService.recalculateSalonRating(salon.salonId());
        assertRatings(salon, "3.00", 2);
        EligibleClient firstReporter = eligibleClient(salon);

        reportReview(firstReporter.email(), badId);
        reportReview(eligibleClient(salon).email(), badId);
        reportReview(eligibleClient(salon).email(), badId);
        Map<String, Object> hidden = ratings(salon);
        String hiddenSummary = data(getAnonymous("/api/v1/masters/" + salon.masterId() + "/reviews/summary"))
                .path("avgRating").asText();
        ResponseEntity<String> restored = resolve(reportIdOn(firstReporter.userId(), badId), "RESTORE");

        assertThat((BigDecimal) hidden.get("master_avg")).isEqualByComparingTo("5.00");
        assertThat(((Number) hidden.get("master_count")).intValue()).isEqualTo(1);
        assertThat((BigDecimal) hidden.get("salon_avg")).isEqualByComparingTo("5.00");
        assertThat(((Number) hidden.get("salon_count")).intValue()).isEqualTo(1);
        assertThat(new BigDecimal(hiddenSummary)).isEqualByComparingTo("5.00");
        assertThat(data(restored).path("resolution").asText()).isEqualTo("RESTORED");
        assertRatings(salon, "3.00", 2);
    }

    @Test
    @DisplayName("REMOVE a provider→client review → the client's rating is recalculated without it (null when none left)")
    void should_recalculateClientRating_when_clientReviewRemoved() throws Exception {
        SalonFixture salon = createSalon();
        UUID clientId = createUser(unique("client"), "CLIENT", null);
        UUID clientReviewId = insertClientReview(insertCompletedBooking(clientId, salon), clientId, salon);
        transactionTemplate.executeWithoutResult(s -> clientReviewRepository.recalculateClientRating(clientId));
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM users WHERE id = ?", Integer.class, clientId))
                .as("precondition").isEqualTo(1);
        String indepEmail = unique("indep");
        UUID indepUserId = createIndependentMaster(indepEmail).userId();
        insertIndependentBooking(clientId, jdbcTemplate.queryForObject(
                "SELECT id FROM masters WHERE user_id = ?", UUID.class, indepUserId));
        postReport(tokenFor(indepEmail), reportBody("CLIENT_REVIEW", clientReviewId, "FALSE_INFO", null));

        ResponseEntity<String> removed = resolve(reportIdOn(indepUserId, clientReviewId), "REMOVE");

        assertThat(data(removed).path("resolution").asText()).as("body: %s", removed.getBody()).isEqualTo("REMOVED");
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT avg_rating, review_count FROM users WHERE id = ?", clientId);
        assertThat(((Number) user.get("review_count")).intValue()).isZero();
        assertThat(user.get("avg_rating")).isNull();
    }

    private void assertRatings(SalonFixture salon, String avg, int count) {
        Map<String, Object> row = ratings(salon);
        assertThat((BigDecimal) row.get("master_avg")).isEqualByComparingTo(avg);
        assertThat(((Number) row.get("master_count")).intValue()).isEqualTo(count);
        assertThat((BigDecimal) row.get("salon_avg")).isEqualByComparingTo(avg);
        assertThat(((Number) row.get("salon_count")).intValue()).isEqualTo(count);
    }

    private Map<String, Object> ratings(SalonFixture salon) {
        return jdbcTemplate.queryForMap(
                "SELECT m.avg_rating AS master_avg, m.review_count AS master_count, "
                        + "s.avg_rating AS salon_avg, s.review_count AS salon_count "
                        + "FROM masters m JOIN salons s ON s.id = m.salon_id WHERE m.id = ?",
                salon.masterId());
    }
}
