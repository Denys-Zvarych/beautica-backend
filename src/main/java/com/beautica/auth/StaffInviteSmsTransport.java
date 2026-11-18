package com.beautica.auth;

import com.beautica.notification.service.StaffInviteSmsChannel;
import com.beautica.notification.sms.OtpSmsSender;
import org.springframework.stereotype.Component;

/**
 * B520 (NTF-59, NTF-D14) — sends the staff invite SMS through the always-live {@link OtpSmsSender} (SMSClub in prod,
 * Mailpit capture on staging), never the {@code app.booking.sms.enabled}-gated {@code SmsService}: an invite is a
 * transactional message to a not-yet-registered person, like an OTP, not booking spend. The SMSClub adapter applies the
 * staging daily cap and the {@code RecipientInvalidSpikeAlarm} to it like to every other send.
 *
 * <p>Lives in {@code com.beautica.auth} because only this package may depend on {@link OtpSmsSender}
 * ({@code SmsFeatureGateTest}). Throws on failure ({@code SmsDeliveryException}) so the outbox row is retried.
 * Sent via {@link OtpSmsSender#sendStaffInvite} (B520 audit-fix): a delivery lifetime of a day, so a phone that is off
 * for a while still gets it — a re-invite of a live invite is an idempotent 201 that sends nothing.
 */
@Component
public class StaffInviteSmsTransport implements StaffInviteSmsChannel {

    private final OtpSmsSender otpSmsSender;

    public StaffInviteSmsTransport(OtpSmsSender otpSmsSender) {
        this.otpSmsSender = otpSmsSender;
    }

    @Override
    public void send(String phoneE164, String text) {
        // The invite channel of the sender: SMSClub uses the ≥ 24 h invite lifetime, not the 10-min OTP one.
        otpSmsSender.sendStaffInvite(phoneE164, text);
    }
}
