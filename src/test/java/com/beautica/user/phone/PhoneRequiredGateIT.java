package com.beautica.user.phone;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import com.beautica.common.TimeZones;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B516 (AUTH-45, AUTH-D12) — the server gate behind the app, flag ON. The flag comes from this class's own
 * {@link TestPropertySource} (its own cached context), never from mutating the shared {@code AuthConsentProperties}
 * bean, so no other class can observe it. There is no provider deadline: an unverified provider is blocked at once.
 * The booking bodies are deliberately empty: the gate runs before argument validation, so a passed gate shows as the
 * 400 of validation. Flag OFF with a real booking: {@code PhoneGateFlagOffIT}.
 */
@TestPropertySource(properties = "app.auth.require-verified-phone=true")
@DisplayName("Verified-phone gate ON: APP_AUTH_REQUIRE_VERIFIED_PHONE (B516, AUTH-45)")
class PhoneRequiredGateIT extends AbstractPhoneIdentityIT {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final String PROVIDER_WRITE_URL = "/api/v1/independent-masters/me/profile";
    private static final String ME_URL = "/api/v1/users/me";
    private static final String INVITE_URL = "/api/v1/auth/invite";

    @Autowired
    private Clock clock;

    private LocalDate kyivToday() {
        return LocalDate.now(clock.withZone(TimeZones.KYIV));
    }

    private String unverifiedToken(String role) {
        String email = newEmail();
        seedUser(role, email, true, null, null);
        return accessToken(email);
    }

    @Test
    @DisplayName("an unverified client creating a booking gets 409 PHONE_NOT_VERIFIED")
    void should_return409_when_flagOnAndUnverifiedClientBooks() {
        String token = unverifiedToken("CLIENT");

        ResponseEntity<String> booking = postAs(token, BOOKINGS_URL, Map.of());

        assertThat(booking.getStatusCode()).as("body: %s", booking.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(booking).at("/data/code").asText()).isEqualTo("PHONE_NOT_VERIFIED");
    }

    @Test
    @DisplayName("an unverified client creating a visit gets 409 PHONE_NOT_VERIFIED")
    void should_return409_when_flagOnAndUnverifiedClientCreatesVisit() {
        String token = unverifiedToken("CLIENT");

        ResponseEntity<String> visit = postAs(token, APPOINTMENTS_URL, Map.of());

        assertThat(visit.getStatusCode()).as("body: %s", visit.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(visit).at("/data/code").asText()).isEqualTo("PHONE_NOT_VERIFIED");
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
    @DisplayName("an unverified provider may still read GET /users/me, which reports phoneRequiredBy = today (Kyiv)")
    void should_allowReadWithPhoneRequiredToday_when_flagOnAndProviderUnverified() {
        String token = unverifiedToken("INDEPENDENT_MASTER");
        LocalDate before = kyivToday();

        ResponseEntity<String> read = exchangeAs(token, HttpMethod.GET, ME_URL, null);

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(read).at("/data/phoneRequiredBy").asText())
                .as("the app clock's Kyiv date, read around the request (midnight-safe)")
                .isIn(before.toString(), kyivToday().toString());
    }

    @Test
    @DisplayName("an unverified provider's other write gets 409 PHONE_NOT_VERIFIED")
    void should_return409_when_flagOnAndUnverifiedProviderWrites() {
        String token = unverifiedToken("INDEPENDENT_MASTER");

        ResponseEntity<String> write = exchangeAs(token, HttpMethod.PATCH, PROVIDER_WRITE_URL, Map.of("bio", "x"));

        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(write).at("/data/code").asText()).isEqualTo("PHONE_NOT_VERIFIED");
    }

    @Test
    @DisplayName("an unverified provider may send the add-phone code (the way out stays open)")
    void should_allowPhoneOtpSend_when_flagOnAndProviderUnverified() {
        String token = unverifiedToken("INDEPENDENT_MASTER");

        ResponseEntity<String> send = postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", newPhone()));

        assertThat(send.getStatusCode()).as("body: %s", send.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an unverified provider may verify the add-phone code, and is then let through")
    void should_allowPhoneOtpVerify_when_flagOnAndProviderUnverified() {
        String token = unverifiedToken("INDEPENDENT_MASTER");
        String phone = newPhone();
        ResponseEntity<String> send = postAs(token, UserPhoneOtpIT.PHONE_SEND_URL, Map.of("phone", phone));
        assertThat(send.getStatusCode()).as("send body: %s", send.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> verify = postAs(token, UserPhoneOtpIT.PHONE_VERIFY_URL,
                Map.of("phone", phone, "code", lastCodeSentTo(phone)));
        ResponseEntity<String> gatedWrite = exchangeAs(token, HttpMethod.PATCH, PROVIDER_WRITE_URL,
                Map.of("bio", "x"));

        assertThat(verify.getStatusCode()).as("body: %s", verify.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(gatedWrite.getStatusCode()).as("gated write after verify, body: %s", gatedWrite.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an unverified provider's PATCH /users/me gets 409: the users/me exemption covers only the phone OTP")
    void should_return409_when_flagOnAndUnverifiedProviderPatchesProfile() {
        String token = unverifiedToken("INDEPENDENT_MASTER");

        ResponseEntity<String> patch = exchangeAs(token, HttpMethod.PATCH, ME_URL, Map.of("firstName", "Нове"));

        assertThat(patch.getStatusCode()).as("body: %s", patch.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(patch).at("/data/code").asText()).isEqualTo("PHONE_NOT_VERIFIED");
    }

    @Test
    @DisplayName("an unverified salon owner's staff invite gets 409 and sends no SMS: /auth/invite is not exempt")
    void should_return409AndSendNoSms_when_flagOnAndUnverifiedOwnerInvites() {
        Salon salon = newSalon();
        String invitedPhone = newPhone();

        ResponseEntity<String> invite = postAs(salon.ownerToken(), INVITE_URL,
                Map.of("phone", invitedPhone, "salonId", salon.salonId(), "role", "SALON_MASTER"));

        assertThat(invite.getStatusCode()).as("body: %s", invite.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(invite).at("/data/code").asText()).isEqualTo("PHONE_NOT_VERIFIED");
        assertThat(smsCountTo(invitedPhone)).as("no invite SMS").isZero();
    }

    @Test
    @DisplayName("an unverified salon owner's salon edit gets 409 PHONE_NOT_VERIFIED and the salon is unchanged")
    void should_return409AndKeepSalon_when_flagOnAndSalonOwnerUnverified() {
        Salon salon = newSalon();

        ResponseEntity<String> edit = exchangeAs(salon.ownerToken(), HttpMethod.PATCH,
                "/api/v1/salons/" + salon.salonId(), Map.of("name", "Нова назва"));

        assertThat(edit.getStatusCode()).as("body: %s", edit.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(edit).at("/data/code").asText()).isEqualTo("PHONE_NOT_VERIFIED");
        String storedName = jdbcTemplate.queryForObject("SELECT name FROM salons WHERE id = ?", String.class,
                salon.salonId());
        assertThat(storedName).as("no write happened").isEqualTo(SALON_NAME);
    }

    @Test
    @DisplayName("a salon owner who verifies a phone passes the gate with the same token (the DB row decides)")
    void should_passGate_when_flagOnAndSalonOwnerVerifiedAfterLogin() {
        Salon salon = newSalon();
        jdbcTemplate.update("UPDATE users SET phone_number = ?, phone_verified_at = NOW() WHERE id = ?",
                newPhone(), salon.ownerId());

        // name-only body: a passed gate surfaces as the 400 of request validation (street / buildingNo required)
        ResponseEntity<String> edit = exchangeAs(salon.ownerToken(), HttpMethod.PATCH,
                "/api/v1/salons/" + salon.salonId(), Map.of("name", "Нова назва"));

        assertThat(edit.getStatusCode()).as("body: %s", edit.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
