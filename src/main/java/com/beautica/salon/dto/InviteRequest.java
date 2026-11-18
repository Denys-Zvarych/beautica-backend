package com.beautica.salon.dto;

import com.beautica.auth.Role;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/salons/{salonId}/invite}. B520: the invitee's Ukrainian mobile number; see
 * {@link com.beautica.auth.dto.InviteRequest} for why {@code phone} is not {@code @NotBlank} (a body without it answers
 * 400 {@code INVITE_PHONE_REQUIRED}).
 */
public record InviteRequest(
        @Size(max = 32, message = "Phone must not exceed 32 characters")
        @Pattern(regexp = "^[+\\d\\s\\-().]*$", message = "Phone contains invalid characters")
        String phone,

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

    /** The phone is PII — never in a log line. */
    @Override
    public String toString() {
        return "InviteRequest[role=" + role + "]";
    }
}
