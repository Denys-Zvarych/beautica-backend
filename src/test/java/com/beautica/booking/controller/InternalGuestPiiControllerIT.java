package com.beautica.booking.controller;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 373 D9 — {@code POST /api/v1/internal/guest-pii/erase} through the real filter chain:
 * {@code InternalApiKeyFilter} gate (fail-closed blank key is covered by
 * {@code InternalApiKeyFilterBlankKeyTest}), validation, and the end-to-end effect.
 */
@DisplayName("InternalGuestPiiController — POST /api/v1/internal/guest-pii/erase")
@ExtendWith(OutputCaptureExtension.class)
class InternalGuestPiiControllerIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/internal/guest-pii/erase";
    private static final String KEY = "test-internal-api-key";
    private static final String PHONE = "+380501234567";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;

    private ResponseEntity<String> post(String body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(URL, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders withKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", key);
        return headers;
    }

    @Test
    @DisplayName("401 without the internal key")
    void should_return401_when_keyMissing() {
        ResponseEntity<String> response = post("{\"phone\":\"0501234567\"}", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("401 with a wrong internal key")
    void should_return401_when_keyWrong() {
        ResponseEntity<String> response = post("{\"phone\":\"0501234567\"}", withKey("not-the-key"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("401 for a SALON_OWNER JWT without the internal key")
    void should_return401_when_jwtWithoutKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(
                UUID.randomUUID(), "owner@beautica.test", Role.SALON_OWNER));

        ResponseEntity<String> response = post("{\"phone\":\"0501234567\"}", headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("400 for an invalid phone, never echoing the input")
    void should_return400_when_phoneInvalid() {
        ResponseEntity<String> response = post("{\"phone\":\"12ab-secret\"}", withKey(KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).doesNotContain("12ab-secret");
    }

    @Test
    @DisplayName("400 for a blank phone")
    void should_return400_when_phoneBlank() {
        ResponseEntity<String> response = post("{\"phone\":\"  \"}", withKey(KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("200 with a valid ticketRef, which is logged with the counts and never the phone")
    void should_logTicketRef_when_valid(CapturedOutput output) {
        ResponseEntity<String> response = post(
                "{\"phone\":\"0931234567\",\"ticketRef\":\"SUP-20260042\"}", withKey(KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(output.getAll())
                .contains("Guest PII erase (ticket SUP-20260042):")
                .doesNotContain("931234567");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad ref", "SUP_1", "SUP-2026-0042", "S-1", "", "ABCDEFGHIJK-1",
            "SUP-123456789012345678901", "x\nINFO forged", "SUP-1\nINFO forged"})
    @DisplayName("400 for an invalid ticketRef, never echoing it")
    void should_return400_when_ticketRefInvalid(String ticketRef) throws Exception {
        String body = objectMapper.writeValueAsString(
                java.util.Map.of("phone", "0931234567", "ticketRef", ticketRef));

        ResponseEntity<String> response = post(body, withKey(KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        if (!ticketRef.isEmpty()) {
            assertThat(response.getBody()).doesNotContain(ticketRef);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0501234567", "380501234567", "+380501234567", "050-1234567"})
    @DisplayName("400 when the ticketRef is a phone number (digits only / phone-shaped), never echoing it")
    void should_return400_when_ticketRefIsDigitsOnly(String ticketRef) throws Exception {
        String body = objectMapper.writeValueAsString(
                java.util.Map.of("phone", "0931234567", "ticketRef", ticketRef));

        ResponseEntity<String> response = post(body, withKey(KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).doesNotContain("501234567");
    }

    @Test
    @DisplayName("200 with zeros for an unknown phone")
    void should_returnZeros_when_phoneUnknown() throws Exception {
        ResponseEntity<String> response = post("{\"phone\":\"0931234567\"}", withKey(KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("bookingsCancelled").asInt()).isZero();
        assertThat(data.path("visitsCancelled").asInt()).isZero();
        assertThat(data.path("bookingsAnonymised").asInt()).isZero();
        assertThat(data.path("visitsAnonymised").asInt()).isZero();
        assertThat(data.path("otpRowsDeleted").asInt()).isZero();
    }

    @Test
    @DisplayName("200: a 0XXXXXXXXX phone anonymises that phone's past guest rows of any age and deletes its OTPs; other rows untouched")
    void should_eraseAllRowsOfPhone_when_localFormat() throws Exception {
        UUID masterId = seedMaster();
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ?", UUID.class, masterId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID old = seedCompletedLink(masterId, masterServiceId, now.minusMonths(30), PHONE);
        UUID recent = seedCompletedLink(masterId, masterServiceId, now.minusDays(3), PHONE);
        UUID other = seedCompletedLink(masterId, masterServiceId, now.minusDays(4), "+380671112233");
        jdbcTemplate.update("INSERT INTO phone_otps (phone, code_hash, expires_at) VALUES (?, 'h', NOW())", PHONE);

        ResponseEntity<String> response = post("{\"phone\":\"050 123 45 67\"}", withKey(KEY));
        ResponseEntity<String> repeat = post("{\"phone\":\"0501234567\"}", withKey(KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("bookingsAnonymised").asInt()).isEqualTo(2);
        assertThat(data.path("otpRowsDeleted").asInt()).isEqualTo(1);
        assertThat(response.getBody()).doesNotContain("501234567");
        for (UUID id : new UUID[]{old, recent}) {
            assertThat(jdbcTemplate.queryForObject("SELECT guest_phone FROM bookings WHERE id = ?", String.class, id))
                    .isNull();
            assertThat(jdbcTemplate.queryForObject("SELECT guest_name FROM bookings WHERE id = ?", String.class, id))
                    .isEqualTo("Гість");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT guest_phone FROM bookings WHERE id = ?", String.class, other))
                .isEqualTo("+380671112233");
        assertThat(objectMapper.readTree(repeat.getBody()).path("data").path("bookingsAnonymised").asInt()).isZero();
    }

    private UUID seedMaster() {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, true, 'Марія', 'Левченко')",
                ownerId, "erase-owner-" + System.nanoTime() + "@beautica.test",
                new BCryptPasswordEncoder(4).encode("test-password"));
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, master_type, is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, ?, NOW(), NOW())",
                masterId, ownerId, "erase-master-" + Long.toString(System.nanoTime(), 36));
        UUID serviceTypeId = jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1", UUID.class);
        UUID defId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Манікюр', ?, 60, 350.00, 0, true, NOW(), NOW())",
                defId, ownerId, serviceTypeId);
        jdbcTemplate.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, true, NOW(), NOW())", UUID.randomUUID(), masterId, defId);
        return masterId;
    }

    private UUID seedCompletedLink(UUID masterId, UUID masterServiceId, OffsetDateTime endsAt, String phone) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "guest_name, guest_surname, guest_phone, reminder_sent, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'COMPLETED', ?, ?, 350.00, 60, 0, 'LINK', 'Олена', 'Шевчук', ?, false, NOW(), NOW())",
                id, masterId, masterServiceId, endsAt.minusMinutes(60), endsAt, phone);
        return id;
    }
}
