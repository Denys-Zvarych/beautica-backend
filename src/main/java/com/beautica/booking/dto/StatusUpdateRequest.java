package com.beautica.booking.dto;

import com.beautica.booking.enums.CancellationReason;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StatusUpdateRequest(
        CancellationReason cancellationReason,
        // max=1000 mirrors booking.provider_comment VARCHAR(1000) (§A). Control-char ban
        // prevents embedded NUL reaching the DB as a 500 instead of 400; line breaks
        // (\n, \r) and tabs (\t) are permitted — this is a long-form free-text field.
        @Size(max = 1000, message = "Comment must be at most 1000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Comment must not contain control characters other than line breaks and tabs")
        String comment
) {}
