package com.beautica.favorite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.PageResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.favorite.dto.FavoriteResponse;
import com.beautica.favorite.dto.FavoriteServiceResponse;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.service.entity.PriceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 249 (31.6) — the BEAUTY WISH LIST contract driven end to end over real HTTP:
 * {@code POST /favorites {SERVICE, …}} → {@code GET /favorites/services} → «Записатись»
 * ({@code POST /bookings}).
 *
 * <h2>Scope split with {@link FavoriteMigrationIT} — deliberately no duplication</h2>
 * {@code FavoriteMigrationIT} already owns the persistence half against real Postgres: the V92/V134
 * CHECK + UNIQUE constraints, the four {@code is_active} read predicates (assignment, definition,
 * master, owning salon), the write-time validators, the salon-master asymmetry, stale-row drop,
 * ordering and per-client scoping. Re-asserting any of that here would add runtime, not coverage.
 *
 * <p>What is genuinely NOT covered below the HTTP layer, and is therefore what this class pins:
 * <ul>
 *   <li>the round trip through the controller, the {@code PageResponse} envelope and Jackson —
 *       {@code FavoriteControllerTest} is a {@code @WebMvcTest} whose service is a mock, so no
 *       value it asserts is derived from a row;</li>
 *   <li>SERVICE idempotency at the DB level (the unit test's repository is stubbed, so it cannot
 *       observe the {@code uq_favorite} index);</li>
 *   <li>page-boundary stability across two real pages;</li>
 *   <li>the Phase 248 rebook race — what «Записатись» actually does when the wish-listed service
 *       was deactivated after the page was rendered.</li>
 * </ul>
 */
@Import(TestSecurityConfig.class)
@DisplayName("BEAUTY WISH LIST — /favorites SERVICE round trip (HTTP + real Postgres)")
class FavoriteServiceIT extends AbstractIntegrationTest {

    private static final String FAVORITES_URL = "/api/v1/favorites";
    private static final String WISH_LIST_URL = FAVORITES_URL + "/services";
    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private NotificationOutboxService notificationOutboxService;

    // ── 246 + 247: the round trip ───────────────────────────────────────────────

    @Test
    @DisplayName("POST /favorites {SERVICE} then GET /favorites/services returns the card with BOTH "
            + "ids «Записатись» needs and the price band derived by ServicePricing")
    void should_persistAndList_when_serviceFavorited() throws Exception {
        UUID clientId = createClient("wish-roundtrip-client@beautica.test");
        String token = loginAndGetToken(clientId);
        UUID master = createIndependentMaster("wish-roundtrip-master@beautica.test", "Olena", "Kovalenko");
        UUID masterServiceId = createMasterService(master);

        ResponseEntity<String> added = post(token, FAVORITES_URL,
                "{\"targetType\":\"SERVICE\",\"targetId\":\"" + masterServiceId + "\"}");

        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.OK);
        FavoriteResponse favorite = objectMapper
                .readValue(added.getBody(), new TypeReference<ApiResponse<FavoriteResponse>>() {}).data();
        assertThat(favorite)
                .extracting(FavoriteResponse::targetType, FavoriteResponse::targetId)
                .containsExactly(FavoriteTargetType.SERVICE, masterServiceId);

        PageResponse<FavoriteServiceResponse> page = getWishList(token, null);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.data()).singleElement()
                .extracting(FavoriteServiceResponse::masterServiceId,
                        FavoriteServiceResponse::masterId,
                        FavoriteServiceResponse::serviceName,
                        FavoriteServiceResponse::masterFirstName,
                        FavoriteServiceResponse::masterLastName,
                        FavoriteServiceResponse::durationMinutes,
                        FavoriteServiceResponse::priceType)
                .containsExactly(masterServiceId, master, "Test Service", "Olena", "Kovalenko",
                        60, PriceType.FIXED);
        assertThat(page.data().get(0).priceMin()).isEqualByComparingTo("500.00");
        assertThat(page.data().get(0).priceMax())
                .as("a FIXED service carries no ceiling — a non-null max here means the band was "
                        + "invented rather than derived")
                .isNull();
    }

    @Test
    @DisplayName("POST /favorites — an unknown targetType is rejected 400 and the error body does "
            + "NOT echo the valid enum constants")
    void should_rejectWithoutLeakingConstants_when_targetTypeIsBogus() throws Exception {
        // The DB-level half of this contract (the V134 CHECK still rejects a fourth value) lives in
        // FavoriteMigrationIT.should_acceptServiceTargetType_when_v134Applied. This is the HTTP half:
        // Jackson's default message for an unknown enum constant enumerates every valid value, which
        // would leak the target surface — GlobalExceptionHandler must flatten it.
        UUID clientId = createClient("wish-bogus-client@beautica.test");
        String token = loginAndGetToken(clientId);

        ResponseEntity<String> response = post(token, FAVORITES_URL,
                "{\"targetType\":\"PROCEDURE\",\"targetId\":\"" + UUID.randomUUID() + "\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .as("the enum surface must not be echoed back to the caller")
                .doesNotContain("SERVICE")
                .doesNotContain("SALON")
                .doesNotContain("MASTER");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM favorites", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("POST /favorites {SERVICE} twice is idempotent against the real uq_favorite index — "
            + "same favorite id, exactly one row, one wish-list card")
    void should_beIdempotent_when_favoritedTwice() throws Exception {
        UUID clientId = createClient("wish-idem-client@beautica.test");
        String token = loginAndGetToken(clientId);
        UUID master = createIndependentMaster("wish-idem-master@beautica.test", "Iryna", "Bondar");
        UUID masterServiceId = createMasterService(master);
        String body = "{\"targetType\":\"SERVICE\",\"targetId\":\"" + masterServiceId + "\"}";

        UUID firstId = objectMapper
                .readValue(post(token, FAVORITES_URL, body).getBody(),
                        new TypeReference<ApiResponse<FavoriteResponse>>() {}).data().id();
        ResponseEntity<String> second = post(token, FAVORITES_URL, body);

        assertThat(second.getStatusCode())
                .as("a re-heart is a 200, never a 409 — the like button is idempotent by contract")
                .isEqualTo(HttpStatus.OK);
        assertThat(objectMapper
                .readValue(second.getBody(), new TypeReference<ApiResponse<FavoriteResponse>>() {})
                .data().id())
                .as("the pre-check must return the EXISTING row, not mint a second one")
                .isEqualTo(firstId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM favorites WHERE client_id = ? AND target_type = 'SERVICE'",
                Integer.class, clientId))
                .isEqualTo(1);
        assertThat(getWishList(token, null).totalElements()).isEqualTo(1);
    }

    // ── Phase 248: the documented rebook race ───────────────────────────────────

    @Test
    @DisplayName("«Записатись» — POST /bookings with a wish-listed service that was deactivated "
            + "after the card was rendered must fail with 404, never mint a booking")
    void should_return404_when_bookingAFavoritedServiceThatWasDeactivated() throws Exception {
        // Phase 248 records this race as the ONE real risk of the wish list and states the expected
        // outcome is a 404 on the assignment lookup — pinned here so nobody "fixes" it into a silent
        // success, and so the is_active read filter's whole justification ("the booking path then
        // rejects it") stops being an untested claim.
        UUID clientId = createClient("wish-race-client@beautica.test");
        String token = loginAndGetToken(clientId);
        UUID master = createIndependentMaster("wish-race-master@beautica.test", "Nadiia", "Tkachuk");
        UUID masterServiceId = createMasterService(master);
        post(token, FAVORITES_URL,
                "{\"targetType\":\"SERVICE\",\"targetId\":\"" + masterServiceId + "\"}");

        // The card was rendered; the master now unassigns the service.
        assertThat(getWishList(token, null).totalElements())
                .as("precondition: the card genuinely was live before the deactivation")
                .isEqualTo(1);
        jdbcTemplate.update("UPDATE master_services SET is_active = false WHERE id = ?", masterServiceId);

        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2)
                .withHour(11).withMinute(0).withSecond(0).withNano(0);
        ResponseEntity<String> booking = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(new CreateBookingRequest(master, masterServiceId, startsAt, null, null),
                        bearerHeaders(token)),
                String.class);

        assertThat(booking.getStatusCode())
                .as("a soft-deleted service must not be bookable through a stale wish-list card")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bookings", Integer.class))
                .as("no booking row may survive a rejected «Записатись»")
                .isZero();
        assertThat(getWishList(token, null).data())
                .as("and the card itself is gone from the list")
                .isEmpty();
    }

    // ── paging ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /favorites/services paginates stably — created_at DESC then id ASC, no row on "
            + "two pages, page metadata agrees with the content")
    void should_paginateStably_when_moreThanOnePage() throws Exception {
        UUID clientId = createClient("wish-page-client@beautica.test");
        String token = loginAndGetToken(clientId);
        UUID master = createIndependentMaster("wish-page-master@beautica.test", "Sofiia", "Melnyk");

        // Three cards hearted oldest-first, with DISTINCT created_at values pinned by SQL: the
        // DB clock is coarse enough that three inserts in one millisecond would make the expected
        // order an accident of the tiebreaker rather than the contract under test.
        UUID oldest = createMasterService(master);
        UUID middle = createMasterService(master);
        UUID newest = createMasterService(master);
        for (UUID id : List.of(oldest, middle, newest)) {
            post(token, FAVORITES_URL, "{\"targetType\":\"SERVICE\",\"targetId\":\"" + id + "\"}");
        }
        jdbcTemplate.update(
                "UPDATE favorites SET created_at = created_at - interval '2 hours' WHERE target_id = ?", oldest);
        jdbcTemplate.update(
                "UPDATE favorites SET created_at = created_at - interval '1 hour' WHERE target_id = ?", middle);

        PageResponse<FavoriteServiceResponse> first = getWishList(token, "?page=0&size=2");
        PageResponse<FavoriteServiceResponse> second = getWishList(token, "?page=1&size=2");

        assertThat(first.data()).extracting(FavoriteServiceResponse::masterServiceId)
                .as("newest favourite first")
                .containsExactly(newest, middle);
        assertThat(second.data()).extracting(FavoriteServiceResponse::masterServiceId)
                .containsExactly(oldest);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(second.totalElements())
                .as("the count query must not drift between pages")
                .isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.data()).extracting(FavoriteServiceResponse::masterServiceId)
                .as("a row appearing on both pages means the ORDER BY is not a total order")
                .doesNotContainAnyElementsOf(
                        second.data().stream().map(FavoriteServiceResponse::masterServiceId).toList());
    }

    // ── HTTP plumbing ───────────────────────────────────────────────────────────

    private PageResponse<FavoriteServiceResponse> getWishList(String token, String query) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                WISH_LIST_URL + (query == null ? "" : query), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(response.getBody(),
                new TypeReference<ApiResponse<PageResponse<FavoriteServiceResponse>>>() {}).data();
    }

    private ResponseEntity<String> post(String token, String url, String jsonBody) {
        return restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(jsonBody, bearerHeaders(token)), String.class);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String loginAndGetToken(UUID clientId) throws Exception {
        String email = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, clientId);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper
                .readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    // ── seed helpers ────────────────────────────────────────────────────────────

    private UUID createClient(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD));
        return id;
    }

    private UUID createIndependentMaster(String email, String firstName, String lastName) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', ?, ?, true, true)",
                userId, email, firstName, lastName);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createMasterService(UUID masterId) {
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, "
                        + "NOW(), NOW())",
                serviceDefId, ownerId, resolveUnusedServiceTypeId("INDEPENDENT_MASTER", ownerId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }
}
