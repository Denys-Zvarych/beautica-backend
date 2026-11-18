package com.beautica.auth.dto;

import com.beautica.auth.Role;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body of {@code POST /api/v1/auth/invite} (and, built by {@code SalonService}, of the salon-scoped invite).
 *
 * <p>B520 (AUTH-16, AUTH-18): the invitee is a Ukrainian mobile number in any human spelling; the service normalises
 * it ({@code UaMobilePhone}, 400 {@code PHONE_NOT_UA_MOBILE}). {@code phone} is deliberately not {@code @NotBlank}: a
 * body without it — an old build still sending only {@code email}, which Jackson ignores as an unknown property —
 * must answer the typed 400 {@code INVITE_PHONE_REQUIRED} the app branches on, not a generic validation error. The
 * boundary caps size and character set only (§A, phone row).
 */
public record InviteRequest(
        @Size(max = 32, message = "Phone must not exceed 32 characters")
        @Pattern(regexp = "^[+\\d\\s\\-().]*$", message = "Phone contains invalid characters")
        String phone,

        @NotNull(message = "Salon ID is required")
        UUID salonId,

        @Nullable Role role
) {
    public Role effectiveRole() {
        return role != null ? role : Role.SALON_MASTER;
    }

    @AssertTrue(message = "Role must be SALON_MASTER or SALON_ADMIN")
    public boolean isRoleAllowed() {
        return role == null || role == Role.SALON_MASTER || role == Role.SALON_ADMIN;
    }

    /** The phone is PII — never in a log line. */
    @Override
    public String toString() {
        return "InviteRequest[salonId=" + salonId + ", role=" + role + "]";
    }
}
