package com.beautica.review.invite.service;

import com.beautica.auth.SecureTokenGenerator;
import com.beautica.config.OtpPepperConfig;
import com.beautica.review.invite.support.ReviewInviteTestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReviewInviteSmsText — phase 400 D5")
class ReviewInviteSmsTextTest {

    private static final int ONE_UCS2_SEGMENT = 70;

    private final ReviewInviteSmsText smsText = new ReviewInviteSmsText(ReviewInviteTestProperties.enabled());
    private final SecureTokenGenerator tokens =
            new SecureTokenGenerator(new OtpPepperConfig("unit-test-otp-pepper-min-32-characters!"));

    @Test
    @DisplayName("exact copy, 56 chars, one UCS-2 segment, with a real 16-byte token")
    void should_renderExactSingleSegmentText_when_realTokenIssued() {
        String token = tokens.generateToken(ReviewInviteSendService.TOKEN_BYTES);

        String text = smsText.render(token);

        assertThat(token).hasSize(22).matches("[A-Za-z0-9_-]{22}");
        assertThat(text).isEqualTo("Оцініть ваш візит: beautica.app/r/" + token);
        assertThat(text).hasSize(56);
        assertThat(text.length()).isLessThanOrEqualTo(ONE_UCS2_SEGMENT);
    }

    @Test
    @DisplayName("a token of the wrong length is refused (the segment budget assumes 22 chars)")
    void should_refuse_when_tokenLengthWrong() {
        String longToken = tokens.generateToken();

        assertThatThrownBy(() -> smsText.render(longToken)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(longToken);
    }
}
