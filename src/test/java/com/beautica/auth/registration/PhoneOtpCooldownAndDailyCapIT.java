package com.beautica.auth.registration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phone-first hardening over HTTP on the registration phone OTP: the 60 s per-phone+purpose resend cooldown
 * (AUTH-D27 a, 429 with {@code Retry-After}) and the 10 failed verifies per phone per 24 h (AUTH-D26 c: verify then
 * answers {@code INVALID_CODE} even for the right code, and send answers 429), counted per (phone, client network) —
 * audit-fix, user 2026-11-19. Earlier failures are seeded in {@code phone_otp_failures} (V258) so the per-IP verify
 * bucket never interferes.
 */
@DisplayName("Registration phone OTP — resend cooldown and daily failed-verify cap (AUTH-D26 c, AUTH-D27 a)")
class PhoneOtpCooldownAndDailyCapIT extends AbstractPhoneIdentityIT {

    /** {@code failures} wrong codes typed for {@code phone} from {@code clientNet} an hour ago (V258 failure log). */
    private void seedFailures(String phone, String clientNet, int failures, String age) {
        for (int i = 0; i < failures; i++) {
            jdbcTemplate.update("INSERT INTO phone_otp_failures (phone, client_net, failed_at) "
                    + "VALUES (?, ?, now() - CAST(? AS interval))", phone, clientNet, age);
        }
    }

    /** ... from this test instance's own network. */
    private void seedFailures(String phone, int failures) {
        seedFailures(phone, clientIp(), failures, "1 hour");
    }

    @Test
    @DisplayName("should_return429WithRetryAfter_when_sameCodeRequestedAgainWithin60s")
    void should_return429WithRetryAfter_when_sameCodeRequestedAgainWithin60s() {
        String phone = newPhone();
        assertThat(sendCode(phone).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> again = sendCode(phone);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        long retryAfter = Long.parseLong(again.getHeaders().getFirst("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 60L);
        assertThat(smsCountTo(phone)).isOne();
    }

    @Test
    @DisplayName("should_sendAgain_when_cooldownElapsed")
    void should_sendAgain_when_cooldownElapsed() {
        String phone = newPhone();
        sendCode(phone);
        elapseResendCooldown(phone);

        ResponseEntity<String> again = sendCode(phone);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(smsCountTo(phone)).isEqualTo(2);
    }

    @Test
    @DisplayName("should_acceptCorrectCode_when_nineFailuresInLast24h")
    void should_acceptCorrectCode_when_nineFailuresInLast24h() {
        String phone = newPhone();
        seedFailures(phone, 9);
        sendCode(phone);

        ResponseEntity<String> verified = verifyCode(phone, lastCodeSentTo(phone));

        assertThat(verified.getStatusCode()).as("10th verify: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("should_refuseCorrectCode_when_tenthFailureJustRecorded")
    void should_refuseCorrectCode_when_tenthFailureJustRecorded() {
        String phone = newPhone();
        seedFailures(phone, 9);
        sendCode(phone);
        String code = lastCodeSentTo(phone);
        String wrong = code.equals("000000") ? "111111" : "000000";
        assertThat(verifyCode(phone, wrong).getStatusCode()).as("10th verify, wrong").isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> eleventh = verifyCode(phone, code);

        assertThat(eleventh.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(eleventh).at("/data/code").asText()).isEqualTo("INVALID_CODE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_registration_tickets WHERE phone = ?",
                Integer.class, phone)).isZero();
    }

    @Test
    @DisplayName("should_return429OnSend_when_tenFailuresInLast24h")
    void should_return429OnSend_when_tenFailuresInLast24h() {
        String phone = newPhone();
        seedFailures(phone, 10);

        ResponseEntity<String> send = sendCode(phone);

        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(smsCountTo(phone)).isZero();
    }

    @Test
    @DisplayName("should_ignoreFailuresOlderThan24h_when_sending")
    void should_ignoreFailuresOlderThan24h_when_sending() {
        String phone = newPhone();
        seedFailures(phone, clientIp(), 10, "24 hours 30 minutes");

        ResponseEntity<String> send = sendCode(phone);

        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Audit-fix (user 2026-11-19): the cap is per (phone, client network) ──

    @Test
    @DisplayName("should_acceptCorrectCodeFromNetworkB_when_tenFailuresCameFromNetworkA")
    void should_acceptCorrectCodeFromNetworkB_when_tenFailuresCameFromNetworkA() {
        String phone = newPhone();
        seedFailures(phone, "198.51.100.77", 10, "1 hour");
        sendCode(phone);

        ResponseEntity<String> verified = verifyCode(phone, lastCodeSentTo(phone));

        assertThat(verified.getStatusCode()).as("another network's failures never lock this one: %s",
                verified.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("should_refuseNetworkAButNotNetworkB_when_tenWrongCodesFromNetworkA")
    void should_refuseNetworkAButNotNetworkB_when_tenWrongCodesFromNetworkA() {
        String phone = newPhone();
        String networkA = "2001:db8:aa:1::5";
        seedFailures(phone, "2001:db8:aa:1:0:0:0:0/64", 9, "1 hour");
        sendCode(phone);
        String code = lastCodeSentTo(phone);
        String wrong = code.equals("000000") ? "111111" : "000000";
        assertThat(postFrom(networkA, VERIFY_URL, java.util.Map.of("phone", phone, "code", wrong))
                .getStatusCode()).as("10th wrong code from network A").isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> fromSameSlash64 = postFrom("2001:db8:aa:1::abcd", VERIFY_URL,
                java.util.Map.of("phone", phone, "code", code));
        ResponseEntity<String> fromNetworkB = verifyCode(phone, code);

        assertThat(json(fromSameSlash64).at("/data/code").asText())
                .as("another address in network A's /64 is network A").isEqualTo("INVALID_CODE");
        assertThat(fromNetworkB.getStatusCode()).as("network B still verifies: %s", fromNetworkB.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otp_failures WHERE phone = ? "
                + "AND client_net = '2001:db8:aa:1:0:0:0:0/64'", Integer.class, phone)).isEqualTo(10);
    }
}
