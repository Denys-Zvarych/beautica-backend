package com.beautica.auth.registration;

import com.beautica.auth.phoneotp.PhoneOtpPurpose;
import com.beautica.auth.phoneotp.PhoneOtpService;
import com.beautica.common.exception.IdentityException;
import com.beautica.common.exception.VerificationException;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegistrationPhoneService — phone step of registration (B514, AUTH-41)")
class RegistrationPhoneServiceTest {

    private static final String PHONE = "+380501234567";

    @Mock
    private PhoneOtpService phoneOtpService;
    @Mock
    private RegistrationTicketService ticketService;
    @Mock
    private UserRepository userRepository;

    private RegistrationPhoneService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationPhoneService(phoneOtpService, ticketService, userRepository);
    }

    @Test
    @DisplayName("should_sendRegistrationCodeToNormalisedPhone_when_uaMobile")
    void should_sendRegistrationCodeToNormalisedPhone_when_uaMobile() {
        service.sendCode("050 123 45 67", "PLAIN");

        verify(phoneOtpService).sendCode(PHONE, "PLAIN", PhoneOtpPurpose.REGISTRATION);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("should_throwPhoneNotUaMobileAndSendNothing_when_landline")
    void should_throwPhoneNotUaMobileAndSendNothing_when_landline() {
        assertThatThrownBy(() -> service.sendCode("+380441234567", null))
                .isInstanceOfSatisfying(IdentityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IdentityException.Code.PHONE_NOT_UA_MOBILE));
        verifyNoInteractions(phoneOtpService);
    }

    @Test
    @DisplayName("should_returnTicket_when_codeMatchesAndPhoneFree")
    void should_returnTicket_when_codeMatchesAndPhoneFree() {
        when(phoneOtpService.consumeCode(PHONE, "123456", PhoneOtpPurpose.REGISTRATION)).thenReturn(true);
        when(userRepository.existsByVerifiedPhone(PHONE)).thenReturn(false);
        when(ticketService.issue(PHONE)).thenReturn("ticket");

        String ticket = service.verifyCode(PHONE, "123456");

        assertThat(ticket).isEqualTo("ticket");
    }

    @Test
    @DisplayName("should_throwInvalidCode_when_codeRejected")
    void should_throwInvalidCode_when_codeRejected() {
        when(phoneOtpService.consumeCode(PHONE, "000000", PhoneOtpPurpose.REGISTRATION)).thenReturn(false);

        assertThatThrownBy(() -> service.verifyCode(PHONE, "000000")).isInstanceOf(VerificationException.class);
        verify(ticketService, never()).issue(anyString());
    }

    @Test
    @DisplayName("should_throwPhoneAlreadyRegistered_when_verifiedAccountHoldsPhone")
    void should_throwPhoneAlreadyRegistered_when_verifiedAccountHoldsPhone() {
        when(phoneOtpService.consumeCode(PHONE, "123456", PhoneOtpPurpose.REGISTRATION)).thenReturn(true);
        when(userRepository.existsByVerifiedPhone(PHONE)).thenReturn(true);

        assertThatThrownBy(() -> service.verifyCode(PHONE, "123456"))
                .isInstanceOfSatisfying(IdentityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IdentityException.Code.PHONE_ALREADY_REGISTERED));
        verify(ticketService, never()).issue(any());
    }
}
