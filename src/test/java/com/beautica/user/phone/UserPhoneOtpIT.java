package com.beautica.user.phone;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import com.beautica.common.TimeZones;
import com.beautica.common.cache.UserProfileCacheEvictor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * B516 (AUTH-44, AUTH-45, CLI-04) — a signed-in account adds a phone by SMS code; the code is bound to the account
 * that asked for it; PATCH /users/me no longer writes the phone; GET /users/me reports phoneVerified /
 * phoneRequiredBy. App Check is OFF in this context (its ENFORCE cases live in {@code AppCheckGuardIT}).
 */
@DisplayName("Add phone by SMS code for a signed-in user (B516, AUTH-44)")
class UserPhoneOtpIT extends AbstractPhoneIdentityIT {

    static final String PHONE_SEND_URL = "/api/v1/users/me/phone/otp/send";
    static final String PHONE_VERIFY_URL = "/api/v1/users/me/phone/otp/verify";
    private static final String ME_URL = "/api/v1/users/me";

    @Autowired
    private Clock clock;

    @Autowired
    private CacheManager cacheManager;

    /** The app clock's Kyiv date (read before and after a request, so a midnight crossing cannot flake). */
    private LocalDate kyivToday() {
        return LocalDate.now(clock.withZone(TimeZones.KYIV));
    }

    private String legacyClientToken(String email) {
        seedUser("CLIENT", email, true, null, "0671112233");
        return accessToken(email);
    }

    private Map<String, Object> verifiedRow(String email) {
        return jdbcTemplate.queryForMap("SELECT phone_number, phone_verified_at FROM users WHERE email = ?", email);
    }

    @Test
    @DisplayName("a signed-in account adds a verified phone with its own code, no password needed")
    void should_addVerifiedPhone_when_ownCodeVerified() {
        String email = newEmail();
        String token = legacyClientToken(email);
        String phone = newPhone();

        assertThat(postAs(token, PHONE_SEND_URL, Map.of("phone", "0" + phone.substring(4))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> verified = postAs(token, PHONE_VERIFY_URL,
                Map.of("phone", phone, "code", lastCodeSentTo(phone)));

        assertThat(verified.getStatusCode()).as("body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = verifiedRow(email);
        assertThat(row.get("phone_number")).isEqualTo(phone);
        assertThat(row.get("phone_verified_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT purpose FROM phone_otps WHERE phone = ?", String.class, phone))
                .isEqualTo("PHONE_ADD");
        ResponseEntity<String> me = exchangeAs(token, HttpMethod.GET, ME_URL, null);
        assertThat(json(me).at("/data/phoneVerified").asBoolean()).isTrue();
        assertThat(json(me).at("/data/phoneRequiredBy").isNull()).isTrue();
        assertThat(accessToken(phone)).as("the new phone is a login identifier now").isNotBlank();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM in_app_notification n JOIN users u "
                + "ON u.id = n.recipient_user_id WHERE u.email = ? AND n.type LIKE 'SECURITY_%'", Integer.class, email))
                .as("a FIRST phone add is not a change: no security notice").isZero();
    }

    @Test
    @DisplayName("another account cannot redeem a code sent for someone else's phone add")
    void should_return400InvalidCodeAndKeepPhone_when_anotherAccountRedeemsTheCode() {
        String ownerEmail = newEmail();
        String ownerToken = legacyClientToken(ownerEmail);
        String attackerEmail = newEmail();
        String attackerToken = legacyClientToken(attackerEmail);
        String phone = newPhone();
        postAs(ownerToken, PHONE_SEND_URL, Map.of("phone", phone));
        String ownersCode = lastCodeSentTo(phone);

        ResponseEntity<String> stolen = postAs(attackerToken, PHONE_VERIFY_URL, Map.of("phone", phone,
                "code", ownersCode));

        assertThat(stolen.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(stolen).at("/data/code").asText()).isEqualTo("INVALID_CODE");
        assertThat(verifiedRow(attackerEmail).get("phone_verified_at")).isNull();
        assertThat(postAs(ownerToken, PHONE_VERIFY_URL, Map.of("phone", phone, "code", ownersCode))
                .getStatusCode()).as("the owner's code is still live").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("send to a number verified on another account answers like any send; the 409 comes only at verify")
    void should_sendThenReturn409AtVerify_when_phoneVerifiedOnAnotherAccount() {
        String phone = newPhone();
        seedUser("CLIENT", null, false, phone, null);
        String email = newEmail();
        String token = legacyClientToken(email);

        ResponseEntity<String> sent = postAs(token, PHONE_SEND_URL, Map.of("phone", phone));
        ResponseEntity<String> verified = postAs(token, PHONE_VERIFY_URL,
                Map.of("phone", phone, "code", lastCodeSentTo(phone)));

        assertThat(sent.getStatusCode()).as("no registration oracle at send").isEqualTo(HttpStatus.OK);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(verified).at("/data/code").asText()).isEqualTo("PHONE_ALREADY_REGISTERED");
        assertThat(verifiedRow(email).get("phone_verified_at")).isNull();
    }

    @Test
    @DisplayName("a number another account verifies in between is a clean 409 at verify, never a 500")
    void should_return409NotServerError_when_anotherAccountVerifiesThePhoneBeforeVerify() {
        String email = newEmail();
        String token = legacyClientToken(email);
        String phone = newPhone();
        postAs(token, PHONE_SEND_URL, Map.of("phone", phone));
        String code = lastCodeSentTo(phone);
        seedUser("CLIENT", null, false, phone, null);

        ResponseEntity<String> response = postAs(token, PHONE_VERIFY_URL, Map.of("phone", phone, "code", code));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("PHONE_ALREADY_REGISTERED");
        assertThat(verifiedRow(email).get("phone_verified_at")).isNull();
    }

    @Test
    @DisplayName("a landline or foreign number is 400 PHONE_NOT_UA_MOBILE and no SMS is sent")
    void should_return400WithoutSms_when_numberIsNotUaMobile() {
        String token = legacyClientToken(newEmail());

        ResponseEntity<String> landline = postAs(token, PHONE_SEND_URL, Map.of("phone", "+380441234567"));
        ResponseEntity<String> foreign = postAs(token, PHONE_SEND_URL, Map.of("phone", "+48601234567"));

        assertThat(landline.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(landline).at("/data/code").asText()).isEqualTo("PHONE_NOT_UA_MOBILE");
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(foreign).at("/data/code").asText()).isEqualTo("PHONE_NOT_UA_MOBILE");
        verify(otpSmsSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("a fourth code for one account within 15 minutes is 429")
    void should_return429_when_accountSendsAFourthCodeWithin15Minutes() {
        String token = legacyClientToken(newEmail());
        for (int i = 0; i < 3; i++) {
            assertThat(postAs(token, PHONE_SEND_URL, Map.of("phone", newPhone())).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> fourth = postAs(token, PHONE_SEND_URL, Map.of("phone", newPhone()));

        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("an anonymous caller gets 401 and no SMS")
    void should_return401_when_notSignedIn() {
        ResponseEntity<String> response = post(PHONE_SEND_URL, Map.of("phone", newPhone()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(otpSmsSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("profile save refuses a different phone but accepts the same phone echoed back")
    void should_reject400_when_patchMeSendsDifferentPhone_and_accept200_when_samePhoneEchoed() {
        String phone = newPhone();
        UUID id = seedUser("CLIENT", null, false, phone, null);
        String token = accessToken(phone);

        ResponseEntity<String> different = exchangeAs(token, HttpMethod.PATCH, ME_URL,
                Map.of("phoneNumber", newPhone()));
        ResponseEntity<String> same = exchangeAs(token, HttpMethod.PATCH, ME_URL,
                Map.of("phoneNumber", phone, "firstName", "Нове"));

        assertThat(different.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(different).at("/data/code").asText()).isEqualTo("PHONE_CHANGE_REQUIRES_OTP");
        assertThat(same.getStatusCode()).as("body: %s", same.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT phone_number FROM users WHERE id = ?", String.class, id))
                .isEqualTo(phone);
    }

    @Test
    @DisplayName("a client without a verified phone sees phoneRequiredBy = today (Kyiv)")
    void should_reportPhoneRequiredByToday_when_clientHasNoVerifiedPhone() {
        String token = legacyClientToken(newEmail());
        LocalDate before = kyivToday();

        ResponseEntity<String> me = exchangeAs(token, HttpMethod.GET, ME_URL, null);

        assertThat(json(me).at("/data/phoneVerified").asBoolean()).isFalse();
        assertThat(json(me).at("/data/emailVerified").asBoolean()).isTrue();
        assertThat(json(me).at("/data/phoneRequiredBy").asText())
                .isIn(before.toString(), kyivToday().toString());
    }

    @Test
    @DisplayName("a code submitted for a different number than requested is INVALID_CODE")
    void should_return400AndKeepPhoneUnset_when_codeSubmittedForAnotherNumber() {
        String email = newEmail();
        String token = legacyClientToken(email);
        String requested = newPhone();
        String other = newPhone();
        postAs(token, PHONE_SEND_URL, Map.of("phone", requested));

        ResponseEntity<String> response = postAs(token, PHONE_VERIFY_URL,
                Map.of("phone", other, "code", lastCodeSentTo(requested)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("INVALID_CODE");
        assertThat(verifiedRow(email)).containsEntry("phone_number", "0671112233")
                .containsEntry("phone_verified_at", null);
    }

    @Test
    @DisplayName("an unverified provider sees phoneRequiredBy = today (Kyiv): no provider deadline (AUTH-D12)")
    void should_reportPhoneRequiredByToday_when_providerUnverified() {
        String email = newEmail();
        seedUser("SALON_OWNER", email, true, null, null);
        String token = accessToken(email);
        LocalDate before = kyivToday();

        ResponseEntity<String> me = exchangeAs(token, HttpMethod.GET, ME_URL, null);

        assertThat(json(me).at("/data/phoneVerified").asBoolean()).isFalse();
        assertThat(json(me).at("/data/phoneRequiredBy").asText())
                .isIn(before.toString(), kyivToday().toString());
    }

    @Test
    @DisplayName("a phone change evicts the account's verified-phone gate entry after commit")
    void should_evictVerifiedPhoneEntry_when_phoneChanged() {
        String oldPhone = newPhone();
        UUID id = seedUser("SALON_OWNER", null, false, oldPhone, null);
        String token = accessToken(oldPhone);
        var verifiedPhones = cacheManager.getCache(UserProfileCacheEvictor.VERIFIED_PHONE_CACHE);
        verifiedPhones.put(id, Boolean.TRUE);
        String newPhone = newPhone();
        postAs(token, PHONE_SEND_URL, Map.of("phone", newPhone));

        ResponseEntity<String> changed = postAs(token, PHONE_VERIFY_URL, Map.of("phone", newPhone,
                "code", lastCodeSentTo(newPhone), "currentPassword", PASSWORD));

        assertThat(changed.getStatusCode()).as("body: %s", changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(verifiedPhones.get(id)).as("evicted after the swap committed").isNull();
    }
}
