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
}
