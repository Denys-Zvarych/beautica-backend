package com.beautica.user.phone;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.IdentityException;
import com.beautica.common.util.UkrainianPhoneNormalizer;

/**
 * B516 (CLI-04, AUTH-44) — profile writes no longer set {@code users.phone_number}: the phone changes only through
 * {@code POST /users/me/phone/otp/send|verify}. Old app builds echo the stored phone on every profile save, so an
 * absent / blank value or the same number (in any spelling) stays a silent no-op; any other value is
 * 400 {@code PHONE_CHANGE_REQUIRES_OTP}.
 */
public final class PhoneChangeGuard {

    private PhoneChangeGuard() {
    }

    /**
     * @param stored    the account's current {@code phone_number} (may be null, may be a legacy free-text value)
     * @param requested the phone in the request body (null = not sent)
     * @throws IdentityException 400 {@code PHONE_CHANGE_REQUIRES_OTP} when {@code requested} names another number
     */
    public static void requireUnchanged(String stored, String requested) {
        if (requested == null || requested.isBlank() || sameNumber(stored, requested)) {
            return;
        }
        throw new IdentityException(IdentityException.Code.PHONE_CHANGE_REQUIRES_OTP);
    }

    private static boolean sameNumber(String stored, String requested) {
        if (stored == null) {
            return false;
        }
        if (stored.strip().equals(requested.strip())) {
            return true;
        }
        String storedE164 = normalised(stored);
        return storedE164 != null && storedE164.equals(normalised(requested));
    }

    /** {@code +380…} when the value parses as a Ukrainian number, otherwise null (never equal to anything). */
    private static String normalised(String value) {
        try {
            return UkrainianPhoneNormalizer.toE164(value);
        } catch (BusinessException notUkrainian) {
            return null;
        }
    }
}
