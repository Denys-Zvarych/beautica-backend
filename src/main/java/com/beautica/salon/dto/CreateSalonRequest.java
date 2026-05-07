package com.beautica.salon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateSalonRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @Size(max = 100) String city,
        @Size(max = 100) String region,
        @Size(max = 500) String address,
        @Size(max = 20) String phone,
        @Pattern(regexp = "^$|^https://(www\\.)?instagram\\.com/[A-Za-z0-9._]+/?$",
                message = "Must be a valid Instagram URL or empty")
        @Size(max = 500) String instagramUrl
) {}
