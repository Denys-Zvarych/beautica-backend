package com.beautica.client.provider;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.security.PhoneHasher;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.support.HibernateStatistics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import com.beautica.client.provider.ProviderClientFixtures.Independent;
import com.beautica.client.provider.ProviderClientFixtures.Salon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B508 — provider client card + visit history (CLI-42..45) against real Postgres: six endpoints, uniform 404, own-scope
 * counts, the GST-D14 phone gate and the statement budget. Rows are seeded with raw SQL (the B507 convention) because
 * the cases need exact control of status, start time, guest PII columns and appointment grouping.
 *
 * <p>Seeding and login helpers live in {@link ProviderClientFixtures}.
 */
@DisplayName("ProviderClient card + visits — B508")
class ProviderClientCardIT extends AbstractIntegrationTest {

    private static final String SALON_URL = "/api/v1/salons/%s/clients";
    private static final String ME_URL = "/api/v1/masters/me/clients";
    private static final String GUEST_PHONE = "+380501112233";
    private static final String ACCOUNT_PHONE = "+380671234567";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private ClientReviewRepository clientReviewRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private com.beautica.client.service.ClientPhoneAttachService attachService;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private ProviderClientFixtures fx;

    @BeforeEach
    void setUpFixtures() {
        fx = new ProviderClientFixtures(jdbcTemplate, restTemplate, objectMapper, phoneHasher, this::testCityId,
                this::resolveUnusedServiceTypeId);
    }

    // ── seeding ──────────────────────────────────────────────────────────────

    private void clientReview(UUID bookingId, UUID clientId, UUID authorMasterId, UUID salonId, int rating,
                              String moderation) {
        fx.clientReview(bookingId, clientId, authorMasterId, salonId, rating, "Запізнився", moderation);
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private String userCard(UUID salonId, UUID userId) {
        return SALON_URL.formatted(salonId) + "/users/" + userId;
    }

    private String guestCard(UUID salonId, UUID bookingId) {
        return SALON_URL.formatted(salonId) + "/guests/" + bookingId;
    }

    private JsonNode ok(ResponseEntity<String> resp) throws Exception {
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private List<JsonNode> visitRows(ResponseEntity<String> resp) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        ok(resp).path("data").forEach(out::add);
        return out;
    }

    /** The error body minus its per-request correlation id (the only field that legitimately differs). */
    private String withoutRequestId(ResponseEntity<String> resp) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode body = (com.fasterxml.jackson.databind.node.ObjectNode)
                objectMapper.readTree(resp.getBody());
        body.remove("requestId");
        return body.toString();
    }

    private static void assertNotFound(ResponseEntity<String> resp) {
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
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
    @DisplayName("1: three counts only - a mixed-status history; raw JSON stats has exactly 3 keys")
    void should_countTotalCompletedUpcoming_when_mixedStatusHistory() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Анна", "Коваль");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-20));
        UUID visit = fx.appointment(c, s.salonId());
        fx.bookingInVisit(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-10), visit);
        fx.bookingInVisit(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-10), visit);
        fx.bookingInVisit(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-10), visit);
        fx.booking(s.masterId(), s.salonId(), c, "CANCELLED", fx.at(-5));
        fx.booking(s.masterId(), s.salonId(), c, "DECLINED", fx.at(-4));
        fx.booking(s.masterId(), s.salonId(), c, "NOT_COMPLETED", fx.at(-3));
        fx.booking(s.masterId(), s.salonId(), c, "CONFIRMED", fx.at(3));

        ResponseEntity<String> resp = fx.get(fx.token(s.ownerEmail()), userCard(s.salonId(), c));

        JsonNode data = ok(resp);
        JsonNode stats = data.path("stats");
        assertThat(stats.path("totalVisits").asInt()).isEqualTo(6);
        assertThat(stats.path("completedVisits").asInt()).isEqualTo(2);
        assertThat(stats.path("upcomingVisits").asInt()).isEqualTo(1);
        List<String> keys = new ArrayList<>();
        stats.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("totalVisits", "completedVisits", "upcomingVisits");
        assertThat(data.path("kind").asText()).isEqualTo("REGISTERED");
        assertThat(data.path("userId").asText()).isEqualTo(c.toString());
        assertThat(data.path("displayName").asText()).isEqualTo("Анна Коваль");
        assertThat(OffsetDateTime.parse(data.path("firstVisitAt").asText()).toInstant())
                .isBefore(OffsetDateTime.parse(data.path("lastVisitAt").asText()).toInstant());
        assertThat(data.path("nextVisitAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("2: an appointment with 1 COMPLETED + 1 CANCELLED row is one visit, completed; history shows both rows")
    void should_countPartlyCancelledVisitOnce_when_oneRowCompleted() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Марта", "Іваненко");
        UUID visit = fx.appointment(c, s.salonId());
        fx.bookingInVisit(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-7), visit);
        fx.bookingInVisit(s.masterId(), s.salonId(), c, "CANCELLED", fx.at(-7), visit);
        String token = fx.token(s.ownerEmail());

        JsonNode stats = ok(fx.get(token, userCard(s.salonId(), c))).path("stats");
        List<JsonNode> visits = visitRows(fx.get(token, userCard(s.salonId(), c) + "/visits"));

        assertThat(stats.path("totalVisits").asInt()).isEqualTo(1);
        assertThat(stats.path("completedVisits").asInt()).isEqualTo(1);
        assertThat(visits).hasSize(1);
        assertThat(visits.get(0).path("visitKey").asText()).isEqualTo(visit.toString());
        List<String> statuses = new ArrayList<>();
        visits.get(0).path("items").forEach(i -> statuses.add(i.path("status").asText()));
        assertThat(statuses).containsExactlyInAnyOrder("COMPLETED", "CANCELLED");
    }

    @Test
    @DisplayName("3: a guest opened by its oldest or its newest booking is the identical card")
    void should_returnIdenticalCard_when_guestOpenedByAnyBooking() throws Exception {
        Salon s = fx.salon();
        UUID oldest = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Іванна", "Коваль", "COMPLETED", fx.at(-20));
        fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Іванна", "Коваль", "COMPLETED", fx.at(-10));
        UUID newest = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Іванна", "Коваль", "COMPLETED", fx.at(-2));
        String token = fx.token(s.ownerEmail());

        JsonNode viaOldest = ok(fx.get(token, guestCard(s.salonId(), oldest)));
        JsonNode viaNewest = ok(fx.get(token, guestCard(s.salonId(), newest)));

        assertThat(viaOldest).isEqualTo(viaNewest);
        assertThat(viaOldest.path("guestBookingId").asText()).as("canonical handle = latest").isEqualTo(newest.toString());
        assertThat(viaOldest.path("stats").path("totalVisits").asInt()).isEqualTo(3);
        assertThat(viaOldest.path("kind").asText()).isEqualTo("GUEST");
    }

    @Test
    @DisplayName("4: guest card for owner, assigned admin and independent master carries the E.164 phone, no rating")
    void should_showGuestPhone_when_viewerIsOwnerAdminOrIndependentMaster() throws Exception {
        Salon s = fx.salon();
        UUID anchor = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гість", "Салону", "COMPLETED", fx.at(-5));
        String adminEmail = fx.admin(s.salonId());
        Independent indep = fx.independent();
        UUID indepAnchor = fx.guestBooking(indep.masterId(), null, GUEST_PHONE, "Гість", "Соло", "COMPLETED", fx.at(-5));

        JsonNode owner = ok(fx.get(fx.token(s.ownerEmail()), guestCard(s.salonId(), anchor)));
        JsonNode admin = ok(fx.get(fx.token(adminEmail), guestCard(s.salonId(), anchor)));
        JsonNode solo = ok(fx.get(fx.token(indep.email()), ME_URL + "/guests/" + indepAnchor));

        assertThat(owner.path("phone").asText()).isEqualTo(GUEST_PHONE);
        assertThat(admin.path("phone").asText()).isEqualTo(GUEST_PHONE);
        assertThat(solo.path("phone").asText()).isEqualTo(GUEST_PHONE);
        assertThat(owner.path("ratingAvg").isNull()).isTrue();
    }

    @Test
    @DisplayName("5: the same guest for a SALON_MASTER has no phone; everything else is present")
    void should_hidePhone_when_viewerIsSalonMaster() throws Exception {
        Salon s = fx.salon();
        UUID anchor = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гість", "Майстра", "COMPLETED", fx.at(-5));

        ResponseEntity<String> resp = fx.get(fx.token(s.masterEmail()), ME_URL + "/guests/" + anchor);

        JsonNode data = ok(resp);
        assertThat(data.path("phone").isMissingNode() || data.path("phone").isNull()).isTrue();
        assertThat(resp.getBody()).doesNotContain("380501112233");
        assertThat(data.path("displayName").asText()).isEqualTo("Гість Майстра");
        assertThat(data.path("stats").path("totalVisits").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("6: a registered client's UNVERIFIED users.phone_number never appears, for any role (B518: verified is ProviderClientPhoneIT)")
    void should_neverExposeRegisteredPhone_when_anyRoleViewsTheCard() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Петро", "Сидоренко");
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", ACCOUNT_PHONE, c);
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));
        Independent indep = fx.independent();
        fx.booking(indep.masterId(), null, c, "COMPLETED", fx.at(-4));
        String adminEmail = fx.admin(s.salonId());

        List<ResponseEntity<String>> responses = List.of(
                fx.get(fx.token(s.ownerEmail()), userCard(s.salonId(), c)),
                fx.get(fx.token(adminEmail), userCard(s.salonId(), c)),
                fx.get(fx.token(s.masterEmail()), ME_URL + "/users/" + c),
                fx.get(fx.token(indep.email()), ME_URL + "/users/" + c));

        for (ResponseEntity<String> resp : responses) {
            JsonNode data = ok(resp);
            assertThat(data.path("phone").isMissingNode() || data.path("phone").isNull()).isTrue();
            assertThat(resp.getBody()).doesNotContain("671234567");
        }
    }

    @Test
    @DisplayName("7: a registered client's booking used as a guest anchor is 404")
    void should_return404_when_guestAnchorBelongsToRegisteredClient() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Реєстрована", "Клієнтка");
        UUID registeredBooking = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));

        assertNotFound(fx.get(fx.token(s.ownerEmail()), guestCard(s.salonId(), registeredBooking)));
        assertNotFound(fx.get(fx.token(s.ownerEmail()), guestCard(s.salonId(), registeredBooking) + "/visits"));
    }

    @Test
    @DisplayName("8/9/10: anonymised anchor, foreign-salon anchor/userId, unrelated user, unknown id - one identical 404")
    void should_return404WithIdenticalBody_when_clientIsNotInScope() throws Exception {
        Salon s = fx.salon();
        Salon foreign = fx.salon();
        UUID anonymised = fx.anonymisedGuestBooking(s.masterId(), s.salonId(), fx.at(-5));
        UUID foreignGuest = fx.guestBooking(foreign.masterId(), foreign.salonId(), GUEST_PHONE, "Чужий", "Гість",
                "COMPLETED", fx.at(-5));
        UUID foreignClient = fx.client("Чужа", "Клієнтка");
        fx.booking(foreign.masterId(), foreign.salonId(), foreignClient, "COMPLETED", fx.at(-5));
        UUID rated = fx.client("Оцінена", "Без Візитів");
        jdbcTemplate.update("UPDATE users SET avg_rating = 4.50, review_count = 3 WHERE id = ?", rated);
        String token = fx.token(s.ownerEmail());

        List<ResponseEntity<String>> responses = List.of(
                fx.get(token, guestCard(s.salonId(), anonymised)),
                fx.get(token, guestCard(s.salonId(), foreignGuest)),
                fx.get(token, userCard(s.salonId(), foreignClient)),
                fx.get(token, userCard(s.salonId(), rated)),
                fx.get(token, guestCard(s.salonId(), UUID.randomUUID())),
                fx.get(token, userCard(s.salonId(), UUID.randomUUID())),
                fx.get(token, userCard(s.salonId(), rated) + "/visits"),
                fx.get(token, guestCard(s.salonId(), foreignGuest) + "/visits"));

        for (ResponseEntity<String> resp : responses) {
            assertNotFound(resp);
            assertThat(withoutRequestId(resp)).isEqualTo(withoutRequestId(responses.get(0)));
        }
        assertThat(responses.get(0).getBody()).doesNotContain("4.5").doesNotContain("Чужий");
    }

    @Test
    @DisplayName("11: a SALON_MASTER sees only own rows - counts and visits exclude the colleague's visit of the "
            + "same client (a DB trigger forbids a 2-master appointment, so a visit is always one master's)")
    void should_limitCountsAndItemsToOwnRows_when_salonMasterViews() throws Exception {
        Salon s = fx.salon();
        Object[] second = fx.secondMaster(s.salonId(), "Другий");
        UUID masterB = (UUID) second[0];
        UUID c = fx.client("Спільна", "Клієнтка");
        UUID visitA = fx.appointment(c, s.salonId());
        UUID rowA1 = fx.bookingInVisit(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-8), visitA);
        UUID rowA2 = fx.bookingInVisit(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-8), visitA);
        UUID visitB = fx.appointment(c, s.salonId());
        UUID rowB1 = fx.bookingInVisit(masterB, s.salonId(), c, "COMPLETED", fx.at(-7), visitB);
        UUID rowB2 = fx.bookingInVisit(masterB, s.salonId(), c, "COMPLETED", fx.at(-7), visitB);
        UUID singleB = fx.booking(masterB, s.salonId(), c, "COMPLETED", fx.at(-3));
        UUID guestB = fx.guestBooking(masterB, s.salonId(), GUEST_PHONE, "Гість", "Б", "COMPLETED", fx.at(-2));
        String tokenA = fx.token(s.masterEmail());
        String tokenOwner = fx.token(s.ownerEmail());

        JsonNode asA = ok(fx.get(tokenA, ME_URL + "/users/" + c));
        List<JsonNode> visitsA = visitRows(fx.get(tokenA, ME_URL + "/users/" + c + "/visits"));
        JsonNode asOwner = ok(fx.get(tokenOwner, userCard(s.salonId(), c)));
        List<JsonNode> visitsOwner = visitRows(fx.get(tokenOwner, userCard(s.salonId(), c) + "/visits"));

        assertThat(asA.path("stats").path("totalVisits").asInt()).isEqualTo(1);
        assertThat(visitsA).hasSize(1);
        assertThat(visitsA.get(0).path("visitKey").asText()).isEqualTo(visitA.toString());
        assertThat(visitsA.get(0).path("items")).hasSize(2);
        assertThat(visitsA.get(0).path("items").findValuesAsText("bookingId"))
                .containsExactlyInAnyOrder(rowA1.toString(), rowA2.toString());
        assertThat(visitsA.get(0).path("items").findValuesAsText("masterId")).containsOnly(s.masterId().toString());
        assertThat(visitsA.toString()).doesNotContain(rowB1.toString()).doesNotContain(singleB.toString());
        assertThat(asOwner.path("stats").path("totalVisits").asInt()).isEqualTo(3);
        assertThat(visitsOwner).hasSize(3);
        assertThat(visitsOwner.toString()).contains(rowB1.toString()).contains(rowB2.toString());
        // the colleague's guest booking is not an anchor in A's scope
        assertNotFound(fx.get(tokenA, ME_URL + "/guests/" + guestB));
    }

    @Test
    @DisplayName("12: 25 visits, size 20 -> 20 + 5, newest first by visit start; a multi-row visit stays grouped")
    void should_pageVisitsNewestFirst_when_moreThanOnePage() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Постійна", "Клієнтка");
        for (int i = 0; i < 24; i++) {
            fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-100 + i));
        }
        UUID visit = fx.appointment(c, s.salonId());
        fx.bookingInVisit(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-60), visit);
        fx.bookingInVisit(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-60), visit);
        fx.bookingInVisit(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-60), visit);
        String token = fx.token(s.ownerEmail());
        String url = userCard(s.salonId(), c) + "/visits";

        JsonNode first = ok(fx.get(token, url));
        JsonNode second = ok(fx.get(token, url + "?page=1"));

        assertThat(first.path("data")).hasSize(20);
        assertThat(second.path("data")).hasSize(5);
        assertThat(first.path("totalElements").asLong()).isEqualTo(25);
        List<Instant> starts = new ArrayList<>();
        first.path("data").forEach(v -> starts.add(OffsetDateTime.parse(v.path("startsAt").asText()).toInstant()));
        second.path("data").forEach(v -> starts.add(OffsetDateTime.parse(v.path("startsAt").asText()).toInstant()));
        assertThat(starts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        List<JsonNode> all = new ArrayList<>();
        first.path("data").forEach(all::add);
        second.path("data").forEach(all::add);
        JsonNode grouped = all.stream().filter(v -> visit.toString().equals(v.path("visitKey").asText()))
                .findFirst().orElseThrow();
        assertThat(grouped.path("items")).hasSize(3);
        assertThat(all.stream().filter(v -> v.path("items").size() == 1)).hasSize(24);
    }

    @Test
    @DisplayName("13: card <= 3 statements, visits page <= 3; independent of page size and items per visit")
    void should_stayWithinStatementBudget_when_cardAndVisitsRead() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Бюджет", "Клієнтка");
        for (int i = 0; i < 6; i++) {
            UUID visit = fx.appointment(c, s.salonId());
            for (int j = 0; j <= i % 3; j++) {
                fx.bookingInVisit(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-50 + i), visit);
            }
        }
        UUID anchor = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гість", "Бюджет", "COMPLETED", fx.at(-9));
        fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гість", "Бюджет", "COMPLETED", fx.at(-8));
        String owner = fx.token(s.ownerEmail());
        String master = fx.token(s.masterEmail());
        String userCard = userCard(s.salonId(), c);
        String guestCard = guestCard(s.salonId(), anchor);
        for (String url : List.of(userCard, userCard + "/visits?size=2", guestCard, guestCard + "/visits")) {
            fx.get(owner, url); // warm one-off lookups (authz memo, query plans)
        }
        fx.get(master, ME_URL + "/users/" + c);

        long userCardStatements = statementsFor(owner, userCard);
        long guestCardStatements = statementsFor(owner, guestCard);
        long masterCardStatements = statementsFor(master, ME_URL + "/users/" + c);
        long smallPage = statementsFor(owner, userCard + "/visits?size=2");
        long largePage = statementsFor(owner, userCard + "/visits?size=20");
        long guestVisits = statementsFor(owner, guestCard + "/visits");

        assertThat(userCardStatements).as("aggregate (+ servedByMasters column) + blocks + salon-access lookup")
                .isLessThanOrEqualTo(3);
        assertThat(guestCardStatements).isLessThanOrEqualTo(3);
        assertThat(masterCardStatements).as("master lookup + aggregate + blocks").isLessThanOrEqualTo(3);
        assertThat(smallPage).as("page + items + salon-access lookup").isLessThanOrEqualTo(3);
        assertThat(largePage).isEqualTo(smallPage);
        assertThat(guestVisits).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("14: rating = average + count of other providers' VISIBLE reviews; no reviewCount / comment key")
    void should_exposeRatingNumberOnly_when_otherProvidersReviewedTheClient() throws Exception {
        Salon s = fx.salon();
        Salon other = fx.salon();
        UUID c = fx.client("Оксана", "Рейтинг");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-9));
        UUID b1 = fx.booking(other.masterId(), other.salonId(), c, "COMPLETED", fx.at(-8));
        UUID b2 = fx.booking(other.masterId(), other.salonId(), c, "COMPLETED", fx.at(-7));
        UUID b3 = fx.booking(other.masterId(), other.salonId(), c, "COMPLETED", fx.at(-6));
        clientReview(b1, c, other.masterId(), other.salonId(), 5, "VISIBLE");
        clientReview(b2, c, other.masterId(), other.salonId(), 3, "VISIBLE");
        clientReview(b3, c, other.masterId(), other.salonId(), 1, "HIDDEN_PENDING");
        transactionTemplate.executeWithoutResult(st -> clientReviewRepository.recalculateClientRating(c));

        ResponseEntity<String> resp = fx.get(fx.token(s.ownerEmail()), userCard(s.salonId(), c));

        JsonNode data = ok(resp);
        assertThat(data.path("ratingAvg").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(data.path("stats").path("totalVisits").asInt()).as("own scope only").isEqualTo(1);
        assertThat(data.path("ratingCount").asInt()).as("B526: cross-provider VISIBLE count").isEqualTo(2);
        assertThat(resp.getBody()).doesNotContain("reviewCount")
                .doesNotContain("comment").doesNotContain("Запізнився");
    }

    @Test
    @DisplayName("15: blocked flag - a salon-blocked guest and client; a foreign salon's block is ignored")
    void should_flagBlocked_when_blockedInTheViewersScope() throws Exception {
        Salon s = fx.salon();
        Salon foreign = fx.salon();
        UUID blockedClient = fx.client("Заблокована", "Клієнтка");
        UUID freeClient = fx.client("Вільна", "Клієнтка");
        fx.booking(s.masterId(), s.salonId(), blockedClient, "COMPLETED", fx.at(-9));
        fx.booking(s.masterId(), s.salonId(), freeClient, "COMPLETED", fx.at(-8));
        UUID blockedGuest = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Заблокований", "Гість",
                "COMPLETED", fx.at(-7));
        UUID foreignBlockedGuest = fx.guestBooking(s.masterId(), s.salonId(), "+380677778899", "Чужий", "Блок",
                "COMPLETED", fx.at(-6));
        fx.blockClientInSalon(s.salonId(), blockedClient);
        fx.blockGuestInSalon(s.salonId(), GUEST_PHONE);
        fx.blockGuestInSalon(foreign.salonId(), "+380677778899");
        String token = fx.token(s.ownerEmail());

        assertThat(ok(fx.get(token, guestCard(s.salonId(), blockedGuest))).path("blocked").asBoolean()).isTrue();
        assertThat(ok(fx.get(token, userCard(s.salonId(), blockedClient))).path("blocked").asBoolean()).isTrue();
        assertThat(ok(fx.get(token, userCard(s.salonId(), freeClient))).path("blocked").asBoolean()).isFalse();
        assertThat(ok(fx.get(token, guestCard(s.salonId(), foreignBlockedGuest))).path("blocked").asBoolean()).isFalse();
        // a SALON_MASTER viewer sees the same salon-scope badge (B507 D5)
        assertThat(ok(fx.get(fx.token(s.masterEmail()), ME_URL + "/guests/" + blockedGuest)).path("blocked").asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("16 (B527 — was: never merged): a LINK guest / STAFF walk-in and a registered account sharing one "
            + "VERIFIED phone are ONE card; the old guest anchor is the uniform 404")
    void should_showOneCard_when_guestRowsShareTheAccountsVerifiedPhone() throws Exception {
        Salon s = fx.salon();
        UUID account = fx.client("Петро", "Спільний");
        jdbcTemplate.update("UPDATE users SET phone_number = ?, phone_verified_at = NOW() WHERE id = ?",
                GUEST_PHONE, account);
        fx.booking(s.masterId(), s.salonId(), account, "COMPLETED", fx.at(-9));
        fx.booking(s.masterId(), s.salonId(), account, "COMPLETED", fx.at(-8));
        fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Петро", "Гість", "COMPLETED", fx.at(-7));
        fx.guestBooking(s.masterId(), s.salonId(), "STAFF", GUEST_PHONE, "Петро", "Гість", "COMPLETED", fx.at(-6));
        UUID anchor = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Петро", "Гість", "COMPLETED", fx.at(-5));
        transactionTemplate.executeWithoutResult(st -> attachService.attachByPhone(GUEST_PHONE,
                com.beautica.booking.enums.AttachReason.PHONE_VERIFIED));
        String token = fx.token(s.ownerEmail());

        JsonNode user = ok(fx.get(token, userCard(s.salonId(), account)));
        ResponseEntity<String> staleGuest = fx.get(token, guestCard(s.salonId(), anchor));

        assertThat(user.path("kind").asText()).isEqualTo("REGISTERED");
        assertThat(user.path("stats").path("totalVisits").asInt()).as("2 app + 3 attached").isEqualTo(5);
        assertThat(user.path("phone").asText()).as("the verified phone is the account's (B518)").isEqualTo(GUEST_PHONE);
        assertThat(visitRows(fx.get(token, userCard(s.salonId(), account) + "/visits"))).hasSize(5);
        assertNotFound(staleGuest);
    }

    @Test
    @DisplayName("16b: the SAME phone as an UNVERIFIED legacy users.phone_number never attaches — two cards, disjoint counts")
    void should_keepCardsApart_when_accountPhoneIsNotVerified() throws Exception {
        Salon s = fx.salon();
        UUID account = fx.client("Петро", "Спільний");
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", GUEST_PHONE, account);
        fx.booking(s.masterId(), s.salonId(), account, "COMPLETED", fx.at(-9));
        fx.booking(s.masterId(), s.salonId(), account, "COMPLETED", fx.at(-8));
        fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Петро", "Гість", "COMPLETED", fx.at(-7));
        fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Петро", "Гість", "COMPLETED", fx.at(-6));
        UUID anchor = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Петро", "Гість", "COMPLETED", fx.at(-5));
        transactionTemplate.executeWithoutResult(st -> attachService.attachByPhone(GUEST_PHONE,
                com.beautica.booking.enums.AttachReason.PHONE_VERIFIED));
        String token = fx.token(s.ownerEmail());

        JsonNode user = ok(fx.get(token, userCard(s.salonId(), account)));
        JsonNode guest = ok(fx.get(token, guestCard(s.salonId(), anchor)));

        assertThat(user.path("kind").asText()).isEqualTo("REGISTERED");
        assertThat(user.path("stats").path("totalVisits").asInt()).isEqualTo(2);
        assertThat(guest.path("kind").asText()).isEqualTo("GUEST");
        assertThat(guest.path("stats").path("totalVisits").asInt()).isEqualTo(3);
        assertThat(guest.path("phone").asText()).isEqualTo(GUEST_PHONE);
        assertThat(user.path("phone").isNull() || user.path("phone").isMissingNode()).isTrue();
        assertThat(visitRows(fx.get(token, userCard(s.salonId(), account) + "/visits"))).hasSize(2);
        assertThat(visitRows(fx.get(token, guestCard(s.salonId(), anchor) + "/visits"))).hasSize(3);
    }

    @Test
    @DisplayName("17: owner master mode - masterId narrows counts and visits; another salon's master is 404")
    void should_narrowToThatMaster_when_masterIdGiven() throws Exception {
        Salon s = fx.salon();
        Object[] second = fx.secondMaster(s.salonId(), "Другий");
        UUID masterB = (UUID) second[0];
        UUID c = fx.client("Двомайстрова", "Клієнтка");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-9));
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-8));
        fx.booking(masterB, s.salonId(), c, "COMPLETED", fx.at(-7));
        UUID guestA = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гість", "А", "COMPLETED", fx.at(-6));
        fx.guestBooking(masterB, s.salonId(), GUEST_PHONE, "Гість", "Б", "COMPLETED", fx.at(-5));
        String token = fx.token(s.ownerEmail());

        JsonNode all = ok(fx.get(token, userCard(s.salonId(), c)));
        JsonNode onlyA = ok(fx.get(token, userCard(s.salonId(), c) + "?masterId=" + s.masterId()));
        JsonNode onlyB = ok(fx.get(token, userCard(s.salonId(), c) + "?masterId=" + masterB));
        List<JsonNode> visitsA = visitRows(fx.get(token, userCard(s.salonId(), c) + "/visits?masterId=" + s.masterId()));
        JsonNode guestOnlyA = ok(fx.get(token, guestCard(s.salonId(), guestA) + "?masterId=" + s.masterId()));

        assertThat(all.path("stats").path("totalVisits").asInt()).isEqualTo(3);
        assertThat(onlyA.path("stats").path("totalVisits").asInt()).isEqualTo(2);
        assertThat(onlyB.path("stats").path("totalVisits").asInt()).isEqualTo(1);
        assertThat(visitsA).hasSize(2);
        assertThat(guestOnlyA.path("stats").path("totalVisits").asInt()).as("B's row of the same phone excluded")
                .isEqualTo(1);
        assertNotFound(fx.get(token, guestCard(s.salonId(), guestA) + "?masterId=" + masterB));
    }

    @Test
    @DisplayName("17b: masterId of another salon's master is 404")
    void should_return404_when_masterIdBelongsToAnotherSalon() throws Exception {
        Salon s = fx.salon();
        Salon foreign = fx.salon();
        UUID c = fx.client("Клієнтка", "Будь-яка");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-9));

        assertNotFound(fx.get(fx.token(s.ownerEmail()), userCard(s.salonId(), c) + "?masterId=" + foreign.masterId()));
    }

    // ── CLI-73 / CLI-74: servedByMasters chips + admin master filter ──────────

    private static List<String> servedIds(JsonNode card) {
        List<String> ids = new ArrayList<>();
        card.path("servedByMasters").forEach(m -> ids.add(m.path("masterId").asText()));
        return ids;
    }

    @Test
    @DisplayName("CLI-73: servedByMasters = this salon's masters who served the client, most recent booking first; "
            + "another salon's master excluded; unchanged by ?masterId=; detached master keeps snapshot name, no avatar")
    void should_listServedMastersMostRecentFirst_when_salonCardRead() throws Exception {
        Salon s = fx.salon();
        Salon other = fx.salon();
        UUID masterB = (UUID) fx.secondMaster(s.salonId(), "Друга")[0];
        UUID masterC = (UUID) fx.secondMaster(s.salonId(), "Третя")[0];
        fx.secondMaster(s.salonId(), "Неробоча"); // never served the client -> no chip
        jdbcTemplate.update("UPDATE users SET avatar_url = 'https://cdn.example/b.png' "
                + "WHERE id = (SELECT user_id FROM masters WHERE id = ?)", masterB);
        UUID c = fx.client("Чіпова", "Клієнтка");
        fx.booking(masterC, s.salonId(), c, "CANCELLED", fx.at(-30));
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-20));
        fx.booking(masterB, s.salonId(), c, "COMPLETED", fx.at(-25));
        fx.booking(masterB, s.salonId(), c, "CONFIRMED", fx.at(4));
        fx.booking(other.masterId(), other.salonId(), c, "COMPLETED", fx.at(-1));
        jdbcTemplate.update("UPDATE masters SET user_id = NULL, detached_first_name = 'Колишня', "
                + "detached_last_name = NULL, detached_at = NOW(), is_active = false WHERE id = ?", masterC);
        String token = fx.token(s.ownerEmail());

        JsonNode all = ok(fx.get(token, userCard(s.salonId(), c)));
        JsonNode filtered = ok(fx.get(token, userCard(s.salonId(), c) + "?masterId=" + s.masterId()));

        assertThat(servedIds(all)).containsExactly(masterB.toString(), s.masterId().toString(), masterC.toString());
        JsonNode chipB = all.path("servedByMasters").get(0);
        assertThat(chipB.path("name").asText()).isEqualTo("Друга Б");
        assertThat(chipB.path("avatarUrl").asText()).isEqualTo("https://cdn.example/b.png");
        JsonNode chipC = all.path("servedByMasters").get(2);
        assertThat(chipC.path("name").asText()).isEqualTo("Колишня");
        assertThat(chipC.path("avatarUrl").isNull()).isTrue();
        assertThat(chipC.size()).as("masterId, name, avatarUrl only").isEqualTo(3);
        assertThat(servedIds(filtered)).as("chip row stays salon-wide").isEqualTo(servedIds(all));
        assertThat(filtered.path("stats").path("totalVisits").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("CLI-73: guest card chips follow the card's guest identity (same phone, client_id NULL) in this salon")
    void should_listServedMastersByGuestIdentity_when_guestCardRead() throws Exception {
        Salon s = fx.salon();
        UUID masterB = (UUID) fx.secondMaster(s.salonId(), "Друга")[0];
        UUID masterC = (UUID) fx.secondMaster(s.salonId(), "Третя")[0];
        UUID anchor = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гість", "А", "COMPLETED", fx.at(-9));
        fx.guestBooking(masterB, s.salonId(), GUEST_PHONE, "Гість", "А", "COMPLETED", fx.at(-3));
        fx.guestBooking(masterC, s.salonId(), "+380677778899", "Інший", "Гість", "COMPLETED", fx.at(-2));
        UUID c = fx.client("Зареєстрована", "Клієнтка");
        fx.booking(masterC, s.salonId(), c, "COMPLETED", fx.at(-1));

        JsonNode card = ok(fx.get(fx.token(s.ownerEmail()), guestCard(s.salonId(), anchor)));

        assertThat(servedIds(card)).containsExactly(masterB.toString(), s.masterId().toString());
    }

    @Test
    @DisplayName("CLI-73: independent-master and SALON_MASTER cards carry an empty servedByMasters")
    void should_returnEmptyServedMasters_when_masterScopeCard() throws Exception {
        Salon s = fx.salon();
        Independent im = fx.independent();
        UUID c = fx.client("Без", "Чіпів");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));
        fx.booking(im.masterId(), null, c, "COMPLETED", fx.at(-4));

        JsonNode asSalonMaster = ok(fx.get(fx.token(s.masterEmail()), ME_URL + "/users/" + c));
        JsonNode asIndependent = ok(fx.get(fx.token(im.email()), ME_URL + "/users/" + c));

        assertThat(asSalonMaster.path("servedByMasters").isArray()).isTrue();
        assertThat(asSalonMaster.path("servedByMasters")).isEmpty();
        assertThat(asIndependent.path("servedByMasters").isArray()).isTrue();
        assertThat(asIndependent.path("servedByMasters")).isEmpty();
    }

    @Test
    @DisplayName("CLI-74: the salon's admin filters card, «Наступний візит» and visits by masterId like the owner")
    void should_narrowCardAndVisits_when_adminFiltersByMaster() throws Exception {
        Salon s = fx.salon();
        UUID masterB = (UUID) fx.secondMaster(s.salonId(), "Друга")[0];
        UUID c = fx.client("Адмінська", "Клієнтка");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-9));
        Instant nextWithA = fx.at(6);
        fx.booking(s.masterId(), s.salonId(), c, "CONFIRMED", nextWithA);
        Instant nextWithB = fx.at(2);
        fx.booking(masterB, s.salonId(), c, "CONFIRMED", nextWithB);
        String admin = fx.token(fx.admin(s.salonId()));

        JsonNode all = ok(fx.get(admin, userCard(s.salonId(), c)));
        JsonNode onlyA = ok(fx.get(admin, userCard(s.salonId(), c) + "?masterId=" + s.masterId()));
        List<JsonNode> visitsA = visitRows(fx.get(admin, userCard(s.salonId(), c) + "/visits?masterId=" + s.masterId()));

        assertThat(OffsetDateTime.parse(all.path("nextVisitAt").asText()).toInstant()).isEqualTo(nextWithB);
        assertThat(OffsetDateTime.parse(onlyA.path("nextVisitAt").asText()).toInstant()).isEqualTo(nextWithA);
        assertThat(onlyA.path("stats").path("totalVisits").asInt()).isEqualTo(2);
        assertThat(visitsA).hasSize(2);
        assertThat(visitsA.toString()).doesNotContain(masterB.toString());
    }

    @Test
    @DisplayName("CLI-74: admin with another salon's masterId gets the uniform 404 on card and visits")
    void should_return404_when_adminFiltersByForeignMaster() throws Exception {
        Salon s = fx.salon();
        Salon foreign = fx.salon();
        UUID c = fx.client("Чужий", "Фільтр");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-9));
        String admin = fx.token(fx.admin(s.salonId()));

        assertNotFound(fx.get(admin, userCard(s.salonId(), c) + "?masterId=" + foreign.masterId()));
        assertNotFound(fx.get(admin, userCard(s.salonId(), c) + "/visits?masterId=" + foreign.masterId()));
    }

    @Test
    @DisplayName("CLI-73: the salon admin sees the same salon-wide chip row as the owner, also under ?masterId=")
    void should_listSalonWideServedMasters_when_adminReadsCardWithAndWithoutMasterFilter() throws Exception {
        Salon s = fx.salon();
        UUID masterB = (UUID) fx.secondMaster(s.salonId(), "Друга")[0];
        UUID c = fx.client("Адмін", "Чіпи");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-9));
        fx.booking(masterB, s.salonId(), c, "COMPLETED", fx.at(-3));
        String admin = fx.token(fx.admin(s.salonId()));
        String owner = fx.token(s.ownerEmail());

        JsonNode adminAll = ok(fx.get(admin, userCard(s.salonId(), c)));
        JsonNode adminFiltered = ok(fx.get(admin, userCard(s.salonId(), c) + "?masterId=" + s.masterId()));
        JsonNode ownerAll = ok(fx.get(owner, userCard(s.salonId(), c)));

        assertThat(servedIds(adminAll)).containsExactly(masterB.toString(), s.masterId().toString());
        assertThat(servedIds(adminFiltered)).as("?masterId= never narrows the chip row").isEqualTo(servedIds(adminAll));
        assertThat(adminFiltered.path("stats").path("totalVisits").asInt()).as("card itself narrowed").isEqualTo(1);
        assertThat(servedIds(ownerAll)).as("owner and admin see one row").isEqualTo(servedIds(adminAll));
    }

    @Test
    @DisplayName("CLI-73/74: guest card under ?masterId= keeps the salon-wide chip row while the card narrows")
    void should_keepSalonWideGuestChips_when_guestCardFilteredByMaster() throws Exception {
        Salon s = fx.salon();
        UUID masterB = (UUID) fx.secondMaster(s.salonId(), "Друга")[0];
        UUID anchor = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гість", "А", "COMPLETED", fx.at(-9));
        fx.guestBooking(masterB, s.salonId(), GUEST_PHONE, "Гість", "А", "COMPLETED", fx.at(-3));
        String token = fx.token(s.ownerEmail());

        JsonNode filtered = ok(fx.get(token, guestCard(s.salonId(), anchor) + "?masterId=" + s.masterId()));

        assertThat(servedIds(filtered)).containsExactly(masterB.toString(), s.masterId().toString());
        assertThat(filtered.path("stats").path("totalVisits").asInt()).as("card narrowed to master A").isEqualTo(1);
    }

    @Test
    @DisplayName("CLI-73: a master who served the client here and then moved to another salon gets no chip")
    void should_excludeMaster_when_masterMovedToAnotherSalon() throws Exception {
        Salon s = fx.salon();
        Salon other = fx.salon();
        UUID moved = (UUID) fx.secondMaster(s.salonId(), "Перейшла")[0];
        UUID c = fx.client("Клієнтка", "Переходу");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-9));
        fx.booking(moved, s.salonId(), c, "COMPLETED", fx.at(-3));
        jdbcTemplate.update("UPDATE masters SET salon_id = ? WHERE id = ?", other.salonId(), moved);
        jdbcTemplate.update("UPDATE users SET salon_id = ? WHERE id = (SELECT user_id FROM masters WHERE id = ?)",
                other.salonId(), moved);

        JsonNode card = ok(fx.get(fx.token(s.ownerEmail()), userCard(s.salonId(), c)));

        assertThat(servedIds(card)).as("only masters on this salon's roster").containsExactly(s.masterId().toString());
        assertThat(card.path("stats").path("totalVisits").asInt()).as("history still counts the visit").isEqualTo(2);
    }

    @Test
    @DisplayName("CLI-73: the chip row is capped at 50, keeping the 50 most recently booked masters")
    void should_capServedMastersAtFifty_when_moreMastersServedTheClient() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Багато", "Майстрів");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-60)); // oldest -> cut by the cap
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            UUID m = (UUID) fx.secondMaster(s.salonId(), "Майстер" + i)[0];
            fx.booking(m, s.salonId(), c, "COMPLETED", fx.at(-i));
            expected.add(m.toString());
        }

        JsonNode card = ok(fx.get(fx.token(s.ownerEmail()), userCard(s.salonId(), c)));

        assertThat(servedIds(card)).as("50 most recent, newest first").containsExactlyElementsOf(expected);
        assertThat(card.path("stats").path("totalVisits").asInt()).as("cap never trims the card").isEqualTo(51);
    }

    // ── auth matrix ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("auth: salon endpoints - 401 anonymous, 403 SALON_MASTER / CLIENT / another salon's owner; "
            + "master endpoints - 403 owner and CLIENT")
    void should_enforceRoleMatrix_when_cardEndpointsCalled() throws Exception {
        Salon s = fx.salon();
        Salon t = fx.salon();
        UUID c = fx.client("Клієнтка", "Матриця");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));
        String clientEmail = fx.email("viewer");
        fx.user(clientEmail, "CLIENT", null, "Хтось", "Клієнт");

        assertThat(fx.get(null, userCard(s.salonId(), c)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(fx.get(fx.token(s.masterEmail()), userCard(s.salonId(), c)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fx.get(fx.token(clientEmail), userCard(s.salonId(), c)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fx.get(fx.token(t.ownerEmail()), userCard(s.salonId(), c)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fx.get(fx.token(t.ownerEmail()), userCard(s.salonId(), c) + "/visits").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fx.get(fx.token(s.ownerEmail()), ME_URL + "/users/" + c).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fx.get(fx.token(s.ownerEmail()), ME_URL + "/users/" + c + "/visits").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fx.get(fx.token(clientEmail), ME_URL + "/users/" + c).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fx.get(fx.token(s.masterEmail()), ME_URL + "/users/" + c).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── QA additions: date semantics, rating, paging bounds, full auth matrix ─────────

    @Test
    @DisplayName("18: upcoming counts only CONFIRMED rows after now; first/last/next visit ignore non-qualifying statuses")
    void should_deriveUpcomingAndVisitDates_when_statusesAndTimesAreMixed() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Дати", "Клієнтка");
        Instant declinedOld = fx.at(-30);
        Instant confirmedPast = fx.at(-2);
        Instant notCompletedRecent = fx.at(-1);
        Instant cancelledFuture = fx.at(1);
        Instant confirmedNear = fx.at(2);
        Instant confirmedFar = fx.at(5);
        fx.booking(s.masterId(), s.salonId(), c, "DECLINED", declinedOld);
        fx.booking(s.masterId(), s.salonId(), c, "CONFIRMED", confirmedPast);
        fx.booking(s.masterId(), s.salonId(), c, "NOT_COMPLETED", notCompletedRecent);
        fx.booking(s.masterId(), s.salonId(), c, "CANCELLED", cancelledFuture);
        fx.booking(s.masterId(), s.salonId(), c, "CONFIRMED", confirmedFar);
        fx.booking(s.masterId(), s.salonId(), c, "CONFIRMED", confirmedNear);

        JsonNode data = ok(fx.get(fx.token(s.ownerEmail()), userCard(s.salonId(), c)));

        assertThat(data.path("stats").path("totalVisits").asInt()).isEqualTo(6);
        assertThat(data.path("stats").path("completedVisits").asInt()).isZero();
        assertThat(data.path("stats").path("upcomingVisits").asInt()).as("past CONFIRMED + future CANCELLED excluded")
                .isEqualTo(2);
        assertThat(OffsetDateTime.parse(data.path("firstVisitAt").asText()).toInstant()).isEqualTo(declinedOld);
        assertThat(OffsetDateTime.parse(data.path("lastVisitAt").asText()).toInstant())
                .as("last = latest past COMPLETED/CONFIRMED, not the later NOT_COMPLETED").isEqualTo(confirmedPast);
        assertThat(OffsetDateTime.parse(data.path("nextVisitAt").asText()).toInstant())
                .as("next = earliest future CONFIRMED, not the earlier CANCELLED").isEqualTo(confirmedNear);
    }

    @Test
    @DisplayName("19: an unrated registered client has a null ratingAvg")
    void should_returnNullRating_when_registeredClientHasNoReviews() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Нова", "Без Оцінок");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));

        JsonNode data = ok(fx.get(fx.token(s.ownerEmail()), userCard(s.salonId(), c)));

        assertThat(data.path("ratingAvg").isNull()).isTrue();
        assertThat(data.path("kind").asText()).isEqualTo("REGISTERED");
    }

    @Test
    @DisplayName("20: visits size above 50 is clamped to 50; page 1000 is accepted (empty), page 1001 is 400")
    void should_clampSizeAndRejectHugePage_when_visitsPagedOutOfBounds() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Багатовізитна", "Клієнтка");
        for (int i = 0; i < 55; i++) {
            fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-200 + i));
        }
        String token = fx.token(s.ownerEmail());
        String url = userCard(s.salonId(), c) + "/visits";

        JsonNode huge = ok(fx.get(token, url + "?size=500"));
        JsonNode fifty = ok(fx.get(token, url + "?size=50"));
        JsonNode fiftyOne = ok(fx.get(token, url + "?size=51"));
        JsonNode lastAllowedPage = ok(fx.get(token, url + "?page=1000"));
        ResponseEntity<String> overLimit = fx.get(token, url + "?page=1001");

        assertThat(huge.path("data")).as("size=500 clamped").hasSize(50);
        assertThat(huge.path("totalElements").asLong()).isEqualTo(55);
        assertThat(fifty.path("data")).hasSize(50);
        assertThat(fiftyOne.path("data")).as("size=51 clamped").hasSize(50);
        assertThat(lastAllowedPage.path("data")).isEmpty();
        assertThat(lastAllowedPage.path("totalElements").asLong()).isEqualTo(55);
        assertThat(overLimit.getStatusCode()).as(overLimit.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("20b: the page-number ceiling also guards the guest and master visit endpoints")
    void should_return400_when_pageAboveCeilingOnGuestAndMasterVisits() throws Exception {
        Salon s = fx.salon();
        UUID anchor = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гість", "Сторінка", "COMPLETED", fx.at(-5));

        ResponseEntity<String> salonGuest = fx.get(fx.token(s.ownerEmail()), guestCard(s.salonId(), anchor) + "/visits?page=1001");
        ResponseEntity<String> masterGuest = fx.get(fx.token(s.masterEmail()), ME_URL + "/guests/" + anchor + "/visits?page=1001");
        ResponseEntity<String> masterGuestOk = fx.get(fx.token(s.masterEmail()),
                ME_URL + "/guests/" + anchor + "/visits?size=500");

        assertThat(salonGuest.getStatusCode()).as(salonGuest.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(masterGuest.getStatusCode()).as(masterGuest.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(visitRows(masterGuestOk)).hasSize(1);
    }

    @Test
    @DisplayName("auth: every salon-family card/visit path (users, guests, +visits) - 401 anonymous; 403 SALON_MASTER, "
            + "CLIENT, INDEPENDENT_MASTER, another salon's owner and admin; 200 owner and own admin")
    void should_enforceRoleMatrixOnAllSalonPaths_when_called() throws Exception {
        Salon s = fx.salon();
        Salon t = fx.salon();
        UUID c = fx.client("Клієнтка", "Матриця Салону");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));
        UUID anchor = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гість", "Матриці", "COMPLETED", fx.at(-4));
        String clientEmail = fx.email("viewer");
        fx.user(clientEmail, "CLIENT", null, "Хтось", "Клієнт");
        Independent indep = fx.independent();
        String ownAdmin = fx.admin(s.salonId());
        String foreignAdmin = fx.admin(t.salonId());
        Map<String, String> denied = new java.util.LinkedHashMap<>();
        denied.put("SALON_MASTER", fx.token(s.masterEmail()));
        denied.put("CLIENT", fx.token(clientEmail));
        denied.put("INDEPENDENT_MASTER", fx.token(indep.email()));
        denied.put("foreign SALON_OWNER", fx.token(t.ownerEmail()));
        denied.put("foreign SALON_ADMIN", fx.token(foreignAdmin));
        String ownerToken = fx.token(s.ownerEmail());
        String adminToken = fx.token(ownAdmin);
        List<String> paths = List.of(userCard(s.salonId(), c), userCard(s.salonId(), c) + "/visits",
                guestCard(s.salonId(), anchor), guestCard(s.salonId(), anchor) + "/visits");

        for (String path : paths) {
            assertThat(fx.get(null, path).getStatusCode()).as("anonymous %s", path).isEqualTo(HttpStatus.UNAUTHORIZED);
            denied.forEach((role, tok) -> assertThat(fx.get(tok, path).getStatusCode())
                    .as("%s on %s", role, path).isEqualTo(HttpStatus.FORBIDDEN));
            assertThat(fx.get(ownerToken, path).getStatusCode()).as("owner %s", path).isEqualTo(HttpStatus.OK);
            assertThat(fx.get(adminToken, path).getStatusCode()).as("own admin %s", path).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("auth: every master-family card/visit path - 401 anonymous; 403 owner, admin, CLIENT; 200 SALON_MASTER "
            + "and INDEPENDENT_MASTER")
    void should_enforceRoleMatrixOnAllMasterPaths_when_called() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Клієнтка", "Матриця Майстра");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));
        UUID anchor = fx.guestBooking(s.masterId(), s.salonId(), GUEST_PHONE, "Гість", "Майстра", "COMPLETED", fx.at(-4));
        Independent indep = fx.independent();
        fx.booking(indep.masterId(), null, c, "COMPLETED", fx.at(-3));
        UUID indepAnchor = fx.guestBooking(indep.masterId(), null, GUEST_PHONE, "Гість", "Соло", "COMPLETED", fx.at(-2));
        String clientEmail = fx.email("viewer");
        fx.user(clientEmail, "CLIENT", null, "Хтось", "Клієнт");
        Map<String, String> denied = new java.util.LinkedHashMap<>();
        denied.put("SALON_OWNER", fx.token(s.ownerEmail()));
        denied.put("SALON_ADMIN", fx.token(fx.admin(s.salonId())));
        denied.put("CLIENT", fx.token(clientEmail));
        String masterToken = fx.token(s.masterEmail());
        String indepToken = fx.token(indep.email());

        for (String suffix : List.of("/users/" + c, "/users/" + c + "/visits")) {
            String path = ME_URL + suffix;
            assertThat(fx.get(null, path).getStatusCode()).as("anonymous %s", path).isEqualTo(HttpStatus.UNAUTHORIZED);
            denied.forEach((role, tok) -> assertThat(fx.get(tok, path).getStatusCode())
                    .as("%s on %s", role, path).isEqualTo(HttpStatus.FORBIDDEN));
            assertThat(fx.get(masterToken, path).getStatusCode()).as("salon master %s", path).isEqualTo(HttpStatus.OK);
            assertThat(fx.get(indepToken, path).getStatusCode()).as("independent %s", path).isEqualTo(HttpStatus.OK);
        }
        for (String path : List.of(ME_URL + "/guests/" + anchor, ME_URL + "/guests/" + anchor + "/visits")) {
            assertThat(fx.get(null, path).getStatusCode()).as("anonymous %s", path).isEqualTo(HttpStatus.UNAUTHORIZED);
            denied.forEach((role, tok) -> assertThat(fx.get(tok, path).getStatusCode())
                    .as("%s on %s", role, path).isEqualTo(HttpStatus.FORBIDDEN));
            assertThat(fx.get(masterToken, path).getStatusCode()).as("salon master %s", path).isEqualTo(HttpStatus.OK);
            assertThat(fx.get(indepToken, path).getStatusCode()).as("independent on foreign anchor %s", path)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(fx.get(indepToken, ME_URL + "/guests/" + indepAnchor).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("24: an anonymised row sharing a visit with an in-scope guest row is absent from the visit's items")
    void should_omitAnonymisedRowFromItems_when_itSharesAVisitWithAnInScopeGuestRow() throws Exception {
        Salon s = fx.salon();
        UUID visit = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO appointments (id, client_id, salon_id, status, booking_source, guest_name, "
                + "guest_phone, created_at, updated_at) VALUES (?, NULL, ?, 'COMPLETED', 'LINK', 'Гість', ?, NOW(), "
                + "NOW())", visit, s.salonId(), GUEST_PHONE);
        UUID inScope = fx.insertBooking(s.masterId(), s.salonId(), fx.masterService(s.masterId(), "SALON",
                s.salonId()), null, "COMPLETED", fx.at(-6), visit, "LINK", "Гість", "Клієнт", GUEST_PHONE, false);
        UUID anonymised = fx.insertBooking(s.masterId(), s.salonId(), fx.masterService(s.masterId(), "SALON",
                s.salonId()), null, "COMPLETED", fx.at(-6), visit, "LINK", "Гість", null, null, true);
        String token = fx.token(s.ownerEmail());

        List<JsonNode> visits = visitRows(fx.get(token, guestCard(s.salonId(), inScope) + "/visits"));

        assertThat(visits).hasSize(1);
        assertThat(visits.get(0).path("visitKey").asText()).isEqualTo(visit.toString());
        assertThat(visits.get(0).path("items").findValuesAsText("bookingId")).containsExactly(inScope.toString());
        assertThat(visits.toString()).doesNotContain(anonymised.toString());
    }
}
