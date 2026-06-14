package com.beautica.auth.dto;

import com.beautica.common.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/reset-password}.
 *
 * <p>Bean Validation rules match §A of the Anti-Bug Playbook:
 * <ul>
 *   <li>{@code token}: {@code @NotBlank} + {@code @Size(max = 512)} + {@code @Pattern} —
 *       512 mirrors the {@code password_reset_tokens.token} column length. The
 *       {@code @Pattern} charset guard rejects malformed payloads (control chars, padded
 *       base64, arbitrary 512-byte blobs) BEFORE they reach the SHA-256 hash + DB lookup.
 *       The accepted alphabet is the unpadded base64url set produced by
 *       {@code SecureTokenGenerator.generateToken()} ({@code [A-Za-z0-9_-]}). Prevents
 *       oversized raw-token inputs from bypassing validation and hitting the DB; does not
 *       reveal the stored hash length.</li>
 *   <li>{@code newPassword}: {@code @NotBlank} + {@code @StrongPassword} — the SAME shared
 *       policy implementation applied to {@link RegisterRequest#password()} (length 8–128 +
 *       common-password denylist), so a password that passed registration can always be set
 *       via the reset path and vice-versa.</li>
 * </ul>
 *
 * <p>Invalid / used / expired tokens all surface as a single generic 400 at the service
 * layer (no oracle). Bean Validation failures here are independent and return the standard
 * 400 validation envelope from {@code GlobalExceptionHandler}.
 */
public record ResetPasswordRequest(

        @NotBlank(message = "Token is required")
        @Size(max = 512, message = "Token is invalid")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Token is invalid")
        String token,

        @NotBlank(message = "Password is required")
        @StrongPassword
        String newPassword

) {}
