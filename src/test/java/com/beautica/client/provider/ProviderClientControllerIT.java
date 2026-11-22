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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B507 — {@code GET /salons/{salonId}/clients} and {@code GET /masters/me/clients} (CLI-38..41) against real
 * Postgres. Rows are seeded with raw SQL (the moderation-IT convention) because the cases need exact control of
 * status, start time, guest PII columns and appointment grouping that the entity factories do not expose; every
 * provider token is a real login.
 */
@DisplayName("ProviderClientController — B507")
class ProviderClientControllerIT extends AbstractIntegrationTest {

    private static final String SALON_URL = "/api/v1/salons/%s/clients";
    private static final String ME_URL = "/api/v1/masters/me/clients";
    private static final String GUEST_PHONE = "+380501112233";

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

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private ResponseEntity<String> salonList(String token, UUID salonId, String query) {
        return fx.get(token, SALON_URL.formatted(salonId) + (query == null ? "" : "?" + query));
    }

    private JsonNode json(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readTree(resp.getBody());
    }

    private List<JsonNode> rows(ResponseEntity<String> resp) throws Exception {
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        List<JsonNode> out = new ArrayList<>();
        json(resp).path("data").path("data").forEach(out::add);
        return out;
    }

    private static JsonNode byName(List<JsonNode> rows, String name) {
        return rows.stream().filter(r -> name.equals(r.path("displayName").asText(null))).findFirst()
                .orElseThrow(() -> new AssertionError("no row named " + name));
    }

    private Instant startsAt(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT starts_at FROM bookings WHERE id = ?", Instant.class, bookingId);
    }

    // ── cases ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1/2 (B527 — was: never merged): a same-phone VERIFIED account takes the attached guest rows — two rows, the account row counts all its visits")
    void should_listTwoRows_when_guestRowsAreAttachedToTheAccount() throws Exception {
        Salon s = fx.salon();
        UUID r1 = fx.client("Анна", "Коваль");
        fx.booking(s.masterId(), s.salonId(), r1, "COMPLETED", fx.at(-20));
        UUID visit = fx.appointment(r1, s.salonId());
        fx.bookingInVisit(s.masterId(), s.salonId(), r1, "COMPLETED", fx.at(-10), visit);
        fx.bookingInVisit(s.masterId(), s.salonId(), r1, "COMPLETED", fx.at(-10), visit);
        fx.bookingInVisit(s.masterId(), s.salonId(), r1, "COMPLETED", fx.at(-10), visit);
        fx.guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Іванна", "Коваль", "COMPLETED", fx.at(-8));
        fx.guestBooking(s.masterId(), s.salonId(), "STAFF", GUEST_PHONE, "Іванна", "Коваль", "COMPLETED", fx.at(-6));
        UUID r2 = fx.client("Петро", "Сидоренко");
        jdbcTemplate.update("UPDATE users SET phone_number = ?, phone_verified_at = NOW() WHERE id = ?", GUEST_PHONE, r2);
        fx.booking(s.masterId(), s.salonId(), r2, "COMPLETED", fx.at(-4));
        transactionTemplate.executeWithoutResult(st -> attachService.attachByPhone(GUEST_PHONE,
                com.beautica.booking.enums.AttachReason.PHONE_VERIFIED));

        List<JsonNode> rows = rows(salonList(fx.token(s.ownerEmail()), s.salonId(), null));

        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(r -> "GUEST".equals(r.path("kind").asText()))).isEmpty();
        JsonNode anna = byName(rows, "Анна Коваль");
        assertThat(anna.path("completedVisits").asInt()).as("1 single + 1 three-service visit").isEqualTo(2);
        assertThat(anna.path("userId").asText()).isEqualTo(r1.toString());
        JsonNode petro = byName(rows, "Петро Сидоренко");
        assertThat(petro.path("completedVisits").asInt()).as("1 app + the attached LINK + STAFF").isEqualTo(3);
        assertThat(petro.path("userId").asText()).isEqualTo(r2.toString());
        assertThat(json(salonList(fx.token(s.ownerEmail()), s.salonId(), null)).path("data").path("totalElements")
                .asLong()).isEqualTo(2);
    }


    @Test
    @DisplayName("1/2b: an UNVERIFIED same-phone account never takes the guest rows — registered, guest and account are three rows; a 3-service visit counts once")
    void should_listThreeRows_when_accountPhoneIsNotVerified() throws Exception {
        Salon s = fx.salon();
        UUID r1 = fx.client("Анна", "Коваль");
        fx.booking(s.masterId(), s.salonId(), r1, "COMPLETED", fx.at(-20));
        UUID visit = fx.appointment(r1, s.salonId());
        fx.bookingInVisit(s.masterId(), s.salonId(), r1, "COMPLETED", fx.at(-10), visit);
        fx.bookingInVisit(s.masterId(), s.salonId(), r1, "COMPLETED", fx.at(-10), visit);
        fx.bookingInVisit(s.masterId(), s.salonId(), r1, "COMPLETED", fx.at(-10), visit);
        fx.guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Іванна", "Коваль", "COMPLETED", fx.at(-8));
        fx.guestBooking(s.masterId(), s.salonId(), "STAFF", GUEST_PHONE, "Іванна", "Коваль", "COMPLETED", fx.at(-6));
        UUID r2 = fx.client("Петро", "Сидоренко");
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", GUEST_PHONE, r2);
        fx.booking(s.masterId(), s.salonId(), r2, "COMPLETED", fx.at(-4));

        List<JsonNode> rows = rows(salonList(fx.token(s.ownerEmail()), s.salonId(), null));

        assertThat(rows).hasSize(3);
        assertThat(rows.stream().filter(r -> "GUEST".equals(r.path("kind").asText()))).hasSize(1);
        assertThat(rows.stream().filter(r -> "REGISTERED".equals(r.path("kind").asText()))).hasSize(2);
        JsonNode anna = byName(rows, "Анна Коваль");
        assertThat(anna.path("completedVisits").asInt()).as("1 single + 1 three-service visit").isEqualTo(2);
        assertThat(anna.path("userId").asText()).isEqualTo(r1.toString());
        assertThat(anna.path("guestBookingId").isNull()).isTrue();
        JsonNode guest = byName(rows, "Іванна Коваль");
        assertThat(guest.path("completedVisits").asInt()).isEqualTo(2);
        assertThat(guest.path("userId").isNull()).isTrue();
        assertThat(guest.path("ratingCount").asInt()).isZero();
        assertThat(guest.path("ratingAvg").isNull()).isTrue();
        assertThat(json(salonList(fx.token(s.ownerEmail()), s.salonId(), null)).path("data").path("totalElements")
                .asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("3: last = latest past COMPLETED/CONFIRMED, next = earliest future CONFIRMED, CANCELLED ignored")
    void should_deriveLastAndNextVisit_when_mixedStatuses() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Марта", "Іваненко");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-10));
        ZoneId kyiv = ZoneId.of("Europe/Kyiv");
        Instant pastKyiv = ZonedDateTime.of(LocalDate.now(kyiv).minusDays(3), LocalTime.of(10, 30), kyiv).toInstant();
        Instant futureKyiv = ZonedDateTime.of(LocalDate.now(kyiv).plusDays(2), LocalTime.of(15, 45), kyiv).toInstant();
        UUID pastConfirmed = fx.booking(s.masterId(), s.salonId(), c, "CONFIRMED", pastKyiv);
        UUID futureConfirmed = fx.booking(s.masterId(), s.salonId(), c, "CONFIRMED", futureKyiv);
        fx.booking(s.masterId(), s.salonId(), c, "CANCELLED", fx.at(1));

        JsonNode row = rows(salonList(fx.token(s.ownerEmail()), s.salonId(), null)).get(0);

        assertThat(OffsetDateTime.parse(row.path("lastVisitAt").asText()).toInstant())
                .isEqualTo(startsAt(pastConfirmed));
        assertThat(OffsetDateTime.parse(row.path("nextVisitAt").asText()).toInstant())
                .isEqualTo(startsAt(futureConfirmed));
        assertThat(OffsetDateTime.parse(row.path("lastVisitAt").asText()).toInstant()).isEqualTo(pastKyiv);
        assertThat(OffsetDateTime.parse(row.path("nextVisitAt").asText()).toInstant()).isEqualTo(futureKyiv);
        assertThat(row.path("completedVisits").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("4: a client with only CANCELLED bookings is listed with no completed visits")
    void should_listClient_when_onlyCancelledBookings() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Лана", "Бойко");
        fx.booking(s.masterId(), s.salonId(), c, "CANCELLED", fx.at(-5));

        JsonNode row = rows(salonList(fx.token(s.ownerEmail()), s.salonId(), null)).get(0);

        assertThat(row.path("completedVisits").asInt()).isZero();
        assertThat(row.path("lastVisitAt").isNull()).isTrue();
        assertThat(row.path("nextVisitAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("5: anonymised guests and detached accounts are not listed and not counted")
    void should_excludeAnonymisedGuestsAndDetachedRows() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Іра", "Мельник");
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));
        fx.anonymisedGuestBooking(s.masterId(), s.salonId(), fx.at(-4));
        fx.detachedBooking(s.masterId(), s.salonId(), fx.at(-3));

        ResponseEntity<String> resp = salonList(fx.token(s.ownerEmail()), s.salonId(), null);

        assertThat(rows(resp)).hasSize(1);
        assertThat(json(resp).path("data").path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("6: a SALON_MASTER sees only the clients of their own master row")
    void should_scopeToOwnMasterRow_when_salonMasterLists() throws Exception {
        Salon s = fx.salon();
        UUID masterBUser = fx.user(fx.email("masterb"), "SALON_MASTER", s.salonId(), "Майстер", "Б");
        UUID masterB = fx.salonMaster(masterBUser, s.salonId());
        UUID own = fx.client("Власна", "Клієнтка");
        UUID other = fx.client("Чужа", "Клієнтка");
        fx.booking(s.masterId(), s.salonId(), own, "COMPLETED", fx.at(-5));
        fx.booking(masterB, s.salonId(), other, "COMPLETED", fx.at(-4));

        List<JsonNode> rows = rows(fx.get(fx.token(s.masterEmail()), ME_URL));

        assertThat(rows).extracting(r -> r.path("displayName").asText()).containsExactly("Власна Клієнтка");
    }

    @Test
    @DisplayName("7: an INDEPENDENT_MASTER sees own (salon-less) bookings only")
    void should_listOwnBookings_when_independentMaster() throws Exception {
        Independent a = fx.independent();
        Independent b = fx.independent();
        UUID own = fx.client("Своя", "Клієнтка");
        UUID other = fx.client("Інша", "Клієнтка");
        fx.booking(a.masterId(), null, own, "COMPLETED", fx.at(-5));
        fx.booking(b.masterId(), null, other, "COMPLETED", fx.at(-4));

        List<JsonNode> rows = rows(fx.get(fx.token(a.email()), ME_URL));

        assertThat(rows).extracting(r -> r.path("displayName").asText()).containsExactly("Своя Клієнтка");
    }

    @Test
    @DisplayName("salon endpoint: masterId narrows to that master's clients")
    void should_narrowToMaster_when_masterIdGiven() throws Exception {
        Salon s = fx.salon();
        UUID masterB = fx.salonMaster(fx.user(fx.email("masterb"), "SALON_MASTER", s.salonId(), "Майстер", "Б"), s.salonId());
        fx.booking(s.masterId(), s.salonId(), fx.client("Перша", "Клієнтка"), "COMPLETED", fx.at(-5));
        fx.booking(masterB, s.salonId(), fx.client("Друга", "Клієнтка"), "COMPLETED", fx.at(-4));

        List<JsonNode> rows = rows(salonList(fx.token(s.ownerEmail()), s.salonId(), "masterId=" + masterB));

        assertThat(rows).extracting(r -> r.path("displayName").asText()).containsExactly("Друга Клієнтка");
        assertThat(rows(salonList(fx.token(s.ownerEmail()), s.salonId(), null))).hasSize(2);
    }

    @Test
    @DisplayName("8: an admin of another salon and the owner of another salon get 403")
    void should_return403_when_notManagingThatSalon() throws Exception {
        Salon s = fx.salon();
        Salon t = fx.salon();
        String adminEmail = fx.email("admin");
        fx.user(adminEmail, "SALON_ADMIN", s.salonId(), "Адмін", "С");

        assertThat(salonList(fx.token(adminEmail), t.salonId(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(salonList(fx.token(s.ownerEmail()), t.salonId(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(salonList(fx.token(adminEmail), s.salonId(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("9: masterId of another salon's master is 404")
    void should_return404_when_masterBelongsToAnotherSalon() throws Exception {
        Salon s = fx.salon();
        Salon t = fx.salon();

        assertThat(salonList(fx.token(s.ownerEmail()), s.salonId(), "masterId=" + t.masterId()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("10: SALON_OWNER on /masters/me/clients 403, CLIENT on both 403, anonymous 401")
    void should_denyOtherRoles_when_authMatrix() throws Exception {
        Salon s = fx.salon();
        String clientEmail = fx.email("plain");
        fx.user(clientEmail, "CLIENT", null, "Звичайний", "Клієнт");

        assertThat(fx.get(fx.token(s.ownerEmail()), ME_URL).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fx.get(fx.token(clientEmail), ME_URL).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(salonList(fx.token(clientEmail), s.salonId(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fx.get(null, ME_URL).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(salonList(null, s.salonId(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("10b: a SALON_MASTER is denied on the salon endpoint")
    void should_return403_when_salonMasterCallsSalonEndpoint() throws Exception {
        Salon s = fx.salon();

        assertThat(salonList(fx.token(s.masterEmail()), s.salonId(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("11: a deactivated SALON_MASTER gets 403")
    void should_return403_when_masterDeactivated() throws Exception {
        Salon s = fx.salon();
        String token = fx.token(s.masterEmail());
        assertThat(fx.get(token, ME_URL).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", s.masterId());

        assertThat(fx.get(token, ME_URL).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("12: rating = VISIBLE reviews only, as number + count; no comment key anywhere")
    void should_exposeRatingNumberOnly_when_clientHasVisibleAndHiddenReviews() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Оксана", "Рейтинг");
        UUID b1 = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-9));
        UUID b2 = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-8));
        UUID b3 = fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-7));
        clientReview(b1, c, s, 5, "VISIBLE");
        clientReview(b2, c, s, 3, "VISIBLE");
        clientReview(b3, c, s, 1, "REMOVED");
        transactionTemplate.executeWithoutResult(st -> clientReviewRepository.recalculateClientRating(c));

        ResponseEntity<String> resp = salonList(fx.token(s.ownerEmail()), s.salonId(), null);

        JsonNode row = rows(resp).get(0);
        assertThat(row.path("ratingCount").asInt()).isEqualTo(2);
        assertThat(row.path("ratingAvg").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(resp.getBody()).doesNotContain("comment").doesNotContain("Запізнився");
    }

    private void clientReview(UUID bookingId, UUID clientId, Salon s, int rating, String moderation) {
        jdbcTemplate.update(
                "INSERT INTO client_reviews (id, booking_id, subject_client_id, author_master_id, salon_id, rating, "
                        + "comment, moderation_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'Запізнився', ?, NOW(), NOW())",
                UUID.randomUUID(), bookingId, clientId, s.masterId(), s.salonId(), rating, moderation);
    }

    @Test
    @DisplayName("13: blocked flag - salon-scope guest + client blocked; a foreign salon's block is ignored")
    void should_flagBlocked_when_blockedInTheViewersScope() throws Exception {
        Salon s = fx.salon();
        Salon foreign = fx.salon();
        UUID blockedClient = fx.client("Заблокована", "Клієнтка");
        UUID freeClient = fx.client("Вільна", "Клієнтка");
        fx.booking(s.masterId(), s.salonId(), blockedClient, "COMPLETED", fx.at(-9));
        fx.booking(s.masterId(), s.salonId(), freeClient, "COMPLETED", fx.at(-8));
        fx.guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Заблокований", "Гість", "COMPLETED", fx.at(-7));
        fx.guestBooking(s.masterId(), s.salonId(), "LINK", "+380677778899", "Чужий", "Блок", "COMPLETED", fx.at(-6));
        fx.blockClientInSalon(s.salonId(), blockedClient);
        fx.blockGuestInSalon(s.salonId(), GUEST_PHONE);
        fx.blockGuestInSalon(foreign.salonId(), "+380677778899");

        List<JsonNode> rows = rows(salonList(fx.token(s.ownerEmail()), s.salonId(), null));

        assertThat(byName(rows, "Заблокована Клієнтка").path("blocked").asBoolean()).isTrue();
        assertThat(byName(rows, "Заблокований Гість").path("blocked").asBoolean()).isTrue();
        assertThat(byName(rows, "Вільна Клієнтка").path("blocked").asBoolean()).isFalse();
        assertThat(byName(rows, "Чужий Блок").path("blocked").asBoolean()).as("foreign block ignored").isFalse();
        // a SALON_MASTER viewer sees the same salon-scope badge (D5)
        assertThat(byName(rows(fx.get(fx.token(s.masterEmail()), ME_URL)), "Заблокований Гість").path("blocked")
                .asBoolean()).isTrue();
    }

    @Test
    @DisplayName("13b: an independent master's own block flags their client")
    void should_flagBlocked_when_independentMasterBlockedTheGuest() throws Exception {
        Independent a = fx.independent();
        fx.guestBooking(a.masterId(), null, "LINK", GUEST_PHONE, "Гість", "Соло", "COMPLETED", fx.at(-5));
        PhoneHasher.HashedPhone hashed = phoneHasher.hash(GUEST_PHONE);
        jdbcTemplate.update("INSERT INTO client_blocks (id, blocker_master_id, phone_hash, phone_last4, display_name) "
                + "VALUES (?, ?, ?, ?, 'Гість')", UUID.randomUUID(), a.masterId(), hashed.hash(), hashed.last4());

        assertThat(rows(fx.get(fx.token(a.email()), ME_URL)).get(0).path("blocked").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("14: no phone field and no +380 anywhere in the raw body")
    void should_neverLeakAPhone_when_listing() throws Exception {
        Salon s = fx.salon();
        fx.guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Гість", "Один", "COMPLETED", fx.at(-5));
        UUID c = fx.client("Реєстрований", "Клієнт");
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", "+380671234567", c);
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-4));

        for (ResponseEntity<String> resp : List.of(salonList(fx.token(s.ownerEmail()), s.salonId(), null),
                fx.get(fx.token(s.masterEmail()), ME_URL))) {
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).doesNotContainIgnoringCase("phone").doesNotContain("+380")
                    .doesNotContain("380501112233").doesNotContain("0501112233");
        }
    }

    @Test
    @DisplayName("15: q matches case-insensitively over name parts, escapes %, 1 char is 400")
    void should_filterByName_when_qGiven() throws Exception {
        Salon s = fx.salon();
        fx.booking(s.masterId(), s.salonId(), fx.client("Анна", "Мороз"), "COMPLETED", fx.at(-5));
        fx.guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Іванна", "Лис", "COMPLETED", fx.at(-4));
        fx.booking(s.masterId(), s.salonId(), fx.client("Петро", "Вовк"), "COMPLETED", fx.at(-3));
        String token = fx.token(s.ownerEmail());

        assertThat(rows(fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "ан"))
                .stream().map(r -> r.path("displayName").asText()).toList())
                .containsExactlyInAnyOrder("Анна Мороз", "Іванна Лис");
        assertThat(rows(fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "%%"))).isEmpty();
        assertThat(rows(fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "ан%"))).isEmpty();
        assertThat(fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "%").getStatusCode())
                .as("a single % is 1 char: too short, never a wildcard").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rows(fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "_н"))).isEmpty();
        assertThat(fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "а").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", " а ").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "x".repeat(51)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("16: 45 clients page 20/20/5, totalElements 45; size=500 is clamped to 50")
    void should_page_when_manyClients() throws Exception {
        Salon s = fx.salon();
        for (int i = 0; i < 45; i++) {
            fx.booking(s.masterId(), s.salonId(), fx.client("Клієнт" + i, "Масовий"), "COMPLETED", fx.at(-100 + i));
        }
        String token = fx.token(s.ownerEmail());

        assertThat(rows(salonList(token, s.salonId(), "page=0&size=20"))).hasSize(20);
        assertThat(rows(salonList(token, s.salonId(), "page=1&size=20"))).hasSize(20);
        ResponseEntity<String> last = salonList(token, s.salonId(), "page=2&size=20");
        assertThat(rows(last)).hasSize(5);
        assertThat(json(last).path("data").path("totalElements").asLong()).isEqualTo(45);
        ResponseEntity<String> big = salonList(token, s.salonId(), "size=500");
        assertThat(rows(big)).hasSize(45);
        assertThat(json(big).path("data").path("size").asInt()).isEqualTo(50);
        assertThat(rows(salonList(token, s.salonId(), "page=0&size=20&sort=firstName,asc")).get(0)
                .path("displayName").asText()).as("any sort param is ignored: newest activity first")
                .isEqualTo("Клієнт44 Масовий");
    }

    @Test
    @DisplayName("17: page=1001 is 400")
    void should_return400_when_pageBeyondCap() throws Exception {
        Salon s = fx.salon();

        assertThat(salonList(fx.token(s.ownerEmail()), s.salonId(), "page=1001").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(salonList(fx.token(s.ownerEmail()), s.salonId(), "page=1000").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("18: at most page (total included) + blocks statements, independent of the page size")
    void should_issueAtMostThreeStatements_when_listing() throws Exception {
        Salon s = fx.salon();
        for (int i = 0; i < 6; i++) {
            fx.booking(s.masterId(), s.salonId(), fx.client("Клієнт" + i, "Статистика"), "COMPLETED", fx.at(-30 + i));
            fx.guestBooking(s.masterId(), s.salonId(), "LINK", "+38050000000" + i, "Гість" + i, "Статистика",
                    "COMPLETED", fx.at(-60 + i));
        }
        String token = fx.token(s.ownerEmail());
        Statistics statistics = HibernateStatistics.enabledOn(entityManagerFactory);
        salonList(token, s.salonId(), "size=2"); // warm the authz memo / any one-off lookups

        long before = statistics.getPrepareStatementCount();
        ResponseEntity<String> small = salonList(token, s.salonId(), "size=2");
        long smallPage = statistics.getPrepareStatementCount() - before;
        before = statistics.getPrepareStatementCount();
        ResponseEntity<String> large = salonList(token, s.salonId(), "size=12");
        long largePage = statistics.getPrepareStatementCount() - before;

        assertThat(rows(small)).hasSize(2);
        assertThat(rows(large)).hasSize(12);
        assertThat(smallPage).as("statements for a 2-row page").isEqualTo(largePage);
        assertThat(largePage).as("page (carries the total) + blocks + the one @authz salon-access lookup")
                .isLessThanOrEqualTo(3);
        assertThat(json(large).path("data").path("totalElements").asLong()).isEqualTo(12);
    }

    @Test
    @DisplayName("18b: the total rides on the page query - a page past the end is empty but still carries it")
    void should_carryTotal_when_pageIsPastTheEnd() throws Exception {
        Salon s = fx.salon();
        for (int i = 0; i < 3; i++) {
            fx.booking(s.masterId(), s.salonId(), fx.client("Клієнт" + i, "Порожня"), "COMPLETED", fx.at(-10 + i));
        }
        String token = fx.token(s.ownerEmail());

        ResponseEntity<String> past = salonList(token, s.salonId(), "page=5&size=2");
        ResponseEntity<String> filteredOut = fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "Нікого");

        assertThat(rows(past)).isEmpty();
        assertThat(json(past).path("data").path("totalElements").asLong()).isEqualTo(3);
        assertThat(rows(filteredOut)).isEmpty();
        assertThat(json(filteredOut).path("data").path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("19: the latest non-null guest name wins")
    void should_showLatestGuestName_when_nameChanges() throws Exception {
        Salon s = fx.salon();
        fx.guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Стара", "Старенко", "COMPLETED", fx.at(-9));
        fx.guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Нова", "Новенко", "COMPLETED", fx.at(-3));

        List<JsonNode> rows = rows(salonList(fx.token(s.ownerEmail()), s.salonId(), null));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("displayName").asText()).isEqualTo("Нова Новенко");
        assertThat(rows.get(0).path("guestBookingId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("19b: first name and surname come from the SAME latest booking - never a mixed name")
    void should_takeNameFromOneBooking_when_latestBookingHasNoSurname() throws Exception {
        Salon s = fx.salon();
        fx.guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Олена", "Коваль", "COMPLETED", fx.at(-9));
        fx.guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Марія", null, "COMPLETED", fx.at(-3));
        String token = fx.token(s.ownerEmail());

        List<JsonNode> rows = rows(salonList(token, s.salonId(), null));
        List<JsonNode> byOldSurname = rows(fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "Коваль"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("displayName").asText()).isEqualTo("Марія");
        assertThat(byOldSurname).as("search matches the shown name, not a stale surname").isEmpty();
    }

    @Test
    @DisplayName("20: blank, whitespace-only and empty q are 400 (the client must omit q, never send it empty)")
    void should_return400_when_qIsBlankWhitespaceOrEmpty() throws Exception {
        Salon s = fx.salon();
        fx.booking(s.masterId(), s.salonId(), fx.client("Анна", "Мороз"), "COMPLETED", fx.at(-5));
        String token = fx.token(s.ownerEmail());

        for (String q : new String[] {"", "   ", "\t"}) {
            ResponseEntity<String> resp = fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", q);

            assertThat(resp.getStatusCode()).as("q='%s' body=%s", q, resp.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(resp).path("success").asBoolean(true)).as("body=%s", resp.getBody()).isFalse();
        }
        assertThat(rows(salonList(token, s.salonId(), null))).as("omitted q lists everyone").hasSize(1);
    }

    @Test
    @DisplayName("15b: q of exactly 50 chars is accepted (51 is 400, case 15); a control char inside the text is 400")
    void should_acceptFiftyCharQ_and_rejectMidStringControlChar() throws Exception {
        Salon s = fx.salon();
        String longSurname = "Б".repeat(45);
        fx.booking(s.masterId(), s.salonId(), fx.client("Анна", longSurname), "COMPLETED", fx.at(-5));
        String token = fx.token(s.ownerEmail());
        String fifty = "Анна " + longSurname;
        assertThat(fifty).hasSize(50);

        List<JsonNode> matched = rows(fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", fifty));
        ResponseEntity<String> control = fx.get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "ан\u0001на");

        assertThat(matched).extracting(r -> r.path("displayName").asText()).containsExactly("Анна " + longSurname);
        assertThat(control.getStatusCode()).as(control.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(control).path("success").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("17b: a provider with no clients gets 200 with an empty content list and totalElements 0")
    void should_returnEmptyPageBody_when_providerHasNoClients() throws Exception {
        Salon s = fx.salon();
        Independent a = fx.independent();

        for (ResponseEntity<String> resp : List.of(salonList(fx.token(s.ownerEmail()), s.salonId(), null),
                fx.get(fx.token(s.masterEmail()), ME_URL), fx.get(fx.token(a.email()), ME_URL))) {
            JsonNode page = json(resp).path("data");

            assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(page.path("data").isArray()).as(resp.getBody()).isTrue();
            assertThat(page.path("data")).as(resp.getBody()).isEmpty();
            assertThat(page.path("totalElements").asLong(-1)).as(resp.getBody()).isZero();
        }
    }

    @Test
    @DisplayName("13c: a registered row with review_count 0 but a stale non-null avg_rating lists ratingAvg null, count 0")
    void should_listNullRating_when_reviewCountIsZeroButAvgIsStale() throws Exception {
        Salon s = fx.salon();
        UUID c = fx.client("Застаріла", "Оцінка");
        jdbcTemplate.update("UPDATE users SET avg_rating = 4.50, review_count = 0 WHERE id = ?", c);
        fx.booking(s.masterId(), s.salonId(), c, "COMPLETED", fx.at(-5));

        ResponseEntity<String> resp = salonList(fx.token(s.ownerEmail()), s.salonId(), null);
        JsonNode row = rows(resp).get(0);

        assertThat(row.path("ratingAvg").isNull()).as(resp.getBody()).isTrue();
        assertThat(row.path("ratingCount").asInt(-1)).isZero();
        assertThat(resp.getBody()).doesNotContain("4.5");
    }

    @Test
    @DisplayName("21: the CHECK rejects an anonymised guest row that still carries a phone, so the list guard is unobservable")
    void should_rejectAnonymisedGuestWithPhone_when_dbCheckEnforced() {
        Salon s = fx.salon();

        assertThatThrownBy(() -> fx.insertBooking(s.masterId(), s.salonId(), fx.serviceOf(s.masterId(), s.salonId()), null,
                "COMPLETED", fx.at(-4), null, "STAFF", "Гість", null, GUEST_PHONE, true, false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_guest_fields");
    }

    @Test
    @DisplayName("22: the CHECK rejects a live guest row without a phone and a registered row that carries a guest phone")
    void should_rejectPhonelessGuestAndRegisteredWithPhone_when_dbCheckEnforced() {
        Salon s = fx.salon();
        UUID service = fx.serviceOf(s.masterId(), s.salonId());
        UUID c = fx.client("Іра", "Мельник");

        assertThatThrownBy(() -> fx.insertBooking(s.masterId(), s.salonId(), service, null, "COMPLETED", fx.at(-4), null,
                "STAFF", "Гість", "Без", null, false, false))
                .as("guest with no phone and not anonymised").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_guest_fields");
        assertThatThrownBy(() -> fx.insertBooking(s.masterId(), s.salonId(), service, c, "COMPLETED", fx.at(-3), null,
                "STAFF", null, null, GUEST_PHONE, false, false))
                .as("registered client row carrying a guest phone").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_guest_fields");
        assertThatThrownBy(() -> fx.insertBooking(s.masterId(), s.salonId(), service, c, "COMPLETED", fx.at(-2), null,
                "APP", null, null, GUEST_PHONE, false, false))
                .as("APP row carrying a guest phone").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_guest_fields");
    }
}
