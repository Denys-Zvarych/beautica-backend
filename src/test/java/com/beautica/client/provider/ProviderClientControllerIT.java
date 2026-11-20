package com.beautica.client.provider;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.security.PhoneHasher;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.support.HibernateStatistics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final String PASSWORD = "Str0ngP@ss1!";
    private static final String SALON_URL = "/api/v1/salons/%s/clients";
    private static final String ME_URL = "/api/v1/masters/me/clients";
    private static final String GUEST_PHONE = "+380501112233";

    private static String encodedPassword;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private ClientReviewRepository clientReviewRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final Map<UUID, UUID> serviceByMaster = new HashMap<>();
    private int dayCounter;

    // ── seeding ──────────────────────────────────────────────────────────────

    private record Salon(UUID salonId, UUID ownerId, String ownerEmail, UUID masterId, UUID masterUserId,
                         String masterEmail) {}

    private record Independent(UUID userId, UUID masterId, String email) {}

    private String email(String prefix) {
        return "b507-" + prefix + "-" + UUID.randomUUID() + "@beautica.test";
    }

    private UUID user(String email, String role, UUID salonId, String first, String last) {
        if (encodedPassword == null) {
            encodedPassword = new BCryptPasswordEncoder(4).encode(PASSWORD);
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified, first_name, "
                        + "last_name, created_at) VALUES (?, ?, ?, ?, ?, true, true, ?, ?, NOW() - interval '1 day')",
                id, email, encodedPassword, role, salonId, first, last);
        return id;
    }

    private Salon salon() {
        String ownerEmail = email("owner");
        UUID ownerId = user(ownerEmail, "SALON_OWNER", null, "Власниця", "Салону");
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());
        String masterEmail = email("master");
        UUID masterUserId = user(masterEmail, "SALON_MASTER", salonId, "Майстер", "А");
        UUID masterId = salonMaster(masterUserId, salonId);
        return new Salon(salonId, ownerId, ownerEmail, masterId, masterUserId, masterEmail);
    }

    private UUID salonMaster(UUID userId, UUID salonId) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())", masterId, userId, salonId);
        return masterId;
    }

    private Independent independent() {
        String email = email("indep");
        UUID userId = user(email, "INDEPENDENT_MASTER", null, "Соло", "Майстер");
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())", masterId, userId);
        return new Independent(userId, masterId, email);
    }

    private UUID client(String first, String last) {
        return user(email("client"), "CLIENT", null, first, last);
    }

    /** A fresh service assignment on the master (a distinct service type each call). */
    private UUID masterService(UUID masterId, String ownerType, UUID ownerId) {
        UUID defId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                defId, ownerType, ownerId, resolveUnusedServiceTypeId(ownerType, ownerId));
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())", id, masterId, defId);
        return id;
    }

    private UUID serviceOf(UUID masterId, UUID salonId) {
        return serviceByMaster.computeIfAbsent(masterId, id -> salonId != null
                ? masterService(id, "SALON", salonId)
                : masterService(id, "INDEPENDENT_MASTER", id));
    }

    /** A start time {@code daysFromNow} away, one-day-unique per call so CONFIRMED rows never overlap. */
    private Instant at(int daysFromNow) {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).plus(daysFromNow, ChronoUnit.DAYS)
                .plus(dayCounter++, ChronoUnit.MINUTES);
    }

    /** Registered client's booking (source APP). */
    private UUID booking(UUID masterId, UUID salonId, UUID clientId, String status, Instant startsAt) {
        return insertBooking(masterId, salonId, serviceOf(masterId, salonId), clientId, status, startsAt, null, "APP",
                null, null, null, false, false);
    }

    private UUID bookingInVisit(UUID masterId, UUID salonId, UUID clientId, String status, Instant startsAt,
                                UUID appointmentId) {
        return insertBooking(masterId, salonId, masterService(masterId, salonId != null ? "SALON"
                : "INDEPENDENT_MASTER", salonId != null ? salonId : masterId), clientId, status, startsAt,
                appointmentId, "APP", null, null, null, false, false);
    }

    private UUID guestBooking(UUID masterId, UUID salonId, String source, String phone, String name, String surname,
                              String status, Instant startsAt) {
        return insertBooking(masterId, salonId, serviceOf(masterId, salonId), null, status, startsAt, null, source,
                name, surname, phone, false, false);
    }

    private UUID anonymisedGuestBooking(UUID masterId, UUID salonId, Instant startsAt) {
        return insertBooking(masterId, salonId, serviceOf(masterId, salonId), null, "COMPLETED", startsAt, null,
                "LINK", "Гість", null, null, true, false);
    }

    private UUID detachedBooking(UUID masterId, UUID salonId, Instant startsAt) {
        return insertBooking(masterId, salonId, serviceOf(masterId, salonId), null, "COMPLETED", startsAt, null,
                "APP", "Видалений", null, null, false, true);
    }

    private UUID appointment(UUID clientId, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO appointments (id, client_id, salon_id, status, booking_source, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'COMPLETED', 'APP', NOW(), NOW())", id, clientId, salonId);
        return id;
    }

    private UUID insertBooking(UUID masterId, UUID salonId, UUID masterServiceId, UUID clientId, String status,
                               Instant startsAt, UUID appointmentId, String source, String guestName,
                               String guestSurname, String guestPhone, boolean anonymised, boolean detached) {
        UUID id = UUID.randomUUID();
        Timestamp start = Timestamp.from(startsAt);
        Timestamp end = Timestamp.from(startsAt.plus(30, ChronoUnit.MINUTES));
        String tokenHash = "LINK".equals(source) && guestPhone != null
                ? id.toString().replace("-", "").repeat(2) : null;
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, appointment_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, guest_name, guest_surname, guest_phone, "
                        + "cancel_token_hash, guest_pii_anonymised_at, client_detached_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 500.00, 30, 0, ?, ?, ?, ?, ?, "
                        + "CASE WHEN ? THEN NOW() END, CASE WHEN ? THEN NOW() END, NOW(), NOW())",
                id, clientId, masterId, masterServiceId, salonId, appointmentId, status, start, end, source,
                guestName, guestSurname, guestPhone, tokenHash, anonymised, detached);
        return id;
    }

    private void blockGuestInSalon(UUID salonId, String phone) {
        PhoneHasher.HashedPhone hashed = phoneHasher.hash(phone);
        jdbcTemplate.update("INSERT INTO client_blocks (id, blocker_salon_id, phone_hash, phone_last4, display_name) "
                + "VALUES (?, ?, ?, ?, 'Гість')", UUID.randomUUID(), salonId, hashed.hash(), hashed.last4());
    }

    private void blockClientInSalon(UUID salonId, UUID userId) {
        jdbcTemplate.update("INSERT INTO client_blocks (id, blocker_salon_id, blocked_user_id, display_name) "
                + "VALUES (?, ?, ?, 'Клієнт')", UUID.randomUUID(), salonId, userId);
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private String token(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login %s", email).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    private ResponseEntity<String> get(String token, String url, Object... vars) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class, vars);
    }

    private ResponseEntity<String> salonList(String token, UUID salonId, String query) {
        return get(token, SALON_URL.formatted(salonId) + (query == null ? "" : "?" + query));
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
    @DisplayName("1/2: registered, merged guest and a same-phone account are three rows; a 3-service visit counts once")
    void should_listThreeRows_when_guestAndAccountShareAPhone() throws Exception {
        Salon s = salon();
        UUID r1 = client("Анна", "Коваль");
        booking(s.masterId(), s.salonId(), r1, "COMPLETED", at(-20));
        UUID visit = appointment(r1, s.salonId());
        bookingInVisit(s.masterId(), s.salonId(), r1, "COMPLETED", at(-10), visit);
        bookingInVisit(s.masterId(), s.salonId(), r1, "COMPLETED", at(-10), visit);
        bookingInVisit(s.masterId(), s.salonId(), r1, "COMPLETED", at(-10), visit);
        guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Іванна", "Коваль", "COMPLETED", at(-8));
        guestBooking(s.masterId(), s.salonId(), "STAFF", GUEST_PHONE, "Іванна", "Коваль", "COMPLETED", at(-6));
        UUID r2 = client("Петро", "Сидоренко");
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", GUEST_PHONE, r2);
        booking(s.masterId(), s.salonId(), r2, "COMPLETED", at(-4));

        List<JsonNode> rows = rows(salonList(token(s.ownerEmail()), s.salonId(), null));

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
        assertThat(json(salonList(token(s.ownerEmail()), s.salonId(), null)).path("data").path("totalElements")
                .asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("3: last = latest past COMPLETED/CONFIRMED, next = earliest future CONFIRMED, CANCELLED ignored")
    void should_deriveLastAndNextVisit_when_mixedStatuses() throws Exception {
        Salon s = salon();
        UUID c = client("Марта", "Іваненко");
        booking(s.masterId(), s.salonId(), c, "COMPLETED", at(-10));
        ZoneId kyiv = ZoneId.of("Europe/Kyiv");
        Instant pastKyiv = ZonedDateTime.of(LocalDate.now(kyiv).minusDays(3), LocalTime.of(10, 30), kyiv).toInstant();
        Instant futureKyiv = ZonedDateTime.of(LocalDate.now(kyiv).plusDays(2), LocalTime.of(15, 45), kyiv).toInstant();
        UUID pastConfirmed = booking(s.masterId(), s.salonId(), c, "CONFIRMED", pastKyiv);
        UUID futureConfirmed = booking(s.masterId(), s.salonId(), c, "CONFIRMED", futureKyiv);
        booking(s.masterId(), s.salonId(), c, "CANCELLED", at(1));

        JsonNode row = rows(salonList(token(s.ownerEmail()), s.salonId(), null)).get(0);

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
        Salon s = salon();
        UUID c = client("Лана", "Бойко");
        booking(s.masterId(), s.salonId(), c, "CANCELLED", at(-5));

        JsonNode row = rows(salonList(token(s.ownerEmail()), s.salonId(), null)).get(0);

        assertThat(row.path("completedVisits").asInt()).isZero();
        assertThat(row.path("lastVisitAt").isNull()).isTrue();
        assertThat(row.path("nextVisitAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("5: anonymised guests and detached accounts are not listed and not counted")
    void should_excludeAnonymisedGuestsAndDetachedRows() throws Exception {
        Salon s = salon();
        UUID c = client("Іра", "Мельник");
        booking(s.masterId(), s.salonId(), c, "COMPLETED", at(-5));
        anonymisedGuestBooking(s.masterId(), s.salonId(), at(-4));
        detachedBooking(s.masterId(), s.salonId(), at(-3));

        ResponseEntity<String> resp = salonList(token(s.ownerEmail()), s.salonId(), null);

        assertThat(rows(resp)).hasSize(1);
        assertThat(json(resp).path("data").path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("6: a SALON_MASTER sees only the clients of their own master row")
    void should_scopeToOwnMasterRow_when_salonMasterLists() throws Exception {
        Salon s = salon();
        UUID masterBUser = user(email("masterb"), "SALON_MASTER", s.salonId(), "Майстер", "Б");
        UUID masterB = salonMaster(masterBUser, s.salonId());
        UUID own = client("Власна", "Клієнтка");
        UUID other = client("Чужа", "Клієнтка");
        booking(s.masterId(), s.salonId(), own, "COMPLETED", at(-5));
        booking(masterB, s.salonId(), other, "COMPLETED", at(-4));

        List<JsonNode> rows = rows(get(token(s.masterEmail()), ME_URL));

        assertThat(rows).extracting(r -> r.path("displayName").asText()).containsExactly("Власна Клієнтка");
    }

    @Test
    @DisplayName("7: an INDEPENDENT_MASTER sees own (salon-less) bookings only")
    void should_listOwnBookings_when_independentMaster() throws Exception {
        Independent a = independent();
        Independent b = independent();
        UUID own = client("Своя", "Клієнтка");
        UUID other = client("Інша", "Клієнтка");
        booking(a.masterId(), null, own, "COMPLETED", at(-5));
        booking(b.masterId(), null, other, "COMPLETED", at(-4));

        List<JsonNode> rows = rows(get(token(a.email()), ME_URL));

        assertThat(rows).extracting(r -> r.path("displayName").asText()).containsExactly("Своя Клієнтка");
    }

    @Test
    @DisplayName("salon endpoint: masterId narrows to that master's clients")
    void should_narrowToMaster_when_masterIdGiven() throws Exception {
        Salon s = salon();
        UUID masterB = salonMaster(user(email("masterb"), "SALON_MASTER", s.salonId(), "Майстер", "Б"), s.salonId());
        booking(s.masterId(), s.salonId(), client("Перша", "Клієнтка"), "COMPLETED", at(-5));
        booking(masterB, s.salonId(), client("Друга", "Клієнтка"), "COMPLETED", at(-4));

        List<JsonNode> rows = rows(salonList(token(s.ownerEmail()), s.salonId(), "masterId=" + masterB));

        assertThat(rows).extracting(r -> r.path("displayName").asText()).containsExactly("Друга Клієнтка");
        assertThat(rows(salonList(token(s.ownerEmail()), s.salonId(), null))).hasSize(2);
    }

    @Test
    @DisplayName("8: an admin of another salon and the owner of another salon get 403")
    void should_return403_when_notManagingThatSalon() throws Exception {
        Salon s = salon();
        Salon t = salon();
        String adminEmail = email("admin");
        user(adminEmail, "SALON_ADMIN", s.salonId(), "Адмін", "С");

        assertThat(salonList(token(adminEmail), t.salonId(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(salonList(token(s.ownerEmail()), t.salonId(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(salonList(token(adminEmail), s.salonId(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("9: masterId of another salon's master is 404")
    void should_return404_when_masterBelongsToAnotherSalon() throws Exception {
        Salon s = salon();
        Salon t = salon();

        assertThat(salonList(token(s.ownerEmail()), s.salonId(), "masterId=" + t.masterId()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("10: SALON_OWNER on /masters/me/clients 403, CLIENT on both 403, anonymous 401")
    void should_denyOtherRoles_when_authMatrix() throws Exception {
        Salon s = salon();
        String clientEmail = email("plain");
        user(clientEmail, "CLIENT", null, "Звичайний", "Клієнт");

        assertThat(get(token(s.ownerEmail()), ME_URL).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(token(clientEmail), ME_URL).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(salonList(token(clientEmail), s.salonId(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(null, ME_URL).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(salonList(null, s.salonId(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("10b: a SALON_MASTER is denied on the salon endpoint")
    void should_return403_when_salonMasterCallsSalonEndpoint() throws Exception {
        Salon s = salon();

        assertThat(salonList(token(s.masterEmail()), s.salonId(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("11: a deactivated SALON_MASTER gets 403")
    void should_return403_when_masterDeactivated() throws Exception {
        Salon s = salon();
        String token = token(s.masterEmail());
        assertThat(get(token, ME_URL).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", s.masterId());

        assertThat(get(token, ME_URL).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("12: rating = VISIBLE reviews only, as number + count; no comment key anywhere")
    void should_exposeRatingNumberOnly_when_clientHasVisibleAndHiddenReviews() throws Exception {
        Salon s = salon();
        UUID c = client("Оксана", "Рейтинг");
        UUID b1 = booking(s.masterId(), s.salonId(), c, "COMPLETED", at(-9));
        UUID b2 = booking(s.masterId(), s.salonId(), c, "COMPLETED", at(-8));
        UUID b3 = booking(s.masterId(), s.salonId(), c, "COMPLETED", at(-7));
        clientReview(b1, c, s, 5, "VISIBLE");
        clientReview(b2, c, s, 3, "VISIBLE");
        clientReview(b3, c, s, 1, "REMOVED");
        transactionTemplate.executeWithoutResult(st -> clientReviewRepository.recalculateClientRating(c));

        ResponseEntity<String> resp = salonList(token(s.ownerEmail()), s.salonId(), null);

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
        Salon s = salon();
        Salon foreign = salon();
        UUID blockedClient = client("Заблокована", "Клієнтка");
        UUID freeClient = client("Вільна", "Клієнтка");
        booking(s.masterId(), s.salonId(), blockedClient, "COMPLETED", at(-9));
        booking(s.masterId(), s.salonId(), freeClient, "COMPLETED", at(-8));
        guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Заблокований", "Гість", "COMPLETED", at(-7));
        guestBooking(s.masterId(), s.salonId(), "LINK", "+380677778899", "Чужий", "Блок", "COMPLETED", at(-6));
        blockClientInSalon(s.salonId(), blockedClient);
        blockGuestInSalon(s.salonId(), GUEST_PHONE);
        blockGuestInSalon(foreign.salonId(), "+380677778899");

        List<JsonNode> rows = rows(salonList(token(s.ownerEmail()), s.salonId(), null));

        assertThat(byName(rows, "Заблокована Клієнтка").path("blocked").asBoolean()).isTrue();
        assertThat(byName(rows, "Заблокований Гість").path("blocked").asBoolean()).isTrue();
        assertThat(byName(rows, "Вільна Клієнтка").path("blocked").asBoolean()).isFalse();
        assertThat(byName(rows, "Чужий Блок").path("blocked").asBoolean()).as("foreign block ignored").isFalse();
        // a SALON_MASTER viewer sees the same salon-scope badge (D5)
        assertThat(byName(rows(get(token(s.masterEmail()), ME_URL)), "Заблокований Гість").path("blocked")
                .asBoolean()).isTrue();
    }

    @Test
    @DisplayName("13b: an independent master's own block flags their client")
    void should_flagBlocked_when_independentMasterBlockedTheGuest() throws Exception {
        Independent a = independent();
        guestBooking(a.masterId(), null, "LINK", GUEST_PHONE, "Гість", "Соло", "COMPLETED", at(-5));
        PhoneHasher.HashedPhone hashed = phoneHasher.hash(GUEST_PHONE);
        jdbcTemplate.update("INSERT INTO client_blocks (id, blocker_master_id, phone_hash, phone_last4, display_name) "
                + "VALUES (?, ?, ?, ?, 'Гість')", UUID.randomUUID(), a.masterId(), hashed.hash(), hashed.last4());

        assertThat(rows(get(token(a.email()), ME_URL)).get(0).path("blocked").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("14: no phone field and no +380 anywhere in the raw body")
    void should_neverLeakAPhone_when_listing() throws Exception {
        Salon s = salon();
        guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Гість", "Один", "COMPLETED", at(-5));
        UUID c = client("Реєстрований", "Клієнт");
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", "+380671234567", c);
        booking(s.masterId(), s.salonId(), c, "COMPLETED", at(-4));

        for (ResponseEntity<String> resp : List.of(salonList(token(s.ownerEmail()), s.salonId(), null),
                get(token(s.masterEmail()), ME_URL))) {
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).doesNotContainIgnoringCase("phone").doesNotContain("+380")
                    .doesNotContain("380501112233").doesNotContain("0501112233");
        }
    }

    @Test
    @DisplayName("15: q matches case-insensitively over name parts, escapes %, 1 char is 400")
    void should_filterByName_when_qGiven() throws Exception {
        Salon s = salon();
        booking(s.masterId(), s.salonId(), client("Анна", "Мороз"), "COMPLETED", at(-5));
        guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Іванна", "Лис", "COMPLETED", at(-4));
        booking(s.masterId(), s.salonId(), client("Петро", "Вовк"), "COMPLETED", at(-3));
        String token = token(s.ownerEmail());

        assertThat(rows(get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "ан"))
                .stream().map(r -> r.path("displayName").asText()).toList())
                .containsExactlyInAnyOrder("Анна Мороз", "Іванна Лис");
        assertThat(rows(get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "%%"))).isEmpty();
        assertThat(rows(get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "ан%"))).isEmpty();
        assertThat(get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "%").getStatusCode())
                .as("a single % is 1 char: too short, never a wildcard").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rows(get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "_н"))).isEmpty();
        assertThat(get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "а").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", " а ").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", "x".repeat(51)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("16: 45 clients page 20/20/5, totalElements 45; size=500 is clamped to 50")
    void should_page_when_manyClients() throws Exception {
        Salon s = salon();
        for (int i = 0; i < 45; i++) {
            booking(s.masterId(), s.salonId(), client("Клієнт" + i, "Масовий"), "COMPLETED", at(-100 + i));
        }
        String token = token(s.ownerEmail());

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
        Salon s = salon();

        assertThat(salonList(token(s.ownerEmail()), s.salonId(), "page=1001").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(salonList(token(s.ownerEmail()), s.salonId(), "page=1000").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("18: at most page + count + blocks statements, independent of the page size")
    void should_issueAtMostFourStatements_when_listing() throws Exception {
        Salon s = salon();
        for (int i = 0; i < 6; i++) {
            booking(s.masterId(), s.salonId(), client("Клієнт" + i, "Статистика"), "COMPLETED", at(-30 + i));
            guestBooking(s.masterId(), s.salonId(), "LINK", "+38050000000" + i, "Гість" + i, "Статистика",
                    "COMPLETED", at(-60 + i));
        }
        String token = token(s.ownerEmail());
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
        assertThat(largePage).as("page + count + blocks + the one @authz salon-access lookup").isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("19: the latest non-null guest name wins")
    void should_showLatestGuestName_when_nameChanges() throws Exception {
        Salon s = salon();
        guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Стара", "Старенко", "COMPLETED", at(-9));
        guestBooking(s.masterId(), s.salonId(), "LINK", GUEST_PHONE, "Нова", "Новенко", "COMPLETED", at(-3));

        List<JsonNode> rows = rows(salonList(token(s.ownerEmail()), s.salonId(), null));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("displayName").asText()).isEqualTo("Нова Новенко");
        assertThat(rows.get(0).path("guestBookingId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("20: blank, whitespace-only and empty q are 400 (the client must omit q, never send it empty)")
    void should_return400_when_qIsBlankWhitespaceOrEmpty() throws Exception {
        Salon s = salon();
        booking(s.masterId(), s.salonId(), client("Анна", "Мороз"), "COMPLETED", at(-5));
        String token = token(s.ownerEmail());

        for (String q : new String[] {"", "   ", "\t"}) {
            ResponseEntity<String> resp = get(token, SALON_URL.formatted(s.salonId()) + "?q={q}", q);

            assertThat(resp.getStatusCode()).as("q='%s' body=%s", q, resp.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(resp).path("success").asBoolean(true)).as("body=%s", resp.getBody()).isFalse();
        }
        assertThat(rows(salonList(token, s.salonId(), null))).as("omitted q lists everyone").hasSize(1);
    }

    @Test
    @DisplayName("21: the CHECK rejects an anonymised guest row that still carries a phone, so the list guard is unobservable")
    void should_rejectAnonymisedGuestWithPhone_when_dbCheckEnforced() {
        Salon s = salon();

        assertThatThrownBy(() -> insertBooking(s.masterId(), s.salonId(), serviceOf(s.masterId(), s.salonId()), null,
                "COMPLETED", at(-4), null, "STAFF", "Гість", null, GUEST_PHONE, true, false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_guest_fields");
    }

    @Test
    @DisplayName("22: the CHECK rejects a live guest row without a phone and a registered row that carries a guest phone")
    void should_rejectPhonelessGuestAndRegisteredWithPhone_when_dbCheckEnforced() {
        Salon s = salon();
        UUID service = serviceOf(s.masterId(), s.salonId());
        UUID c = client("Іра", "Мельник");

        assertThatThrownBy(() -> insertBooking(s.masterId(), s.salonId(), service, null, "COMPLETED", at(-4), null,
                "STAFF", "Гість", "Без", null, false, false))
                .as("guest with no phone and not anonymised").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_guest_fields");
        assertThatThrownBy(() -> insertBooking(s.masterId(), s.salonId(), service, c, "COMPLETED", at(-3), null,
                "STAFF", null, null, GUEST_PHONE, false, false))
                .as("registered client row carrying a guest phone").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_guest_fields");
        assertThatThrownBy(() -> insertBooking(s.masterId(), s.salonId(), service, c, "COMPLETED", at(-2), null,
                "APP", null, null, GUEST_PHONE, false, false))
                .as("APP row carrying a guest phone").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_guest_fields");
    }
}
