package com.beautica.notification;

import com.beautica.notification.email.ServiceLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NTF-52 / phase 498 acceptance: ALL 14 service-bearing e-mail templates render the line
 * «HH:mm name · duration · price» taken from the shared {@link ServiceLineExpectedCopy}. Table-driven so a
 * template added to the list but wired to the old {@code serviceNames} rows goes red.
 */
@SpringBootTest(classes = ThymeleafAutoConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "spring.thymeleaf.cache=false")
@DisplayName("NTF-52 — every service-bearing e-mail template renders the service line")
class EmailServiceLinesAllTemplatesTest {

    private static final String FIRST = "Манікюр";
    private static final String SECOND = "Педикюр";

    /** Templates fed a {@code serviceLines} list (one row per service). */
    private static Stream<String> listTemplates() {
        return Stream.of("booking-confirmed", "new-booking", "booking-declined", "booking-declined-by-salon-provider",
                "booking-cancelled-provider", "master-removed", "salon-closed");
    }

    /** Templates fed ONE {@code serviceLine}. */
    private static Stream<String> singleTemplates() {
        return Stream.of("booking-reminder", "booking-rescheduled-client", "booking-rescheduled-provider",
                "booking-rescheduled-by-salon-provider", "booking-cancelled-client", "closure-reminder",
                "review-request");
    }

    @Autowired
    SpringTemplateEngine templateEngine;

    @ParameterizedTest(name = "{0}")
    @MethodSource("listTemplates")
    @DisplayName("serviceLines template renders every line as «HH:mm name · duration · price», in order")
    void should_renderEachServiceLine_when_listTemplateProcessed(String template) {
        var lines = ServiceLineExpectedCopy.lines(FIRST, SECOND);

        String html = templateEngine.process("email/" + template, context(null, lines));

        assertThat(html)
                .as("%s must render both lines from ServiceLineExpectedCopy", template)
                .contains(ServiceLineExpectedCopy.text(FIRST))
                .contains(ServiceLineExpectedCopy.text(SECOND));
        assertThat(html.indexOf(ServiceLineExpectedCopy.text(FIRST)))
                .as("%s keeps visit order", template)
                .isLessThan(html.indexOf(ServiceLineExpectedCopy.text(SECOND)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleTemplates")
    @DisplayName("single-serviceLine template renders «HH:mm name · duration · price»")
    void should_renderTheServiceLine_when_singleLineTemplateProcessed(String template) {
        String html = templateEngine.process("email/" + template,
                context(ServiceLineExpectedCopy.line(FIRST), ServiceLineExpectedCopy.lines(FIRST)));

        assertThat(html)
                .as("%s must render the line from ServiceLineExpectedCopy", template)
                .contains(ServiceLineExpectedCopy.text(FIRST));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listTemplates")
    @DisplayName("a range-price line renders «500–700 ₴» in every serviceLines template")
    void should_renderRangePrice_when_lineCarriesRange(String template) {
        var line = new ServiceLine(ServiceLineExpectedCopy.TIME, FIRST, ServiceLineExpectedCopy.DURATION,
                ServiceLineExpectedCopy.RANGE_PRICE);

        String html = templateEngine.process("email/" + template, context(null, List.of(line)));

        assertThat(html)
                .as("%s", template)
                .contains(ServiceLineExpectedCopy.text(ServiceLineExpectedCopy.TIME, FIRST,
                        ServiceLineExpectedCopy.DURATION, ServiceLineExpectedCopy.RANGE_PRICE));
    }

    private Context context(ServiceLine single, List<ServiceLine> lines) {
        var ctx = new Context();
        ctx.setVariable("masterName", "Оксана");
        ctx.setVariable("clientName", "Іван Петренко");
        ctx.setVariable("serviceLine", single);
        ctx.setVariable("serviceLines", lines);
        ctx.setVariable("serviceLabel", lines.size() > 1 ? "Послуги" : "Послуга");
        ctx.setVariable("startsAt", "10:00, 20 травня 2026");
        ctx.setVariable("visitHeader", lines.size() > 1 ? ServiceLineExpectedCopy.VISIT_HEADER : null);
        ctx.setVariable("salonName", "Aurora");
        ctx.setVariable("reviewUrl", "https://beautica.app/review/t");
        ctx.setVariable("bookingUrl", "https://beautica.app/bookings/t");
        ctx.setVariable("selfAction", false);
        ctx.setVariable("dayBefore", false);
        return ctx;
    }
}
