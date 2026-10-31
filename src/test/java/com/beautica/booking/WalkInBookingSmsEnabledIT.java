package com.beautica.booking;

import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.common.TimeZones;
import com.beautica.notification.sms.SmsService;
import com.beautica.notification.sms.TurbosmsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

    /** Phase 412 D1/D3 (reverses 254 D) — the walk-in SMS carries the visit's one-time cancel link. */
    @Test
    @DisplayName("flag on — the walk-in body carries the visit cancel link whose hash the header stores")
    void should_carryVisitCancelLink_when_theGateIsOn() throws Exception {
        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String text = sentTurbosmsPayload().path("sms").path("text").asText();
        assertThat(text)
                .as("no unresolved placeholder")
                .doesNotContain("{")
                .contains("Скасувати: ")
                .doesNotContain("за телефоном майстра")
                .containsPattern("/book/cancel/[0-9a-f-]{36}$");
        UUID token = tokenOf(text);
        assertThat(jdbcTemplate.queryForObject("SELECT cancel_token_hash FROM appointments WHERE id = ?",
                String.class, onlyBooking().get("appointment_id")))
                .as("only the hash is at rest (394)").isEqualTo(CancelTokenHasher.hash(token));
        assertThat(onlyBooking().get("cancel_token_hash")).as("child rows stay tokenless").isNull();
    }

    /** Phase 412 D4 — the walk-in cancels by link through the LINK visit path: one guest SMS, one provider row. */
    @Test
    @DisplayName("flag on — cancelling a walk-in by its link → CANCELLED, one cancel SMS, one provider notification")
    void should_cancelWalkInVisit_when_cancelLinkFollowed() throws Exception {
        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID token = tokenOf(sentTurbosmsPayload().path("sms").path("text").asText());
        UUID bookingId = (UUID) onlyBooking().get("id");
        UUID appointmentId = (UUID) onlyBooking().get("appointment_id");
        resetTurbosms();

        ResponseEntity<String> cancel = restTemplate.postForEntity("/api/v1/book/cancel/" + token, null, String.class);

        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(onlyBooking().get("status")).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class,
                appointmentId)).isEqualTo("CANCELLED");
        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(E164_PHONE);
        assertThat(sentTurbosmsPayload().path("sms").path("text").asText())
                .contains("скасовано")
                .as("the cancel SMS names the PLATFORM service type, never the provider's custom name")
                .contains(platformServiceName())
                .doesNotContain(SERVICE_NAME);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE "
                + "event_type = 'CLIENT_CANCELLED' AND aggregate_id = ?", Integer.class, bookingId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM in_app_notification WHERE "
                + "type = 'BOOKING_CANCELLED_BY_CLIENT' AND appointment_id = ?", Integer.class, appointmentId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("flag on — a consumed walk-in link answers 404 and sends nothing (one-time, as LINK)")
    void should_return404_when_walkInLinkReplayed() throws Exception {
        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID token = tokenOf(sentTurbosmsPayload().path("sms").path("text").asText());
        restTemplate.postForEntity("/api/v1/book/cancel/" + token, null, String.class);
        resetTurbosms();

        ResponseEntity<String> replay = restTemplate.postForEntity("/api/v1/book/cancel/" + token, null, String.class);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("flag on — a walk-in inside the cancel cutoff answers the existing 422, still CONFIRMED")
    void should_return422_when_walkInInsideCutoff() throws Exception {
        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID token = tokenOf(sentTurbosmsPayload().path("sms").path("text").asText());
        jdbcTemplate.update("UPDATE bookings SET starts_at = NOW() + interval '30 minutes', "
                + "ends_at = NOW() + interval '90 minutes' WHERE id = ?", onlyBooking().get("id"));

        ResponseEntity<String> cancel = restTemplate.postForEntity("/api/v1/book/cancel/" + token, null, String.class);

        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(onlyBooking().get("status")).isEqualTo("CONFIRMED");
    }

    /** Phase 412 QA — a walk-in token is a capability for ITS visit only; a sibling visit is untouched. */
    @Test
    @DisplayName("flag on — walk-in A's link cancels visit A only; visit B stays CONFIRMED and its own link still works")
    void should_cancelOnlyOwnVisit_when_twoWalkInVisitsExist() throws Exception {
        UUID visitA = createWalkInVisitId(tomorrowAtNoon());
        UUID tokenA = tokenOf(sentTurbosmsPayload().path("sms").path("text").asText());
        resetTurbosms();
        UUID visitB = createWalkInVisitId(tomorrowAt(LocalTime.of(15, 0)));
        UUID tokenB = tokenOf(sentTurbosmsPayload().path("sms").path("text").asText());
        resetTurbosms();

        ResponseEntity<String> cancelA =
                restTemplate.postForEntity("/api/v1/book/cancel/" + tokenA, null, String.class);

        assertThat(cancelA.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(visitStatus(visitA)).isEqualTo("CANCELLED");
        assertThat(visitStatus(visitB)).as("token A must never reach visit B's header").isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForList("SELECT status FROM bookings WHERE appointment_id = ?", String.class,
                visitB)).as("nor visit B's items").containsExactly("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("SELECT cancel_token_hash FROM appointments WHERE id = ?",
                String.class, visitB)).as("visit B's token is not consumed").isEqualTo(CancelTokenHasher.hash(tokenB));
        assertThat(restTemplate.postForEntity("/api/v1/book/cancel/" + tokenB, null, String.class).getStatusCode())
                .as("visit B still cancels by its own link").isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** Phase 412 QA (394 invariant) — the raw walk-in token is never at rest, in any column of either table. */
    @Test
    @DisplayName("flag on — the raw walk-in token appears in no column of the header or the item, only its hash")
    void should_storeNoPlaintextToken_when_walkInCreated() throws Exception {
        UUID appointmentId = createWalkInVisitId(tomorrowAtNoon());
        UUID token = tokenOf(sentTurbosmsPayload().path("sms").path("text").asText());

        String headerRow = jdbcTemplate.queryForObject(
                "SELECT row_to_json(a)::text FROM appointments a WHERE a.id = ?", String.class, appointmentId);
        String itemRow = jdbcTemplate.queryForObject(
                "SELECT row_to_json(b)::text FROM bookings b WHERE b.appointment_id = ?", String.class, appointmentId);

        assertThat(headerRow).as("header row=%s", headerRow)
                .doesNotContain(token.toString())
                .contains(CancelTokenHasher.hash(token));
        assertThat(itemRow).as("item row").doesNotContain(token.toString());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM appointments a JOIN bookings b "
                        + "ON b.appointment_id = a.id WHERE a.id = ? "
                        + "AND (a.cancel_token IS NOT NULL OR b.cancel_token IS NOT NULL)",
                Integer.class, appointmentId)).as("the legacy plaintext cancel_token column stays NULL (394)").isZero();
    }

    private UUID createWalkInVisitId(OffsetDateTime startsAt) {
        ResponseEntity<String> resp = createWalkIn(startsAt);
        assertThat(resp.getStatusCode()).as("walk-in create body=%s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(readJson(resp.getBody()).path("data").path("id").asText());
    }

    private String visitStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    /** Phase 413 D4 (after 412) — with the walk-in SMS gate on, a walk-in joins the 24h sweep. */
    @Test
    @DisplayName("flag on — the 24h sweep reminds a walk-in once, naming the platform service type")
    void should_remindWalkIn_when_theGateIsOn() throws Exception {
        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        resetTurbosms();

        runReminderSweep((UUID) onlyBooking().get("id"));

        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        JsonNode sent = sentTurbosmsPayload();
        assertThat(sent.path("recipients").get(0).asText()).isEqualTo(E164_PHONE);
        assertThat(sent.path("sms").path("text").asText())
                .contains("Нагадуємо").contains(platformServiceName()).doesNotContain(SERVICE_NAME);
        assertThat(onlyBooking().get("reminder_sent")).isEqualTo(true);
    }

    private static UUID tokenOf(String smsText) {
        return UUID.fromString(smsText.substring(smsText.lastIndexOf('/') + 1).trim());
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

    // ════════════════════════════════════════════════════════════════════════════════
    // Phase 22.13 — one SMS per VISIT, and the per-phone budget counts visits, not rows
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * The headline row: a 5-service walk-in must still send exactly ONE Turbosms request, and its
     * BODY must actually widen to name every service beyond the first — a bare call-count assertion
     * would pass even against the pre-22.13 first-service-only text, since a 3+-service visit sent
     * exactly one message either way (see {@code StaffBookingServiceTest} for the mutation-check that
     * moving the call inside the per-item loop turns count-only assertions red; this row is the
     * complementary "count alone is not enough" proof).
     */
    @Test
    @DisplayName("flag on — a 5-service visit dispatches exactly ONE message naming the first service "
            + "and counting the rest")
    void should_dispatchExactlyOneMessage_when_fiveServiceWalkIn() throws Exception {
        String firstServicePlatformName = platformServiceName();
        List<UUID> masterServiceIds = new ArrayList<>();
        masterServiceIds.add(salon.masterServiceId());
        for (int i = 0; i < 4; i++) {
            masterServiceIds.add(insertService(salon.masterId(), "SALON", salon.salonId()));
        }

        assertThat(createWalkIn(tomorrowAtNoon(), masterServiceIds).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        JsonNode sent = sentTurbosmsPayload();
        assertThat(sent.path("recipients").get(0).asText())
                .as("normalised to E.164 for the multi-service visit too, exactly as the "
                        + "single-service row already proves — the phone-normalisation step does "
                        + "not depend on how many services the visit chains")
                .isEqualTo(E164_PHONE);
        assertThat(sent.path("sms").path("text").asText())
                .as("D3 — first service named, plural-correct «та ще N послуг(и)» for the rest")
                .contains(firstServicePlatformName + " та ще 4 послуги");
    }

    /**
     * D1 — the per-phone budget is denominated in VISITS: five visits (mixed service counts) for one
     * recipient exhaust {@code MAX_WALK_INS_PER_PHONE_PER_WINDOW}, and the SIXTH — whatever its own
     * service count — 429s. If the budget still counted ROWS, the 5-service visit created first would
     * alone exhaust the cap and the very next (single-service) visit would 429 instead of the sixth.
     */
    @Test
    @DisplayName("flag on — five visits (one multi-service) exhaust the per-phone budget; the sixth 429s")
    void should_allowFiveVisitsThenReject429_when_sameRecipientAcrossMixedVisitSizes() {
        List<UUID> firstVisitServiceIds = new ArrayList<>();
        firstVisitServiceIds.add(salon.masterServiceId());
        firstVisitServiceIds.add(insertService(salon.masterId(), "SALON", salon.salonId()));
        firstVisitServiceIds.add(insertService(salon.masterId(), "SALON", salon.salonId()));

        // Visit #1 — three services back to back, 09:00-12:00 (3 x 60 min, 0 buffer).
        assertThat(createWalkIn(tomorrowAt(LocalTime.of(9, 0)), firstVisitServiceIds).getStatusCode())
                .as("visit #1 (3 services) — if rows were still the unit, this alone would exhaust "
                        + "the 5-per-hour budget")
                .isEqualTo(HttpStatus.CREATED);
        // Visits #2-5 — single service each, starting where visit #1's span ends so none collides.
        for (int hour = 12; hour <= 15; hour++) {
            assertThat(createWalkIn(tomorrowAt(LocalTime.of(hour, 0))).getStatusCode())
                    .as("visit #%d of 5", hour - 10)
                    .isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<String> sixth = createWalkIn(tomorrowAt(LocalTime.of(16, 0)));

        assertThat(sixth.getStatusCode())
                .as("the sixth VISIT for this recipient — five were admitted, whatever their own "
                        + "service counts, so the cap is denominated in visits, not rows")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * D1's coalesce contract, at the wire: two PRE-22.12-shaped rows (no {@code appointments} header
     * at all) for one recipient must each count as their own visit — the same property
     * {@code V138WalkInPhoneIndexMigrationTest} pins at the query tier, repeated here end to end so a
     * regression shows up against the real create path too, not only against a hand-built query.
     */
    @Test
    @DisplayName("flag on — two legacy appointment-less rows for a phone count as two visits toward its budget")
    void should_notCountLegacyNullAppointmentRowsAsOne() {
        insertLegacyStaffWalkIn(E164_PHONE, tomorrowAt(LocalTime.of(6, 0)));
        insertLegacyStaffWalkIn(E164_PHONE, tomorrowAt(LocalTime.of(7, 0)));

        // Three MORE real visits should still fit: 2 legacy + 3 real = 5, inside the budget.
        assertThat(createWalkIn(tomorrowAt(LocalTime.of(9, 0))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createWalkIn(tomorrowAt(LocalTime.of(10, 0))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createWalkIn(tomorrowAt(LocalTime.of(11, 0))).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The SIXTH visit overall (2 legacy + 3 real + this one) must 429 — had the two legacy NULL-
        // appointment rows collapsed into one DISTINCT group, this sixth visit would still be admitted.
        ResponseEntity<String> sixth = createWalkIn(tomorrowAt(LocalTime.of(12, 0)));
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** A pre-22.12-shaped STAFF row: {@code appointment_id} is NOT bound, so it stays NULL. */
    private void insertLegacyStaffWalkIn(String phone, OffsetDateTime startsAt) {
        jdbcTemplate.update("""
                INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, starts_at,
                    ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking,
                    booking_source, guest_name, guest_surname, guest_phone, cancel_token,
                    created_by_user_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'STAFF', 'Олена', 'Коваль', ?,
                    NULL, ?, NOW(), NOW())
                """,
                UUID.randomUUID(), salon.masterId(), salon.masterServiceId(), salon.salonId(),
                startsAt, startsAt.plusMinutes(60), phone, salon.ownerId());
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
