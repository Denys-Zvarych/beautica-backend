package com.beautica.auth;

import com.beautica.notification.sms.OtpSmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** B520 audit-fix — the invite goes through the sender's invite channel (day-long lifetime), never the OTP one. */
@DisplayName("StaffInviteSmsTransport — invite channel, not the 10-min OTP channel (B520)")
class StaffInviteSmsTransportTest {

    @Test
    @DisplayName("send() delegates to OtpSmsSender.sendStaffInvite, never to the OTP send")
    void should_useInviteChannel_when_sendingStaffInvite() {
        OtpSmsSender sender = mock(OtpSmsSender.class);

        new StaffInviteSmsTransport(sender).send("+380501234567", "text");

        verify(sender).sendStaffInvite("+380501234567", "text");
        verify(sender, never()).send(anyString(), anyString());
    }
}
