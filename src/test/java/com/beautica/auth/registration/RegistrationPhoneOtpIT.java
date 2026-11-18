package com.beautica.auth.registration;

import com.beautica.auth.phoneotp.PhoneOtpPurpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("POST /auth/register/phone/otp/send|verify — registration phone OTP (B514, AUTH-41)")
class RegistrationPhoneOtpIT extends AbstractPhoneIdentityIT {

    private static final String GUEST_SEND_URL = "/api/v1/book/otp/send";
    private static final String GUEST_VERIFY_URL = "/api/v1/book/otp/verify";

    private void seedVerifiedUser(String phone) {
        jdbcTemplate.update("INSERT INTO users (id, password_hash, role, is_active, email_verified, phone_number, "
                + "phone_verified_at) VALUES (gen_random_uuid(), 'x', 'CLIENT', true, false, ?, now())", phone);
    }

    @Test
    @DisplayName("send answers the same 200 for a new phone and a registered one (no oracle on send)")
    void should_answerIdentical200_when_phoneNewOrAlreadyRegistered() {
        String fresh = newPhone();
        String taken = newPhone();
        seedVerifiedUser(taken);

        ResponseEntity<String> freshResponse = sendCode(fresh);
        ResponseEntity<String> takenResponse = sendCode(taken);

        assertThat(freshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(takenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(takenResponse)).isEqualTo(json(freshResponse));
        assertThat(lastCodeSentTo(taken)).matches("\\d{6}");
    }

    @Test
    @DisplayName("a sent code is stored with the REGISTRATION purpose")
    void should_storeRegistrationPurpose_when_codeSent() {
        String phone = newPhone();

        sendCode(phone);

        String purpose = jdbcTemplate.queryForObject("SELECT purpose FROM phone_otps WHERE phone = ?",
                String.class, phone);
        assertThat(purpose).isEqualTo(PhoneOtpPurpose.REGISTRATION.name());
    }

    @Test
    @DisplayName("the fourth send to one phone within 15 min is 429")
    void should_return429_when_fourthSendWithin15MinForSamePhone() {
        String phone = newPhone();
        for (int i = 0; i < 3; i++) {
            assertThat(sendCode(phone).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> fourth = sendCode(phone);

        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("a landline or foreign number is 400 PHONE_NOT_UA_MOBILE and no SMS goes out")
    void should_return400PhoneNotUaMobileAndSendNoSms_when_landlineOrForeign() {
        ResponseEntity<String> landline = sendCode("+380441234567");
        ResponseEntity<String> foreign = sendCode("+48501234567");

        assertThat(landline.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(landline).at("/data/code").asText()).isEqualTo("PHONE_NOT_UA_MOBILE");
        assertThat(json(foreign).at("/data/code").asText()).isEqualTo("PHONE_NOT_UA_MOBILE");
        verify(otpSmsSender, never()).send(anyString(), anyString());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otps", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a correct code returns a registration ticket (national spelling accepted)")
    void should_returnTicket_when_codeCorrect() {
        String phone = newPhone();
        sendCode("0" + phone.substring(4));

        ResponseEntity<String> response = verifyCode(phone, lastCodeSentTo(phone));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).at("/data/registrationTicket").asText()).hasSizeGreaterThan(30);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM phone_registration_tickets WHERE phone = ?", Integer.class, phone)).isOne();
    }

    @Test
    @DisplayName("a wrong code is a generic 400")
    void should_returnGeneric400_when_codeWrong() {
        String phone = newPhone();
        sendCode(phone);
        String wrong = lastCodeSentTo(phone).equals("000000") ? "111111" : "000000";

        ResponseEntity<String> response = verifyCode(phone, wrong);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVALID_CODE");
    }

    @Test
    @DisplayName("a correct code for a phone a verified account holds is 409")
    void should_return409AfterCode_when_verifiedAccountHoldsPhone() {
        String phone = newPhone();
        seedVerifiedUser(phone);
        sendCode(phone);

        ResponseEntity<String> response = verifyCode(phone, lastCodeSentTo(phone));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("PHONE_ALREADY_REGISTERED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_registration_tickets",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("a guest-booking code cannot complete registration (purpose isolation)")
    void should_reject_when_guestCodeUsedForRegistration() {
        String phone = newPhone();
        post(GUEST_SEND_URL, Map.of("phone", phone, "privacyAccepted", true, "privacyPolicyVersion", "2026-10-20"));
        String guestCode = lastCodeSentTo(phone);

        ResponseEntity<String> response = verifyCode(phone, guestCode);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a registration code cannot verify a guest booking (purpose isolation)")
    void should_reject_when_registrationCodeUsedForGuestBooking() {
        String phone = newPhone();
        sendCode(phone);
        String registrationCode = lastCodeSentTo(phone);

        ResponseEntity<String> response = post(GUEST_VERIFY_URL, Map.of("phone", phone, "code", registrationCode));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("B514 QA (AUTH-41 / AUTH-D14): the 11th verify from one IP within 15 min is 429 before any code check "
            + "— the per-IP brute-force bucket covers the registration verify path")
    void should_return429_when_eleventhVerifyFromSameIp() {
        String phone = newPhone();
        sendCode(phone);
        String wrong = lastCodeSentTo(phone).equals("000000") ? "111111" : "000000";
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(verifyCode(phone, wrong).getStatusCode())
                    .as("attempt %d is inside the per-IP budget", attempt).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        ResponseEntity<String> eleventh = verifyCode(phone, wrong);

        assertThat(eleventh.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(eleventh.getHeaders().getFirst("Retry-After")).as("429 carries Retry-After").isNotBlank();
    }
}
