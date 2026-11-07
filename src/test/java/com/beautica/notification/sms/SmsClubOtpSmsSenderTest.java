package com.beautica.notification.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("SmsClubOtpSmsSender — verbatim delegation to its own SmsClubService (phase 439)")
class SmsClubOtpSmsSenderTest {

    private static final String PHONE = "+380671234567";
    private static final String TEXT = "Ваш код 482913";

    @Test
    @DisplayName("the OTP sender passes the phone and text to its SmsClubService unchanged")
    void should_delegateVerbatim_when_sending() {
        SmsClubService delegate = mock(SmsClubService.class);
        SmsClubOtpSmsSender sender = new SmsClubOtpSmsSender(delegate);

        sender.send(PHONE, TEXT);

        verify(delegate).send(PHONE, TEXT);
    }

    @Test
    @DisplayName("a delivery failure from the delegate reaches the caller unchanged")
    void should_propagateDeliveryFailure_when_delegateThrows() {
        SmsClubService delegate = mock(SmsClubService.class);
        SmsDeliveryException failure = new SmsDeliveryException("SMS provider is not configured");
        doThrow(failure).when(delegate).send(PHONE, TEXT);
        SmsClubOtpSmsSender sender = new SmsClubOtpSmsSender(delegate);

        assertThatThrownBy(() -> sender.send(PHONE, TEXT)).isSameAs(failure);
    }

    @Test
    @DisplayName("the ungated OTP sender is never an SmsService, so the money gate cannot resolve it")
    void should_stayOutsideTheSmsServiceType_when_inspected() {
        boolean isSmsService = SmsService.class.isAssignableFrom(SmsClubOtpSmsSender.class);

        assertThat(isSmsService).as("the ungated OTP bean must never be an SmsService").isFalse();
    }
}
