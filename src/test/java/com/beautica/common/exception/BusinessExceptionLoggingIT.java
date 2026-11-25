package com.beautica.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 500 acceptance through the real stack (Testcontainers Postgres, full Spring context, real HTTP via
 * {@link TestRestTemplate}): a business 4xx leaves exactly one line naming the status and the handler's path
 * TEMPLATE, and nothing from the raw URI — no token, phone or email — reaches the log.
 *
 * <p>Order-independent by construction: the handler logger is pinned to INFO (the prod level; a context booted
 * earlier re-applies {@code com.beautica: INFO}, and a unit test may have left it at DEBUG) and restored after each
 * case; the handler's per-template log budget is swapped for a fresh one so 4xx traffic from earlier ITs in the
 * shared cached context cannot have used up the 20-lines-a-minute window for these templates.
 */
@Import(TestSecurityConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Phase 500 — business 4xx log line through the real stack (IT)")
class BusinessExceptionLoggingIT extends AbstractIntegrationTest {

    private static final String SUGGEST_PATH = "/api/v1/service-types/suggest";
    private static final String CANCEL_TEMPLATE = "/api/v1/book/cancel/{token}";
    // HAIRDRESSING is seeded APPROVED + active (V74/V78).
    private static final String SEEDED_CATEGORY = "HAIRDRESSING";
    private static final String SUGGESTED_TYPE = "Qzlogging Ritual Phasefive";
    private static final String PENDING_MESSAGE = "A matching suggestion is already pending review";
    private static final String PHONE = "+380671234567";
    private static final String PHONE_DIGITS = "671234567";
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern UA_PHONE_SHAPE = Pattern.compile("\\+?380\\d{9}");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GlobalExceptionHandler globalExceptionHandler;

    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private Level previousHandlerLevel;
    private Object previousThrottle;

    @BeforeEach
    void pinLoggerAndResetLogBudget() {
        previousHandlerLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.INFO);
        previousThrottle = ReflectionTestUtils.getField(globalExceptionHandler, "businessLogThrottle");
        ReflectionTestUtils.setField(globalExceptionHandler, "businessLogThrottle",
                new BusinessLogThrottle(Clock.systemUTC()));
    }

    @AfterEach
    void restoreLoggerAndSuggestionRows() {
        handlerLogger.setLevel(previousHandlerLevel);
        ReflectionTestUtils.setField(globalExceptionHandler, "businessLogThrottle", previousThrottle);
        // service_type_suggestion is not in AbstractIntegrationTest#cleanDb; no FK points at it.
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE suggested_name = ?", SUGGESTED_TYPE);
    }

    @Test
    @DisplayName("duplicate suggestion POST — 409 writes exactly one WARN «Business 409» line with the path "
            + "template, and no token, phone or email")
    void should_logOneWarnLineWithTemplate_when_duplicateSuggestionConflicts(CapturedOutput output) throws Exception {
        // Arrange
        String emailLocalPart = "b500-log-" + System.nanoTime();
        String email = emailLocalPart + "@beautica.test";
        BookingTestFixtures booking = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        booking.createIndependentMaster(email);
        String jwt = booking.tokenFor(email);
        HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(
                new SuggestServiceTypeRequest(SUGGESTED_TYPE, SEEDED_CATEGORY, "phase 500 IT")), jsonBearer(jwt));
        // PII in the query string: only the raw URI carries it, so a template-only line cannot.
        String piiQuery = "?contact={contact}&phone={phone}";
        Map<String, String> piiParams = Map.of("contact", email, "phone", PHONE);
        ResponseEntity<String> first = restTemplate.exchange(
                SUGGEST_PATH + piiQuery, HttpMethod.POST, request, String.class, piiParams);
        assertThat(first.getStatusCode()).as("first submit body=%s", first.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        int actStart = output.getAll().length();

        // Act — the same suggestion again while the first is PENDING
        ResponseEntity<String> duplicate = restTemplate.exchange(
                SUGGEST_PATH + piiQuery, HttpMethod.POST, request, String.class, piiParams);

        // Assert
        assertThat(duplicate.getStatusCode()).as("duplicate body=%s", duplicate.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        String actOutput = output.getAll().substring(actStart);
        List<String> businessLines = linesContaining(actOutput, "Business 4");
        assertThat(businessLines).as("business lines in the act window").hasSize(1);
        assertThat(businessLines.get(0))
                .contains(" WARN ")
                .contains("Business 409 CONFLICT on POST " + SUGGEST_PATH + ": " + PENDING_MESSAGE)
                .doesNotContain("?");
        assertNoSecrets(businessLines.get(0), actOutput, emailLocalPart, jwt);
    }

    @Test
    @DisplayName("{token} path 4xx — the line names the path template, never the raw token, phone or email")
    void should_logTemplateNotToken_when_tokenPathRejectsRequest(CapturedOutput output) throws Exception {
        // Arrange — a well-formed 16-char cancel token; the empty bookingIds list is rejected (400) before lookup
        String rawToken = randomBase62(16);
        String emailLocalPart = "b500-guest-" + System.nanoTime();
        String email = emailLocalPart + "@beautica.test";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
                objectMapper.writeValueAsString(Map.of("bookingIds", List.of())), headers);
        int actStart = output.getAll().length();

        // Act
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/book/cancel/{token}?email={email}&phone={phone}", HttpMethod.POST, request, String.class,
                Map.of("token", rawToken, "email", email, "phone", PHONE));

        // Assert
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        String actOutput = output.getAll().substring(actStart);
        List<String> businessLines = linesContaining(actOutput, "Business 4");
        assertThat(businessLines).as("business lines in the act window").hasSize(1);
        assertThat(businessLines.get(0))
                .contains(" INFO ")
                .doesNotContain(" WARN ")
                .contains("Business 400 BAD_REQUEST on POST " + CANCEL_TEMPLATE + ": bookingIds must not be empty");
        assertNoSecrets(businessLines.get(0), actOutput, emailLocalPart, rawToken);
    }

    /**
     * Soft, so one leak reports every check it breaks. The email is matched by its unique local part, which survives
     * URL-encoding ({@code %40}); the phone by its subscriber digits, which survive {@code %2B}.
     */
    private static void assertNoSecrets(String businessLine, String actOutput, String emailLocalPart, String secret) {
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(EMAIL_SHAPE.matcher(businessLine).find())
                    .as("email-shaped text in the business line: %s", businessLine).isFalse();
            softly.assertThat(UA_PHONE_SHAPE.matcher(businessLine).find())
                    .as("phone-shaped text in the business line: %s", businessLine).isFalse();
            softly.assertThat(actOutput).as("email (raw or URL-encoded) in the act-window log")
                    .doesNotContain(emailLocalPart);
            softly.assertThat(actOutput).as("phone (raw or URL-encoded) in the act-window log")
                    .doesNotContain(PHONE_DIGITS);
            softly.assertThat(actOutput).as("raw token in the act-window log").doesNotContain(secret);
        });
    }

    private static List<String> linesContaining(String text, String fragment) {
        return text.lines().filter(line -> line.contains(fragment)).toList();
    }

    private HttpHeaders jsonBearer(String jwt) {
        HttpHeaders headers = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder)
                .bearerHeaders(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String randomBase62(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder token = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            token.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        return token.toString();
    }
}
