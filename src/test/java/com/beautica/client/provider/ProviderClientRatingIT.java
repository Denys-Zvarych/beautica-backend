package com.beautica.client.provider;

import com.beautica.AbstractIntegrationTest;
import com.beautica.client.provider.ProviderClientFixtures.Independent;
import com.beautica.client.provider.ProviderClientFixtures.Salon;
import com.beautica.auth.TokensValidAfterCache;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.common.security.PhoneHasher;
import com.beautica.support.HibernateStatistics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B526 — client card rating count + distribution (cross-provider) and the viewer's own ratings list (CLI-43, CLI-59,
 * REV-36) against real Postgres. Seeding via {@link ProviderClientFixtures}; ratings aggregate through the production recalculation.
 */
@DisplayName("ProviderClient rating detail — B526")
class ProviderClientRatingIT extends AbstractIntegrationTest {

    private static final String SALON_URL = "/api/v1/salons/%s/clients";
    private static final String ME_URL = "/api/v1/masters/me/clients";
    private static final String MARKER = "УНІКАЛЬНИЙ-КОМЕНТАР-МАРКЕР";
    private static final String GUEST_PHONE = "+380501112233";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private ClientReviewRepository clientReviewRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TokensValidAfterCache tokensValidAfterCache;

    private ProviderClientFixtures fx;

    @BeforeEach
    void setUpFixtures() {
        fx = new ProviderClientFixtures(jdbcTemplate, restTemplate, objectMapper, phoneHasher, this::testCityId,
                this::resolveUnusedServiceTypeId);
    }

    // ── seeding ──────────────────────────────────────────────────────────────

    /** Inserts the review, then recomputes users.avg_rating / review_count through the production recalculation. */
    private void rate(UUID bookingId, UUID clientId, UUID authorMasterId, UUID salonId, int rating, String moderation) {
        fx.clientReview(bookingId, clientId, authorMasterId, salonId, rating, MARKER, moderation);
        transactionTemplate.executeWithoutResult(st -> clientReviewRepository.recalculateClientRating(clientId));
    }

    /** Logs in and primes TokensValidAfterCache for that user so every measured request is warm (no extra read). */
    private String warmToken(String email) throws Exception {
        String token = fx.token(email);
        tokensValidAfterCache.get(fx.userIdOf(email));
        return token;
    }

    private UUID completed(UUID masterId, UUID salonId, UUID clientId, int daysAgo) {
        return fx.booking(masterId, salonId, clientId, "COMPLETED", fx.at(-daysAgo));
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private String salonUser(UUID salonId, UUID userId) {
        return SALON_URL.formatted(salonId) + "/users/" + userId;
    }

    private String salonGuest(UUID salonId, UUID bookingId) {
        return SALON_URL.formatted(salonId) + "/guests/" + bookingId;
    }

    private JsonNode ok(ResponseEntity<String> resp) throws Exception {
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private static List<String> buckets(JsonNode distribution) {
        List<String> out = new ArrayList<>();
        distribution.forEach(b -> out.add(b.path("rating").asInt() + ":" + b.path("count").asLong()));
        return out;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(out::add);
        return out;
    }

    private static List<UUID> bookingIds(JsonNode page) {
        List<UUID> out = new ArrayList<>();
        page.path("data").forEach(r -> out.add(UUID.fromString(r.path("bookingId").asText())));
        return out;
    }

    private String withoutRequestId(ResponseEntity<String> resp) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode body =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(resp.getBody());
        body.remove("requestId");
        return body.toString();
    }

    private long statementsFor(String token, String url) {
        Statistics statistics = HibernateStatistics.enabledOn(entityManagerFactory);
        long before = statistics.getPrepareStatementCount();
        ResponseEntity<String> resp = fx.get(token, url);
        long used = statistics.getPrepareStatementCount() - before;
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        return used;
    }

    // ── cases ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1+2: card rating is cross-provider (A: 5,5,4 + B: 2) and equals /users/me/rating")
    void should_showAllProvidersRatings_when_cardOpened() throws Exception {
        Salon a = fx.salon();
        Independent b = fx.independent();
        UUID c = fx.client("Оксана", "Рейтинг");
        rate(completed(a.masterId(), a.salonId(), c, 9), c, a.masterId(), a.salonId(), 5, "VISIBLE");
        rate(completed(a.masterId(), a.salonId(), c, 8), c, a.masterId(), a.salonId(), 5, "VISIBLE");
        rate(completed(a.masterId(), a.salonId(), c, 7), c, a.masterId(), a.salonId(), 4, "VISIBLE");
        rate(completed(b.masterId(), null, c, 6), c, b.masterId(), null, 2, "VISIBLE");

        JsonNode card = ok(fx.get(fx.token(a.ownerEmail()), salonUser(a.salonId(), c)));
        String clientEmail = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, c);
        JsonNode mine = ok(fx.get(fx.token(clientEmail), "/api/v1/users/me/rating"));

        assertThat(card.path("ratingAvg").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(card.path("ratingCount").asInt()).isEqualTo(4);
        assertThat(buckets(card.path("ratingDistribution"))).containsExactly("5:2", "4:1", "3:0", "2:1", "1:0");
        assertThat(card.path("ratingAvg").decimalValue()).isEqualByComparingTo(mine.path("avgRating").decimalValue());
        assertThat(card.path("ratingCount").asInt()).isEqualTo(mine.path("reviewCount").asInt());
        assertThat(buckets(card.path("ratingDistribution"))).isEqualTo(buckets(mine.path("ratingDistribution")));
    }

    @Test
    @DisplayName("lead: 5 by provider A and 3 by provider B - A's card avg 4.0 count 2 {5:1,3:1}; A's list has only A's row")
    void should_showGeneralRatingButOnlyOwnRows_when_ratedByTwoProviders() throws Exception {
        Salon a = fx.salon();
        Salon b = fx.salon();
        UUID c = fx.client("Загальна", "Оцінка");
        UUID aBooking = completed(a.masterId(), a.salonId(), c, 5);
        UUID bBooking = completed(b.masterId(), b.salonId(), c, 4);
        rate(aBooking, c, a.masterId(), a.salonId(), 5, "VISIBLE");
        rate(bBooking, c, b.masterId(), b.salonId(), 3, "VISIBLE");
        String token = fx.token(a.ownerEmail());

        JsonNode card = ok(fx.get(token, salonUser(a.salonId(), c)));
        JsonNode list = ok(fx.get(token, salonUser(a.salonId(), c) + "/ratings"));

        assertThat(card.path("ratingAvg").decimalValue()).isEqualByComparingTo("4.0");
        assertThat(card.path("ratingCount").asInt()).isEqualTo(2);
        assertThat(buckets(card.path("ratingDistribution"))).containsExactly("5:1", "4:0", "3:1", "2:0", "1:0");
        assertThat(bookingIds(list)).containsExactly(aBooking);
    }

    @Test
    @DisplayName("3+4: own list = A's rows only, newest first, exactly four keys; no comment/author/id anywhere")
    void should_listOwnRowsWithFourKeys_when_ownerOpensRatings() throws Exception {
        Salon a = fx.salon();
        Independent b = fx.independent();
        UUID c = fx.client("Список", "Оцінок");
        UUID oldest = completed(a.masterId(), a.salonId(), c, 9);
        UUID middle = completed(a.masterId(), a.salonId(), c, 8);
        UUID newest = completed(a.masterId(), a.salonId(), c, 7);
        UUID foreign = completed(b.masterId(), null, c, 6);
        rate(oldest, c, a.masterId(), a.salonId(), 5, "VISIBLE");
        rate(middle, c, a.masterId(), a.salonId(), 5, "VISIBLE");
        rate(newest, c, a.masterId(), a.salonId(), 4, "VISIBLE");
        rate(foreign, c, b.masterId(), null, 2, "VISIBLE");
        String token = fx.token(a.ownerEmail());

        ResponseEntity<String> listResp = fx.get(token, salonUser(a.salonId(), c) + "/ratings");
        ResponseEntity<String> cardResp = fx.get(token, salonUser(a.salonId(), c));

        JsonNode list = ok(listResp);
        assertThat(bookingIds(list)).containsExactly(newest, middle, oldest);
        list.path("data").forEach(r -> assertThat(fieldNames(r))
                .containsExactlyInAnyOrder("bookingId", "visitAt", "serviceName", "stars"));
        assertThat(list.path("data").get(0).path("stars").asInt()).isEqualTo(4);
        for (ResponseEntity<String> r : List.of(listResp, cardResp)) {
            assertThat(r.getBody()).doesNotContain("comment").doesNotContain(MARKER)
                    .doesNotContain("authorMasterId").doesNotContain("\"id\"");
        }
    }

    @Test
    @DisplayName("5: independent master sees only their own rows")
    void should_listOwnRows_when_independentMasterOpensRatings() throws Exception {
        Salon a = fx.salon();
        Independent b = fx.independent();
        UUID c = fx.client("Соло", "Клієнт");
        rate(completed(a.masterId(), a.salonId(), c, 9), c, a.masterId(), a.salonId(), 5, "VISIBLE");
        UUID mine = completed(b.masterId(), null, c, 8);
        rate(mine, c, b.masterId(), null, 3, "VISIBLE");

        JsonNode list = ok(fx.get(fx.token(b.email()), ME_URL + "/users/" + c + "/ratings"));

        assertThat(bookingIds(list)).containsExactly(mine);
    }

    @Test
    @DisplayName("6+7: SALON_MASTER sees only ratings on their own visits; owner masterId narrows to that master")
    void should_scopeRatingsToMaster_when_salonMasterOrMasterIdMode() throws Exception {
        Salon s = fx.salon();
        Object[] second = fx.secondMaster(s.salonId(), "Другий");
        UUID otherMaster = (UUID) second[0];
        UUID c = fx.client("Спільна", "Клієнтка");
        UUID onM = completed(s.masterId(), s.salonId(), c, 9);
        UUID onOther = completed(otherMaster, s.salonId(), c, 8);
        rate(onM, c, s.masterId(), s.salonId(), 5, "VISIBLE");
        rate(onOther, c, otherMaster, s.salonId(), 4, "VISIBLE");

        JsonNode asMaster = ok(fx.get(fx.token(s.masterEmail()), ME_URL + "/users/" + c + "/ratings"));
        JsonNode narrowed = ok(fx.get(fx.token(s.ownerEmail()),
                salonUser(s.salonId(), c) + "/ratings?masterId=" + otherMaster));
        JsonNode all = ok(fx.get(fx.token(s.ownerEmail()), salonUser(s.salonId(), c) + "/ratings"));

        assertThat(bookingIds(asMaster)).containsExactly(onM);
        assertThat(bookingIds(narrowed)).containsExactly(onOther);
        assertThat(bookingIds(all)).containsExactlyInAnyOrder(onM, onOther);
    }

    @Test
    @DisplayName("8: a REMOVED rating leaves the list, the count and the distribution")
    void should_dropRemovedRating_when_moderated() throws Exception {
        Salon a = fx.salon();
        UUID c = fx.client("Модерація", "Клієнт");
        UUID kept = completed(a.masterId(), a.salonId(), c, 9);
        UUID removed = completed(a.masterId(), a.salonId(), c, 8);
        rate(kept, c, a.masterId(), a.salonId(), 5, "VISIBLE");
        rate(removed, c, a.masterId(), a.salonId(), 1, "REMOVED");
        String token = fx.token(a.ownerEmail());

        JsonNode card = ok(fx.get(token, salonUser(a.salonId(), c)));
        JsonNode list = ok(fx.get(token, salonUser(a.salonId(), c) + "/ratings"));

        assertThat(bookingIds(list)).containsExactly(kept);
        assertThat(card.path("ratingCount").asInt()).isEqualTo(1);
        assertThat(buckets(card.path("ratingDistribution"))).containsExactly("5:1", "4:0", "3:0", "2:0", "1:0");
    }

    @Test
    @DisplayName("9: guest card (owner, IM, salon master) = unrated values; /guests/{id}/ratings = 200 empty page")
    void should_returnUnratedValues_when_clientIsGuest() throws Exception {
        Salon s = fx.salon();
        Independent im = fx.independent();
        UUID sg = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гостя", "Одна", "COMPLETED", fx.at(-5));
        UUID ig = fx.guestBooking(im.masterId(), null, "+380502223344", "Гість", "Два", "COMPLETED", fx.at(-5));
        List<String> urls = List.of(salonGuest(s.salonId(), sg), ME_URL + "/guests/" + sg);
        String owner = fx.token(s.ownerEmail());
        String master = fx.token(s.masterEmail());
        String imToken = fx.token(im.email());

        List<ResponseEntity<String>> cards = List.of(fx.get(owner, urls.get(0)), fx.get(master, urls.get(1)),
                fx.get(imToken, ME_URL + "/guests/" + ig));
        List<ResponseEntity<String>> lists = List.of(fx.get(owner, urls.get(0) + "/ratings"),
                fx.get(master, urls.get(1) + "/ratings"), fx.get(imToken, ME_URL + "/guests/" + ig + "/ratings"));

        for (ResponseEntity<String> r : cards) {
            JsonNode card = ok(r);
            assertThat(card.path("ratingAvg").isNull()).isTrue();
            assertThat(card.path("ratingCount").asInt()).isZero();
            assertThat(buckets(card.path("ratingDistribution"))).containsExactly("5:0", "4:0", "3:0", "2:0", "1:0");
        }
        for (ResponseEntity<String> r : lists) {
            JsonNode page = ok(r);
            assertThat(page.path("data")).isEmpty();
            assertThat(page.path("totalElements").asLong()).isZero();
        }
    }

    @Test
    @DisplayName("10: registered unrated = count 0, five zero buckets, no distribution statement")
    void should_skipDistributionQuery_when_registeredClientUnrated() throws Exception {
        Salon s = fx.salon();
        UUID unrated = fx.client("Без", "Оцінок");
        UUID rated = fx.client("З", "Оцінками");
        completed(s.masterId(), s.salonId(), unrated, 9);
        rate(completed(s.masterId(), s.salonId(), rated, 8), rated, s.masterId(), s.salonId(), 5, "VISIBLE");
        String token = fx.token(s.ownerEmail());

        JsonNode card = ok(fx.get(token, salonUser(s.salonId(), unrated)));
        long unratedStatements = statementsFor(token, salonUser(s.salonId(), unrated));
        long ratedStatements = statementsFor(token, salonUser(s.salonId(), rated));

        assertThat(card.path("ratingCount").asInt()).isZero();
        assertThat(buckets(card.path("ratingDistribution"))).containsExactly("5:0", "4:0", "3:0", "2:0", "1:0");
        assertThat(ratedStatements).as("distribution query runs only when count > 0")
                .isEqualTo(unratedStatements + 1);
    }

    @Test
    @DisplayName("11: out-of-scope user / guest anchor / anonymised anchor - identical 404 body as the card")
    void should_return404_when_clientOutOfScope() throws Exception {
        Salon s = fx.salon();
        Salon foreign = fx.salon();
        UUID c = fx.client("Чужий", "Клієнт");
        completed(foreign.masterId(), foreign.salonId(), c, 9);
        UUID foreignGuest = fx.guestBooking(foreign.masterId(), foreign.salonId(), GUEST_PHONE, "Ч", "Г",
                "COMPLETED", fx.at(-5));
        UUID anonymised = fx.anonymisedGuestBooking(s.masterId(), s.salonId(), fx.at(-4));
        String token = fx.token(s.ownerEmail());

        ResponseEntity<String> cardBody = fx.get(token, salonUser(s.salonId(), c));
        List<ResponseEntity<String>> lists = List.of(
                fx.get(token, salonUser(s.salonId(), c) + "/ratings"),
                fx.get(token, salonUser(s.salonId(), UUID.randomUUID()) + "/ratings"),
                fx.get(token, salonGuest(s.salonId(), foreignGuest) + "/ratings"),
                fx.get(token, salonGuest(s.salonId(), anonymised) + "/ratings"));

        assertThat(cardBody.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for (ResponseEntity<String> r : lists) {
            assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(withoutRequestId(r)).isEqualTo(withoutRequestId(cardBody));
        }
    }

    @Test
    @DisplayName("12: 25 rated bookings - size 20 then 5; size 60 clamped to 50; page 1001 is 400")
    void should_pageRatings_when_moreThanOnePage() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Багато", "Оцінок");
        for (int i = 0; i < 25; i++) {
            rate(completed(s.masterId(), s.salonId(), c, 100 - i), c, s.masterId(), s.salonId(), 1 + i % 5, "VISIBLE");
        }
        String token = fx.token(s.ownerEmail());
        String url = salonUser(s.salonId(), c) + "/ratings";

        JsonNode first = ok(fx.get(token, url));
        JsonNode second = ok(fx.get(token, url + "?page=1"));
        JsonNode big = ok(fx.get(token, url + "?size=60"));
        ResponseEntity<String> over = fx.get(token, url + "?page=1001");

        assertThat(first.path("data")).hasSize(20);
        assertThat(second.path("data")).hasSize(5);
        assertThat(first.path("totalElements").asLong()).isEqualTo(25);
        assertThat(big.path("data")).hasSize(25);
        assertThat(big.path("size").asInt()).isEqualTo(50);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("13: exact statement budgets - card, /ratings (any page size), master /ratings, guest /ratings")
    void should_stayWithinStatementBudget_when_cardAndRatingsRead() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Бюджет", "Запитів");
        for (int i = 0; i < 12; i++) {
            rate(completed(s.masterId(), s.salonId(), c, 50 - i), c, s.masterId(), s.salonId(), 1 + i % 5, "VISIBLE");
        }
        UUID g = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Бюджет", "Гість", "COMPLETED", fx.at(-3));
        // Both tokens are primed in TokensValidAfterCache, so no measured call pays the one-off users.tokens_valid_after read.
        String owner = warmToken(s.ownerEmail());
        String master = warmToken(s.masterEmail());
        String url = salonUser(s.salonId(), c);

        long card = statementsFor(owner, url);
        long small = statementsFor(owner, url + "/ratings?size=2");
        long large = statementsFor(owner, url + "/ratings?size=20");
        long masterList = statementsFor(master, ME_URL + "/users/" + c + "/ratings");
        long guest = statementsFor(owner, salonGuest(s.salonId(), g) + "/ratings");

        // Exact counts, warm auth. Breakdown per call (statement = prepared JDBC statement):
        assertThat(card).as("card: salon-access check + card aggregate + block lookup + distribution").isEqualTo(4);
        assertThat(small).as("ratings: salon-access check + exists + page + count").isEqualTo(4);
        assertThat(large).as("page size does not change the count").isEqualTo(small);
        assertThat(masterList).as("master ratings: master lookup + exists + page + count").isEqualTo(4);
        assertThat(guest).as("guest ratings: salon-access check + anchor phone + page + count").isEqualTo(4);
        assertThat(guest).isLessThanOrEqualTo(small);
    }

    @Test
    @DisplayName("14: auth matrix over the 4 /ratings paths - 401 anonymous, 403 wrong role, 200 allowed")
    void should_enforceAuthMatrix_when_ratingsPathsCalled() throws Exception {
        Salon s = fx.salon();
        Salon other = fx.salon();
        Independent im = fx.independent();
        UUID c = fx.client("Авторизація", "Клієнт");
        completed(s.masterId(), s.salonId(), c, 9);
        UUID guest = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "А", "Г", "COMPLETED", fx.at(-3));
        completed(im.masterId(), null, c, 8);
        String clientToken = fx.token(jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?",
                String.class, c));
        String owner = fx.token(s.ownerEmail());
        String foreignOwner = fx.token(other.ownerEmail());
        String master = fx.token(s.masterEmail());
        List<String> salonPaths = List.of(salonUser(s.salonId(), c) + "/ratings",
                salonGuest(s.salonId(), guest) + "/ratings");
        List<String> mePaths = List.of(ME_URL + "/users/" + c + "/ratings", ME_URL + "/guests/" + guest + "/ratings");

        for (String path : salonPaths) {
            assertThat(fx.get(null, path).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(fx.get(clientToken, path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(fx.get(master, path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(fx.get(foreignOwner, path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(fx.get(owner, path).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        for (String path : mePaths) {
            assertThat(fx.get(null, path).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(fx.get(clientToken, path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(fx.get(owner, path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(fx.get(master, path).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("15: multi-service visit with two rated rows - two rows, each with its own service name")
    void should_listTwoRows_when_multiServiceVisitRatedTwice() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Мульти", "Послуга");
        UUID visit = fx.appointment(c, s.salonId());
        UUID b1 = fx.bookingInVisit(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-9), visit);
        UUID b2 = fx.bookingInVisit(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-9), visit);
        rate(b1, c, s.masterId(), s.salonId(), 5, "VISIBLE");
        rate(b2, c, s.masterId(), s.salonId(), 4, "VISIBLE");

        JsonNode list = ok(fx.get(fx.token(s.ownerEmail()), salonUser(s.salonId(), c) + "/ratings"));

        List<String> actual = new ArrayList<>();
        list.path("data").forEach(r -> actual.add(r.path("bookingId").asText() + "=" + r.path("serviceName").asText()));
        assertThat(actual).containsExactlyInAnyOrder(b1 + "=" + fx.serviceNameOf(b1), b2 + "=" + fx.serviceNameOf(b2));
        assertThat(fx.serviceNameOf(b1)).as("fixture: two distinct services").isNotEqualTo(fx.serviceNameOf(b2));
    }

    @Test
    @DisplayName("16: visitAt is the rated booking's starts_at (same instant), serviceName and stars match the row")
    void should_returnBookingStartsAtAsVisitAt_when_ratingsListed() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Дата", "Візиту");
        java.time.Instant older = fx.at(-12);
        java.time.Instant newer = fx.at(-3);
        UUID olderBooking = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", older);
        UUID newerBooking = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", newer);
        rate(olderBooking, c, s.masterId(), s.salonId(), 2, "VISIBLE");
        rate(newerBooking, c, s.masterId(), s.salonId(), 5, "VISIBLE");
        String expectedService = fx.serviceNameOf(newerBooking);

        JsonNode list = ok(fx.get(fx.token(s.ownerEmail()), salonUser(s.salonId(), c) + "/ratings"));

        JsonNode first = list.path("data").get(0);
        JsonNode second = list.path("data").get(1);
        assertThat(java.time.OffsetDateTime.parse(first.path("visitAt").asText()).toInstant())
                .as("newest row visitAt, actual=%s", first.path("visitAt")).isEqualTo(newer);
        assertThat(java.time.OffsetDateTime.parse(second.path("visitAt").asText()).toInstant())
                .as("older row visitAt, actual=%s", second.path("visitAt")).isEqualTo(older);
        assertThat(first.path("serviceName").asText()).isEqualTo(expectedService);
        assertThat(first.path("stars").asInt()).isEqualTo(5);
        assertThat(second.path("stars").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("17: SALON_MASTER D4 - only the salon's visible ratings of THIS client on the master's own visits")
    void should_showOnlyOwnVisitRatings_when_salonMasterOpensRatings() throws Exception {
        Salon s = fx.salon();
        Object[] second = fx.secondMaster(s.salonId(), "Інший");
        UUID otherMaster = (UUID) second[0];
        UUID c = fx.client("Клієнт", "Майстра");
        UUID otherClient = fx.client("Інший", "Клієнт");
        UUID ownVisible = completed(s.masterId(), s.salonId(), c, 9);
        UUID ownRemoved = completed(s.masterId(), s.salonId(), c, 8);
        completed(s.masterId(), s.salonId(), c, 7);
        UUID otherMastersVisit = completed(otherMaster, s.salonId(), c, 6);
        UUID otherClientsVisit = completed(s.masterId(), s.salonId(), otherClient, 5);
        rate(ownVisible, c, s.masterId(), s.salonId(), 4, "VISIBLE");
        rate(ownRemoved, c, s.masterId(), s.salonId(), 1, "REMOVED");
        rate(otherMastersVisit, c, s.masterId(), s.salonId(), 5, "VISIBLE");
        rate(otherClientsVisit, otherClient, s.masterId(), s.salonId(), 3, "VISIBLE");

        JsonNode list = ok(fx.get(fx.token(s.masterEmail()), ME_URL + "/users/" + c + "/ratings"));

        assertThat(bookingIds(list)).containsExactly(ownVisible);
        assertThat(list.path("totalElements").asLong()).isEqualTo(1);
        assertThat(list.path("data").get(0).path("stars").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("18: unknown guest anchor = identical CLIENT_NOT_FOUND 404 on /guests/{id}/ratings; known guest = 200 empty")
    void should_return404_when_guestAnchorUnknown() throws Exception {
        Salon s = fx.salon();
        UUID known = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Відомий", "Гість", "COMPLETED",
                fx.at(-5));
        String owner = fx.token(s.ownerEmail());
        String master = fx.token(s.masterEmail());
        UUID unknown = UUID.randomUUID();

        ResponseEntity<String> knownResp = fx.get(owner, salonGuest(s.salonId(), known) + "/ratings");
        ResponseEntity<String> unknownSalon = fx.get(owner, salonGuest(s.salonId(), unknown) + "/ratings");
        ResponseEntity<String> unknownMe = fx.get(master, ME_URL + "/guests/" + unknown + "/ratings");
        ResponseEntity<String> unknownCard = fx.get(owner, salonGuest(s.salonId(), unknown));

        JsonNode page = ok(knownResp);
        assertThat(page.path("data")).isEmpty();
        assertThat(unknownSalon.getStatusCode()).as(unknownSalon.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknownMe.getStatusCode()).as(unknownMe.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutRequestId(unknownSalon)).isEqualTo(withoutRequestId(unknownCard));
        assertThat(withoutRequestId(unknownMe)).isEqualTo(withoutRequestId(unknownCard));
    }
}
