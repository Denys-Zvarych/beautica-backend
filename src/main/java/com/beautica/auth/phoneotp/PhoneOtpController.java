package com.beautica.auth.phoneotp;

import com.beautica.auth.filter.ClientIpResolver;
import com.beautica.auth.turnstile.TurnstileVerifier;
import com.beautica.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
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
    private final TurnstileVerifier turnstileVerifier;

    /**
     * Order on the request (phase 382 D1): per-IP filter bucket -> {@code @Valid} DTO -> Turnstile -> per-phone
     * limit -> OTP row -> SMS. A rejected Turnstile token therefore creates no {@code phone_otps} row and sends
     * no SMS. The visitor IP handed to Cloudflare is the one the per-IP bucket charged ({@link ClientIpResolver}).
     */
    @PostMapping("/send")
    public ApiResponse<Void> send(@Valid @RequestBody PhoneOtpSendRequest request, HttpServletRequest httpRequest) {
        turnstileVerifier.verify(request.turnstileToken(), ClientIpResolver.resolve(httpRequest));
        phoneOtpService.sendOtp(request.phone());
        return ApiResponse.ok(null);
    }

    @PostMapping("/verify")
    public ApiResponse<GuestTokenResponse> verify(@Valid @RequestBody PhoneOtpVerifyRequest request) {
        String guestToken = phoneOtpService.verifyOtp(request.phone(), request.code());
        return ApiResponse.ok(new GuestTokenResponse(guestToken));
    }
}
