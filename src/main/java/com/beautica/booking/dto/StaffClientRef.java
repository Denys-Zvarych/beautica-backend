package com.beautica.booking.dto;

import com.beautica.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who a staff-created booking is FOR — the client-identity half of {@link StaffBookingCommand}.
 *
 * <p>Sealed with exactly two variants because a staff booking has exactly two possible subjects,
 * and the DB agrees: {@code chk_bookings_guest_fields} (V137) gives {@code STAFF} a two-mode branch
 * — linked-client XOR walk-in — even though only the walk-in mode ships in this track. Modelling it
 * as a sealed type lets {@code StaffBookingService} decide with an exhaustive pattern switch (no
 * {@code default}), so the day Phase 22.3 lights up {@link ExistingClient} the compiler names every
 * site that must handle it instead of one silently falling through.
 *
 * <p><b>Only {@link Guest} is constructed today.</b> Phase 22.3 (existing-platform-CLIENT branch)
 * is deferred; {@code StaffBookingService} answers {@link ExistingClient} with a
 * {@code 501 Not Implemented}. Do not delete the variant to "clean up" — its presence is what keeps
 * the switch exhaustive-by-compiler rather than exhaustive-by-comment.
 *
 * <p><b>Not a request DTO.</b> This is an internal command holder; Bean Validation lives on the
 * HTTP request record Phase 22.4 introduces. The compact constructors below are the service-layer
 * backstop for the invariants the amendment locks: a walk-in needs all three of first name, last
 * name and phone, and each must fit its column.
 */
public sealed interface StaffClientRef {

    /**
     * An account-less walk-in / phone-in client, identified by name + surname + phone only.
     *
     * <p><b>All three are required</b> (locked 2026-08-18) — this is the invariant
     * {@code Booking.staffBooking(...)} re-asserts at the entity seam and
     * {@code chk_bookings_guest_fields} re-asserts in the database. Rejected as a {@code 400}
     * before any DB write.
     *
     * <p>{@code phone} is stored RAW here and normalised to E.164 by {@code StaffBookingService}
     * immediately before the insert (see {@code UkrainianPhoneNormalizer} for why the DB CHECK makes
     * that mandatory). This record deliberately does not normalise: a value object that silently
     * rewrites its own input is a poor place to hide a format conversion the caller must be able to
     * see fail.
     *
     * <p>Lengths mirror {@code bookings.guest_name} / {@code guest_surname} {@code VARCHAR(100)}
     * (Anti-Bug §A: an over-long value must be a clean 400, never a
     * {@code DataIntegrityViolationException} 500 at flush). {@code phone} is bounded by the
     * normaliser, whose output is always 13 characters, well inside {@code VARCHAR(20)}.
     */
    record Guest(String name, String surname, String phone) implements StaffClientRef {

        /** Mirrors {@code bookings.guest_name} / {@code guest_surname} {@code VARCHAR(100)}. */
        public static final int MAX_NAME_LENGTH = 100;

        /**
         * The character class {@code GuestBookingRequest} enforces on the OTP-verified LINK path
         * ({@code @Pattern("^[^\\p{Cntrl}]*$")} on both {@code name} and {@code surname}) — restated
         * here verbatim rather than paraphrased, so the two guest-identity paths reject the same
         * inputs with the same words.
         *
         * <p><b>Why (security MEDIUM, 2026-08-18).</b> Until this was added the <em>less</em>-trusted
         * path was the weaker one: a staff walk-in needs no OTP and carries a third party's PII, yet
         * enforced only trim + length. A CR/LF or an ASCII control byte in {@code guest_name}
         * therefore persisted and flowed straight into Phase 22.7's SMS body.
         */
        private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

        public Guest {
            name = boundedText(name, "Client name");
            surname = boundedText(surname, "Client surname");
            // The phone's FORMAT (and its length ceiling) is the normaliser's business — only its
            // PRESENCE is this record's, so there is exactly one place that can reject a bad number
            // and exactly one message it can reject it with.
            phone = requireText(phone, "Client phone");
        }

        /**
         * Present, trimmed, within the column, and free of control characters.
         *
         * <p>The control-character test runs on the TRIMMED value, so a stray leading newline is
         * normalised away rather than 400ed — marginally more lenient than the LINK DTO's
         * {@code @Pattern}, which sees the raw string. The value that reaches the database and the
         * SMS renderer is identical either way; what matters is that an <em>embedded</em> control
         * character can no longer survive on either path.
         */
        private static String boundedText(String value, String field) {
            String trimmed = requireText(value, field);
            if (trimmed.length() > MAX_NAME_LENGTH) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        field + " must not exceed " + MAX_NAME_LENGTH + " characters");
            }
            if (CONTROL_CHARS.matcher(trimmed).find()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        field + " must not contain control characters");
            }
            return trimmed;
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, field + " is required");
            }
            return value.trim();
        }
    }

    /**
     * A registered platform {@code CLIENT} the staff member linked the booking to.
     *
     * <p><b>Phase 22.3, deferred.</b> Nothing constructs this today and
     * {@code StaffBookingService} rejects it with {@code 501}; 22.3 lights it up additively by
     * replacing that switch arm with a resolver call.
     *
     * <h2>HARD PRECONDITION on whoever writes that resolver (security LOW, 2026-08-18)</h2>
     * This variant is a {@code UUID} that names a real person's account, handed in by a staff user.
     * The typed variant plus the "replace the switch arm with a resolver call" note above make
     * <b>consent-free linking the path of least resistance</b> — a resolver that simply loads the
     * user by id would let any salon attach any platform client to a booking, which is a booking
     * created in a stranger's name, visible in their history, and notified to their device.
     *
     * <p>22.3's resolver therefore <b>MUST</b>:
     * <ul>
     *   <li>require a <b>pre-existing relationship</b> — a prior booking between this client and the
     *       commanding salon or master — <b>or</b> an explicit, recorded client consent
     *       (an accepted invitation / link request), and</li>
     *   <li><b>reject an arbitrary user id</b> that satisfies neither, with the same
     *       {@code 404}-shaped answer an unknown id gets, so the endpoint is not an oracle for
     *       "does this UUID belong to a platform client?"; and</li>
     *   <li>refuse any id whose user is not a {@code CLIENT}, so a staff user cannot book a colleague
     *       or an owner into a client slot.</li>
     * </ul>
     *
     * <p>"Fall back to a walk-in {@link Guest} when the relationship is absent" is <b>not</b> an
     * acceptable resolution either: it would silently persist the named client's PII without the
     * link, i.e. the leak without the audit trail.
     */
    record ExistingClient(UUID userId) implements StaffClientRef {
        public ExistingClient {
            // 400, not an NPE → 500 catch-all. Same reasoning as Guest above and StaffBookingCommand.
            if (userId == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "Client id is required");
            }
        }
    }
}
