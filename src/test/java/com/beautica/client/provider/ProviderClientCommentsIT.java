package com.beautica.client.provider;

import com.beautica.client.provider.ProviderClientFixtures.Independent;
import com.beautica.support.HibernateStatistics;
import com.beautica.support.NotATimedTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B565 (REV-44, REV-D10, REV-13, MOD-41) — the anonymous cross-provider comments page on the client card, against real
 * Postgres: every visible non-blank comment from any provider (pre-existing rows included), newest first, exactly five
 * keys, a per-viewer {@code own} flag, the card's 404/403 gate, the guest (phone) arm and its attach move, the client
 * never seeing the text, and {@code CLIENT_REVIEW} reports by readers without auto-hide.
 */
@DisplayName("ProviderClient comments — B565 REV-44")
class ProviderClientCommentsIT extends AbstractClientRatingSubjectIT {

    private static final String SALON_URL = "/api/v1/salons/%s/clients";
    private static final String ME_URL = "/api/v1/masters/me/clients";
    private static final String MARKER = "КОМЕНТАР-B565-МАРКЕР";
    private static final List<String> KEYS = List.of("reviewId", "visitMonth", "stars", "comment", "own");
    /**
     * Per request: the @authz salon-access lookup + the card gate + the page carrying its total (list pattern,
     * ProviderClientControllerIT 18). Both measured pages are FULL, so the bound holds whether or not the count is skipped
     * on a short page (PageableExecutionUtils).
     */
    /** Mirrors /ratings (ProviderClientRatingIT #13 = 4): salon-access + gate/anchor + page + count. */
    private static final long MAX_STATEMENTS = 4;

    @Autowired private EntityManagerFactory entityManagerFactory;

    // ── seeding ──────────────────────────────────────────────────────────────

    private UUID completed(UUID masterId, UUID salonId, UUID clientId, int daysAgo) {
        return fx.booking(masterId, salonId, clientId, "COMPLETED", fx.at(-daysAgo));
    }

    /** A pre-existing (raw-inserted) review about an account, written {@code hoursAgo} ago. */
    private UUID review(UUID bookingId, UUID clientId, UUID authorMasterId, UUID salonId, int stars, String comment,
                        String moderation, int hoursAgo) {
        return fx.clientReviewAt(bookingId, clientId, null, authorMasterId, salonId, stars, comment, moderation,
                Instant.now().minus(hoursAgo, ChronoUnit.HOURS));
    }

    private UUID visible(UUID bookingId, UUID clientId, UUID authorMasterId, UUID salonId, String comment,
                         int hoursAgo) {
        return review(bookingId, clientId, authorMasterId, salonId, 4, comment, "VISIBLE", hoursAgo);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private static String salonUser(UUID salonId, UUID userId) {
        return SALON_URL.formatted(salonId) + "/users/" + userId + "/comments";
    }

    private static String salonGuest(UUID salonId, UUID bookingId) {
        return SALON_URL.formatted(salonId) + "/guests/" + bookingId + "/comments";
    }

    private static String meUser(UUID userId) {
        return ME_URL + "/users/" + userId + "/comments";
    }

    private static String meGuest(UUID bookingId) {
        return ME_URL + "/guests/" + bookingId + "/comments";
    }

    private JsonNode ok(ResponseEntity<String> resp) throws Exception {
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private static List<UUID> reviewIds(JsonNode page) {
        List<UUID> out = new ArrayList<>();
        page.path("data").forEach(r -> out.add(UUID.fromString(r.path("reviewId").asText())));
        return out;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(out::add);
        return out;
    }

    private static boolean ownOf(JsonNode page, UUID reviewId) {
        for (JsonNode r : page.path("data")) {
            if (r.path("reviewId").asText().equals(reviewId.toString())) {
                return r.path("own").asBoolean();
            }
        }
        throw new AssertionError("review " + reviewId + " not on the page: " + page);
    }

    private String withoutRequestId(ResponseEntity<String> resp) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(resp.getBody());
        body.remove("requestId");
        return body.toString();
    }

    /** A {@code ?masterId=} outside the salon (foreign or unknown) = the card's own 404 body, user and guest arm. */
    private void assertForeignMasterIdIsCardNotFound(String owner, ProviderClientFixtures.Salon a, UUID masterId,
                                                     UUID userId, UUID guestAnchor) throws Exception {
        String query = "?masterId=" + masterId;
        ResponseEntity<String> userCard = fx.get(owner, SALON_URL.formatted(a.salonId()) + "/users/" + userId + query);
        ResponseEntity<String> guestCard = fx.get(owner, guestCardUrl(a.salonId(), guestAnchor) + query);
        ResponseEntity<String> userComments = fx.get(owner, salonUser(a.salonId(), userId) + query);
        ResponseEntity<String> guestComments = fx.get(owner, salonGuest(a.salonId(), guestAnchor) + query);

        assertThat(userCard.getStatusCode()).as(userCard.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(guestCard.getStatusCode()).as(guestCard.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(userComments.getStatusCode()).as("user comments, masterId=%s: %s", masterId, userComments.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(guestComments.getStatusCode()).as("guest comments, masterId=%s: %s", masterId,
                guestComments.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutRequestId(userComments)).isEqualTo(withoutRequestId(userCard));
        assertThat(withoutRequestId(guestComments)).isEqualTo(withoutRequestId(guestCard));
    }

    private ResponseEntity<String> report(String token, UUID targetId) {
        return postAs(token, "/api/v1/reports",
                Map.of("targetType", "CLIENT_REVIEW", "targetId", targetId.toString(), "reason", "OFFENSIVE"));
    }

    // ── cases ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("provider B pages provider A's pre-existing comment on the salon and master endpoints, anonymously, "
            + "with exactly the five keys")
    void should_showOtherProvidersCommentAnonymously_when_registeredCardCommentsRead() throws Exception {
        ProviderClientFixtures.Salon a = fx.salon();
        ProviderClientFixtures.Salon b = fx.salon();
        Independent im = fx.independent();
        UUID c = fx.client("Оксана", "Коментар");
        UUID onA = completed(a.masterId(), a.salonId(), c, 9);
        completed(b.masterId(), b.salonId(), c, 8);
        completed(im.masterId(), null, c, 7);
        UUID reviewId = visible(onA, c, a.masterId(), a.salonId(), MARKER, 1);

        List<ResponseEntity<String>> reads = List.of(
                fx.get(fx.token(b.ownerEmail()), salonUser(b.salonId(), c)),
                fx.get(fx.token(b.masterEmail()), meUser(c)),
                fx.get(fx.token(im.email()), meUser(c)));

        for (ResponseEntity<String> resp : reads) {
            JsonNode page = ok(resp);
            assertThat(reviewIds(page)).containsExactly(reviewId);
            JsonNode item = page.path("data").get(0);
            assertThat(fieldNames(item)).containsExactlyInAnyOrderElementsOf(KEYS);
            assertThat(item.path("comment").asText()).isEqualTo(MARKER);
            assertThat(item.path("stars").asInt()).isEqualTo(4);
            assertThat(item.path("own").asBoolean()).isFalse();
            assertThat(resp.getBody()).doesNotContain("author").doesNotContain("serviceName")
                    .doesNotContain("salonId").doesNotContain("masterId")
                    .doesNotContain(a.salonId().toString()).doesNotContain(a.masterId().toString())
                    .doesNotContain(fx.serviceNameOf(onA));
        }
    }

    @Test
    @DisplayName("guest arm: a phone-keyed comment from salon A is on salon B's guest card (owner + salon master)")
    void should_showPhoneKeyedComment_when_guestCardCommentsRead() throws Exception {
        ProviderClientFixtures.Salon a = fx.salon();
        ProviderClientFixtures.Salon b = fx.salon();
        String phone = newPhone();
        UUID onA = completedGuest(a, "LINK", phone);
        UUID anchorB = completedGuest(b, "STAFF", phone);
        UUID reviewId = fx.clientReviewAt(onA, null, phone, a.masterId(), a.salonId(), 2, MARKER, "VISIBLE",
                Instant.now().minus(1, ChronoUnit.HOURS));

        JsonNode owner = ok(fx.get(fx.token(b.ownerEmail()), salonGuest(b.salonId(), anchorB)));
        JsonNode master = ok(fx.get(fx.token(b.masterEmail()), meGuest(anchorB)));

        for (JsonNode page : List.of(owner, master)) {
            assertThat(reviewIds(page)).containsExactly(reviewId);
            assertThat(fieldNames(page.path("data").get(0))).containsExactlyInAnyOrderElementsOf(KEYS);
            assertThat(page.path("data").get(0).path("own").asBoolean()).isFalse();
        }
    }

    @Test
    @DisplayName("hidden, removed, null, blank and whitespace-only comments are excluded; newest first; total counts "
            + "the listed ones only")
    void should_listOnlyVisibleNonBlankNewestFirst_when_commentsMixed() throws Exception {
        ProviderClientFixtures.Salon a = fx.salon();
        UUID c = fx.client("Фільтр", "Коментарів");
        UUID older = visible(completed(a.masterId(), a.salonId(), c, 20), c, a.masterId(), a.salonId(), "Старіший", 30);
        UUID newest = visible(completed(a.masterId(), a.salonId(), c, 19), c, a.masterId(), a.salonId(), "Новіший", 2);
        UUID middle = visible(completed(a.masterId(), a.salonId(), c, 18), c, a.masterId(), a.salonId(), "Середній", 10);
        review(completed(a.masterId(), a.salonId(), c, 17), c, a.masterId(), a.salonId(), 1, "Прихований",
                "HIDDEN_PENDING", 1);
        review(completed(a.masterId(), a.salonId(), c, 16), c, a.masterId(), a.salonId(), 1, "Видалений", "REMOVED", 1);
        visible(completed(a.masterId(), a.salonId(), c, 15), c, a.masterId(), a.salonId(), null, 1);
        visible(completed(a.masterId(), a.salonId(), c, 14), c, a.masterId(), a.salonId(), "   ", 1);
        visible(completed(a.masterId(), a.salonId(), c, 13), c, a.masterId(), a.salonId(), "\n\t ", 1);

        JsonNode page = ok(fx.get(fx.token(a.ownerEmail()), salonUser(a.salonId(), c)));

        assertThat(reviewIds(page)).containsExactly(newest, middle, older);
        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        assertThat(page.toString()).doesNotContain("Прихований").doesNotContain("Видалений");
    }

    @Test
    @DisplayName("visitMonth is the rated visit's month in Europe/Kyiv (31 Jan 22:30 UTC = February)")
    void should_returnKyivVisitMonth_when_visitCrossesMidnight() throws Exception {
        ProviderClientFixtures.Salon a = fx.salon();
        UUID c = fx.client("Місяць", "Візиту");
        UUID booking = fx.booking(a.masterId(), a.salonId(), c, "COMPLETED", Instant.parse("2025-01-31T22:30:00Z"));
        visible(booking, c, a.masterId(), a.salonId(), MARKER, 1);

        JsonNode page = ok(fx.get(fx.token(a.ownerEmail()), salonUser(a.salonId(), c)));

        assertThat(page.path("data").get(0).path("visitMonth").asText()).isEqualTo("2025-02");
    }

    @Test
    @DisplayName("55 comments: default size 20, size 60 clamped to 50, page 1001 is 400")
    void should_clampPageSize_when_sizeAboveFifty() throws Exception {
        ProviderClientFixtures.Salon a = fx.salon();
        UUID c = fx.client("Багато", "Коментарів");
        for (int i = 0; i < 55; i++) {
            visible(completed(a.masterId(), a.salonId(), c, 200 - i), c, a.masterId(), a.salonId(), "К" + i, i + 1);
        }
        String token = fx.token(a.ownerEmail());
        String url = salonUser(a.salonId(), c);

        JsonNode first = ok(fx.get(token, url));
        JsonNode big = ok(fx.get(token, url + "?size=60"));
        ResponseEntity<String> over = fx.get(token, url + "?page=1001");

        assertThat(first.path("data")).hasSize(20);
        assertThat(first.path("totalElements").asLong()).isEqualTo(55);
        assertThat(big.path("data")).hasSize(50);
        assertThat(big.path("size").asInt()).isEqualTo(50);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("statement budget: at most salon-access + gate + page + count, the same for a 2-row and a 50-row page "
            + "and never above /ratings for the same client (registered and guest arm)")
    @NotATimedTest(reason = "Seeds 52 registered + 52 guest rated visits BECAUSE a 50-row page against a 2-row page "
            + "IS the experiment (size-independence of the statement count); the wall clock measures the fixture.")
    void should_issueAtMostFourStatements_when_commentsPaged() throws Exception {
        ProviderClientFixtures.Salon a = fx.salon();
        UUID c = fx.client("Бюджет", "Запитів");
        String phone = newPhone();
        UUID anchor = completedGuest(a, "STAFF", phone);
        for (int i = 0; i < 52; i++) {
            visible(completed(a.masterId(), a.salonId(), c, 300 - i), c, a.masterId(), a.salonId(), "К" + i, i + 1);
            UUID guestVisit = fx.guestBooking(a.masterId(), a.salonId(), "LINK", phone, "Іванна", "Коваль", "COMPLETED",
                    fx.at(-400 + i));
            fx.clientReviewAt(guestVisit, null, phone, a.masterId(), a.salonId(), 3, "Г" + i,
                    "VISIBLE", Instant.now().minus(i + 1, ChronoUnit.HOURS));
        }
        String token = fx.token(a.ownerEmail());
        Statistics statistics = HibernateStatistics.enabledOn(entityManagerFactory);

        for (String url : List.of(salonUser(a.salonId(), c), salonGuest(a.salonId(), anchor))) {
            fx.get(token, url + "?size=2"); // warm the authz memo / any one-off lookups

            long before = statistics.getPrepareStatementCount();
            JsonNode small = ok(fx.get(token, url + "?size=2"));
            long smallPage = statistics.getPrepareStatementCount() - before;
            before = statistics.getPrepareStatementCount();
            JsonNode large = ok(fx.get(token, url + "?size=50"));
            long largePage = statistics.getPrepareStatementCount() - before;
            String ratingsUrl = url.replace("/comments", "/ratings");
            fx.get(token, ratingsUrl + "?size=2"); // same warm-up as the comments read
            before = statistics.getPrepareStatementCount();
            ok(fx.get(token, ratingsUrl + "?size=2"));
            long ratingsPage = statistics.getPrepareStatementCount() - before;

            assertThat(small.path("data")).as(url).hasSize(2);
            assertThat(large.path("data")).as(url).hasSize(50);
            assertThat(large.path("totalElements").asLong()).as(url).isEqualTo(52);
            assertThat(smallPage).as("statements for a 2-row page vs a 50-row page, %s", url).isEqualTo(largePage);
            assertThat(largePage).as("salon-access + gate + page + count, %s", url)
                    .isLessThanOrEqualTo(MAX_STATEMENTS);
            assertThat(smallPage).as("comments vs /ratings for the same client (parity), %s", url)
                    .isLessThanOrEqualTo(ratingsPage);
        }
    }

    @Test
    @DisplayName("short first page skips the count: 1 comment at size 50 issues one statement fewer than the same "
            + "client at size 1 (full page), and totalElements is still 1")
    void should_skipCountStatement_when_firstPageIsShort() throws Exception {
        ProviderClientFixtures.Salon a = fx.salon();
        UUID c = fx.client("Коротка", "Сторінка");
        visible(completed(a.masterId(), a.salonId(), c, 5), c, a.masterId(), a.salonId(), MARKER, 1);
        String token = fx.token(a.ownerEmail());
        String url = salonUser(a.salonId(), c);
        Statistics statistics = HibernateStatistics.enabledOn(entityManagerFactory);
        fx.get(token, url + "?size=1"); // warm the authz memo / any one-off lookups

        long before = statistics.getPrepareStatementCount();
        JsonNode full = ok(fx.get(token, url + "?size=1"));
        long fullPage = statistics.getPrepareStatementCount() - before;
        before = statistics.getPrepareStatementCount();
        JsonNode shortPage = ok(fx.get(token, url + "?size=50"));
        long shortPageStatements = statistics.getPrepareStatementCount() - before;

        assertThat(full.path("data")).hasSize(1);
        assertThat(shortPage.path("data")).hasSize(1);
        assertThat(shortPage.path("totalElements").asLong()).isEqualTo(1);
        assertThat(fullPage).as("salon-access + gate + page + count on a full page").isEqualTo(MAX_STATEMENTS);
        assertThat(shortPageStatements).as("count skipped on a short first page").isEqualTo(MAX_STATEMENTS - 1);
    }

    @Test
    @DisplayName("own per viewer kind: salon owner/admin = the salon's rows, salon master = authored rows, "
            + "independent master = own solo rows; everyone else false")
    void should_computeOwnPerViewer_when_severalProvidersRated() throws Exception {
        ProviderClientFixtures.Salon a = fx.salon();
        Independent im = fx.independent();
        Object[] second = fx.secondMaster(a.salonId(), "Друга");
        UUID secondMaster = (UUID) second[0];
        UUID c = fx.client("Власні", "Позначки");
        UUID bySalon = visible(completed(a.masterId(), a.salonId(), c, 9), c, a.masterId(), a.salonId(), "Салон", 3);
        UUID byIm = visible(completed(im.masterId(), null, c, 8), c, im.masterId(), null, "Соло", 2);
        completed(secondMaster, a.salonId(), c, 7);

        JsonNode owner = ok(fx.get(fx.token(a.ownerEmail()), salonUser(a.salonId(), c)));
        JsonNode admin = ok(fx.get(fx.token(fx.admin(a.salonId())), salonUser(a.salonId(), c)));
        JsonNode author = ok(fx.get(fx.token(a.masterEmail()), meUser(c)));
        JsonNode otherMaster = ok(fx.get(fx.token((String) second[1]), meUser(c)));
        JsonNode solo = ok(fx.get(fx.token(im.email()), meUser(c)));

        for (JsonNode salonSide : List.of(owner, admin, author)) {
            assertThat(ownOf(salonSide, bySalon)).isTrue();
            assertThat(ownOf(salonSide, byIm)).isFalse();
        }
        assertThat(ownOf(otherMaster, bySalon)).as("a salon master owns only what they authored").isFalse();
        assertThat(ownOf(otherMaster, byIm)).isFalse();
        assertThat(ownOf(solo, byIm)).isTrue();
        assertThat(ownOf(solo, bySalon)).isFalse();
    }

    @Test
    @DisplayName("out-of-scope user / guest anchor / unknown id = the card's identical 404; auth matrix 401/403")
    void should_returnCardGate_when_viewerCannotOpenCard() throws Exception {
        ProviderClientFixtures.Salon a = fx.salon();
        ProviderClientFixtures.Salon stranger = fx.salon();
        UUID c = fx.client("Чужий", "Клієнт");
        completed(a.masterId(), a.salonId(), c, 9);
        UUID guest = completedGuest(a, "LINK", newPhone());
        String strangerOwner = fx.token(stranger.ownerEmail());
        ResponseEntity<String> card = fx.get(strangerOwner, SALON_URL.formatted(stranger.salonId()) + "/users/" + c);

        List<ResponseEntity<String>> notFound = List.of(
                fx.get(strangerOwner, salonUser(stranger.salonId(), c)),
                fx.get(strangerOwner, salonUser(stranger.salonId(), UUID.randomUUID())),
                fx.get(strangerOwner, salonGuest(stranger.salonId(), guest)),
                fx.get(fx.token(stranger.masterEmail()), meUser(c)),
                fx.get(fx.token(stranger.masterEmail()), meGuest(guest)));

        assertThat(card.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for (ResponseEntity<String> r : notFound) {
            assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(withoutRequestId(r)).isEqualTo(withoutRequestId(card));
        }
        String client = fx.token(emailOf(c));
        String owner = fx.token(a.ownerEmail());
        assertForeignMasterIdIsCardNotFound(owner, a, stranger.masterId(), c, guest);
        assertForeignMasterIdIsCardNotFound(owner, a, UUID.randomUUID(), c, guest);
        String master = fx.token(a.masterEmail());
        for (String path : List.of(salonUser(a.salonId(), c), salonGuest(a.salonId(), guest))) {
            assertThat(fx.get(null, path).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(fx.get(client, path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(fx.get(master, path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(fx.get(strangerOwner, path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(fx.get(owner, path).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        for (String path : List.of(meUser(c), meGuest(guest))) {
            assertThat(fx.get(null, path).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(fx.get(client, path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(fx.get(owner, path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(fx.get(master, path).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("REV-13 pin: the comment text never appears in any client-facing body (/users/me/rating, /users/me, "
            + "/reviews/me, /bookings/me, /bookings/{id}) while the provider page carries it")
    void should_neverSerializeCommentToClient_when_clientReadsOwnData() throws Exception {
        ProviderClientFixtures.Salon a = fx.salon();
        UUID c = fx.client("Клієнтка", "Пін");
        UUID booking = completed(a.masterId(), a.salonId(), c, 9);
        visible(booking, c, a.masterId(), a.salonId(), MARKER, 1);
        String client = fx.token(emailOf(c));

        List<ResponseEntity<String>> clientReads = List.of(
                fx.get(client, "/api/v1/users/me/rating"),
                fx.get(client, "/api/v1/users/me"),
                fx.get(client, "/api/v1/reviews/me"),
                fx.get(client, "/api/v1/bookings/me"),
                fx.get(client, "/api/v1/bookings/" + booking));
        ResponseEntity<String> provider = fx.get(fx.token(a.ownerEmail()), salonUser(a.salonId(), c));

        for (ResponseEntity<String> r : clientReads) {
            assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody()).doesNotContain(MARKER);
        }
        assertThat(provider.getBody()).as("non-vacuous: the provider page carries the text").contains(MARKER);
    }

    @Test
    @DisplayName("attach: a guest's phone-keyed comment is on the guest card, then after registration on the account "
            + "card — never on /users/me/rating")
    void should_followAttachToAccountCard_when_guestRegisters() throws Exception {
        ProviderClientFixtures.Salon a = fx.salon();
        String phone = newPhone();
        UUID link = completedGuest(a, "LINK", phone);
        String owner = fx.token(a.ownerEmail());
        rateOk(owner, link, 5, MARKER);

        JsonNode asGuest = ok(fx.get(owner, salonGuest(a.salonId(), link)));
        register(phone);
        UUID account = userIdOfPhone(phone);
        JsonNode asAccount = ok(fx.get(owner, salonUser(a.salonId(), account)));
        JsonNode mine = myRating(accessToken(phone));

        assertThat(asGuest.path("data").get(0).path("comment").asText()).isEqualTo(MARKER);
        assertThat(asAccount.path("data").get(0).path("comment").asText()).isEqualTo(MARKER);
        assertThat(asAccount.path("data").get(0).path("own").asBoolean()).isTrue();
        assertThat(mine.toString()).doesNotContain(MARKER);
    }

    @Test
    @DisplayName("MOD-41: a reader provider reports the CLIENT_REVIEW (201); a non-reader gets the unknown-id 404 body; "
            + "three provider reports leave it VISIBLE")
    void should_letReadersReportWithoutAutoHide_when_clientReviewReported() throws Exception {
        ProviderClientFixtures.Salon a = fx.salon();
        ProviderClientFixtures.Salon b = fx.salon();
        Independent im = fx.independent();
        ProviderClientFixtures.Salon stranger = fx.salon();
        UUID c = fx.client("Скарга", "Коментар");
        UUID reviewId = visible(completed(a.masterId(), a.salonId(), c, 9), c, a.masterId(), a.salonId(), MARKER, 1);
        completed(b.masterId(), b.salonId(), c, 8);
        completed(im.masterId(), null, c, 7);
        String strangerToken = fx.token(stranger.ownerEmail());

        ResponseEntity<String> byOwner = report(fx.token(b.ownerEmail()), reviewId);
        ResponseEntity<String> byMaster = report(fx.token(b.masterEmail()), reviewId);
        ResponseEntity<String> bySolo = report(fx.token(im.email()), reviewId);
        ResponseEntity<String> byStranger = report(strangerToken, reviewId);
        ResponseEntity<String> unknown = report(strangerToken, UUID.randomUUID());

        for (ResponseEntity<String> r : List.of(byOwner, byMaster, bySolo)) {
            assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(byStranger.getStatusCode()).as(byStranger.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutRequestId(byStranger)).isEqualTo(withoutRequestId(unknown));
        assertThat(jdbcTemplate.queryForObject("SELECT moderation_status FROM client_reviews WHERE id = ?",
                String.class, reviewId)).as("client reviews are never auto-hidden").isEqualTo("VISIBLE");
        assertThat(reviewIds(ok(fx.get(fx.token(b.ownerEmail()), salonUser(b.salonId(), c)))))
                .containsExactly(reviewId);
    }
}
