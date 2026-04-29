package com.beautica.salon.dto;

import com.beautica.auth.Role;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteRequest(
        @NotBlank @Email @Size(max = 255) String email,

        @Nullable Role role
) {
    public Role effectiveRole() {
        return role != null ? role : Role.SALON_MASTER;
    }
}
