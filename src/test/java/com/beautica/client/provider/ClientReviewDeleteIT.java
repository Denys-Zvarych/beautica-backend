package com.beautica.client.provider;

import com.beautica.client.provider.ProviderClientFixtures.Independent;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B570 (REV-45, REV-D12, REV-42, MOD-E03) — {@code DELETE /api/v1/client-reviews/{reviewId}} against real Postgres:
 * 204 for each provider role on its own rating (a salon rating is the salon's, whoever wrote it), one 404 for
 * not-own / unknown / repeat / hidden, 403 for a CLIENT, and the after-delete effects (registered aggregate, guest
 * phone aggregate, REV-44 page, {@code providerCanReviewClient}, re-rate 201, archive pending count, reports).
 */
@DisplayName("Client review delete — B570 REV-45")
class ClientReviewDeleteIT extends AbstractClientRatingSubjectIT {

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private UUID rateId(String token, UUID bookingId, int stars, String comment) throws Exception {
        ResponseEntity<String> resp = rate(token, bookingId, stars, comment);
        assertThat(resp.getStatusCode()).as("rate body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(resp.getBody()).path("data").path("id").asText());
    }

    private HttpStatus delete(String token, UUID reviewId) {
        return HttpStatus.valueOf(exchangeAs(token, HttpMethod.DELETE, CLIENT_REVIEWS_URL + "/" + reviewId, null)
                .getStatusCode().value());
    }

    private JsonNode data(String token, String url) throws Exception {
        ResponseEntity<String> resp = fx.get(token, url);
        assertThat(resp.getStatusCode()).as("%s body: %s", url, resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private boolean canReviewFlag(String token, UUID bookingId) throws Exception {
        return data(token, "/api/v1/bookings/" + bookingId).path("providerCanReviewClient").asBoolean();
    }

    private boolean exists(UUID reviewId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM client_reviews WHERE id = ?", Integer.class,
                reviewId) == 1;
    }

    private UUID completed(UUID masterId, UUID salonId, UUID clientId) {
        return fx.booking(masterId, salonId, clientId, "COMPLETED", fx.at(-3));
    }

    // ── 204 per role ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("owner deletes the performing master's rating (204); the flag is true again, the owner re-rates "
            + "201 and the salon pending count includes the booking again in between")
    void should_return204AndFreeBooking_when_ownerDeletesMastersRating() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        UUID clientId = fx.client("Ольга", "К");
        UUID bookingId = completed(s.masterId(), s.salonId(), clientId);
        String ownerToken = fx.token(s.ownerEmail());
        UUID reviewId = rateId(fx.token(s.masterEmail()), bookingId, 2, "Запізнилась");
        String countUrl = "/api/v1/bookings/salon/" + s.salonId() + "/pending-actions/count";
        long pendingRated = data(ownerToken, countUrl).path("toRateClient").asLong();

        HttpStatus status = delete(ownerToken, reviewId);

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exists(reviewId)).as("row deleted").isFalse();
        assertThat(data(ownerToken, countUrl).path("toRateClient").asLong()).as("archive pending count")
                .isEqualTo(pendingRated + 1);
        assertThat(canReviewFlag(ownerToken, bookingId)).as("providerCanReviewClient after delete").isTrue();
        assertThat(rate(ownerToken, bookingId, 5, null).getStatusCode()).as("re-rate").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("the performing salon master deletes the admin's rating, the admin deletes the owner's (204)")
    void should_return204_when_masterOrAdminDeletesColleaguesRating() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String adminToken = fx.token(fx.admin(s.salonId()));
        String ownerToken = fx.token(s.ownerEmail());
        UUID first = completed(s.masterId(), s.salonId(), fx.client("Ірина", "А"));
        UUID second = completed(s.masterId(), s.salonId(), fx.client("Марта", "Б"));
        UUID adminReview = rateId(adminToken, first, 3, "Адмін");
        UUID ownerReview = rateId(ownerToken, second, 4, "Власниця");

        HttpStatus byMaster = delete(fx.token(s.masterEmail()), adminReview);
        HttpStatus byAdmin = delete(adminToken, ownerReview);

        assertThat(byMaster).as("master deletes admin's").isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(byAdmin).as("admin deletes owner's").isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exists(adminReview)).isFalse();
        assertThat(exists(ownerReview)).isFalse();
    }

    @Test
    @DisplayName("independent master deletes their own rating (204); another independent master gets 404")
    void should_return204_when_independentMasterDeletesOwnRating() throws Exception {
        Independent im = fx.independent();
        Independent other = fx.independent();
        UUID reviewId = rateId(fx.token(im.email()), completed(im.masterId(), null, fx.client("Ліна", "В")), 4, "Ок");

        HttpStatus byOther = delete(fx.token(other.email()), reviewId);
        HttpStatus byOwn = delete(fx.token(im.email()), reviewId);

        assertThat(byOther).as("another independent master").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(byOwn).as("own").isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── 404 / 403 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("404 for an independent master on a salon rating (the salon owns it, REV-D12); the row stays")
    void should_return404_when_independentMasterDeletesSalonRating() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        Independent im = fx.independent();
        UUID reviewId = rateId(fx.token(s.ownerEmail()), completed(s.masterId(), s.salonId(), fx.client("Дана", "М")),
                3, "Салонна");

        HttpStatus status = delete(fx.token(im.email()), reviewId);

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exists(reviewId)).isTrue();
    }

    @Test
    @DisplayName("two concurrent deletes of the same rating by the owner: exactly one 204 and one 404 (row lock), "
            + "never a 500")
    void should_returnOne204AndOne404_when_concurrentDoubleDelete() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String ownerToken = fx.token(s.ownerEmail());
        UUID reviewId = rateId(ownerToken, completed(s.masterId(), s.salonId(), fx.client("Поля", "Н")), 2, "Двічі");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<HttpStatus> statuses;
        try {
            Future<HttpStatus> a = pool.submit(() -> { start.await(); return delete(ownerToken, reviewId); });
            Future<HttpStatus> b = pool.submit(() -> { start.await(); return delete(ownerToken, reviewId); });

            start.countDown();
            statuses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).as("both outcomes").containsExactlyInAnyOrder(HttpStatus.NO_CONTENT, HttpStatus.NOT_FOUND);
        assertThat(exists(reviewId)).isFalse();
    }

    @Test
    @DisplayName("404 for another salon's owner/admin/master, an unknown id and a repeat delete; 403 for a CLIENT; "
            + "the denied calls delete nothing")
    void should_return404Or403_when_notOwnUnknownRepeatOrClient() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        ProviderClientFixtures.Salon other = fx.salon();
        UUID clientId = fx.client("Тетяна", "Г");
        UUID reviewId = rateId(fx.token(s.ownerEmail()), completed(s.masterId(), s.salonId(), clientId), 3, "Текст");
        String clientToken = fx.token(jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, clientId));

        assertThat(delete(fx.token(other.ownerEmail()), reviewId)).as("other owner").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(fx.token(fx.admin(other.salonId())), reviewId)).as("other admin")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(fx.token(other.masterEmail()), reviewId)).as("other master").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(clientToken, reviewId)).as("client").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete(fx.token(s.ownerEmail()), UUID.randomUUID())).as("unknown").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exists(reviewId)).as("nothing deleted by the denied calls").isTrue();
        assertThat(delete(fx.token(s.ownerEmail()), reviewId)).as("own").isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(fx.token(s.ownerEmail()), reviewId)).as("repeat").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("404 for a salon master of the SAME salon who did not perform the booking; the row stays")
    void should_return404_when_colleagueSalonMasterDeletes() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        UUID reviewId = rateId(fx.token(s.ownerEmail()), completed(s.masterId(), s.salonId(), fx.client("Ніна", "К")),
                3, "Колега");
        String colleagueEmail = (String) fx.secondMaster(s.salonId(), "Колега")[1];

        HttpStatus status = delete(fx.token(colleagueEmail), reviewId);

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exists(reviewId)).isTrue();
    }

    @Test
    @DisplayName("404 for the performing salon master once their master row is deactivated (live token); the row stays")
    void should_return404_when_performingSalonMasterDeactivated() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String masterToken = fx.token(s.masterEmail());
        UUID reviewId = rateId(masterToken, completed(s.masterId(), s.salonId(), fx.client("Оксана", "Л")), 2, "Своя");
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", s.masterId());

        HttpStatus status = delete(masterToken, reviewId);

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exists(reviewId)).isTrue();
    }

    @Test
    @DisplayName("404 for the owner's own rating while it is moderation-hidden; the row stays")
    void should_return404_when_reviewHidden() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String ownerToken = fx.token(s.ownerEmail());
        UUID reviewId = rateId(ownerToken, completed(s.masterId(), s.salonId(), fx.client("Віра", "Д")), 1, "Прихов");
        jdbcTemplate.update("UPDATE client_reviews SET moderation_status = 'HIDDEN_PENDING' WHERE id = ?", reviewId);

        HttpStatus status = delete(ownerToken, reviewId);

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exists(reviewId)).isTrue();
    }

    // ── after-delete effects ─────────────────────────────────────────────────

    @Test
    @DisplayName("registered: 5★ + 1★, delete the 1★ → users.avg_rating = 5.00, review_count = 1, and the REV-44 "
            + "comments page drops the row")
    void should_recomputeAggregateAndDropComment_when_registeredRatingDeleted() throws Exception {
        Independent im = fx.independent();
        String token = fx.token(im.email());
        UUID clientId = fx.client("Аня", "Е");
        rateId(token, completed(im.masterId(), null, clientId), 5, "Чудово");
        UUID low = rateId(token, completed(im.masterId(), null, clientId), 1, "Не прийшла вчасно");
        String commentsUrl = "/api/v1/masters/me/clients/users/" + clientId + "/comments";
        assertThat(data(token, commentsUrl).path("totalElements").asLong()).as("premise: two comments").isEqualTo(2);

        assertThat(delete(token, low)).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> agg = jdbcTemplate.queryForMap(
                "SELECT avg_rating, review_count FROM users WHERE id = ?", clientId);
        assertThat((BigDecimal) agg.get("avg_rating")).isEqualByComparingTo("5.00");
        assertThat(((Number) agg.get("review_count")).intValue()).isEqualTo(1);
        JsonNode page = data(token, commentsUrl);
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        assertThat(page.path("data").toString()).doesNotContain(low.toString());
    }

    @Test
    @DisplayName("registered: 3★ + 1★, delete the 1★ and re-rate that booking 5★ → the new rating counts: "
            + "avg_rating = 4.00, review_count = 2")
    void should_countNewRatingInAggregate_when_deletedBookingReRated() throws Exception {
        Independent im = fx.independent();
        String token = fx.token(im.email());
        UUID clientId = fx.client("Галя", "П");
        rateId(token, completed(im.masterId(), null, clientId), 3, "Нормально");
        UUID bookingId = completed(im.masterId(), null, clientId);
        UUID low = rateId(token, bookingId, 1, "Погано");
        assertThat(delete(token, low)).isEqualTo(HttpStatus.NO_CONTENT);

        UUID fresh = rateId(token, bookingId, 5, "Перепрошую, все добре");

        Map<String, Object> agg = jdbcTemplate.queryForMap(
                "SELECT avg_rating, review_count FROM users WHERE id = ?", clientId);
        assertThat(fresh).as("a new row, not the deleted one").isNotEqualTo(low);
        assertThat((BigDecimal) agg.get("avg_rating")).isEqualByComparingTo("4.00");
        assertThat(((Number) agg.get("review_count")).intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("guest phone: 5★ + 1★, delete the 1★ → the card's computed rating is 5 over 1")
    void should_moveGuestAggregate_when_guestRatingDeleted() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String ownerToken = fx.token(s.ownerEmail());
        String phone = ProviderClientFixtures.freshPhone("67");
        UUID anchor = completedGuest(s, "LINK", phone);
        rateId(ownerToken, anchor, 5, null);
        UUID low = rateId(ownerToken, completedGuest(s, "STAFF", phone), 1, "Не прийшла");
        assertThat(data(ownerToken, guestCardUrl(s.salonId(), anchor)).path("ratingCount").asInt())
                .as("premise").isEqualTo(2);

        assertThat(delete(ownerToken, low)).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode card = data(ownerToken, guestCardUrl(s.salonId(), anchor));
        assertThat(card.path("ratingCount").asInt()).isEqualTo(1);
        assertThat(card.path("ratingAvg").decimalValue()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("an OPEN report on the deleted rating is closed TARGET_GONE with target_gone_at set (MOD-E03)")
    void should_closeOpenReportAsTargetGone_when_ratingDeleted() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String ownerToken = fx.token(s.ownerEmail());
        UUID reviewId = rateId(ownerToken, completed(s.masterId(), s.salonId(), fx.client("Зоя", "Ж")), 2, "Скарга");
        UUID reportId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO content_reports (id, reporter_user_id, target_type, target_id, reason, "
                + "created_at, updated_at) VALUES (?, ?, 'CLIENT_REVIEW', ?, 'SPAM', NOW(), NOW())",
                reportId, fx.client("Репортер", "З"), reviewId);

        assertThat(delete(ownerToken, reviewId)).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> report = jdbcTemplate.queryForMap(
                "SELECT status, resolution, target_gone_at FROM content_reports WHERE id = ?", reportId);
        assertThat(report.get("status")).isEqualTo("RESOLVED");
        assertThat(report.get("resolution")).isEqualTo("TARGET_GONE");
        assertThat(report.get("target_gone_at")).isNotNull();
    }

    @Test
    @DisplayName("deleting the rating revokes every live e-mail action link on it; another target's link stays live "
            + "(MOD-E03)")
    void should_revokeActionLinks_when_ratingDeleted() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String ownerToken = fx.token(s.ownerEmail());
        UUID reviewId = rateId(ownerToken, completed(s.masterId(), s.salonId(), fx.client("Рита", "С")), 1, "Лінк");
        UUID ownLink = insertActionToken(reviewId);
        UUID otherLink = insertActionToken(UUID.randomUUID());

        assertThat(delete(ownerToken, reviewId)).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(revokedAt(ownLink)).as("link on the deleted rating").isNotNull();
        assertThat(revokedAt(otherLink)).as("link on another target").isNull();
    }

    private UUID insertActionToken(UUID targetId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO moderation_action_tokens (id, target_type, target_id, action, token_hash, "
                        + "expires_at) VALUES (?, 'CLIENT_REVIEW', ?, 'REMOVE', ?, NOW() + INTERVAL '1 day')",
                id, targetId, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""));
        return id;
    }

    private Object revokedAt(UUID tokenId) {
        return jdbcTemplate.queryForObject("SELECT revoked_at FROM moderation_action_tokens WHERE id = ?",
                Object.class, tokenId);
    }
}
