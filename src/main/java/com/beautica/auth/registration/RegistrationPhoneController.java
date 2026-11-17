package com.beautica.auth.registration;

import com.beautica.auth.appcheck.RequireAppCheck;
import com.beautica.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B514 (AUTH-41) — public phone step of registration. A separate controller (not {@code AuthController}) so the
 * existing {@code AuthController} slices keep their dependency set.
 *
 * <p>Order on {@code /send}: per-IP Bucket4j bucket (shared with the guest OTP send) → App Check
 * ({@link RequireAppCheck}, interceptor) → {@code @Valid} → UA-mobile check → per-phone cap → OTP row → SMS. A
 * rejected App Check token therefore creates no OTP row and sends no SMS.
 */
@RestController
@RequestMapping("/api/v1/auth/register/phone/otp")
@RequiredArgsConstructor
public class RegistrationPhoneController {

    private final RegistrationPhoneService registrationPhoneService;

    /**
     * 200 for every Ukrainian mobile number, registered or not (D3, no oracle); 400 {@code PHONE_NOT_UA_MOBILE};
     * 403 {@code APP_CHECK_FAILED}; 429; 503 (SMS provider or App Check keys unavailable).
     */
    @RequireAppCheck
    @PostMapping("/send")
    public ApiResponse<Void> send(@Valid @RequestBody RegistrationOtpSendRequest request) {
        registrationPhoneService.sendCode(request.phone(), request.smsFormat());
        return ApiResponse.ok(null);
    }

    /**
     * 200 {@code {registrationTicket}}; 400 {@code INVALID_CODE} for a wrong / expired code; 409
     * {@code PHONE_ALREADY_REGISTERED} when a verified account holds the phone.
     */
    @PostMapping("/verify")
    public ApiResponse<RegistrationTicketResponse> verify(@Valid @RequestBody RegistrationOtpVerifyRequest request) {
        String ticket = registrationPhoneService.verifyCode(request.phone(), request.code());
        return ApiResponse.ok(new RegistrationTicketResponse(ticket));
    }
}
