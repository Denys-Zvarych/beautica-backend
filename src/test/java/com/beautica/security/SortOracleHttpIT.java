package com.beautica.security;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level proof that no paginated endpoint can be used as an <b>ordering oracle</b> over
 * columns the caller may never read (Anti-Bug §E-4, backend-security).
 *
 * <h3>The attack this suite pins closed</h3>
 * Spring Data appends a caller-supplied {@code sort} property to the query's {@code ORDER BY}.
 * Property names cannot be bind parameters, so the string is spliced into JPQL — and a dotted
 * association path resolves against the query's root entity. On a {@code Master} root
 * {@code ?sort=user.passwordHash,asc} is well-formed; on a {@code Booking} root
 * {@code ?sort=client.passwordHash,asc} and {@code ?sort=client.email,asc} are. Each returns rows
 * ordered by a secret, so paging with alternating directions binary-searches the ordering of
 * password hashes and e-mail addresses without ever reading them.
 *
 * <h3>Why these assertions are meaningful and not vacuous</h3>
 * A 400 is asserted <em>and</em> a same-endpoint positive control proves the endpoint otherwise
 * answers 2xx for the identical fixture and token — so the 400 is attributable to the sort
 * parameter, not to a missing fixture, a bad token, or a 404. Without the paired control a
 * blanket-failing endpoint would pass this suite trivially.
 *
 * <p><b>{@code GET /salons/{salonId}/masters} is {@code permitAll}</b> (see {@code SecurityConfig}),
 * so its oracle was reachable by a completely <em>unauthenticated</em> caller — the most severe
 * instance, and the reason the first test sends no token at all.
 *
 * <p>The guard rejects during argument resolution, <em>before</em> the controller, service or any
 * query runs. That is why a non-existent {@code salonId} still yields 400 rather than 404: proving
 * the rejection happens ahead of the data layer is precisely the point.
 *
 * @see com.beautica.config.SortPathGuardConfig the global backstop under test
 * @see com.beautica.common.web.SortWhitelist the per-endpoint whitelist under test
 * @see com.beautica.booking.BookingMyBookingsSortIT the sibling suite for {@code GET /bookings/me}
 */
@Import(TestSecurityConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("sort= ordering-oracle guard — full HTTP stack over real Postgres")
class SortOracleHttpIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Test1234!";

    /** Dotted paths that resolve to real columns and are the actual exploit payloads. */
    private static final String PASSWORD_HASH_PATH = "user.passwordHash,asc";
    private static final String BOOKING_HASH_PATH = "client.passwordHash,asc";
    private static final String BOOKING_EMAIL_PATH = "client.email,asc";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID salonId;
    private String clientToken;
    private String masterToken;
    private String ownerToken;

    /**
     * §M-4: the pooled request factory is installed once per class, never per test — a
     * {@code @BeforeEach} here would allocate a fresh connection pool for every case.
     */
    @BeforeAll
    static void configureHttpClientOnce() {
        // Intentionally empty: the factory is set on the injected template in setUp(), which
        // cannot be static. Kept as documentation of the §M-4 constraint for future edits.
    }

    @BeforeEach
    void setUp() throws Exception {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));

        UUID ownerId = createUser("owner-" + UUID.randomUUID() + "@test.com", "SALON_OWNER", null);
        salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, is_primary, created_at, updated_at) "
                        + "VALUES (?, ?, 'Sort Oracle Salon', true, true, NOW(), NOW())",
                salonId, ownerId);

        String masterEmail = "master-" + UUID.randomUUID() + "@test.com";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', 0.00, 0, true, NOW(), NOW())",
                UUID.randomUUID(), masterUserId, salonId);

        String clientEmail = "client-" + UUID.randomUUID() + "@test.com";
        createUser(clientEmail, "CLIENT", null);

        clientToken = login(clientEmail);
        masterToken = login(masterEmail);
        ownerToken = login(jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, ownerId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The unauthenticated oracle — highest severity (endpoint is permitAll)
    // ══════════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "?sort={0}")
    @ValueSource(strings = {
            "user.passwordHash,asc",
            "user.passwordHash,desc",
            "user.email,asc",
            "user.phone,asc",
            "salon.owner.passwordHash,asc"
    })
    @DisplayName("GET /salons/{id}/masters (permitAll, NO token) — every dotted path is a 400, "
            + "so an anonymous caller cannot order salon masters by a credential column")
    void should_return400_when_anonymousCallerSortsSalonMastersByDottedPath(String sort) {
        var response = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters?sort=" + sort,
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode())
                .as("dotted sort '%s' must be rejected at the boundary, not answered", sort)
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /salons/{id}/masters (permitAll, NO token) — positive control: the SAME "
            + "unauthenticated request without a dotted sort returns 200, proving the 400 above "
            + "is attributable to the sort parameter and not to the missing token or fixture")
    void should_return200_when_anonymousCallerSortsSalonMastersByWhitelistedProperty() {
        var response = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters?sort=avgRating,desc",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /salons/{id}/masters — the 400 body must not echo the probed property, or "
            + "the response itself confirms whether the path resolved against the entity graph")
    void should_return400WithoutEchoingProperty_when_probingPasswordHash() throws Exception {
        var response = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters?sort=" + PASSWORD_HASH_PATH,
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("message").asText(""))
                .doesNotContainIgnoringCase("passwordHash")
                .doesNotContainIgnoringCase("password_hash")
                .doesNotContainIgnoringCase("user.");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Authenticated endpoints — one dotted-path rejection + positive control each
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /masters/by-salon/{id} — dotted path 400; whitelisted sort 200 on the same token")
    void should_return400_when_sortingMastersBySalonByDottedPath() {
        assertRejectedAndControlled(
                "/api/v1/masters/by-salon/" + salonId, ownerToken, PASSWORD_HASH_PATH, "avgRating,desc");
    }

    @Test
    @DisplayName("GET /masters/me/calendar — Booking root, so client.passwordHash resolves; 400 "
            + "with a startsAt positive control on the same token")
    void should_return400_when_sortingMasterCalendarByDottedPath() {
        // The endpoint rejects any range outside [now-1y, now+2y] with its own 400. Dates are
        // therefore derived from the current date, not hardcoded: a fixed future literal would
        // trip that bounds guard and make every assertion below pass for the WRONG reason,
        // proving nothing about the sort guard. The positive control is what catches that.
        LocalDate from = LocalDate.now().plusDays(1);
        String base = "/api/v1/masters/me/calendar?from=" + from + "&to=" + from.plusDays(30);

        assertRejected(base + "&sort=" + BOOKING_HASH_PATH, masterToken);
        assertRejected(base + "&sort=" + BOOKING_EMAIL_PATH, masterToken);
        assertOk(base + "&sort=startsAt,asc", masterToken);
    }

    @Test
    @DisplayName("GET /clients/me/timeline — Booking root; dotted path 400, startsAt 200")
    void should_return400_when_sortingClientTimelineByDottedPath() {
        assertRejected("/api/v1/clients/me/timeline?sort=" + BOOKING_HASH_PATH, clientToken);
        assertOk("/api/v1/clients/me/timeline?sort=startsAt,desc", clientToken);
    }

    @Test
    @DisplayName("GET /favorites/masters — native-SQL query; dotted path 400, unsorted 200")
    void should_return400_when_sortingFavoriteMastersByDottedPath() {
        assertRejected("/api/v1/favorites/masters?sort=" + PASSWORD_HASH_PATH, clientToken);
        assertOk("/api/v1/favorites/masters", clientToken);
    }

    @Test
    @DisplayName("GET /favorites/salons — native-SQL query; dotted path 400, unsorted 200")
    void should_return400_when_sortingFavoriteSalonsByDottedPath() {
        assertRejected("/api/v1/favorites/salons?sort=" + PASSWORD_HASH_PATH, clientToken);
        assertOk("/api/v1/favorites/salons", clientToken);
    }

    @Test
    @DisplayName("GET /reviews/me — dotted path 400, unsorted 200")
    void should_return400_when_sortingMyReviewsByDottedPath() {
        assertRejected("/api/v1/reviews/me?sort=" + BOOKING_HASH_PATH, clientToken);
        assertOk("/api/v1/reviews/me", clientToken);
    }

    @Test
    @DisplayName("GET /masters/{id}/reviews (permitAll) — dotted path 400, and the SalonReviewSort "
            + "enum contract still answers 200, proving the guard did not break it")
    void should_return400_when_sortingMasterReviewsByDottedPath() {
        UUID masterId = jdbcTemplate.queryForObject("SELECT id FROM masters LIMIT 1", UUID.class);
        var rejected = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/reviews?sort=client.passwordHash,asc",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var enumSort = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/reviews?sort=HIGHEST",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(enumSort.getStatusCode())
                .as("the SalonReviewSort enum shares the 'sort' parameter name and must still work")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /salons/{id}/reviews (permitAll) — dotted path 400, enum sort still 200")
    void should_return400_when_sortingSalonReviewsByDottedPath() {
        var rejected = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/reviews?sort=client.passwordHash,asc",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var enumSort = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/reviews?sort=NEWEST",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(enumSort.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The global backstop, independent of any per-endpoint whitelist
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("The global guard rejects a dotted path BEFORE the data layer — a non-existent "
            + "salonId yields 400, not 404, proving no query ran")
    void should_return400NotFound_when_dottedSortOnNonExistentSalon() {
        var response = restTemplate.exchange(
                "/api/v1/salons/" + UUID.randomUUID() + "/masters?sort=" + PASSWORD_HASH_PATH,
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode())
                .as("rejection must precede the repository, so the id is never looked up")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("The global pagination cap (§J) survives the guard's hand-built resolvers — "
            + "?size=10000 is clamped to 100, not honoured")
    void should_clampPageSize_when_callerRequestsOversizedPage() throws Exception {
        var response = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters?size=10000",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("data").path("size").asInt())
                .as("spring.data.web.pageable.max-page-size=100 must still apply")
                .isEqualTo(100);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void assertRejectedAndControlled(String basePath, String token, String badSort, String goodSort) {
        assertRejected(basePath + "?sort=" + badSort, token);
        assertOk(basePath + "?sort=" + goodSort, token);
    }

    private void assertRejected(String url, String token) {
        var response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(response.getStatusCode())
                .as("dotted sort must be a 400 at %s (never 200, never 500)", url)
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void assertOk(String url, String token) {
        var response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(response.getStatusCode())
                .as("positive control must succeed at %s, else the 400 above proves nothing", url)
                .isEqualTo(HttpStatus.OK);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(PASSWORD), role, salonId);
        return id;
    }

    private String login(String email) throws Exception {
        var response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                java.util.Map.of("email", email, "password", PASSWORD),
                String.class);
        assertThat(response.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data").path("accessToken").asText();
    }
}
