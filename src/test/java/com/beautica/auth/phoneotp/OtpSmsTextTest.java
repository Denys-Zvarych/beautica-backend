package com.beautica.auth.phoneotp;

import com.beautica.config.OtpDomainRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 389 — the guest OTP SMS template: exact text, origin-bound last line, one UCS-2 segment. */
@DisplayName("OtpSmsText — guest OTP SMS template (Phase 389)")
class OtpSmsTextTest {

    private static final String CODE = "932279";

    @Test
    @DisplayName("with a domain — exact two-paragraph text, 62 chars")
    void should_renderTwoParagraphsWithDomainLine_when_domainPresent() {
        String text = OtpSmsText.forDomain(Optional.of("beautica.app")).render(CODE);

        assertThat(text).isEqualTo("Код Beautica: 932279. Нікому не кажіть.\n\n@beautica.app #932279");
        assertThat(text).hasSize(62);
    }

    @Test
    @DisplayName("without a domain — first line only, 39 chars")
    void should_renderFirstLineOnly_when_domainEmpty() {
        String text = OtpSmsText.forDomain(Optional.empty()).render(CODE);

        assertThat(text).isEqualTo("Код Beautica: 932279. Нікому не кажіть.");
        assertThat(text).hasSize(39);
    }

    @Test
    @DisplayName("with a domain — `@host #code` is the final line, no trailing newline")
    void should_endWithOriginBoundLine_when_domainPresent() {
        String text = OtpSmsText.forDomain(Optional.of("beautica.app")).render(CODE);

        String lastLine = text.substring(text.lastIndexOf('\n') + 1);
        assertThat(lastLine).matches("^@[a-z0-9.-]+ #\\d{6}$").isEqualTo("@beautica.app #" + CODE);
        assertThat(text).doesNotEndWith("\n");
    }

    @Test
    @DisplayName("18-char host — still one 70-char UCS-2 segment")
    void should_fitOneUcs2Segment_when_hostAtMaximumLength() {
        String longestHost = "a".repeat(OtpDomainRules.MAX_HOST_LENGTH - 4) + ".app";

        String text = OtpSmsText.forDomain(Optional.of(longestHost)).render("999999");

        assertThat(longestHost).hasSize(OtpDomainRules.MAX_HOST_LENGTH);
        assertThat(text.length()).isLessThanOrEqualTo(OtpSmsText.MAX_SINGLE_SEGMENT_CHARS);
    }

    @Test
    @DisplayName("18-char host with CRLF line breaks — still one 70-char segment")
    void should_fitOneUcs2Segment_when_gatewayRewritesNewlinesToCrlfAtMaximumHostLength() {
        String longestHost = "a".repeat(OtpDomainRules.MAX_HOST_LENGTH - 4) + ".app";

        String crlfText = OtpSmsText.forDomain(Optional.of(longestHost)).render("999999").replace("\n", "\r\n");

        assertThat(crlfText).contains("\r\n\r\n@");
        assertThat(crlfText.length()).isLessThanOrEqualTo(OtpSmsText.MAX_SINGLE_SEGMENT_CHARS);
    }

    @Test
    @DisplayName("stg.beautica.app (suggested staging host) is accepted")
    void should_acceptSuggestedStagingHost_when_built() {
        String text = OtpSmsText.forDomain(Optional.of("stg.beautica.app")).render(CODE);

        assertThat(text).endsWith("\n\n@stg.beautica.app #" + CODE);
    }

    @Test
    @DisplayName("19-char host is rejected (would risk a second segment)")
    void should_throw_when_hostLongerThanMaximum() {
        String tooLong = "a".repeat(OtpDomainRules.MAX_HOST_LENGTH - 3) + ".app";

        assertThatThrownBy(() -> OtpSmsText.forDomain(Optional.of(tooLong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most " + OtpDomainRules.MAX_HOST_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://beautica.app", "beautica.app:8080", "beautica.app/book", "Beautica.app",
            "beautica", ".beautica.app", "beautica.app.", "-x.app", "beautica .app", "beautica.app\n#1"})
    @DisplayName("scheme / port / path / upper case / malformed label / newline host is rejected")
    void should_throw_when_hostNotBare(String host) {
        assertThatThrownBy(() -> OtpSmsText.forDomain(Optional.of(host)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bare host");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "1234567", "12345a", ""})
    @DisplayName("a code that is not exactly 6 digits is rejected at render")
    void should_throw_when_codeNotSixDigits(String code) {
        OtpSmsText template = OtpSmsText.forDomain(Optional.empty());

        assertThatThrownBy(() -> template.render(code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OTP code must be exactly 6 digits");
    }
}
