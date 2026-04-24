package com.beautica.user;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(

        @Size(min = 1, max = 100, message = "First name must be 1–100 characters")
        String firstName,

        @Size(min = 1, max = 100, message = "Last name must be 1–100 characters")
        String lastName,

        @Pattern(
                regexp = "^\\+?[0-9\\s\\-()]{7,20}$",
                message = "Phone number must be 7–20 digits"
        )
        String phoneNumber
) {
}
