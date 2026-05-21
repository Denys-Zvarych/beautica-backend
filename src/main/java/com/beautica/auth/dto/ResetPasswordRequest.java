package com.beautica.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/reset-password}.
 *
 * <p>Bean Validation rules match §A of the Anti-Bug Playbook:
 * <ul>
 *   <li>{@code token}: {@code @NotBlank} + {@code @Size(max = 512)} — 512 mirrors the
 *       {@code password_reset_tokens.token} column length. Prevents oversized raw-token
 *       inputs from bypassing validation and hitting the DB; does not reveal the stored
 *       hash length.</li>
 *   <li>{@code newPassword}: {@code @NotBlank} + {@code @Size(min = 8, max = 128)} —
 *       identical policy to {@link RegisterRequest#password()} so a password that passed
 *       registration can always be set via the reset path and vice-versa.</li>
 * </ul>
 *
 * <p>Invalid / used / expired tokens all surface as a single generic 400 at the service
 * layer (no oracle). Bean Validation failures here are independent and return the standard
 * 400 validation envelope from {@code GlobalExceptionHandler}.
 */
public record ResetPasswordRequest(

        @NotBlank(message = "Token is required")
        @Size(max = 512, message = "Token is invalid")
        String token,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
        String newPassword

) {}
