package com.beautica.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ThymeleafAutoConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = "spring.thymeleaf.cache=false")
@DisplayName("Email template rendering — Thymeleaf slice")
class EmailTemplateRenderingTest {

    @Autowired
    SpringTemplateEngine templateEngine;

    @Test
    @DisplayName("new-booking template renders masterName, clientName, serviceName and startsAt")
    void should_renderRequiredFields_when_newBookingTemplateProcessed() {
        var ctx = new Context();
        ctx.setVariable("masterName", "Оксана");
        ctx.setVariable("clientName", "Іван Петренко");
        ctx.setVariable("serviceName", "Стрижка");
        ctx.setVariable("startsAt", "10:00, 20 травня 2026");

        String html = templateEngine.process("email/new-booking", ctx);

        assertThat(html).contains("Оксана");
        assertThat(html).contains("Іван Петренко");
        assertThat(html).contains("Стрижка");
        assertThat(html).contains("10:00, 20 травня 2026");
    }

    @Test
    @DisplayName("booking-confirmed template renders clientName, serviceName and startsAt")
    void should_renderRequiredFields_when_bookingConfirmedTemplateProcessed() {
        var ctx = new Context();
        ctx.setVariable("clientName", "Марія Коваль");
        ctx.setVariable("serviceName", "Манікюр");
        ctx.setVariable("startsAt", "14:30, 22 травня 2026");

        String html = templateEngine.process("email/booking-confirmed", ctx);

        assertThat(html).contains("Марія Коваль");
        assertThat(html).contains("Манікюр");
        assertThat(html).contains("14:30, 22 травня 2026");
    }

    @Test
    @DisplayName("booking-declined template renders comment row when comment is present")
    void should_renderCommentRow_when_bookingDeclinedWithComment() {
        var ctx = new Context();
        ctx.setVariable("clientName", "Сергій");
        ctx.setVariable("serviceName", "Фарбування");
        ctx.setVariable("comment", "Майстер хворіє");

        String html = templateEngine.process("email/booking-declined", ctx);

        assertThat(html).contains("Майстер хворіє");
        assertThat(html).contains("Причина");
    }

    @Test
    @DisplayName("booking-declined template omits comment row when comment is null")
    void should_omitCommentRow_when_bookingDeclinedWithNullComment() {
        var ctx = new Context();
        ctx.setVariable("clientName", "Сергій");
        ctx.setVariable("serviceName", "Фарбування");
        ctx.setVariable("comment", null);

        String html = templateEngine.process("email/booking-declined", ctx);

        assertThat(html).doesNotContain("Причина");
    }

    @Test
    @DisplayName("booking-cancelled-provider template renders comment row when comment is present")
    void should_renderCommentRow_when_cancelledProviderWithComment() {
        var ctx = new Context();
        ctx.setVariable("masterName", "Оксана");
        ctx.setVariable("clientName", "Іван");
        ctx.setVariable("serviceName", "Стрижка");
        ctx.setVariable("startsAt", "10:00, 25 травня 2026");
        ctx.setVariable("comment", "Особисті обставини");

        String html = templateEngine.process("email/booking-cancelled-provider", ctx);

        assertThat(html).contains("Особисті обставини");
        assertThat(html).contains("Причина");
    }

    @Test
    @DisplayName("booking-cancelled-provider template omits comment row when comment is null")
    void should_omitCommentRow_when_cancelledProviderWithNullComment() {
        var ctx = new Context();
        ctx.setVariable("masterName", "Оксана");
        ctx.setVariable("clientName", "Іван");
        ctx.setVariable("serviceName", "Стрижка");
        ctx.setVariable("startsAt", "10:00, 25 травня 2026");
        ctx.setVariable("comment", null);

        String html = templateEngine.process("email/booking-cancelled-provider", ctx);

        assertThat(html).doesNotContain("Причина");
    }

    @Test
    @DisplayName("invite-master template renders inviteUrl in href attribute")
    void should_renderInviteUrlInHref_when_inviteMasterTemplateProcessed() {
        var inviteUrl = "https://beautica.app/invite/accept?token=abc-123";
        var ctx = new Context();
        ctx.setVariable("salonName", "Glamour Studio");
        ctx.setVariable("inviteUrl", inviteUrl);

        String html = templateEngine.process("email/invite-master", ctx);

        assertThat(html).contains("href=\"" + inviteUrl + "\"");
        assertThat(html).contains("Glamour Studio");
    }

    // ── reset-password-otp template ───────────────────────────────────────────

    @Test
    @DisplayName("reset-password-otp — th:text='\\${code}' renders the actual OTP code, not the static placeholder")
    void should_renderOtpCode_when_resetPasswordOtpTemplateProcessed() {
        var ctx = new Context();
        ctx.setVariable("code", "947512");

        String html = templateEngine.process("email/reset-password-otp", ctx);

        assertThat(html)
                .as("rendered OTP must contain the value passed as 'code'")
                .contains("947512");
        assertThat(html)
                .as("rendered OTP must NOT contain the static placeholder '000000'")
                .doesNotContain("000000");
    }

    @Test
    @DisplayName("reset-password-otp — rendered HTML contains no raw Thymeleaf syntax (no ${ or th:)")
    void should_notContainRawThymeleafSyntax_when_resetPasswordOtpTemplateProcessed() {
        var ctx = new Context();
        ctx.setVariable("code", "112233");

        String html = templateEngine.process("email/reset-password-otp", ctx);

        assertThat(html)
                .as("rendered template must not contain unresolved Thymeleaf expression '\\${'")
                .doesNotContain("${");
        assertThat(html)
                .as("rendered template must not contain unresolved Thymeleaf attribute like 'th:text' or 'th:href'")
                .doesNotContainPattern("th:[a-z]");
    }

    @Test
    @DisplayName("reset-password-otp — renders 15-minute validity note")
    void should_renderValidityNote_when_resetPasswordOtpTemplateProcessed() {
        var ctx = new Context();
        ctx.setVariable("code", "000111");

        String html = templateEngine.process("email/reset-password-otp", ctx);

        assertThat(html)
                .as("reset-password-otp template must mention the 15-minute OTP expiry")
                .contains("15 хвилин");
    }

    // ── verify-email template ─────────────────────────────────────────────────

    @Test
    @DisplayName("verify-email — th:text='\\${code}' renders the actual OTP code, not the static placeholder")
    void should_renderOtpCode_when_verifyEmailTemplateProcessed() {
        var ctx = new Context();
        ctx.setVariable("code", "836274");

        String html = templateEngine.process("email/verify-email", ctx);

        assertThat(html)
                .as("rendered OTP must contain the value passed as 'code'")
                .contains("836274");
        assertThat(html)
                .as("rendered OTP must NOT contain the static placeholder '000000'")
                .doesNotContain("000000");
    }

    @Test
    @DisplayName("verify-email — rendered HTML contains no raw Thymeleaf syntax (no ${ or th:)")
    void should_notContainRawThymeleafSyntax_when_verifyEmailTemplateProcessed() {
        var ctx = new Context();
        ctx.setVariable("code", "112233");

        String html = templateEngine.process("email/verify-email", ctx);

        assertThat(html)
                .as("rendered template must not contain unresolved Thymeleaf expression '\\${'")
                .doesNotContain("${");
        assertThat(html)
                .as("rendered template must not contain unresolved Thymeleaf attribute like 'th:text' or 'th:href'")
                .doesNotContainPattern("th:[a-z]");
    }

    @Test
    @DisplayName("verify-email — renders 15-minute validity note")
    void should_renderValidityNote_when_verifyEmailTemplateProcessed() {
        var ctx = new Context();
        ctx.setVariable("code", "000111");

        String html = templateEngine.process("email/verify-email", ctx);

        assertThat(html)
                .as("verify-email template must mention the 15-minute OTP expiry")
                .contains("15 хвилин");
    }

    // ── invite (owner invite) template ────────────────────────────────────────

    @Test
    @DisplayName("invite — @{${inviteLink}} renders https:// href and salonName and expiresHours")
    void should_renderInviteLinkAndSalonName_when_inviteTemplateProcessed() {
        var inviteLink = "https://beautica.app/invite/accept?token=xyz-owner-invite";
        var ctx = new Context();
        ctx.setVariable("inviteLink", inviteLink);
        ctx.setVariable("salonName", "Stella Beauty");
        ctx.setVariable("expiresHours", 48L);

        String html = templateEngine.process("email/invite", ctx);

        assertThat(html)
                .as("rendered href must equal the https:// invite link")
                .contains("href=\"" + inviteLink + "\"");
        assertThat(html)
                .as("rendered body must contain the salon name")
                .contains("Stella Beauty");
        assertThat(html)
                .as("rendered body must contain the expiry hours value")
                .contains("48");
    }

    @Test
    @DisplayName("invite — rendered HTML contains no raw Thymeleaf syntax (no ${ or th:)")
    void should_notContainRawThymeleafSyntax_when_inviteTemplateProcessed() {
        var ctx = new Context();
        ctx.setVariable("inviteLink", "https://beautica.app/invite/accept?token=tok");
        ctx.setVariable("salonName", "Test Salon");
        ctx.setVariable("expiresHours", 72L);

        String html = templateEngine.process("email/invite", ctx);

        assertThat(html)
                .as("rendered template must not contain unresolved Thymeleaf expression '\\${'")
                .doesNotContain("${");
        assertThat(html)
                .as("rendered template must not contain unresolved Thymeleaf attribute like 'th:text' or 'th:href'")
                .doesNotContainPattern("th:[a-z]");
    }
}
