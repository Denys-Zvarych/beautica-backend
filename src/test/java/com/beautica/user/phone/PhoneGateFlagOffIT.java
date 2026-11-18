package com.beautica.user.phone;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.common.TimeZones;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B516 (AUTH-45) — flag OFF (the application default, so this class shares the phone-identity context): a client
 * WITHOUT a verified phone creates a real booking end to end (201), proving the gate is inert, not merely that it lets
 * an invalid request reach validation.
 */
@DisplayName("Verified-phone gate OFF: an unverified client books for real (B516, AUTH-45)")
class PhoneGateFlagOffIT extends AbstractPhoneIdentityIT {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("with the flag off a client without a verified phone creates a booking (201)")
    void should_createBooking_when_flagOffAndClientUnverified() throws Exception {
        var fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        UUID masterId = fixtures.createIndependentMaster("gate-off-master-" + UUID.randomUUID() + "@beautica.test");
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);
        String clientEmail = "gate-off-client-" + UUID.randomUUID() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        assertThat(jdbcTemplate.queryForObject("SELECT phone_verified_at FROM users WHERE id = ?", Object.class,
                clientId)).as("the client has no verified phone").isNull();
        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV).plusDays(2)
                .withHour(12).withMinute(0).withSecond(0).withNano(0);
        String body = objectMapper.writeValueAsString(Map.of(
                "masterId", masterId.toString(),
                "masterServiceId", masterServiceId.toString(),
                "startsAt", startsAt.toOffsetDateTime().toString()));

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(body, fixtures.bearerHeaders(fixtures.tokenFor(clientEmail))), String.class);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
    }
}
