package com.beautica.review.invite.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.auth.SecureTokenGenerator;
import com.beautica.config.OtpPepperConfig;
import com.beautica.review.invite.config.ReviewInviteProperties;
import com.beautica.review.invite.support.ReviewInviteTestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReviewInviteSmsText — phase 400 D5")
class ReviewInviteSmsTextTest {

    /** Two concatenated UCS-2 segments (phase 435). */
    private static final int TWO_UCS2_SEGMENTS = 134;
    /** The prod rendered length with the 22-char token (phase 435). */
    private static final int PROD_RENDERED_LENGTH = 122;
    /** A staging-like origin long enough that the full copy overruns two segments (the 2026-11-05 staging case). */
    private static final String STAGING_ORIGIN = "https://beautica-website-staging.example.workers.dev";

    private final ReviewInviteSmsText smsText = new ReviewInviteSmsText(ReviewInviteProperties.PROD_ORIGIN);
    private final SecureTokenGenerator tokens =
            new SecureTokenGenerator(new OtpPepperConfig("unit-test-otp-pepper-min-32-characters!"));

    @Test
    @DisplayName("phase 435: exact prod copy, «Beautica:» first, https link on its own line, 122 chars (two UCS-2 segments)")
    void should_renderExactProdText_when_realTokenIssued() {
        String token = tokens.generateToken(ReviewInviteSendService.TOKEN_BYTES);

        String text = smsText.render(token);

        assertThat(token).hasSize(22).matches("[A-Za-z0-9_-]{22}");
        assertThat(text).isEqualTo(ReviewInviteTestProperties.PROD_SMS_PREFIX + token);
        assertThat(text).hasSize(PROD_RENDERED_LENGTH);
        assertThat(text.length()).isLessThanOrEqualTo(TWO_UCS2_SEGMENTS);
        assertThat(text.substring(text.lastIndexOf('\n') + 1)).isEqualTo("https://beautica.app/r/" + token);
    }

    @Test
    @DisplayName("audit cycle 3: production copy + prod origin + path equals the literal phase-435 fixture")
    void should_matchLiteralFixture_when_productionConstantsAssembled() {
        String assembled = ReviewInviteProperties.SMS_COPY + ReviewInviteProperties.PROD_ORIGIN
                + ReviewInviteProperties.REVIEW_PATH;

        assertThat(assembled).isEqualTo(ReviewInviteTestProperties.PROD_SMS_PREFIX);
    }

    @Test
    @DisplayName("audit cycle 3: a whitespace-padded prod origin is stripped, as the fingerprint guard does")
    void should_renderExactProdText_when_originHasTrailingWhitespace() {
        String token = tokens.generateToken(ReviewInviteSendService.TOKEN_BYTES);
        ReviewInviteSmsText padded = new ReviewInviteSmsText(ReviewInviteProperties.PROD_ORIGIN + " ");

        String text = padded.render(token);

        assertThat(text).isEqualTo(ReviewInviteTestProperties.PROD_SMS_PREFIX + token);
    }

    @Test
    @DisplayName("audit cycle 3: a copy grown past two UCS-2 segments fails on the prod origin")
    void should_throw_when_longCopyWithProdOrigin() {
        String longCopy = "Д".repeat(TWO_UCS2_SEGMENTS);

        assertThatThrownBy(() -> ReviewInviteSmsText.requireWithinBudget(longCopy, ReviewInviteProperties.PROD_ORIGIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("two UCS-2 segments are " + TWO_UCS2_SEGMENTS)
                .hasMessageNotContaining(longCopy);
    }

    @Test
    @DisplayName("audit cycle 4: a copy rendering to exactly two UCS-2 segments on the prod origin is accepted")
    void should_accept_when_copyRendersToExactlyBudgetOnProdOrigin() {
        String copy = "Д".repeat(maxCopyLengthOnProdOrigin());

        assertThatCode(() -> ReviewInviteSmsText.requireWithinBudget(copy, ReviewInviteProperties.PROD_ORIGIN))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("audit cycle 4: a copy rendering one char past two UCS-2 segments on the prod origin fails")
    void should_throw_when_copyRendersOneCharOverBudgetOnProdOrigin() {
        String copy = "Д".repeat(maxCopyLengthOnProdOrigin() + 1);

        assertThatThrownBy(() -> ReviewInviteSmsText.requireWithinBudget(copy, ReviewInviteProperties.PROD_ORIGIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("renders to " + (ReviewInviteProperties.MAX_UCS2_CHARS + 1) + " chars");
    }

    @Test
    @DisplayName("2026-11-05: a non-prod origin keeps the full copy and links to that origin")
    void should_renderFullCopyWithOwnOrigin_when_nonProdOrigin() {
        String token = tokens.generateToken(ReviewInviteSendService.TOKEN_BYTES);
        ReviewInviteSmsText staging = new ReviewInviteSmsText(STAGING_ORIGIN);

        String text = staging.render(token);

        assertThat(text).isEqualTo(ReviewInviteProperties.SMS_COPY + STAGING_ORIGIN + "/r/" + token);
    }

    @Test
    @DisplayName("2026-11-05: a non-prod origin over the two-segment budget only warns (staging must still boot)")
    void should_notFailConstruction_when_nonProdOriginExceedsBudget() {
        int rendered = ReviewInviteProperties.SMS_COPY.length() + STAGING_ORIGIN.length()
                + ReviewInviteProperties.REVIEW_PATH.length() + ReviewInviteProperties.TOKEN_LENGTH;

        assertThat(rendered).isGreaterThan(TWO_UCS2_SEGMENTS);
        assertThatCode(() -> new ReviewInviteSmsText(STAGING_ORIGIN)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a token of the wrong length is refused (the segment budget assumes 22 chars)")
    void should_refuse_when_tokenLengthWrong() {
        String longToken = tokens.generateToken();

        assertThatThrownBy(() -> smsText.render(longToken)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(longToken);
    }

    @Test
    @DisplayName("2026-11-05: the over-budget WARN names rule + length only — no copy, origin, link or token")
    void should_warnWithoutTextOrToken_when_nonProdOriginExceedsBudget() {
        int rendered = ReviewInviteProperties.SMS_COPY.length() + STAGING_ORIGIN.length()
                + ReviewInviteProperties.REVIEW_PATH.length() + ReviewInviteProperties.TOKEN_LENGTH;
        String token = tokens.generateToken(ReviewInviteSendService.TOKEN_BYTES);

        List<ILoggingEvent> events = captureLogs(() -> new ReviewInviteSmsText(STAGING_ORIGIN).render(token));

        assertThat(events).hasSize(1);
        ILoggingEvent warn = events.get(0);
        String message = warn.getFormattedMessage();
        assertThat(warn.getLevel()).isEqualTo(Level.WARN);
        assertThat(message).as("WARN message, actual=%s", message)
                .contains("rule=two-segment-budget", "length=" + rendered)
                .doesNotContain(token, STAGING_ORIGIN, "workers.dev", ReviewInviteProperties.REVIEW_PATH,
                        ReviewInviteProperties.SMS_COPY.strip(), "Дякуємо");
        assertThat(warn.getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("prod origin within budget: construction and render log nothing")
    void should_logNothing_when_prodOriginWithinBudget() {
        String token = tokens.generateToken(ReviewInviteSendService.TOKEN_BYTES);

        List<ILoggingEvent> events =
                captureLogs(() -> new ReviewInviteSmsText(ReviewInviteProperties.PROD_ORIGIN).render(token));

        assertThat(events).isEmpty();
    }

    /** Longest copy that still renders within the two-segment budget on the prod origin. */
    private static int maxCopyLengthOnProdOrigin() {
        return ReviewInviteProperties.MAX_UCS2_CHARS - ReviewInviteProperties.PROD_ORIGIN.length()
                - ReviewInviteProperties.REVIEW_PATH.length() - ReviewInviteProperties.TOKEN_LENGTH;
    }

    private static List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ReviewInviteSmsText.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }
}
