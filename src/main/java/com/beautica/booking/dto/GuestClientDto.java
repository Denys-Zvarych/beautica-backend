package com.beautica.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The walk-in identity a staff-created booking is for — name, surname, phone, no account
 * (Phase 22.4, amendment A4).
 *
 * <p><b>All three are required</b> (locked 2026-08-18). {@code chk_bookings_guest_fields} (V137)
 * and {@link StaffClientRef.Guest}'s own compact constructor both re-assert that, but a missing
 * value must surface as a field-named 400 from Bean Validation rather than as the service's
 * generic {@code BusinessException} message — hence the annotations here as well as the backstops
 * behind them (Anti-Bug §A).
 *
 * <p><b>Sizes mirror the columns.</b> {@code bookings.guest_name} / {@code guest_surname} are
 * {@code VARCHAR(100)} and {@code guest_phone} is {@code VARCHAR(20)}. Without the caps an
 * over-long value reaches the insert and returns a 500
 * {@code DataIntegrityViolationException} instead of a 400.
 *
 * <p><b>Control characters are banned on name/surname</b> with the exact {@code @Pattern} the
 * OTP-verified LINK path already carries on {@code GuestBookingRequest} — restated verbatim, not
 * paraphrased, so the two guest-identity paths reject the same inputs. A CR/LF in
 * {@code guest_name} would otherwise persist and flow into Phase 22.7's SMS body.
 *
 * <p><b>Why {@code phone} is NOT constrained to E.164 here.</b> The phase doc's amendment A4 asks
 * for {@code ^\+[0-9]{6,18}$} at the DTO, but that contradicts the shape Phase 22.2 actually
 * shipped and the preconditions block above it: a staff user types {@code 050 123 45 67} into a
 * form, and {@code UkrainianPhoneNormalizer} — called INSIDE {@code StaffBookingService},
 * deliberately, so exactly one place can reject a bad number and with exactly one message —
 * converts it. The controller "must not helpfully pre-format", so an E.164-only {@code @Pattern}
 * here would 400 every human-typed number the service is built to accept
 * ({@code StaffBookingServiceTest#should_normalisePhoneToE164_when_staffTypedItWithSpaces} pins
 * that behaviour). The pattern below is therefore the normaliser's own input alphabet — digits,
 * a leading {@code +}, and the separators it strips — which blocks control characters, letters and
 * injection payloads while leaving format rejection to the single normaliser.
 *
 * @param name    the walk-in client's first name
 * @param surname the walk-in client's last name
 * @param phone   a human-typed Ukrainian number in any form
 *                {@code UkrainianPhoneNormalizer} accepts
 */
@Schema(description = "Walk-in (account-less) client identity for a staff-created booking.")
public record GuestClientDto(

        @NotBlank(message = "Client name is required")
        @Size(max = 100, message = "Client name must be at most 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Name must not contain control characters")
        @Schema(example = "Марія", maxLength = 100)
        String name,

        @NotBlank(message = "Client surname is required")
        @Size(max = 100, message = "Client surname must be at most 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Surname must not contain control characters")
        @Schema(example = "Левченко", maxLength = 100)
        String surname,

        @NotBlank(message = "Client phone is required")
        @Size(max = 20, message = "Client phone must be at most 20 characters")
        @Pattern(regexp = "^[+0-9 ()\\-.\\u00A0\\u202F]+$",
                message = "Phone must contain digits, an optional leading +, and separators only")
        @Schema(example = "050 123 45 67", maxLength = 20,
                description = "Normalised to E.164 (+380XXXXXXXXX) server-side; foreign numbers are rejected.")
        String phone
) {
}
