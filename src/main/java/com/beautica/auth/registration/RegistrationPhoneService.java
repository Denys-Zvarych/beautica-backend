package com.beautica.auth.registration;

import com.beautica.auth.phoneotp.PhoneOtpPurpose;
import com.beautica.auth.phoneotp.PhoneOtpService;
import com.beautica.common.exception.IdentityException;
import com.beautica.common.exception.VerificationException;
import com.beautica.common.util.UaMobilePhone;
import com.beautica.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * B514 (AUTH-41, AUTH-D11) — the phone step of registration: send an SMS code to a Ukrainian mobile number, then
 * trade a matching code for a single-use {@link RegistrationTicket}. REUSE-FIRST: the code lifecycle, SMS sender,
 * per-phone cap and 503 compensation are {@link PhoneOtpService}'s, with {@link PhoneOtpPurpose#REGISTRATION}.
 *
 * <p>Anti-enumeration (D3): send never looks at the users table — a taken phone gets the same 200 and the same SMS;
 * only verify, after the code proved the caller owns the phone, answers {@code 409 PHONE_ALREADY_REGISTERED}.
 */
@Service
public class RegistrationPhoneService {

    private final PhoneOtpService phoneOtpService;
    private final RegistrationTicketService ticketService;
    private final UserRepository userRepository;

    public RegistrationPhoneService(PhoneOtpService phoneOtpService,
                                    RegistrationTicketService ticketService,
                                    UserRepository userRepository) {
        this.phoneOtpService = phoneOtpService;
        this.ticketService = ticketService;
        this.userRepository = userRepository;
    }

    /**
     * Validates the number (AUTH-D15, no SMS for anything but a UA mobile) and sends the code. {@code NOT_SUPPORTED}:
     * the provider call must not hold a DB connection (see {@link PhoneOtpService#sendCode}).
     *
     * @throws IdentityException {@code 400 PHONE_NOT_UA_MOBILE}
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendCode(String rawPhone, String smsFormat) {
        String phone = UaMobilePhone.require(rawPhone);
        phoneOtpService.sendCode(phone, smsFormat, PhoneOtpPurpose.REGISTRATION);
    }

    /**
     * Redeems the code and mints the ticket in one transaction.
     *
     * @return the raw registration ticket
     * @throws VerificationException {@code 400 INVALID_CODE} for a wrong, expired, missing or other-purpose code
     * @throws IdentityException     {@code 409 PHONE_ALREADY_REGISTERED} when a verified account holds the phone
     *                               (the code is not burnt: the transaction rolls back)
     */
    @Transactional
    public String verifyCode(String rawPhone, String code) {
        String phone = UaMobilePhone.require(rawPhone);
        if (!phoneOtpService.consumeCode(phone, code, PhoneOtpPurpose.REGISTRATION)) {
            throw new VerificationException(VerificationException.Code.INVALID_CODE);
        }
        if (userRepository.existsByVerifiedPhone(phone)) {
            throw new IdentityException(IdentityException.Code.PHONE_ALREADY_REGISTERED);
        }
        return ticketService.issue(phone);
    }
}
