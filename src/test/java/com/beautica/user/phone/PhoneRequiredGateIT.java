package com.beautica.user.phone;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B516 (AUTH-45, D2) — the server gate behind the app, flag ON. The flag and the provider deadline come from this
 * class's own {@link TestPropertySource} (its own cached context), never from mutating the shared
 * {@code AuthConsentProperties} bean, so no other class can observe them. Fixed dates far from "now" keep the
 * deadline arithmetic stable. The booking bodies are deliberately empty: the gate runs before argument validation, so
 * a passed gate shows as the 400 of validation. Flag OFF with a real booking: {@code PhoneGateFlagOffIT}; flag ON
 * before the deadline: {@code PhoneRequiredGateBeforeDeadlineIT}.
 */
@TestPropertySource(properties = {
        "app.auth.require-verified-phone=true",
        "app.auth.phone-provider-deadline=" + PhoneRequiredGateIT.PAST_DEADLINE})
@DisplayName("Verified-phone gate ON: APP_AUTH_REQUIRE_VERIFIED_PHONE (B516, AUTH-45)")
class PhoneRequiredGateIT extends AbstractPhoneIdentityIT {

    static final String PAST_DEADLINE = "2020-01-01";
    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final String PROVIDER_WRITE_URL = "/api/v1/independent-masters/me/profile";

    private String unverifiedToken(String role) {
        String email = newEmail();
        seedUser(role, email, true, null, null);
        return accessToken(email);
    }

    @Test
    @DisplayName("an unverified client creating a booking or a visit gets 409 PHONE_NOT_VERIFIED")
    void should_return409_when_flagOnAndUnverifiedClientBooks() {
        String token = unverifiedToken("CLIENT");

        ResponseEntity<String> booking = postAs(token, BOOKINGS_URL, Map.of());
        ResponseEntity<String> visit = postAs(token, APPOINTMENTS_URL, Map.of());

        assertThat(booking.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(booking).at("/data/code").asText()).isEqualTo("PHONE_NOT_VERIFIED");
        assertThat(visit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("a client with a verified phone passes the gate (and reaches request validation)")
    void should_passGate_when_flagOnAndClientVerified() {
        String phone = newPhone();
        seedUser("CLIENT", null, false, phone, null);

        ResponseEntity<String> response = postAs(accessToken(phone), BOOKINGS_URL, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("past the deadline an unverified provider may read and add a phone, but other writes get 409")
    void should_blockProviderWritesOnly_when_flagOnAndDeadlinePassed() {
        String token = unverifiedToken("INDEPENDENT_MASTER");

        ResponseEntity<String> read = exchangeAs(token, HttpMethod.GET, "/api/v1/users/me", null);
        ResponseEntity<String> write = exchangeAs(token, HttpMethod.PATCH, PROVIDER_WRITE_URL, Map.of("bio", "x"));
        ResponseEntity<String> addPhone = postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", newPhone()));

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(read).at("/data/phoneRequiredBy").asText()).isEqualTo(PAST_DEADLINE);
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(write).at("/data/code").asText()).isEqualTo("PHONE_NOT_VERIFIED");
        assertThat(addPhone.getStatusCode()).as("the way out stays open").isEqualTo(HttpStatus.OK);
    }
}
