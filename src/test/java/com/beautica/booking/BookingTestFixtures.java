package com.beautica.booking;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared HTTP + JDBC fixture helpers for the Phase 26.x {@code GET /bookings/me} integration-test
 * family — {@link BookingMyBookingsMultiStatusFilterIT} (26.1), {@link BookingMyBookingsSortIT}
 * (26.3), {@link BookingMyBookingsDateRangeFilterIT} (26.2). Extracted per backend-qa's own 26.2
 * audit (LOW finding — these seven helpers were byte-identical copy-paste across all three IT
 * classes, crossing the three-occurrence "extraction overdue" threshold, Q4 in the QA playbook).
 *
 * <p>Mirrors the {@code com.beautica.service.ServiceTestFixtures} convention already established
 * in this codebase: a package-private, constructor-injected plain class instantiated per test in
 * {@code @BeforeEach} — deliberately NOT a shared base class, so each IT keeps extending {@link
 * com.beautica.AbstractIntegrationTest} directly and its Spring context wiring is untouched.
 *
 * <p><b>What did NOT move here</b> — each IT's {@code insertBooking}/{@code callMyBookings*}
 * helpers were checked and found to have genuinely diverged (different SQL columns bound, different
 * parameter shapes: multi-status filter's insert takes a {@code status} enum string, sort's insert
 * additionally takes a {@code price} string, date-range's insert is always {@code CONFIRMED} and
 * takes no status at all), so those stay local to their own IT rather than being force-merged.
 * {@code createSalon}/{@code SalonFixture}/{@code createSalonService} are used only by the
 * multi-status suite and also stay local.
 */
class BookingTestFixtures {

    static final String TEST_PASSWORD = "Str0ngP@ss1!";

    private final TestRestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;

    BookingTestFixtures(
            TestRestTemplate restTemplate,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.restTemplate = restTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
    }

    UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    UUID createIndependentMaster(String email) {
        UUID userId = createUser(email, "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    /** Resolves a real, selectable {@code service_types.id} (V111 made this column NOT NULL). */
    UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    String tokenFor(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    List<UUID> extractIds(JsonNode root) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode row : root.path("data").path("data")) {
            ids.add(UUID.fromString(row.path("id").asText()));
        }
        return ids;
    }
}
