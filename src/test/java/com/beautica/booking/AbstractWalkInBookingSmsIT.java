package com.beautica.booking;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.job.BookingReminderJob;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 22.7 — the shared rig for observing {@code app.booking.sms.enabled} at the ONLY boundary
 * that can prove anything about it: the Turbosms HTTP wire.
 *
 * <h2>Why an assertion at the wire, and not on a mocked {@code SmsService}</h2>
 * Every other full-context suite touching these paths — {@code StaffBookingEndpointIT},
 * {@code GuestBookingConcurrencyIT}, {@code GuestReminderSweepPersistenceIT},
 * {@code GuestCancelConcurrencyIT}, {@code GuestBookingLifecycleContractIT},
 * {@code PhoneOtpIntegrationTest} — declares {@code @MockBean SmsService}. A {@code @MockBean}
 * <b>replaces the bean {@code SmsConfig} registered</b>, so in those contexts the gate is not merely
 * untested, it is absent: the same Mockito stub is injected whether the flag is {@code true},
 * {@code false} or unset, and every one of those suites would stay green if
 * {@code @ConditionalOnProperty} were deleted outright. They prove the CALL SITE fires; only an
 * assertion downstream of the real bean can prove whether anything left the building.
 *
 * <p>Hence: <b>no subclass of this class may mock {@link SmsService}.</b> The real conditional
 * wiring resolves, {@code app.sms.turbosms.base-url} points at a WireMock server, and every claim
 * about delivery is an assertion on recorded HTTP — {@code verify(0, ...)} / {@code verify(1, ...)}.
 * The only {@code @MockBean}s in scope are the two mail beans {@link AbstractIntegrationTest}
 * declares, which are genuinely external.
 *
 * <h2>Every consumer of the gate, not only the walk-in one</h2>
 * The acceptance row is "the flag governs guest/LINK confirmation and the 24h reminder too", and
 * before 2026-08-18 nothing proved it: both were covered only by suites that mock the seam, so both
 * would have stayed green with the gate deleted. This rig therefore drives, at the wire and in both
 * flag states:
 * <ul>
 *   <li>the walk-in (STAFF) confirmation — {@link #createWalkIn};</li>
 *   <li>the guest (LINK) confirmation over the real {@code permitAll} route —
 *       {@link #createGuestBooking};</li>
 *   <li>the 24h reminder sweep — {@link #runReminderSweep};</li>
 *   <li>the guest phone-OTP, which must behave the OPPOSITE way: it is deliberately NOT on this
 *       gate (see {@code OtpSmsSender}), so it delivers in both states.</li>
 * </ul>
 * The reminder sweep is deterministic here because the {@code test} profile registers synchronous
 * stand-ins for both reminder executors and for {@code smsSendExecutor} (see {@code AsyncConfig}) —
 * no {@code Thread.sleep}, no timing-dependent assertion.
 *
 * <h2>Two contexts, two concrete classes — deliberately not {@code @Nested}</h2>
 * The flag decides which bean is CREATED, so it cannot be toggled inside a running context; the two
 * states need two contexts. {@code @Nested} + {@code @TestPropertySource} does not deliver that
 * here, and fails in a way that looks like a passing test rather than an error: JUnit instantiates
 * the ENCLOSING class too, and Spring injects that outer instance from the OUTER context, so every
 * inherited {@code @Autowired} field — {@code TestRestTemplate} included — still points at the
 * enclosing (flag-off) application while the nested context sits unused beside it. The "flag on"
 * rows then drive HTTP against the flag-off app and observe zero provider calls. Two top-level
 * classes over one abstract base have no such ambiguity: {@link WalkInBookingSmsIT} runs the
 * committed default, {@link WalkInBookingSmsEnabledIT} adds the {@code @TestPropertySource}.
 *
 * <p>The salon/master/service/schedule fixture and the HTTP plumbing come from
 * {@link AbstractStaffBookingIT}, shared with {@code StaffBookingEndpointIT}.
 *
 * <p>Fixture data uses no occupied-territory locality references.
 */
@Import(TestSecurityConfig.class)
abstract class AbstractWalkInBookingSmsIT extends AbstractStaffBookingIT {

    /** The subscriber digits alone — a partial leak is still a leak (Anti-Bug §I-3). */
    protected static final String PHONE_SUBSCRIBER_DIGITS = "501234567";
    protected static final String MASKED_PHONE = "+380***4567";

    /** A separate number for OTP rows, so an OTP send can never be mistaken for the walk-in one. */
    protected static final String OTP_PHONE = "+380509876543";
    protected static final String OTP_SUBSCRIBER_DIGITS = "509876543";

    /** A third number, for the guest (LINK) booking — distinct from both of the above. */
    protected static final String LINK_PHONE = "+380502223344";
    protected static final String LINK_SUBSCRIBER_DIGITS = "502223344";

    /**
     * Deliberately DIFFERENT from the seeded master's name ({@link #MASTER_NAME}), so "the body
     * carries the master name" is a real assertion rather than one any name would satisfy.
     */
    protected static final String GUEST_NAME = "Оксана";
    protected static final String GUEST_SURNAME = "Бондаренко";

    protected static final String TURBOSMS_PATH = "/message/send.json";
    private static final String TURBOSMS_TOKEN = "wiremock-turbosms-token";

    protected static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * The stubbed Turbosms endpoint, started ONCE per JVM and never stopped between classes —
     * mirroring {@link AbstractIntegrationTest}'s singleton Postgres container, and for the same
     * reason: a {@code @AfterAll} on this base would tear the server down after whichever concrete
     * subclass finished first, leaving the other with a dead port and a stale, cached Spring context
     * pointing at it. The JVM shutdown hook below is the release point.
     */
    protected static final WireMockServer TURBOSMS = new WireMockServer(options().dynamicPort());

    static {
        TURBOSMS.start();
        Runtime.getRuntime().addShutdownHook(new Thread(TURBOSMS::stop));
    }

    @DynamicPropertySource
    static void turbosmsEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.sms.turbosms.base-url", () -> TURBOSMS.baseUrl() + TURBOSMS_PATH);
        // A NON-blank token on purpose: a blank one short-circuits TurbosmsService#send BEFORE the
        // HTTP call, so "zero requests recorded" would be true for the wrong reason and the flag-off
        // rows would pass even with the gate wired backwards.
        registry.add("app.sms.turbosms.token", () -> TURBOSMS_TOKEN);
        // application-test.yml raises every other rate-limit capacity but not these two; the
        // production defaults are 3 per 15 min per IP for OTP and 10 per 60 s per user for the
        // SMS-spend budget on the walk-in create, and every test here shares 127.0.0.1.
        registry.add("app.rate-limit.otp-send-capacity", () -> 100_000);
        registry.add("app.rate-limit.staff-booking-sms-capacity", () -> 100_000);
    }

    @Autowired
    protected ApplicationContext applicationContext;

    /** The REAL gated bean — never a mock. Which class this is IS the feature under test. */
    @Autowired
    protected SmsService smsService;

    /**
     * Mints the guest JWT for the LINK path directly. The OTP code is stored only as a SHA-256 hash,
     * so a test cannot read it back to complete the real verify round trip; the token this issues is
     * the same one {@code PhoneOtpService#verifyOtp} would hand out, and the OTP dispatch itself is
     * covered separately by the {@code sendOtp} rows.
     */
    @Autowired
    private GuestTokenProvider guestTokenProvider;

    @Autowired
    private BookingReminderJob bookingReminderJob;

    /**
     * Root-logger capture, not a per-class one: "never logs the body or an unmasked phone" is a claim
     * about the WHOLE request, and a leak introduced in {@code StaffBookingService}'s warn — or in
     * some future collaborator — would be invisible to an appender bound to {@code NoOpSmsService}.
     */
    private ListAppender<ILoggingEvent> logAppender;
    private Logger rootLogger;

    @BeforeEach
    void resetTurbosmsAndCaptureLogs() {
        resetTurbosms();

        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        rootLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    /**
     * Clears the request journal AND re-installs the 200 stub.
     *
     * <p>Both halves, always: {@code resetAll()} removes stubs as well as recorded requests, so a
     * reset that forgot to re-stub would leave WireMock answering 404 — which several assertions
     * here would misread as a delivery failure rather than as a broken fixture.
     */
    protected void resetTurbosms() {
        TURBOSMS.resetAll();
        TURBOSMS.stubFor(post(urlEqualTo(TURBOSMS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"response_status\":\"OK\"}")));
    }

    // ── shared assertions ─────────────────────────────────────────────────────────

    /**
     * The "the response is identical to the enabled case" acceptance row, expressed as a contract
     * BOTH gate states are held to rather than as a byte comparison across two contexts (the id and
     * {@code createdAt} differ by construction, so raw-body equality is not the property anyone
     * means). Both concrete classes call this on their 201.
     */
    protected void assertConfirmedWalkInBody(ResponseEntity<String> resp) {
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode data = readJson(resp.getBody()).path("data");
        assertThat(data.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(data.path("serviceName").asText()).isEqualTo(SERVICE_NAME);
        assertThat(data.path("id").asText())
                .as("the created row's id comes back whatever the SMS gate did")
                .isNotBlank();
        assertThat(resp.getBody())
                .as("no field ever tells the caller whether a message left the building")
                .doesNotContain("smsSent")
                .doesNotContain("SmsDeliveryException");
    }

    protected void assertNoPiiInLogs(String phone, String subscriberDigits) {
        assertThat(logMessages())
                .as("the captured log must be non-empty, or every assertion below is vacuous")
                .isNotEmpty()
                .noneMatch(line -> line.contains(phone))
                .noneMatch(line -> line.contains(subscriberDigits))
                .as("the rendered body carries the client's appointment details (Anti-Bug §I)")
                .noneMatch(line -> line.contains(SERVICE_NAME))
                .noneMatch(line -> line.contains("Запис підтверджено"))
                .noneMatch(line -> line.contains("Нагадуємо"))
                .noneMatch(line -> line.contains("код підтвердження"));
    }

    /**
     * Every formatted line the application logged during the test, EXCLUDING the test harness's own
     * {@code com.beautica.support} announcements.
     *
     * <p>That exclusion is load-bearing, not cosmetic: {@code TestIntentLoggerExtension} echoes each
     * test's {@code @DisplayName} to the root logger, so an unfiltered capture would let a display
     * name mentioning {@code SmsDeliveryException} satisfy — or violate — an assertion about what
     * production code logged. The filter is by LOGGER NAME, so no application logger can be hidden
     * by it.
     */
    protected List<String> logMessages() {
        return logAppender.list.stream()
                .filter(event -> !event.getLoggerName().startsWith("com.beautica.support"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ── Turbosms wire helpers ─────────────────────────────────────────────────────

    /**
     * The single recorded Turbosms request body, parsed. Read through Jackson rather than matched as
     * a substring so Cyrillic copy and JSON escaping cannot make an assertion pass or fail for
     * encoding reasons that have nothing to do with the message.
     */
    protected JsonNode sentTurbosmsPayload() throws Exception {
        List<LoggedRequest> requests = TURBOSMS.findAll(postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(requests).as("exactly one provider call is expected here").hasSize(1);
        return objectMapper.readTree(requests.get(0).getBodyAsString());
    }

    protected JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable response body: " + body, e);
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────────

    /** {@code POST /api/v1/masters/{masterId}/bookings} — the STAFF walk-in create. */
    protected ResponseEntity<String> createWalkIn(OffsetDateTime startsAt) {
        String body = """
                {"masterServiceId":"%s","startsAt":"%s",
                 "guest":{"name":"%s","surname":"%s","phone":"%s"}}
                """.formatted(salon.masterServiceId(), startsAt, GUEST_NAME, GUEST_SURNAME, RAW_PHONE);
        return restTemplate.exchange(
                "/api/v1/masters/" + salon.masterId() + "/bookings", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(tokenFor(salon.ownerEmail()))), String.class);
    }

    /**
     * {@code POST /api/v1/book/{slug}/booking} — the real {@code permitAll} guest (LINK) create,
     * driven with a genuine guest JWT for {@link #LINK_PHONE}. This is the second consumer of the
     * gate and, until 2026-08-18, the acceptance row nothing proved.
     */
    protected ResponseEntity<String> createGuestBooking(OffsetDateTime startsAt) {
        String body = """
                {"serviceId":"%s","startsAt":"%s","name":"%s","surname":"%s"}
                """.formatted(salon.masterServiceId(), startsAt, GUEST_NAME, GUEST_SURNAME);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(guestTokenProvider.generate(LINK_PHONE));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/book/" + salon.bookingSlug() + "/booking",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    protected ResponseEntity<String> sendOtp(String phone) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/book/otp/send", HttpMethod.POST,
                new HttpEntity<>("{\"phone\":\"" + phone + "\"}", headers), String.class);
    }

    // ── the 24h reminder sweep ────────────────────────────────────────────────────

    /**
     * Moves the one existing booking into the sweep's 23–25h window, clears its reminder flag and
     * runs {@code BookingReminderJob#sendReminders()} directly (the {@code @Scheduled} cron is not
     * waited on).
     *
     * <p>Time-shifting an already-created LINK booking rather than inserting a raw row keeps the row
     * exactly as the production create path writes it — cancel token, guest columns, source and all —
     * so the sweep's own {@code findGuestBookingsForReminder} predicate is exercised against real
     * data rather than against a fixture's guess at it.
     *
     * <p>Both reminder executors are synchronous under the {@code test} profile, so the provider
     * calls this triggers have all completed by the time the method returns.
     */
    protected void runReminderSweep(UUID bookingId) {
        jdbcTemplate.update("""
                UPDATE bookings
                   SET starts_at = NOW() + interval '24 hours',
                       ends_at   = NOW() + interval '25 hours',
                       reminder_sent = false
                 WHERE id = ?
                """, bookingId);
        bookingReminderJob.sendReminders();
    }

    protected int otpRowCount(String phone) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM phone_otps WHERE phone = ?", Integer.class, phone);
    }
}
