package com.beautica.common.util;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.IdentityException;

import java.util.Optional;
import java.util.Set;

/**
 * B514 (AUTH-D15) — a Ukrainian MOBILE number in E.164 ({@code +380} + 2-digit operator code + 7 digits).
 *
 * <p>REUSE-FIRST: parsing is {@link UkrainianPhoneNormalizer#toE164} (every human spelling it accepts —
 * {@code 0501234567}, {@code +38 (050) 123-45-67}, {@code 00380…} — is accepted here too); this class only adds the
 * operator-code check that turns "a Ukrainian number" into "a Ukrainian mobile number", so a landline
 * ({@code +38044…}) is refused before any SMS is paid for. Reused by B516 (add/change phone) and B520 (staff
 * invite by phone).
 *
 * <p>Operator codes: Kyivstar 67 68 96 97 98 77 39, Vodafone 50 66 95 99 75, lifecell 63 73 93, 3Mob 91,
 * PEOPLEnet 92, Intertelecom 94, Ukrtelecom mobile 89. Adding a newly allocated code is a one-line change here.
 */
public final class UaMobilePhone {

    private static final Set<String> MOBILE_OPERATOR_CODES = Set.of(
            "39", "50", "63", "66", "67", "68", "73", "75", "77", "89",
            "91", "92", "93", "94", "95", "96", "97", "98", "99");

    /** {@code +380} — the operator code starts right after it. */
    private static final int OPERATOR_CODE_START = 4;
    private static final int OPERATOR_CODE_END = OPERATOR_CODE_START + 2;

    private UaMobilePhone() {
    }

    /**
     * @param raw a phone in any spelling {@link UkrainianPhoneNormalizer} accepts
     * @return the {@code +380…} form when {@code raw} is a Ukrainian mobile number, otherwise empty (never throws)
     */
    public static Optional<String> parse(String raw) {
        String e164;
        try {
            e164 = UkrainianPhoneNormalizer.toE164(raw);
        } catch (BusinessException notUkrainian) {
            return Optional.empty();
        }
        String operatorCode = e164.substring(OPERATOR_CODE_START, OPERATOR_CODE_END);
        return MOBILE_OPERATOR_CODES.contains(operatorCode) ? Optional.of(e164) : Optional.empty();
    }

    /**
     * @return the {@code +380…} form
     * @throws IdentityException {@code 400 PHONE_NOT_UA_MOBILE} for anything else (the input is never echoed)
     */
    public static String require(String raw) {
        return parse(raw).orElseThrow(() -> new IdentityException(IdentityException.Code.PHONE_NOT_UA_MOBILE));
    }
}
