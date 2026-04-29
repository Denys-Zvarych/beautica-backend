package com.beautica.auth.dto;

import com.beautica.auth.Role;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record InviteRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotNull(message = "Salon ID is required")
        UUID salonId,

        @Nullable Role role
) {
    public Role effectiveRole() {
        return role != null ? role : Role.SALON_MASTER;
    }
}
