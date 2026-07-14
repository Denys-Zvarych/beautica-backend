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

/**
 * Phase 25.6 — email is the ONE place in this app that renders free-text user input (booking
 * notes: {@code clientComment}, {@code providerComment}, {@code clientCancellationNote}) into a
 * document a browser or mail client will parse as HTML. A Flutter {@code Text} widget never
 * interprets markup, so this is the sole injection surface for a stored-XSS-style attack via a
 * booking note. Asserts hostile payloads survive Thymeleaf's {@code th:text} auto-escaping —
 * NEVER {@code th:utext} — and that multi-line notes render as multiple lines via
 * {@code white-space: pre-line} CSS, not literal {@code <br>} substitution (which would require
 * unescaped HTML and reopen the same hole).
 *
 * <p>Uses the same minimal {@code ThymeleafAutoConfiguration}-only slice as
 * {@link EmailTemplateRenderingTest} — a full {@code @SpringBootTest} would be wasted machinery
 * for a template-rendering assertion (§M).
 */
@SpringBootTest(
        classes = ThymeleafAutoConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = "spring.thymeleaf.cache=false")
@DisplayName("booking-declined email — XSS-hardening + multi-line rendering (Phase 25.6)")
class BookingDeclinedEmailRenderTest {

    @Autowired
    SpringTemplateEngine templateEngine;

    @Test
    @DisplayName("a <script> payload in the comment renders escaped, never as live markup")
    void should_escapeScriptTag_when_commentContainsScriptPayload() {
        String html = renderWithComment("<script>alert(1)</script>");

        assertThat(html)
                .as("the raw <script> tag must never appear unescaped in the rendered email")
                .doesNotContain("<script>alert(1)</script>");
        assertThat(html)
                .as("Thymeleaf th:text must HTML-escape the payload")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    @DisplayName("an attribute-breakout payload renders escaped, never as a live <img onerror> tag")
    void should_escapeAttributeBreakoutPayload_when_commentContainsImgOnerror() {
        String payload = "\"><img src=x onerror=alert(1)>";

        String html = renderWithComment(payload);

        assertThat(html)
                .as("the raw <img onerror=...> tag must never appear unescaped in the rendered email")
                .doesNotContain("<img src=x onerror=alert(1)>");
        assertThat(html)
                .as("the double-quote must be escaped so it cannot break out of a surrounding attribute")
                .contains("&quot;&gt;&lt;img");
    }

    @Test
    @DisplayName("a two-line comment renders with white-space:pre-line so both lines are visible")
    void should_renderTwoLines_when_commentContainsNewline() {
        String html = renderWithComment("Будь ласка, зателефонуйте.\nМи підберемо інший час.");

        assertThat(html)
                .as("both lines of the comment must appear verbatim in the escaped output")
                .contains("Будь ласка, зателефонуйте.")
                .contains("Ми підберемо інший час.");
        assertThat(html)
                .as("the comment cell must use white-space:pre-line so \\n renders as a visible "
                        + "line break instead of being collapsed by normal HTML whitespace rules — "
                        + "never th:utext + <br> substitution")
                .contains("white-space:pre-line");
    }

    @Test
    @DisplayName("th:utext is never used anywhere in this template (belt-and-braces on top of the CI grep gate)")
    void should_notUseUtext_inBookingDeclinedTemplate() {
        String html = renderWithComment("plain comment");

        // The rendered HTML cannot directly prove the source had no th:utext (Thymeleaf strips
        // its own attributes during processing either way), so this also greps the raw template
        // resource — the authoritative check the CI gate (scripts/forbid_th_utext_in_email.sh)
        // performs on every template. Kept here too so a `./gradlew test` run alone (no CI) still
        // catches a regression.
        assertThat(html).isNotNull();
        String templateSource = readTemplateSource();
        assertThat(templateSource).doesNotContain("th:utext");
    }

    private String renderWithComment(String comment) {
        var ctx = new Context();
        ctx.setVariable("clientName", "Тест Клієнт");
        ctx.setVariable("serviceName", "Стрижка");
        ctx.setVariable("comment", comment);
        return templateEngine.process("email/booking-declined", ctx);
    }

    private String readTemplateSource() {
        try (var in = getClass().getClassLoader()
                .getResourceAsStream("templates/email/booking-declined.html")) {
            if (in == null) {
                throw new IllegalStateException("booking-declined.html not found on classpath");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read booking-declined.html", e);
        }
    }
}
