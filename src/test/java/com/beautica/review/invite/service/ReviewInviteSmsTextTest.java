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

    /** Two concatenated UCS-2 segments (phase 435). */
    private static final int TWO_UCS2_SEGMENTS = 134;

    private final ReviewInviteSmsText smsText = new ReviewInviteSmsText(ReviewInviteTestProperties.enabled());
    private final SecureTokenGenerator tokens =
            new SecureTokenGenerator(new OtpPepperConfig("unit-test-otp-pepper-min-32-characters!"));

    @Test
    @DisplayName("phase 435: exact copy, «Beautica:» first, https link on its own line, 122 chars (two UCS-2 segments)")
    void should_renderExactSingleSegmentText_when_realTokenIssued() {
        String token = tokens.generateToken(ReviewInviteSendService.TOKEN_BYTES);

        String text = smsText.render(token);

        assertThat(token).hasSize(22).matches("[A-Za-z0-9_-]{22}");
        assertThat(text).isEqualTo("Beautica: Дякуємо, що обрали наш сервіс. Залиште свій відгук про Ваш візит!\n\nhttps://beautica.app/r/" + token);
        assertThat(text).hasSize(122);
        assertThat(text.length()).isLessThanOrEqualTo(TWO_UCS2_SEGMENTS);
        assertThat(text.substring(text.lastIndexOf('\n') + 1)).startsWith("https://");
    }

    @Test
    @DisplayName("a token of the wrong length is refused (the segment budget assumes 22 chars)")
    void should_refuse_when_tokenLengthWrong() {
        String longToken = tokens.generateToken();

        assertThatThrownBy(() -> smsText.render(longToken)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(longToken);
    }
}
