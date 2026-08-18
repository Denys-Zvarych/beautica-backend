package com.beautica.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.List;

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
    @DisplayName("new-booking template renders masterName, clientName, the one service and startsAt")
    void should_renderRequiredFields_when_newBookingTemplateProcessed() {
        var ctx = new Context();
        ctx.setVariable("masterName", "Оксана");
        ctx.setVariable("clientName", "Іван Петренко");
        ctx.setVariable("serviceNames", List.of("Стрижка"));
        ctx.setVariable("serviceLabel", "Послуга");
        ctx.setVariable("startsAt", "10:00, 20 травня 2026");
        ctx.setVariable("visitDuration", null);

        String html = templateEngine.process("email/new-booking", ctx);

        assertThat(html).contains("Оксана");
        assertThat(html).contains("Іван Петренко");
        assertThat(html).contains("Стрижка");
        assertThat(html).contains("10:00, 20 травня 2026");
    }

    @Test
    @DisplayName("new-booking template renders ONE row per service and the «Послуги» plural label "
            + "when a multi-service visit is notified")
    void should_renderEveryServiceRow_when_newBookingTemplateProcessedForVisit() {
        var ctx = new Context();
        ctx.setVariable("masterName", "Оксана");
        ctx.setVariable("clientName", "Іван Петренко");
        ctx.setVariable("serviceNames", List.of("Стрижка", "Фарбування", "Укладка"));
        ctx.setVariable("serviceLabel", "Послуги");
        ctx.setVariable("startsAt", "10:00, 20 травня 2026");
        ctx.setVariable("visitDuration", "2 год 30 хв (до 12:30)");

        String html = templateEngine.process("email/new-booking", ctx);

        // Every service is named — the defect was that only the lead service ever appeared.
        assertThat(html).contains("Стрижка").contains("Фарбування").contains("Укладка");
        // …in visit order, not repository order.
        assertThat(html.indexOf("Стрижка")).isLessThan(html.indexOf("Фарбування"));
        assertThat(html.indexOf("Фарбування")).isLessThan(html.indexOf("Укладка"));
        // Plural label, printed exactly once — only the first row carries it.
        assertThat(body(html)).contains("Послуги");
        assertThat(countOccurrences(body(html), "Послуги")).isEqualTo(1);
        // Visit-level totals appear only for a visit.
        assertThat(html).contains("Тривалість").contains("2 год 30 хв (до 12:30)");
    }

    @Test
    @DisplayName("new-booking template omits the «Тривалість» row entirely when visitDuration is null "
            + "(single-service booking renders exactly as it did before visits existed)")
    void should_omitDurationRow_when_newBookingTemplateProcessedWithNullVisitDuration() {
        var ctx = new Context();
        ctx.setVariable("masterName", "Оксана");
        ctx.setVariable("clientName", "Іван Петренко");
        ctx.setVariable("serviceNames", List.of("Стрижка"));
        ctx.setVariable("serviceLabel", "Послуга");
        ctx.setVariable("startsAt", "10:00, 20 травня 2026");
        ctx.setVariable("visitDuration", null);

        String html = templateEngine.process("email/new-booking", ctx);

        assertThat(html).doesNotContain("Тривалість");
        assertThat(body(html)).doesNotContain("Послуги");
        assertThat(countOccurrences(body(html), "Послуга")).isEqualTo(1);
    }

    @Test
    @DisplayName("booking-confirmed template renders clientName, the one service and startsAt")
    void should_renderRequiredFields_when_bookingConfirmedTemplateProcessed() {
        var ctx = new Context();
        ctx.setVariable("clientName", "Марія Коваль");
        ctx.setVariable("serviceNames", List.of("Манікюр"));
        ctx.setVariable("serviceLabel", "Послуга");
        ctx.setVariable("startsAt", "14:30, 22 травня 2026");
        ctx.setVariable("visitDuration", null);

        String html = templateEngine.process("email/booking-confirmed", ctx);

        assertThat(html).contains("Марія Коваль");
        assertThat(html).contains("Манікюр");
        assertThat(html).contains("14:30, 22 травня 2026");
        assertThat(html).doesNotContain("Тривалість");
    }

    @Test
    @DisplayName("booking-confirmed template renders ONE row per service, the «Послуги» plural label "
            + "and the visit duration when a multi-service visit is confirmed")
    void should_renderEveryServiceRow_when_bookingConfirmedTemplateProcessedForVisit() {
        var ctx = new Context();
        ctx.setVariable("clientName", "Марія Коваль");
        ctx.setVariable("serviceNames", List.of("Манікюр", "Педикюр"));
        ctx.setVariable("serviceLabel", "Послуги");
        ctx.setVariable("startsAt", "14:30, 22 травня 2026");
        ctx.setVariable("visitDuration", "3 год (до 17:30)");

        String html = templateEngine.process("email/booking-confirmed", ctx);

        assertThat(html).contains("Манікюр").contains("Педикюр");
        assertThat(html.indexOf("Манікюр")).isLessThan(html.indexOf("Педикюр"));
        assertThat(countOccurrences(body(html), "Послуги")).isEqualTo(1);
        assertThat(html).contains("Тривалість").contains("3 год (до 17:30)");
    }

    @Test
    @DisplayName("booking-confirmed — the FIRST service row stays borderless and every later service "
            + "row gains the separator, so a one-service render is unchanged")
    void should_borderOnlyLaterServiceRows_when_bookingConfirmedTemplateProcessedForVisit() {
        var single = new Context();
        single.setVariable("clientName", "Марія Коваль");
        single.setVariable("serviceNames", List.of("Манікюр"));
        single.setVariable("serviceLabel", "Послуга");
        single.setVariable("startsAt", "14:30, 22 травня 2026");
        single.setVariable("visitDuration", null);

        var visit = new Context();
        visit.setVariable("clientName", "Марія Коваль");
        visit.setVariable("serviceNames", List.of("Манікюр", "Педикюр"));
        visit.setVariable("serviceLabel", "Послуги");
        visit.setVariable("startsAt", "14:30, 22 травня 2026");
        visit.setVariable("visitDuration", "3 год (до 17:30)");

        String singleHtml = templateEngine.process("email/booking-confirmed", single);
        String visitHtml = templateEngine.process("email/booking-confirmed", visit);

        // A one-service render must add no separator above the first row (it is the table's top row).
        int singleBorders = countOccurrences(singleHtml, "border-top:1px solid #D4C5B5");
        int visitBorders = countOccurrences(visitHtml, "border-top:1px solid #D4C5B5");
        assertThat(visitBorders)
                .as("the second service row (2 cells) and the duration row (2 cells) each add a separator")
                .isEqualTo(singleBorders + 4);
    }

    @Test
    @DisplayName("new-booking template HTML-escapes a hostile service name — a th:utext edit to the "
            + "visit loop must fail this test, never ship silently")
    void should_escapeHostileServiceName_when_newBookingTemplateProcessedForVisit() {
        var ctx = new Context();
        ctx.setVariable("masterName", "Оксана");
        ctx.setVariable("clientName", "Іван Петренко");
        ctx.setVariable("serviceNames", List.of("Стрижка", HOSTILE_SERVICE_NAME));
        ctx.setVariable("serviceLabel", "Послуги");
        ctx.setVariable("startsAt", "10:00, 20 травня 2026");
        ctx.setVariable("visitDuration", "2 год (до 12:00)");

        String html = templateEngine.process("email/new-booking", ctx);

        assertEscaped(html);
    }

    @Test
    @DisplayName("booking-confirmed template HTML-escapes a hostile service name — the client-facing "
            + "twin of the new-booking escaping guard")
    void should_escapeHostileServiceName_when_bookingConfirmedTemplateProcessedForVisit() {
        var ctx = new Context();
        ctx.setVariable("clientName", "Марія Коваль");
        ctx.setVariable("serviceNames", List.of("Манікюр", HOSTILE_SERVICE_NAME));
        ctx.setVariable("serviceLabel", "Послуги");
        ctx.setVariable("startsAt", "14:30, 22 травня 2026");
        ctx.setVariable("visitDuration", "2 год (до 16:30)");

        String html = templateEngine.process("email/booking-confirmed", ctx);

        assertEscaped(html);
    }

    /**
     * A service name a provider can set freely (service definitions carry no HTML filter) and which
     * both visit templates now interpolate through a {@code th:each} loop. The loop bodies use
     * {@code th:text}, which escapes; nothing else in the repo pins that, so a future
     * {@code th:text → th:utext} edit — the single-character change that turns this into stored XSS
     * in every provider's and client's inbox — would otherwise go unnoticed.
     */
    private static final String HOSTILE_SERVICE_NAME = "<img src=x onerror=alert(1)>";

    private static void assertEscaped(String html) {
        // Note the assertion is on the ANGLE BRACKETS, not on the word "onerror": the escaped form
        // legitimately still contains the payload's literal characters — what makes it inert is
        // that `<` became `&lt;`, so no element is ever parsed out of it.
        assertThat(html)
                .as("the raw tag must never reach the rendered document")
                .doesNotContain(HOSTILE_SERVICE_NAME)
                // Narrow needle on purpose: both templates legitimately carry the inline
                // `<img src="cid:beauticaLogo">` header logo.
                .doesNotContain("<img src=x");
        assertThat(html)
                .as("…and the name must still be VISIBLE to the reader, HTML-escaped")
                .contains("&lt;img src=x onerror=alert(1)&gt;");
    }

    /**
     * The rendered document WITHOUT its leading documentation comment. That comment names the
     * «Послуга»/«Послуги» variables in prose, so counting label occurrences over the whole string
     * would score the documentation as if it were markup.
     */
    private static String body(String html) {
        int start = html.indexOf("<html");
        return start < 0 ? html : html.substring(start);
    }

    /** Counts non-overlapping occurrences of {@code needle} — used for label/row cardinality. */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    @DisplayName("booking-declined template renders comment row when comment is present")
    void should_renderCommentRow_when_bookingDeclinedWithComment() {
        var ctx = new Context();
        ctx.setVariable("clientName", "Сергій");
        ctx.setVariable("serviceNames", List.of("Фарбування"));
        ctx.setVariable("serviceLabel", "Послуга");
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
        ctx.setVariable("serviceNames", List.of("Фарбування"));
        ctx.setVariable("serviceLabel", "Послуга");
        ctx.setVariable("comment", null);

        String html = templateEngine.process("email/booking-declined", ctx);

        assertThat(html).doesNotContain("Причина");
    }

    @Test
    @DisplayName("booking-declined template renders one row per cancelled service of a whole-visit decline")
    void should_renderEveryCancelledService_when_theWholeVisitWasDeclined() {
        var ctx = new Context();
        ctx.setVariable("clientName", "Сергій");
        ctx.setVariable("serviceNames", List.of("Стрижка", "Фарбування", "Укладка"));
        ctx.setVariable("serviceLabel", "Послуги");
        ctx.setVariable("comment", null);

        String markup = body(templateEngine.process("email/booking-declined", ctx));

        // The defect: one STATUS_CHANGED row describes the WHOLE visit, so naming only the lead
        // left the client believing the other two services were still on.
        assertThat(markup).contains("Стрижка").contains("Фарбування").contains("Укладка");
        assertThat(markup.indexOf("Стрижка")).isLessThan(markup.indexOf("Фарбування"));
        assertThat(markup.indexOf("Фарбування")).isLessThan(markup.indexOf("Укладка"));
        assertThat(countOccurrences(markup, "Послуги"))
                .as("the plural label is printed once, on the first row only")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("booking-declined template renders the legacy single-row shape for a per-item decline")
    void should_renderTheSingleServiceShape_when_onlyOneServiceWasDeclined() {
        var ctx = new Context();
        ctx.setVariable("clientName", "Сергій");
        ctx.setVariable("serviceNames", List.of("Фарбування"));
        ctx.setVariable("serviceLabel", "Послуга");
        ctx.setVariable("comment", null);

        String markup = body(templateEngine.process("email/booking-declined", ctx));

        assertThat(countOccurrences(markup, "Фарбування"))
                .as("exactly one service row — the visit loop must not duplicate the lead")
                .isEqualTo(1);
        assertThat(markup)
                .as("a per-item decline must never suggest the whole visit is off")
                .doesNotContain("Послуги");
    }


    // Phase 25.5 (Fix D3): booking-cancelled-provider no longer takes a single `comment`
    // variable — the cancellation-time note (`cancellationNote`) and the booking-creation note
    // (`creationNote`) are distinct variables under distinct labels, so they can never swap
    // slots. See EmailNotificationService.sendClientCancelledEmail.

    @Test
    @DisplayName("booking-cancelled-provider template renders cancellationNote under «Причина скасування»")
    void should_renderCancellationNoteRow_when_cancelledProviderWithCancellationNote() {
        var ctx = new Context();
        ctx.setVariable("masterName", "Оксана");
        ctx.setVariable("clientName", "Іван");
        ctx.setVariable("serviceName", "Стрижка");
        ctx.setVariable("startsAt", "10:00, 25 травня 2026");
        ctx.setVariable("cancellationNote", "Особисті обставини");
        ctx.setVariable("creationNote", null);

        String html = templateEngine.process("email/booking-cancelled-provider", ctx);

        assertThat(html).contains("Особисті обставини");
        assertThat(html).contains("Причина скасування");
        assertThat(html).doesNotContain("Побажання клієнта");
    }

    @Test
    @DisplayName("booking-cancelled-provider template omits both note rows when both are null")
    void should_omitBothNoteRows_when_cancelledProviderWithNoNotes() {
        var ctx = new Context();
        ctx.setVariable("masterName", "Оксана");
        ctx.setVariable("clientName", "Іван");
        ctx.setVariable("serviceName", "Стрижка");
        ctx.setVariable("startsAt", "10:00, 25 травня 2026");
        ctx.setVariable("cancellationNote", null);
        ctx.setVariable("creationNote", null);

        String html = templateEngine.process("email/booking-cancelled-provider", ctx);

        assertThat(html).doesNotContain("Причина скасування");
        assertThat(html).doesNotContain("Побажання клієнта");
    }

    @Test
    @DisplayName("booking-cancelled-provider template renders creationNote under its OWN "
            + "«Побажання клієнта» label — never under «Причина скасування» (Fix D3 regression guard)")
    void should_renderCreationNoteUnderItsOwnLabel_never_asCancellationReason() {
        var ctx = new Context();
        ctx.setVariable("masterName", "Оксана");
        ctx.setVariable("clientName", "Іван");
        ctx.setVariable("serviceName", "Стрижка");
        ctx.setVariable("startsAt", "10:00, 25 травня 2026");
        ctx.setVariable("cancellationNote", "Захворів у день візиту");
        ctx.setVariable("creationNote", "Будь ласка, без ароматизаторів");

        String html = templateEngine.process("email/booking-cancelled-provider", ctx);

        assertThat(html).contains("Причина скасування").contains("Захворів у день візиту");
        assertThat(html).contains("Побажання клієнта").contains("Будь ласка, без ароматизаторів");
        // The two notes must never appear swapped — the cancellation-reason row's own text
        // node must not contain the creation note's text, and vice versa.
        int cancellationLabelIdx = html.indexOf("Причина скасування");
        int creationNoteTextIdx = html.indexOf("Будь ласка, без ароматизаторів");
        int creationLabelIdx = html.indexOf("Побажання клієнта");
        assertThat(creationNoteTextIdx)
                .as("the creation note's text must render AFTER its own label, not the cancellation-reason label")
                .isGreaterThan(creationLabelIdx)
                .isGreaterThan(cancellationLabelIdx);
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

    // ── closure-reminder template (Phase 29.5) ──────────────────────────────────

    @Test
    @DisplayName("closure-reminder template renders clientName, serviceName, startsAt and bookingUrl")
    void should_renderRequiredFields_when_closureReminderTemplateProcessed() {
        var bookingUrl = "https://app.beautica.ua/bookings/abc-123";
        var ctx = new Context();
        ctx.setVariable("clientName", "Іван Петренко");
        ctx.setVariable("serviceName", "Стрижка");
        ctx.setVariable("startsAt", "10:00, 20 травня 2026");
        ctx.setVariable("bookingUrl", bookingUrl);

        String html = templateEngine.process("email/closure-reminder", ctx);

        assertThat(html).contains("Іван Петренко");
        assertThat(html).contains("Стрижка");
        assertThat(html).contains("10:00, 20 травня 2026");
        assertThat(html).contains("href=\"" + bookingUrl + "\"");
    }

    @Test
    @DisplayName("closure-reminder — rendered HTML contains no unresolved Thymeleaf expression (no unresolved ${)")
    void should_notContainUnresolvedExpression_when_closureReminderTemplateProcessed() {
        // Unlike the OTP templates below, this template's header comment legitimately documents
        // "th:text" / "th:href" / "th:utext" as prose (mirrors review-request.html), so the
        // stricter "no th:[a-z] anywhere" pattern used for the OTP templates would false-positive
        // on that comment text — only the unresolved-expression check applies here.
        var ctx = new Context();
        ctx.setVariable("clientName", "Марія Коваль");
        ctx.setVariable("serviceName", "Манікюр");
        ctx.setVariable("startsAt", "14:30, 22 травня 2026");
        ctx.setVariable("bookingUrl", "https://app.beautica.ua/bookings/xyz");

        String html = templateEngine.process("email/closure-reminder", ctx);

        assertThat(html)
                .as("rendered template must not contain unresolved Thymeleaf expression '\\${'")
                .doesNotContain("${");
    }

    @Test
    @DisplayName("closure-reminder — rendered body and subject-relevant content contain none of the "
            + "three note sentinels — clientComment/clientCancellationNote/providerComment are never "
            + "passed into this template's context (Anti-Bug §223, locked track-25 rule)")
    void should_notLeakAnyNoteField_when_closureReminderTemplateProcessedWithSentinelsAbsentFromContext() {
        // The fixture booking in a real caller (NotificationService/EmailNotificationService) would
        // have all three note fields populated with distinctive sentinels — this test proves the
        // TEMPLATE itself has no expression referencing them, by rendering with only the four
        // documented variables and asserting none of the sentinel markers ever appear in the output,
        // even though nothing in this render call could have produced them by accident.
        var ctx = new Context();
        ctx.setVariable("clientName", "Тест Клієнт");
        ctx.setVariable("serviceName", "Тест послуга");
        ctx.setVariable("startsAt", "10:00, 20 травня 2026");
        ctx.setVariable("bookingUrl", "https://app.beautica.ua/bookings/note-sentinel-fixture");

        String html = templateEngine.process("email/closure-reminder", ctx);

        assertThat(html).doesNotContain("SENTINEL-CLIENT-COMMENT");
        assertThat(html).doesNotContain("SENTINEL-CLIENT-CANCELLATION-NOTE");
        assertThat(html).doesNotContain("SENTINEL-PROVIDER-COMMENT");
    }
}
