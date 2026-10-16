package com.beautica.config;

import com.beautica.notification.sms.CapturingOtpSmsSender;
import com.beautica.notification.sms.CapturingSmsSender;
import com.beautica.notification.sms.CapturingSmsService;
import com.beautica.notification.sms.NoOpSmsService;
import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.notification.sms.SmsService;
import com.beautica.notification.sms.TurbosmsOtpSmsSender;
import com.beautica.notification.sms.TurbosmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects the {@link SmsService} implementation from the single money gate
 * {@code app.booking.sms.enabled} (Phase 22.7).
 *
 * <h2>One switch, every sender</h2>
 * Booking SMS is a metered, paid vendor call. Rather than a per-call-site {@code if} — which a new
 * sender inevitably forgets — the gate is applied once, at the seam every sender already depends
 * on. Flipping this property changes which bean satisfies {@link SmsService} for
 * {@code GuestBookingService}, {@code GuestReminderDispatcher}, {@code BookingCancellationService},
 * {@code GuestVisitCancellationService}, {@code NotificationService} and
 * {@code StaffBookingService} simultaneously. Sibling precedent: {@code FIREBASE_ENABLED} for push,
 * {@code app.cloudflare-r2.enabled} for media ({@code S3Config}).
 *
 * <h2>The one carve-out: the guest phone-OTP</h2>
 * {@code PhoneOtpService} is <b>not</b> on this gate, and never was meant to be (security/perf/QA
 * HIGH, 2026-08-18). It injects {@link OtpSmsSender}, supplied unconditionally by
 * {@link #otpSmsSender}. The locked decision this phase implements is "one flag for all outbound
 * <em>booking</em> SMS"; an OTP is an auth credential, not booking spend. Left on the gate, an
 * environment with {@code TURBOSMS_TOKEN} set but {@code APP_BOOKING_SMS_ENABLED} unset answered
 * {@code POST /book/otp/send} with 200, wrote the hashed code, burnt both rate-limit budgets and
 * delivered nothing — silently disabling the only guest-auth flow the platform has.
 *
 * <h2>Bean selection, not bean absence</h2>
 * {@code ClosureReminderJob}'s style — {@code @ConditionalOnProperty} on a whole scheduled
 * component, so nothing is constructed when off — is wrong for this seam. {@link SmsService} is an
 * injected constructor collaborator of seven beans, so the injection point must ALWAYS resolve;
 * removing the bean would fail the context at boot instead of disabling a feature. Hence exactly
 * one of the two mutually exclusive bean methods below is always active:
 * <table>
 *   <caption>Resolution matrix</caption>
 *   <tr><th>{@code app.booking.sms.enabled}</th><th>bean</th></tr>
 *   <tr><td>absent</td><td>{@link NoOpSmsService} (via {@code matchIfMissing})</td></tr>
 *   <tr><td>{@code false}</td><td>{@link NoOpSmsService}</td></tr>
 *   <tr><td>{@code true}</td><td>{@link TurbosmsService}</td></tr>
 * </table>
 * {@code SmsFeatureGateTest} asserts all three rows <b>by bean class</b>, and asserts the count is
 * exactly one — a duplicate-bean or no-such-bean failure at boot is the expensive failure mode
 * here, and it is not one an "it didn't throw" assertion would catch.
 *
 * <p><b>Write the value as {@code true}/{@code false} — case-insensitively.</b>
 * {@link ConditionalOnProperty} compares the property against {@code havingValue} with
 * {@code equalsIgnoreCase}, so {@code TRUE}, {@code True} and {@code tRuE} all
 * <b>start billing</b> exactly as {@code true} does; likewise {@code FALSE} is off. What it does
 * NOT do is run the value through Spring's relaxed boolean binder, so a spelling that binder would
 * accept ({@code on}, {@code yes}, {@code 1}) matches neither condition and leaves the context with
 * no {@link SmsService} at all — every injection point fails at boot. Both halves are pinned by
 * {@code SmsFeatureGateTest}; an earlier version of this Javadoc claimed only the exact lowercase
 * literals were understood, which would have let an operator enable spend believing they had not.
 * {@code application.yml} declares the literal default for exactly this reason.
 *
 * <h2>Second axis: {@code beautica.sms.mode} (Phase 368)</h2>
 * The money gate above decides <em>whether</em> booking SMS is sent; {@code beautica.sms.mode}
 * ({@link SmsMode}) decides <em>where</em> it goes. {@code turbosms} (default, also when absent) keeps
 * every bean above exactly as before. {@code capture} (staging only — {@code EnvironmentFingerprintGuard}
 * rejects it elsewhere) swaps the transport for {@link CapturingSmsSender}, which mails the text to a
 * Mailpit inbox. Resolution matrix, booking {@link SmsService}:
 * <table>
 *   <caption>SmsService resolution</caption>
 *   <tr><th>enabled</th><th>mode</th><th>bean</th></tr>
 *   <tr><td>false / absent</td><td>any</td><td>{@link NoOpSmsService}</td></tr>
 *   <tr><td>true</td><td>turbosms / absent</td><td>{@link TurbosmsService}</td></tr>
 *   <tr><td>true</td><td>capture</td><td>{@link CapturingSmsService}</td></tr>
 * </table>
 * and the (ungated) {@link OtpSmsSender}: {@code turbosms} → {@link TurbosmsOtpSmsSender}, {@code capture}
 * → {@link CapturingOtpSmsSender}. Exactly one bean of each type in every cell.
 */
@Slf4j
@Configuration
public class SmsConfig {

    /** Active only for {@code beautica.sms.mode=turbosms}, or when the property is absent. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @ConditionalOnProperty(prefix = "beautica.sms", name = "mode", havingValue = "turbosms", matchIfMissing = true)
    @interface OnTurbosmsMode {
    }

    /** Active only for {@code beautica.sms.mode=capture}. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @ConditionalOnProperty(prefix = "beautica.sms", name = "mode", havingValue = "capture")
    @interface OnCaptureMode {
    }

    /**
     * The real Turbosms adapter — registered only when the gate is explicitly on.
     *
     * <p>A missing {@code TURBOSMS_TOKEN} is deliberately NOT checked here (contrast
     * {@code S3Config#s3Client}, which fails fast on blank credentials): the token is optional by
     * design so the app boots and serves all non-SMS traffic without it, and
     * {@link TurbosmsService#send} already surfaces a clean {@code SmsDeliveryException} on the
     * first attempt. Turning the gate on with no token is loud at send time, which is the intended
     * contrast with the gate-off case's deliberate silence.
     *
     * @param restClientBuilder Boot's auto-configured builder; the adapter applies its own timeouts
     * @param properties        Turbosms endpoint, sender name and Bearer token
     */
    @Bean
    @OnTurbosmsMode
    @ConditionalOnProperty(prefix = "app.booking.sms", name = "enabled", havingValue = "true")
    public SmsService turbosmsService(RestClient.Builder restClientBuilder,
                                      TurbosmsProperties properties) {
        log.info("Booking SMS is ENABLED (app.booking.sms.enabled=true) — "
                + "registering TurbosmsService; outbound messages will be billed");
        return new TurbosmsService(restClientBuilder, properties);
    }

    /**
     * Staging booking SMS: gate on AND {@code beautica.sms.mode=capture} — the text is mailed to
     * Mailpit, never to Turbosms. See the class Javadoc matrix.
     */
    @Bean
    @OnCaptureMode
    @ConditionalOnProperty(prefix = "app.booking.sms", name = "enabled", havingValue = "true")
    public SmsService capturingSmsService(CapturingSmsSender capturingSmsSender) {
        log.warn("Booking SMS is ENABLED in CAPTURE mode (beautica.sms.mode=capture) — "
                + "messages are mailed to the capture inbox, nothing reaches Turbosms");
        return new CapturingSmsService(capturingSmsSender);
    }

    /**
     * The shared capture transport. Not an {@link SmsService}/{@link OtpSmsSender}, so it cannot disturb
     * either exactly-one invariant. {@code JavaMailSender} is Boot's auto-configured bean (Mailpit in staging).
     */
    @Bean
    @OnCaptureMode
    public CapturingSmsSender capturingSmsSender(
            JavaMailSender mailSender,
            @Value("${app.invite.from-email:noreply@beautica.app}") String fromAddress) {
        return new CapturingSmsSender(mailSender, fromAddress);
    }

    /**
     * The default: a log-only sink that never reaches the network.
     *
     * <p>{@code matchIfMissing = true} is what makes "off" the behaviour of an environment that has
     * never heard of this property — a new deployment, a test slice, a developer's laptop — so
     * spending money is always an explicit act.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "app.booking.sms", name = "enabled", havingValue = "false", matchIfMissing = true)
    public SmsService noOpSmsService() {
        log.warn("Booking SMS is DISABLED (app.booking.sms.enabled=false) — "
                + "every send is suppressed and logged; set APP_BOOKING_SMS_ENABLED=true to deliver");
        return new NoOpSmsService();
    }

    /**
     * The auth-channel sender — <b>unconditional, on purpose</b>. See the class Javadoc's carve-out
     * section and {@link OtpSmsSender} for why the guest phone-OTP is not booking spend and must
     * never sit behind {@code app.booking.sms.enabled}.
     *
     * <p>Degradation is unchanged and deliberately loud: a blank {@code TURBOSMS_TOKEN} still lets
     * the application boot and serve every non-SMS route, and the first OTP send then raises a clean
     * {@code SmsDeliveryException} which {@code PhoneOtpService} turns into a 503 with the OTP row
     * rolled back. "Unconfigured credential" and "deliberately silent feature" stay different
     * situations with different outcomes.
     *
     * <p>Returns {@link OtpSmsSender}, not {@link SmsService}: the declared and actual bean types
     * are both outside the {@link SmsService} hierarchy, so this bean cannot disturb the
     * exactly-one-{@link SmsService} invariant the two methods above rely on.
     */
    @Bean
    @OnTurbosmsMode
    public OtpSmsSender otpSmsSender(RestClient.Builder restClientBuilder,
                                     TurbosmsProperties properties) {
        return new TurbosmsOtpSmsSender(new TurbosmsService(restClientBuilder, properties));
    }

    /**
     * The auth-channel sender in {@code capture} mode: same unconditional-on-the-money-gate carve-out as
     * {@link #otpSmsSender}, but the OTP SMS is mailed to Mailpit. Distinct method name; exactly one
     * OtpSmsSender bean exists per mode; {@code PhoneOtpService} is unchanged.
     */
    @Bean
    @OnCaptureMode
    public OtpSmsSender capturingOtpSmsSender(CapturingSmsSender capturingSmsSender) {
        return new CapturingOtpSmsSender(capturingSmsSender);
    }
}
