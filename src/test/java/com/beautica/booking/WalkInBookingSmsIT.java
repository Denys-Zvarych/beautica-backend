package com.beautica.booking;

import com.beautica.notification.sms.NoOpSmsService;
import com.beautica.notification.sms.SmsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 22.7 — the walk-in confirmation SMS with the gate in the state <b>every committed profile
 * ships</b>: {@code app.booking.sms.enabled} left at the {@code application.yml} default of
 * {@code false}. No property is overridden here; that absence is the test subject.
 *
 * <p>See {@link AbstractWalkInBookingSmsIT} for why nothing below mocks {@link SmsService} and why
 * the enabled half lives in {@link WalkInBookingSmsEnabledIT} rather than a {@code @Nested} class.
 */
@DisplayName("WalkInBookingSmsIT — app.booking.sms.enabled at its committed default (false)")
class WalkInBookingSmsIT extends AbstractWalkInBookingSmsIT {

    @Test
    @DisplayName("default profile — the injected SmsService is the no-op, and it is the only one")
    void should_injectExactlyOneNoOpSmsService_when_noOverrideIsPresent() {
        assertThat(smsService)
                .as("no committed profile may ship a live sender; application.yml declares false")
                .isInstanceOf(NoOpSmsService.class);
        assertThat(applicationContext.getBeanNamesForType(SmsService.class))
                .as("a stray @Service restored on TurbosmsService would add a SECOND bean and fail all "
                        + "seven injection points at boot — invisible to SmsFeatureGateTest, whose "
                        + "ApplicationContextRunner performs no component scan")
                .hasSize(1);
    }

    @Test
    @DisplayName("gate off — a walk-in create makes ZERO Turbosms requests yet still persists CONFIRMED")
    void should_makeZeroTurbosmsRequests_when_walkInIsCreatedWithTheGateOff() {
        ResponseEntity<String> resp = createWalkIn(tomorrowAtNoon());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));

        Map<String, Object> row = onlyBooking();
        assertThat(row.get("status"))
                .as("suppressing the message must not touch the booking it describes")
                .isEqualTo("CONFIRMED");
        assertThat(row.get("booking_source")).isEqualTo("STAFF");
        assertThat(row.get("guest_phone")).isEqualTo(E164_PHONE);
    }

    @Test
    @DisplayName("gate off — silent, not failing: no provider failure is logged, one INFO line is")
    void should_logOneSuppressionAndNoFailure_when_theGateIsOff() {
        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(logMessages())
                .as("a deliberate state must never be reported as a provider failure")
                .isNotEmpty()
                .noneMatch(line -> line.contains("SmsDeliveryException"))
                .noneMatch(line -> line.contains("status=FAILED"))
                .noneMatch(line -> line.contains("Walk-in confirmation SMS failed"));
        assertThat(logMessages())
                .as("exactly one audit line, and it names the flag so an operator can find the switch")
                .filteredOn(line -> line.contains("SMS suppressed (app.booking.sms.enabled=false)"))
                .hasSize(1);
    }

    @Test
    @DisplayName("gate off — the 201 body carries the same booking contract the enabled path returns")
    void should_returnTheSameConfirmedBookingContract_when_theGateIsOff() {
        assertConfirmedWalkInBody(createWalkIn(tomorrowAtNoon()));
    }

    @Test
    @DisplayName("gate off — no log line anywhere carries the body or an unmasked phone")
    void should_neverLogTheBodyOrAnUnmaskedPhone_when_theGateIsOff() {
        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertNoPiiInLogs(E164_PHONE, PHONE_SUBSCRIBER_DIGITS);
        assertThat(logMessages())
                .as("the recipient IS logged — but only through PhoneMask")
                .anyMatch(line -> line.contains(MASKED_PHONE));
    }

    /**
     * <b>Inverted on 2026-08-18, deliberately — this row used to pin the defect.</b>
     *
     * <p>Phase 22.7 shipped {@code PhoneOtpService} on the booking money gate, so with
     * {@code APP_BOOKING_SMS_ENABLED} unset {@code POST /book/otp/send} answered 200, persisted the
     * hashed code, burnt both the per-phone and the per-IP budget and delivered nothing: the only
     * guest-auth flow the platform has, silently disabled in every committed profile. Security, perf
     * and QA all raised it independently as HIGH, and QA pinned the muted behaviour here so it could
     * not be lost. This is the commit that fixes it, so this is the assertion that flips.
     *
     * <p>The carve-out is {@code OtpSmsSender} — an unconditional bean, a type outside the
     * {@link SmsService} hierarchy, injected only by {@code PhoneOtpService}. The row therefore now
     * reads: <b>the booking gate is off, and the OTP is delivered anyway</b>. Its sibling in
     * {@code WalkInBookingSmsEnabledIT} is unchanged and still asserts delivery with the gate on —
     * together they say the OTP no longer depends on this flag in either direction.
     *
     * <p>This is the ONLY provider call the gate-off context may make, which is why the walk-in rows
     * above can keep asserting {@code verify(0, ...)}.
     */
    @Test
    @DisplayName("gate off — the guest phone-OTP is delivered ANYWAY: it is not booking spend")
    void should_stillDeliverTheGuestPhoneOtp_when_theBookingGateIsOff() throws Exception {
        ResponseEntity<String> resp = sendOtp(OTP_PHONE);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otpRowCount(OTP_PHONE))
                .as("the whole OTP path ran, dispatch included")
                .isOne();
        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText())
                .as("addressed to the number that asked for the code, not to the walk-in guest")
                .isEqualTo(OTP_PHONE);
        assertNoPiiInLogs(OTP_PHONE, OTP_SUBSCRIBER_DIGITS);
    }

    /**
     * The discrimination gate for the row above: it must not pass merely because "everything is
     * delivered now". One request, one flow — the OTP's — and the guest (LINK) confirmation, which
     * IS booking copy, stays suppressed in the same context.
     *
     * <p>This is also one half of the acceptance row nothing proved before 2026-08-18: every suite
     * covering the guest confirmation mocks {@code SmsService}, so all of them would stay green with
     * {@code @ConditionalOnProperty} deleted. See {@link AbstractWalkInBookingSmsIT}.
     */
    @Test
    @DisplayName("gate off — the guest (LINK) confirmation makes ZERO Turbosms requests")
    void should_makeZeroTurbosmsRequests_when_aGuestBookingIsCreatedWithTheGateOff() {
        ResponseEntity<String> resp = createGuestBooking(tomorrowAtNoon());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(onlyBooking().get("status"))
                .as("suppressing the message must not touch the booking it describes")
                .isEqualTo("CONFIRMED");
        assertNoPiiInLogs(LINK_PHONE, LINK_SUBSCRIBER_DIGITS);
    }

    /** The other half: the 24h reminder sweep is on the gate too, and nothing proved that either. */
    @Test
    @DisplayName("gate off — the 24h reminder sweep makes ZERO Turbosms requests")
    void should_makeZeroTurbosmsRequests_when_theReminderSweepRunsWithTheGateOff() {
        assertThat(createGuestBooking(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        resetTurbosms();

        runReminderSweep((UUID) onlyBooking().get("id"));

        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(onlyBooking().get("reminder_sent"))
                .as("the sweep ran and flagged the row — the suppression is at the sender, not upstream")
                .isEqualTo(true);
        assertNoPiiInLogs(LINK_PHONE, LINK_SUBSCRIBER_DIGITS);
    }
}
