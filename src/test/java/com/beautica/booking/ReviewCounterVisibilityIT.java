package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phases 383/384 QA guard - the persisted {@code review_count} / {@code avg_rating} of the master AND the salon are
 * already updated in the database when {@code POST /reviews} RETURNS (no polling, no sleep). The public
 * {@code /reviews} "beyond the last page" shortcut reads these counters; the structural twin is
 * {@code com.beautica.review.event.ReviewCounterSynchronyGuardTest}.
 * The counters are written by a synchronous AFTER_COMMIT listener in its own transaction (not in the review insert's
 * transaction), so this IT, not a transaction check, is the contract: visible on the very next read.
 */
@Import(TestSecurityConfig.class)
@DisplayName("POST /reviews returns only after masters/salons review_count + avg_rating are visible (guards the /reviews shortcut)")
class ReviewCounterVisibilityIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("a salon-master review: masters.* and salons.* counters hold the new values the instant the 201 arrives")
    void should_haveCountersVisible_when_postReviewReturns() throws Exception {
        var pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
        var salon = pages.salon("Counter Salon " + System.nanoTime());
        UUID masterId = pages.salonMaster(salon.salonId(), "Анна", "Біла");
        BookableMasterSeeder.makeBookable(jdbcTemplate, salon.salonId(), masterId);
        UUID clientId = pages.insertClient();
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, clientId);
        ResponseEntity<String> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, PublicBookingPageFixtures.PASSWORD), String.class);
        String token = objectMapper.readValue(login.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? LIMIT 1", UUID.class, masterId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'COMPLETED', "
                        + "NOW() - interval '2 hours', NOW() - interval '1 hour', 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salon.salonId());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> created = restTemplate.exchange("/api/v1/reviews", HttpMethod.POST,
                new HttpEntity<>("{\"bookingId\":\"" + bookingId + "\",\"rating\":4}", headers), String.class);

        assertThat(created.getStatusCode()).as("body=%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM masters WHERE id = ?", Integer.class, masterId))
                .as("masters.review_count right after the 201").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT avg_rating FROM masters WHERE id = ?", BigDecimal.class, masterId))
                .as("masters.avg_rating right after the 201").isEqualByComparingTo("4");
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM salons WHERE id = ?", Integer.class, salon.salonId()))
                .as("salons.review_count right after the 201").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT avg_rating FROM salons WHERE id = ?", BigDecimal.class, salon.salonId()))
                .as("salons.avg_rating right after the 201").isEqualByComparingTo("4");
    }
}
