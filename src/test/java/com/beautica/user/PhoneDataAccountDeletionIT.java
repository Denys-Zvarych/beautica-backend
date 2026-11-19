package com.beautica.user;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SUP-15 / phone-first hardening — deleting a phone-registered account leaves no live phone credential behind: its
 * user-bound codes go with the row (FK ON DELETE CASCADE, V244), its registration ticket is spent, and the same phone
 * can register a fresh account afterwards.
 */
@DisplayName("Account deletion — phone codes and tickets, and the phone is reusable (SUP-15)")
class PhoneDataAccountDeletionIT extends AbstractPhoneIdentityIT {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String DELETE_ME_URL = "/api/v1/users/me";
    private static final String PHONE_SEND_URL = "/api/v1/users/me/phone/otp/send";

    private ResponseEntity<String> registerClient(String phone) {
        return post(REGISTER_URL, consent(Map.of("registrationTicket", ticketFor(phone), "password", PASSWORD,
                "firstName", "Олена", "role", "CLIENT")));
    }

    private UUID userIdOf(String phone) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone_number = ?", UUID.class, phone);
    }

    @Test
    @DisplayName("should_removeUserBoundCodesAndAllowReRegistration_when_phoneAccountDeleted")
    void should_removeUserBoundCodesAndAllowReRegistration_when_phoneAccountDeleted() {
        String phone = newPhone();
        String otherPhone = newPhone();
        ResponseEntity<String> registered = registerClient(phone);
        assertThat(registered.getStatusCode()).as("body: %s", registered.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode auth = json(registered).at("/data/auth");
        String token = auth.at("/accessToken").asText();
        UUID userId = userIdOf(phone);
        assertThat(postAs(token, PHONE_SEND_URL, Map.of("phone", otherPhone)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> deleted = exchangeAs(token, HttpMethod.DELETE, DELETE_ME_URL, null);

        assertThat(deleted.getStatusCode().is2xxSuccessful()).as("body: %s", deleted.getBody()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otps WHERE user_id = ?", Integer.class,
                userId)).as("user-bound codes go with the account").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otps WHERE phone = ? AND used = false",
                Integer.class, phone)).as("no live code for the deleted account's phone").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM phone_registration_tickets WHERE phone = ? AND used = false", Integer.class,
                phone)).as("no redeemable ticket for the deleted account's phone").isZero();

        elapseResendCooldown(phone); // AUTH-D27 (a): the signup code above was sent moments ago
        ResponseEntity<String> again = registerClient(phone);

        assertThat(again.getStatusCode()).as("the phone registers again: %s", again.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(userIdOf(phone)).isNotEqualTo(userId);
    }
}
