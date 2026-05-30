package com.beautica.service.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for setting or replacing the photo URL of a service definition.
 *
 * <p>Accepts a presigned Cloudflare R2 URL or any direct {@code https://} URL.
 * The {@code @Pattern} rejects non-HTTPS schemes ({@code http://}, {@code javascript:},
 * {@code data:}) per anti-bug §A URL-field rule.
 *
 * <p>{@code @NotNull} is paired with the other constraints because Bean Validation
 * silently skips null targets — without it, a null payload would pass validation
 * and reach the service as a null wipe (anti-bug §A).
 */
public record UpdateServicePhotoRequest(
        @NotNull(message = "photoUrl must not be null")
        @Size(max = 2048, message = "Photo URL must be at most 2048 characters")
        @Pattern(
                regexp = "^https://[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]{1,2000}$",
                message = "Photo URL must be a valid HTTPS URL"
        )
        String photoUrl
) {
}
