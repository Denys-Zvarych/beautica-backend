package com.beautica.notification.telegram.link;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.TestSecurityConfig;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 407 — the guest Telegram link API with the channel OFF (the test-profile default, so this class shares the
 * plain {@code TestSecurityConfig} context): 404 on all three endpoints even for an eligible booking, and the D6
 * {@code telegram} block of a guest booking reads {@code available:false, linked:false} — never touching the link
 * table — while the booking itself is created normally.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GuestTelegramLinkController — channel off (phase 407)")
class GuestTelegramLinkDisabledIT extends AbstractIntegrationTest {

    private static final String PHONE = "+380501234567";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private GuestTokenProvider guestTokenProvider;

    private static String nextIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "198.19." + (n / 250) + "." + (n % 250 + 1);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", nextIp());
        return headers;
    }

    private UUID bookableMaster(String slug) {
        UUID masterId = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder)
                .createIndependentMaster("tg-407-off-" + UUID.randomUUID() + "@beautica.test");
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, masterId);
        UUID defId = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, masterId);
        jdbcTemplate.update("UPDATE service_definitions SET category = "
                + "(SELECT platform_category_name FROM service_types WHERE id = service_type_id) WHERE id = ?", defId);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);
        return jdbcTemplate.queryForObject("SELECT id FROM master_services WHERE service_def_id = ?", UUID.class,
                defId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"link-token", "status", "unlink"})
    @DisplayName("should return 404 when channel off for eligible booking")
    void should_return404_when_channelOffForEligibleBooking(String op) throws Exception {
        BookingTestFixtures fixtures = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder);
        UUID masterId = fixtures.createIndependentMaster("tg-407-off-" + UUID.randomUUID() + "@beautica.test");
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        UUID token = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, cancel_token_hash, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, NULL, 'CONFIRMED', NOW() + interval '2 days', "
                        + "NOW() + interval '2 days 1 hour', 500.00, 60, 0, 'LINK', 'Гість', 'Тестовий', ?, ?, NOW(), NOW())",
                UUID.randomUUID(), masterId, masterServiceId, PHONE, CancelTokenHasher.hash(token));

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/book/telegram/" + op, HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(java.util.Map.of("cancelToken", token.toString())),
                        jsonHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("message").asText()).isEqualTo("Resource not found");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens", Integer.class)).isZero();
    }

    @Test
    @DisplayName("should report unavailable when channel off even with active link")
    void should_reportUnavailable_when_channelOffEvenWithActiveLink() throws Exception {
        String slug = "tg-407-off-" + UUID.randomUUID().toString().substring(0, 8);
        UUID serviceId = bookableMaster(slug);
        jdbcTemplate.update("INSERT INTO telegram_links (id, phone_hash, chat_id, status, linked_at, status_changed_at) "
                + "VALUES (?, ?, 7100000099, 'ACTIVE', NOW(), NOW())", UUID.randomUUID(), phoneHasher.hash(PHONE).hash());
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(guestTokenProvider.generate(PHONE));
        ZonedDateTime startsAt = ZonedDateTime.now(KYIV).plusDays(3).withHour(12).withMinute(0).withSecond(0).withNano(0);
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("serviceId", serviceId.toString());
        fields.put("startsAt", startsAt.toOffsetDateTime().toString());
        fields.put("name", "Олена");
        fields.put("surname", "Коваль");
        fields.put("privacyAccepted", true);
        fields.put("privacyPolicyVersion", "2025-02-03");
        String body = objectMapper.writeValueAsString(fields);

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = objectMapper.readTree(response.getBody());
        assertThat(created.path("telegram").toString()).isEqualTo("{\"available\":false,\"linked\":false}");
        assertThat(created.path("cancelUrl").asText()).contains("/book/cancel/");
    }
}
