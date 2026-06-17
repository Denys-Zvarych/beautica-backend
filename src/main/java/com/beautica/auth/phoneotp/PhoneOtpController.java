package com.beautica.auth.phoneotp;

import com.beautica.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public guest-booking OTP endpoints (Phase 13.2). Both paths fall under
 * {@code /api/v1/book/**} and are {@code permitAll} (unauthenticated clients opening a
 * shared master booking link); SecurityConfig permits {@code POST /api/v1/book/otp/**}.
 *
 * <p>Defence layers: a per-IP Bucket4j throttle ({@code AuthRateLimitFilter}) on
 * {@code /send}, plus the per-phone rate limit inside {@link PhoneOtpService}. The
 * controller is HTTP-only — validation at the boundary ({@code @Valid}), all logic in
 * the service.
 */
@RestController
@RequestMapping("/api/v1/book/otp")
@RequiredArgsConstructor
public class PhoneOtpController {

    private final PhoneOtpService phoneOtpService;

    @PostMapping("/send")
    public ApiResponse<Void> send(@Valid @RequestBody PhoneOtpSendRequest request) {
        phoneOtpService.sendOtp(request.phone());
        return ApiResponse.ok(null);
    }

    @PostMapping("/verify")
    public ApiResponse<GuestTokenResponse> verify(@Valid @RequestBody PhoneOtpVerifyRequest request) {
        String guestToken = phoneOtpService.verifyOtp(request.phone(), request.code());
        return ApiResponse.ok(new GuestTokenResponse(guestToken));
    }
}
