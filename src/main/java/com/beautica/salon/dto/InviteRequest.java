package com.beautica.salon.dto;

import com.beautica.auth.Role;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteRequest(
        @NotBlank @Email @Size(max = 255) String email,

        @Nullable Role role
) {
    /**
     * Validates that only {@code SALON_MASTER} or {@code SALON_ADMIN} may be invited.
     * Prevents privilege escalation by blocking {@code CLIENT}, {@code SALON_OWNER}, and
     * {@code INDEPENDENT_MASTER} from being specified as the invite role (SEC-MEDIUM-6).
     */
    @AssertTrue(message = "Role must be SALON_MASTER or SALON_ADMIN")
    public boolean isRoleAllowed() {
        return role == null || role == Role.SALON_MASTER || role == Role.SALON_ADMIN;
    }

    public Role effectiveRole() {
        return role != null ? role : Role.SALON_MASTER;
    }
}
