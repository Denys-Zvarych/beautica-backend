package com.beautica.booking.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/book/{slug}/booking} (Phase 13.3).
 *
 * <p>The phone is deliberately NOT in the body — it is the OTP-verified {@code sub}
 * claim of the guest JWT and is extracted server-side, so a caller cannot book on
 * behalf of an arbitrary phone.
 *
 * <p>{@code serviceId} is the {@code MasterServiceAssignment} id (the slot key
 * surfaced by {@code GET /book/{slug}/info}); it is a UUID in the actual schema,
 * not the {@code Long} the phase doc assumed.
 *
 * <p>Anti-Bug §A: every field carries its boundary validation — {@code startsAt}
 * is {@code @NotNull @Future}, free-text name/surname are {@code @NotBlank @Size}
 * matched to the {@code VARCHAR(100)} columns plus a control-char {@code @Pattern}
 * (single-line: NUL/C0/DEL/TAB/LF/CR are rejected — a name is never multi-line and
 * these would be an SMS / log-injection surface). Mirrors the {@code RegisterRequest}
 * name guards.
 */
public record GuestBookingRequest(

        @NotNull
        UUID serviceId,

        @NotNull
        @Future
        OffsetDateTime startsAt,

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Name must not contain control characters")
        String name,

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Surname must not contain control characters")
        String surname
) {
}
