package com.beautica.auth.phoneotp;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B514 (D1) / phone-first hardening — a code issued for one {@link PhoneOtpPurpose} never redeems another purpose for
 * the same phone (every ordered pair of the five purposes), and stays redeemable for its own purpose afterwards. A
 * user-bound purpose is issued to and redeemed by a real account (FK).
 */
@DisplayName("Phone OTP — a code of purpose A never redeems purpose B (all purposes)")
class PhoneOtpPurposeIsolationIT extends AbstractPhoneIdentityIT {

    @Autowired
    private PhoneOtpService phoneOtpService;

    static Stream<Arguments> purposePairs() {
        return Arrays.stream(PhoneOtpPurpose.values()).flatMap(issued -> Arrays.stream(PhoneOtpPurpose.values())
                .filter(redeemed -> redeemed != issued)
                .map(redeemed -> Arguments.of(issued, redeemed)));
    }

    private UUID ownerFor(PhoneOtpPurpose purpose, UUID account) {
        return purpose.isUserBound() ? account : null;
    }

    private boolean consume(String phone, String code, PhoneOtpPurpose purpose, UUID account) {
        UUID owner = ownerFor(purpose, account);
        return owner == null
                ? phoneOtpService.consumeCode(phone, code, purpose)
                : phoneOtpService.consumeCode(phone, code, purpose, owner);
    }

    @ParameterizedTest(name = "{0} code -> {1}")
    @MethodSource("purposePairs")
    @DisplayName("should_refuseOtherPurpose_when_codeIssuedForOnePurpose")
    void should_refuseOtherPurpose_when_codeIssuedForOnePurpose(PhoneOtpPurpose issued, PhoneOtpPurpose redeemed) {
        String phone = newPhone();
        UUID account = seedUser("CLIENT", newEmail(), true, null, null);
        phoneOtpService.sendCode(phone, null, issued, ownerFor(issued, account));
        String code = lastCodeSentTo(phone);

        boolean crossRedeemed = consume(phone, code, redeemed, account);

        assertThat(crossRedeemed).as("%s code must not redeem %s", issued, redeemed).isFalse();
        assertThat(consume(phone, code, issued, account)).as("the code still works for %s", issued).isTrue();
    }
}
