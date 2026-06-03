package com.beautica.booking.dto;

import com.beautica.booking.enums.CancellationReason;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StatusUpdateRequest(
        CancellationReason cancellationReason,
        // max=1000 mirrors booking.provider_comment VARCHAR(1000) (§A). Control-char
        // ban prevents embedded NUL/newline reaching the DB as a 500 instead of 400.
        @Size(max = 1000, message = "Comment must be at most 1000 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Comment must not contain control characters")
        String comment
) {}
