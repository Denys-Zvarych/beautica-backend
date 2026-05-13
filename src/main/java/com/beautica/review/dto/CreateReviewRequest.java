package com.beautica.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateReviewRequest(

        @NotNull
        UUID bookingId,

        @NotNull
        @Min(1)
        @Max(5)
        Integer rating,

        // null = no comment; if provided, first character must be non-whitespace (rejects blank and leading-whitespace strings)
        @Size(min = 1, max = 2000)
        @Pattern(regexp = "^\\S[\\s\\S]{0,1999}$", message = "comment must start with a non-whitespace character")
        String comment

) {}
