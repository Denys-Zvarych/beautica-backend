package com.beautica.salon;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared JDBC/HTTP fixtures for the salon integration tests (§M-3).
 *
 * <p>{@code insertUser} / {@code insertSalon} / {@code emailOf} / {@code loginAndGetToken} /
 * {@code bearerHeaders} had been copied byte-for-byte into four classes —
 * {@link SalonSiblingSalonsEndpointIT}, {@link SalonSiblingRotationParityIT},
 * {@link SalonAdminRotationIntegrationTest} and {@link SalonStaffEndpointIT} — with the salon
 * INSERT already drifting cosmetically (one-line vs wrapped SQL, {@code insertAdmin} vs
 * {@code insertSalonAdminUser}, a separate {@code insertInactiveSalon} where a boolean overload
 * would do). §M-3 says extract the moment the second file copies them.
 *
 * <p>Modelled on {@link com.beautica.service.ServiceTestFixtures}: a plain collaborator holding the
 * autowired beans, constructed by each test class rather than a base class every salon IT would be
 * forced to extend — the salon ITs already extend {@link com.beautica.AbstractIntegrationTest}
 * (the shared Testcontainers singleton, §M-2), and Java has no second superclass to give.
 *
 * <p>The city id is taken as a {@link Supplier} so callers pass
 * {@code AbstractIntegrationTest::testCityId} — the single shared resolver whose Javadoc forbids
 * re-querying {@code cities} ad hoc. It stays lazy so a fixture object may be built in a field
 * initialiser before any DB access.
 *
 * <p>Real BCrypt throughout ({@link PasswordEncoder} is the application's own bean, never a literal
 * {@code $2a$10$...} string) — §M-5.
 */
class SalonItFixtures {

    static final String TEST_PASSWORD = "Str0ngP@ss1!";

    private final TestRestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final Supplier<UUID> cityIdSupplier;

    SalonItFixtures(
            TestRestTemplate restTemplate,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PasswordEncoder passwordEncoder,
            Supplier<UUID> cityIdSupplier
    ) {
        this.restTemplate = restTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
        this.cityIdSupplier = cityIdSupplier;
    }

    // ── users ──────────────────────────────────────────────────────────────────

    /** An active, email-verified user of {@code role} with no salon assignment. */
    UUID insertUser(String email, String role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role);
        return id;
    }

    /**
     * An active, email-verified user of {@code role} assigned to {@code salonId} — the shape both
     * {@code SALON_ADMIN} and {@code SALON_MASTER} staff fixtures need, so neither role gets its
     * own near-duplicate INSERT.
     */
    UUID insertUserWithSalon(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    /** A {@code SALON_ADMIN} assigned to {@code salonId}. */
    UUID insertAdmin(String email, UUID salonId) {
        return insertUserWithSalon(email, "SALON_ADMIN", salonId);
    }

    /** A {@code SALON_MASTER} (read-only staff) assigned to {@code salonId}. */
    UUID insertSalonMasterUser(String email, UUID salonId) {
        return insertUserWithSalon(email, "SALON_MASTER", salonId);
    }

    // ── salons ─────────────────────────────────────────────────────────────────

    /** An ACTIVE salon owned by {@code ownerId}. */
    UUID insertSalon(UUID ownerId, String name) {
        return insertSalon(ownerId, name, true);
    }

    /** A salon owned by {@code ownerId} with an explicit {@code is_active} flag. */
    UUID insertSalon(UUID ownerId, String name, boolean isActive) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW(), ?)",
                salonId, ownerId, name, isActive, cityIdSupplier.get());
        return salonId;
    }

    /**
     * An ACTIVE salon with {@code created_at} pinned {@code minutesAgo} in the past, for tests that
     * assert the {@code created_at ASC} ordering contract without relying on insertion order.
     */
    UUID insertSalonAgedMinutes(UUID ownerId, String name, int minutesAgo) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW() - CAST(? AS interval), NOW(), ?)",
                salonId, ownerId, name, minutesAgo + " minutes", cityIdSupplier.get());
        return salonId;
    }

    // ── reads ──────────────────────────────────────────────────────────────────

    String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    /** The user's persisted {@code salon_id} — the column {@code rotateAdmin} moves. */
    UUID readSalonId(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT salon_id FROM users WHERE id = ?", UUID.class, userId);
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
