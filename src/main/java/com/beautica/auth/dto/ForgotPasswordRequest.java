package com.beautica.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/forgot-password}.
 *
 * <p>Bean Validation rules match §A of the Anti-Bug Playbook:
 * <ul>
 *   <li>{@code @NotBlank} — prevents blank/whitespace-only input producing a spurious DB read.</li>
 *   <li>{@code @Email} — rejects malformed addresses before they reach the service layer.
 *       Paired with {@code @NotBlank} because {@code @Email} silently passes {@code null}.</li>
 *   <li>{@code @Size(max = 255)} — mirrors {@code users.email} column length; prevents
 *       oversized payloads from reaching the DB and producing {@code DataIntegrityViolationException}.</li>
 * </ul>
 *
 * <p>Enumeration protection is enforced in the service — a valid 200 is returned regardless
 * of whether the email maps to a real account. Bean Validation failures (blank, malformed)
 * still return a 400 because no information about account existence is conveyed.
 */
public record ForgotPasswordRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email

) {}
