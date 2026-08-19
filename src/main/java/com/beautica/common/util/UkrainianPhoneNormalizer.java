package com.beautica.common.util;

import com.beautica.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.regex.Pattern;

/**
 * Normalises a human-typed Ukrainian phone number to E.164 ({@code +380XXXXXXXXX}).
 *
 * <p><b>Why this exists (Phase 22.2, carried forward from 22.1).</b> {@code bookings.guest_phone}
 * is constrained at the DATABASE level by {@code chk_bookings_guest_phone_format} (V89) to
 * {@code ^\+[0-9]{6,18}$}. Every pre-22.2 producer of that column was already E.164 by
 * construction — the guest/LINK path reads the phone out of a guest JWT minted by
 * {@code PhoneOtpService}, which only ever accepts {@code +380XXXXXXXXX}. The STAFF walk-in path is
 * the first one where a HUMAN types the number into a form, so {@code 050 123 45 67} would reach
 * the insert verbatim and be rejected by the CHECK as a {@code DataIntegrityViolationException}
 * (a 500), not stored. Staff-entered phones are therefore normalised here, in the service layer,
 * before the insert.
 *
 * <p><b>No pre-existing normaliser was reused because none existed.</b> The two places that touch
 * Ukrainian phone numbers only ever VALIDATE an already-normalised string and never convert one:
 * {@code PhoneOtpService.E164_UA} ({@code \+380[0-9]{9}}) and its DTO
 * {@code PhoneOtpSendRequest}'s identical {@code @Pattern}; {@code TurbosmsService} documents its
 * parameter as "already E.164" and passes it straight through. {@code RegisterRequest.phoneNumber}
 * has a loose {@code @Pattern} and is stored verbatim on {@code users}, which carries no format
 * CHECK at all — so it is not a normalisation precedent either.
 *
 * <p><b>Output shape is deliberately narrower than the DB CHECK.</b> The CHECK admits any E.164
 * number, but this normaliser emits {@code +380} + 9 subscriber digits and rejects everything else,
 * matching {@code PhoneOtpService}'s existing UA-only contract and Turbosms' (UA) delivery reach —
 * so a walk-in phone is the same shape as every guest phone already in the table, and Phase 22.7's
 * walk-in SMS can dial it. A foreign number is a deliberate, flagged limitation, not an oversight.
 *
 * <p><b>Accepted inputs</b> (separators {@code space}, NBSP, {@code ( ) - .} are stripped anywhere):
 * <pre>
 *   +380501234567   380501234567   00380501234567
 *   0501234567      501234567
 *   +38 (050) 123-45-67
 * </pre>
 * A national-trunk {@code 0} prefix and a bare 9-digit subscriber number are accepted ONLY without
 * an explicit {@code +}: {@code +0501234567} names country code 0, which does not exist.
 *
 * <p>Every rejection is one generic {@code 400 "Invalid phone format"} — byte-identical to
 * {@code PhoneOtpService}'s message, so the two phone entry points expose no distinguishing oracle.
 */
public final class UkrainianPhoneNormalizer {

    /** UA country code, without the leading {@code +}. */
    private static final String UA_COUNTRY_CODE = "380";

    /** Digits in a Ukrainian subscriber number, after the country/trunk prefix ({@code 501234567}). */
    private static final int SUBSCRIBER_DIGITS = 9;

    /** {@code 380} + {@link #SUBSCRIBER_DIGITS}. */
    private static final int COUNTRY_PREFIXED_DIGITS = UA_COUNTRY_CODE.length() + SUBSCRIBER_DIGITS;

    /** National form: trunk {@code 0} + {@link #SUBSCRIBER_DIGITS}. */
    private static final int NATIONAL_DIGITS = 1 + SUBSCRIBER_DIGITS;

    /**
     * Upper bound on the raw string before any scanning. Well above the longest legitimate input
     * ({@code +38 (050) 123-45-67} is 20 chars) and far below anything worth a regex pass — a cheap
     * ceiling so a hostile 1 MB "phone" is rejected without being walked.
     */
    private static final int MAX_RAW_LENGTH = 32;

    /** Formatting characters a human may type; stripped anywhere in the string. */
    private static final Pattern SEPARATORS = Pattern.compile("[ \\u00A0\\u202F()\\-.]");

    private static final Pattern DIGITS_ONLY = Pattern.compile("[0-9]+");

    private static final String INVALID = "Invalid phone format";

    private UkrainianPhoneNormalizer() {
    }

    /**
     * @param raw a staff-entered phone in any of the accepted forms above
     * @return the number as {@code +380XXXXXXXXX}
     * @throws BusinessException {@code 400 "Invalid phone format"} for null, blank, over-long,
     *                           non-Ukrainian or otherwise unparseable input. The message never
     *                           echoes the input back (PII).
     */
    public static String toE164(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_RAW_LENGTH) {
            throw invalid();
        }
        String compact = SEPARATORS.matcher(raw.trim()).replaceAll("");
        // International-access prefix: 00 is the ITU alias for '+'. Normalised first so the two
        // spellings converge before the country-code branch below.
        if (compact.startsWith("00")) {
            compact = "+" + compact.substring(2);
        }
        boolean explicitPlus = compact.startsWith("+");
        String digits = explicitPlus ? compact.substring(1) : compact;
        if (digits.isEmpty() || !DIGITS_ONLY.matcher(digits).matches()) {
            throw invalid();
        }
        return "+" + UA_COUNTRY_CODE + subscriberOf(digits, explicitPlus);
    }

    /**
     * The 9 subscriber digits, from whichever of the three accepted spellings was supplied.
     *
     * <p>The trunk-{@code 0} and bare-subscriber forms are national notation and are therefore
     * refused when the caller wrote an explicit {@code +}: {@code +0501234567} and {@code +501234567}
     * name country codes {@code 0} and {@code 5}, neither of which is Ukraine — silently reading
     * them as UA numbers would turn a typo into a wrong-number SMS in 22.7.
     */
    private static String subscriberOf(String digits, boolean explicitPlus) {
        if (digits.length() == COUNTRY_PREFIXED_DIGITS && digits.startsWith(UA_COUNTRY_CODE)) {
            return digits.substring(UA_COUNTRY_CODE.length());
        }
        if (!explicitPlus && digits.length() == NATIONAL_DIGITS && digits.startsWith("0")) {
            return digits.substring(1);
        }
        if (!explicitPlus && digits.length() == SUBSCRIBER_DIGITS) {
            return digits;
        }
        throw invalid();
    }

    private static BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, INVALID);
    }
}
