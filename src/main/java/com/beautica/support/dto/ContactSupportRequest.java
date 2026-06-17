package com.beautica.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Text parts of a Help / Contact-us submission.
 *
 * <p>Bound from {@code multipart/form-data} form fields (NOT a JSON body) — file
 * attachments are accepted as a separate {@code List<MultipartFile>} request part
 * on the controller, never as a field here. The DTO carries NO sender-identity
 * field: the authenticated user's id and email are captured server-side from the
 * {@code SecurityContext}, so a caller cannot spoof who the message is from.
 *
 * <p>Validation runs at the controller boundary via {@code @Valid}. {@code message}
 * length is bounded both ways: the lower bound rejects empty/near-empty noise, the
 * upper bound caps payload size before it reaches the email body.
 */
public record ContactSupportRequest(

        @NotBlank
        @Size(min = 10, max = 5000)
        String message,

        // Optional one-line subject hint. Control chars are rejected so the value
        // cannot inject CR/LF into the rendered email (the actual mail Subject
        // header is a server-side constant + the authenticated email, never this).
        @Size(max = 150)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Subject contains invalid characters")
        String subject
) {
}
