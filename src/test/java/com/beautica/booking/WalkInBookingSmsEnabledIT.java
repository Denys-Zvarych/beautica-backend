package com.beautica.booking;

import com.beautica.common.TimeZones;
import com.beautica.notification.sms.SmsService;
import com.beautica.notification.sms.TurbosmsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 22.7 — the same walk-in create with {@code app.booking.sms.enabled=true}, i.e. the state
 * Railway will be flipped into at release via {@code APP_BOOKING_SMS_ENABLED}.
 *
 * <p>A sibling class rather than a {@code @Nested} block for the reason set out in
 * {@link AbstractWalkInBookingSmsIT}: the flag selects a bean, so it needs a second context, and
 * {@code @Nested} would silently leave the inherited {@code TestRestTemplate} pointing at the
 * enclosing flag-off application.
 */
@TestPropertySource(properties = "app.booking.sms.enabled=true")
@DisplayName("WalkInBookingSmsEnabledIT — app.booking.sms.enabled=true, the release state")
class WalkInBookingSmsEnabledIT extends AbstractWalkInBookingSmsIT {

    @Test
    @DisplayName("flag on — the injected SmsService is Turbosms, and it is still the only one")
    void should_injectExactlyOneTurbosmsService_when_theFlagIsTrue() {
        assertThat(smsService).isInstanceOf(TurbosmsService.class);
        assertThat(applicationContext.getBeanNamesForType(SmsService.class))
                .as("flipping the flag must SWAP the bean, never add one")
                .hasSize(1);
    }

    @Test
    @DisplayName("flag on — exactly ONE Turbosms request, addressed to the walk-in guest in E.164")
    void should_sendExactlyOneMessageToTheGuestInE164_when_theGateIsOn() throws Exception {
        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        JsonNode sent = sentTurbosmsPayload();
        assertThat(sent.path("recipients"))
                .as("one recipient, normalised — never the '050 123 45 67' that was posted")
                .hasSize(1);
        assertThat(sent.path("recipients").get(0).asText()).isEqualTo(E164_PHONE);
    }

    @Test
    @DisplayName("flag on — the body names the master, the date and the Kyiv wall-clock time")
    void should_renderMasterNameDateAndTime_when_theGateIsOn() throws Exception {
        OffsetDateTime startsAt = tomorrowAtNoon();
        assertThat(createWalkIn(startsAt).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String text = sentTurbosmsPayload().path("sms").path("text").asText();
        assertThat(text)
                .as("the MASTER's name, not the guest's — the client is being told who they will see")
                .contains(MASTER_NAME)
                .doesNotContain(GUEST_NAME)
                // The PLATFORM taxonomy name, never the provider's custom service_definitions.name
                // (security LOW, 2026-08-19). This message reaches a number that never opted in, so
                // the only free text a self-registered account can put into a Beautica-branded SMS is
                // its own display name — the service string is platform-authored.
                .contains(platformServiceName())
                .doesNotContain(SERVICE_NAME)
                .contains(DATE_FMT.format(startsAt.atZoneSameInstant(TimeZones.KYIV)))
                .as("the wall clock the client reads, not the UTC instant the column stores")
                .contains("12:00");
    }

    @Test
    @DisplayName("flag on — the walk-in body carries no cancel link and no unresolved placeholder")
    void should_carryNoCancelLink_when_theGateIsOn() throws Exception {
        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String text = sentTurbosmsPayload().path("sms").path("text").asText();
        assertThat(text)
                .as("a STAFF booking has cancel_token = NULL (V137) — there is no link to build")
                .doesNotContain("http")
                .doesNotContain("/cancel")
                .as("a {cancelUrl} added to the template would render LITERALLY in a client's SMS — "
                        + "Placeholders#format leaves an unmapped placeholder verbatim")
                .doesNotContain("{")
                .as("nor a bare «Скасувати: » label with nothing after it — the dangling-label defect "
                        + "declineReason exists to avoid, which a doesNotContain(\"{\") alone misses")
                .doesNotContain("Скасувати:")
                .as("the client cancels by phoning the master back")
                .contains("Скасувати — за телефоном майстра.");
    }

    @Test
    @DisplayName("flag on + Turbosms 500 — the booking stays CONFIRMED and the request still returns 201")
    void should_keepTheBookingConfirmed_when_turbosmsReturns5xx() {
        TURBOSMS.resetAll();
        TURBOSMS.stubFor(post(urlEqualTo(TURBOSMS_PATH))
                .willReturn(aResponse().withStatus(500).withBody("provider down")));

        ResponseEntity<String> resp = createWalkIn(tomorrowAtNoon());

        assertConfirmedWalkInBody(resp);
        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(onlyBooking().get("status"))
                .as("a committed booking must survive a provider outage — the send is AFTER commit")
                .isEqualTo("CONFIRMED");
        assertThat(logMessages())
                .as("the failure is recorded, by exception CLASS only — and named by kind, since "
                        + "four request-path senders now share BookingSmsDispatcher")
                .anyMatch(line -> line.contains("Booking SMS send failed")
                        && line.contains("kind=walk_in_confirmation")
                        && line.contains("cause=SmsDeliveryException"));
    }

    /**
     * <b>The defect this suite discovered, now FIXED — and this row is the proof, not the pin.</b>
     *
     * <p>503 stays a SEPARATE row from the 500 above because the two used to behave differently.
     * {@code TurbosmsService} built its {@code RestClient} from
     * {@code ClientHttpRequestFactoryBuilder.detect()}, which resolved Apache HC5 <em>under test</em>
     * (httpclient5 is a {@code testImplementation} dependency), and HC5's
     * {@code DefaultHttpRequestRetryStrategy} treats <b>429 and 503</b> as retryable — the two codes
     * an overloaded or rate-limited SMS gateway is most likely to answer with. Turbosms bills per
     * delivered message and the request carries no idempotency key, so the blast radius was a
     * duplicate charge and a duplicate SMS to a real client.
     *
     * <p>Production, with no HC5 on the classpath, fell back to {@code JdkClientHttpRequestFactory}
     * and never retried — so the pinned "sends it twice" behaviour, AND the connect/read timeouts
     * documented as the thread-starvation defence, were all verified against a factory production
     * did not use. One change closes both: the factory is now named explicitly
     * ({@code ClientHttpRequestFactoryBuilder.jdk()}), so test and production agree and no
     * infrastructure the call site cannot see repeats a billable send.
     *
     * <p>The expectation is therefore {@code 1}. If it ever reads {@code 2} again, someone has
     * restored {@code detect()} or promoted httpclient5 to {@code implementation} without re-pinning
     * the retry strategy.
     */
    @Test
    @DisplayName("flag on + Turbosms 503 — exactly ONE request: a billable send is never retried")
    void should_sendExactlyOnce_when_turbosmsReturns503() {
        TURBOSMS.resetAll();
        TURBOSMS.stubFor(post(urlEqualTo(TURBOSMS_PATH))
                .willReturn(aResponse().withStatus(503).withBody("provider overloaded")));

        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(onlyBooking().get("status"))
                .as("a provider outage never endangers the booking itself — the send is AFTER commit")
                .isEqualTo("CONFIRMED");
    }

    /**
     * The OTHER way infrastructure can repeat a billable send, and the sibling of the 503 row above:
     * {@code TurbosmsService} pins {@code HttpClient.Redirect.NEVER}, so a 3xx must NOT make the
     * client re-issue this exact paid POST at a location the call site never chose. Asserted at the
     * wire because it is unobservable anywhere else — the send is fire-and-forget and the response
     * body is discarded, so a followed redirect would show up only as a second Turbosms invoice.
     *
     * <p>The redirect target is the SAME path, deliberately: it makes the recorded-request count the
     * whole assertion (1 = not followed, 2 = followed) instead of depending on a second stub.
     *
     * <p><b>307, not 302 — and that choice is what makes this row falsifiable.</b> A 302 was tried
     * first and proved unfalsifiable: neither the JDK client nor Apache HC5 re-issues a POST on
     * 301/302 whatever their redirect policy says, so the row stayed green under every mutation and
     * asserted nothing. 307 is the code that preserves the method, so it is the one a redirect
     * setting can actually act on — mutation-verified 2026-08-19 (switching to
     * {@code Redirect.NORMAL} turns this row, and only this row, red).
     */
    @Test
    @DisplayName("flag on + Turbosms 307 — the redirect is NOT followed: one request, one charge")
    void should_notFollowRedirects_when_turbosmsAnswers307() {
        TURBOSMS.resetAll();
        TURBOSMS.stubFor(post(urlEqualTo(TURBOSMS_PATH))
                .willReturn(aResponse().withStatus(307).withHeader("Location", TURBOSMS_PATH)));

        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(onlyBooking().get("status"))
                .as("and the booking is unaffected either way — the send is AFTER commit")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("flag on + Turbosms 5xx — the failure log still carries no body and no unmasked phone")
    void should_neverLogTheBodyOrAnUnmaskedPhone_when_turbosmsFails() {
        TURBOSMS.resetAll();
        TURBOSMS.stubFor(post(urlEqualTo(TURBOSMS_PATH))
                .willReturn(aResponse().withStatus(503).withBody("provider down")));

        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertNoPiiInLogs(E164_PHONE, PHONE_SUBSCRIBER_DIGITS);
        assertThat(logMessages()).anyMatch(line -> line.contains(MASKED_PHONE));
    }

    /**
     * The other half of {@code WalkInBookingSmsIT}'s OTP row. That sibling was INVERTED on
     * 2026-08-18 — the OTP is no longer on the booking gate at all ({@code OtpSmsSender}) — so the
     * pair now reads: delivered with the flag off, delivered with the flag on. This row is
     * deliberately left unchanged by that fix, which is the point: the carve-out must not have
     * disturbed the enabled path.
     */
    @Test
    @DisplayName("flag on — the guest phone-OTP is delivered")
    void should_deliverTheGuestPhoneOtp_when_theGateIsOn() throws Exception {
        assertThat(sendOtp(OTP_PHONE).getStatusCode()).isEqualTo(HttpStatus.OK);

        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(OTP_PHONE);
        assertNoPiiInLogs(OTP_PHONE, OTP_SUBSCRIBER_DIGITS);
    }

    /**
     * The acceptance row nothing proved before 2026-08-18: the guest (LINK) confirmation is on this
     * flag. Every other suite covering it declares {@code @MockBean SmsService}, which REPLACES the
     * conditional bean, so all of them would have stayed green with {@code @ConditionalOnProperty}
     * deleted outright. Asserted here at the wire, against the real {@code permitAll} route and a
     * real guest JWT, with {@code WalkInBookingSmsIT}'s mirror row holding the gate-off half.
     */
    @Test
    @DisplayName("flag on — a guest (LINK) create sends exactly one message, with its cancel link")
    void should_sendTheGuestConfirmation_when_theGateIsOn() throws Exception {
        assertThat(createGuestBooking(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        JsonNode sent = sentTurbosmsPayload();
        assertThat(sent.path("recipients").get(0).asText())
                .as("addressed to the OTP-verified guest phone the token carries")
                .isEqualTo(LINK_PHONE);
        assertThat(sent.path("sms").path("text").asText())
                .as("the LINK template — unlike the walk-in one — DOES carry a self-cancel link, "
                        + "which is what makes this a different template and not the same assertion twice")
                .contains("Скасувати:")
                .contains(MASTER_NAME)
                .contains(SERVICE_NAME);
    }

    /**
     * The third consumer, same story: the 24h reminder sweep. Driven through the real
     * {@code BookingReminderJob} over a real LINK row, which also exercises its
     * {@code findGuestBookingsForReminder} predicate rather than a fixture's guess at it.
     */
    @Test
    @DisplayName("flag on — the 24h reminder sweep sends exactly one message per due guest booking")
    void should_sendTheGuestReminder_when_theGateIsOn() throws Exception {
        assertThat(createGuestBooking(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        resetTurbosms();

        runReminderSweep((UUID) onlyBooking().get("id"));

        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        JsonNode sent = sentTurbosmsPayload();
        assertThat(sent.path("recipients").get(0).asText()).isEqualTo(LINK_PHONE);
        assertThat(sent.path("sms").path("text").asText())
                .as("the reminder template, not the confirmation one")
                .contains("Нагадуємо")
                .contains(SERVICE_NAME);
        assertThat(onlyBooking().get("reminder_sent")).isEqualTo(true);
    }

    /**
     * The platform {@code ServiceType.nameUk} the fixture attached, asserted to DIFFER from the
     * provider's custom {@link #SERVICE_NAME} first: if a future seed change made the two equal, the
     * {@code doesNotContain(SERVICE_NAME)} half above would pass while proving nothing, and the whole
     * point of the row is that the two strings are distinguishable.
     */
    private String platformServiceName() {
        assertThat(lastPlatformServiceName)
                .as("the fixture must attach a platform name distinct from the provider's custom "
                        + "name, or this row cannot tell which of the two was rendered")
                .isNotBlank()
                .isNotEqualTo(SERVICE_NAME);
        return lastPlatformServiceName;
    }
}
