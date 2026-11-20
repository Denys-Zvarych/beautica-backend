package com.beautica.user;

import com.beautica.auth.appcheck.RequireAppCheck;
import com.beautica.auth.dto.ResendVerificationRequest;
import com.beautica.auth.registration.RegistrationOtpSendRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.user.email.NotificationEmailService;
import com.beautica.user.email.NotificationEmailVerifyRequest;
import com.beautica.user.phone.PhoneChangeVerifyRequest;
import com.beautica.user.phone.UserPhoneService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B516 — the signed-in user's own contact identifiers: add / change the phone by SMS code (AUTH-44) and add / change
 * the optional notifications email by emailed code (AUTH-50, CLI-50). A separate controller (not
 * {@link UserController}) so the existing {@code UserController} slices keep their dependency set. Any role; the
 * identity is the JWT {@code sub} (never the principal name, empty for a phone-only account).
 *
 * <p>The send / request bodies reuse the registration phone OTP and email-verification records; the two verify bodies
 * are their own records (same fields and validation plus the optional {@code currentPassword}, AUTH-D19).
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserContactController {

    private final UserPhoneService userPhoneService;
    private final NotificationEmailService notificationEmailService;

    @Operation(summary = "Send an SMS code to a new phone (add or change)",
            description = "Needs X-Firebase-AppCheck (AUTH-54). 200; 400 PHONE_NOT_UA_MOBILE (no SMS); 403 "
                    + "APP_CHECK_FAILED; 429 (3 per 15 min per phone, account, IP); 503. Never 409: a number held by "
                    + "another account is refused only at verify.")
    @RequireAppCheck
    @PostMapping("/phone/otp/send")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> sendPhoneCode(@Valid @RequestBody RegistrationOtpSendRequest request,
                                           Authentication authentication) {
        userPhoneService.sendCode(AuthenticationUtils.userId(authentication), request.phone(), request.smsFormat());
        return ApiResponse.ok(null);
    }

    @Operation(summary = "Confirm the new phone with its SMS code",
            description = "Swaps the phone and marks it verified in one write. A CHANGE (the account already has a "
                    + "verified phone) also needs currentPassword, checked first; a first add needs only the code. "
                    + "200; 400 data.code CURRENT_PASSWORD_INVALID (counts as a failed try on the code) / INVALID_CODE "
                    + "(wrong / expired / exhausted / another account's code); 400 PHONE_NOT_UA_MOBILE; "
                    + "409 PHONE_ALREADY_REGISTERED.")
    @PostMapping("/phone/otp/verify")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> verifyPhoneCode(@Valid @RequestBody PhoneChangeVerifyRequest request,
                                             Authentication authentication) {
        userPhoneService.verifyCode(AuthenticationUtils.userId(authentication), request.phone(), request.code(),
                request.currentPassword());
        return ApiResponse.ok(null);
    }

    @Operation(summary = "Email a code to a new notifications email",
            description = "200; 429 within the 60 s cooldown (Retry-After). Never 409: an address held by another "
                    + "account is refused only at verify.")
    @PostMapping("/email/request-code")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> requestEmailCode(@Valid @RequestBody ResendVerificationRequest request,
                                              Authentication authentication) {
        notificationEmailService.requestCode(AuthenticationUtils.userId(authentication), request.email());
        return ApiResponse.ok(null);
    }

    @Operation(summary = "Confirm the notifications email with its code",
            description = "Stores the email (emailVerified = true) only now. Needs currentPassword (add and change), "
                    + "checked first. 200; 400 data.code CURRENT_PASSWORD_INVALID (counts as a failed try on the code) "
                    + "/ INVALID_CODE / CODE_EXPIRED; 409 EMAIL_ALREADY_REGISTERED.")
    @PostMapping("/email/verify")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> verifyEmailCode(@Valid @RequestBody NotificationEmailVerifyRequest request,
                                             Authentication authentication) {
        notificationEmailService.verifyCode(AuthenticationUtils.userId(authentication), request.email(),
                request.code(), request.currentPassword());
        return ApiResponse.ok(null);
    }
}
