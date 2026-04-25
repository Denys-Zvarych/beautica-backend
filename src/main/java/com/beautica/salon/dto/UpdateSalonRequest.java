package com.beautica.salon.dto;

import jakarta.validation.constraints.Size;

public record UpdateSalonRequest(
        @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @Size(max = 100) String city,
        @Size(max = 100) String region,
        @Size(max = 500) String address,
        @Size(max = 20) String phone,
        @Size(max = 500) String instagramUrl
) {}
