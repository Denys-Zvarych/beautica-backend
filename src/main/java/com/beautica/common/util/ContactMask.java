package com.beautica.common.util;

import java.util.regex.Pattern;

/**
 * B516 (AUTH-D19) — the masked form of a phone or email shown to the account holder in a security notice (feed, push,
 * email): enough to recognise their own value, never the whole of it. Never used for logging (logs keep their own,
 * stricter masks).
 */
public final class ContactMask {

    /** Trailing phone digits left visible. */
    private static final int VISIBLE_PHONE_TAIL = 4;
    private static final String PHONE_PREFIX = "+380";
    private static final String HIDDEN = "***";
    /** Trailing digits left visible by {@link #invitePhone}. */
    private static final int INVITE_VISIBLE_TAIL = 2;
    private static final Pattern UA_E164 = Pattern.compile("^\\+380\\d{9}$");

    private ContactMask() {
    }

    /** {@code +380671234567} → {@code +380*****4567}; null / too short → {@code null}. */
    public static String phone(String e164) {
        if (e164 == null || e164.length() <= PHONE_PREFIX.length() + VISIBLE_PHONE_TAIL) {
            return null;
        }
        int hidden = e164.length() - PHONE_PREFIX.length() - VISIBLE_PHONE_TAIL;
        return PHONE_PREFIX + "*".repeat(hidden) + e164.substring(e164.length() - VISIBLE_PHONE_TAIL);
    }

    /**
     * B520 (AUTH-21, D3) — the invite preview mask, {@code +380671234545} → {@code +380 •• ••• •• 45}: only the last
     * two digits, so a forwarded join link does not reveal the invitee's number while the invitee still recognises it.
     * Anything but a {@code +380} + 9-digit number → {@code null}.
     */
    public static String invitePhone(String e164) {
        if (e164 == null || !UA_E164.matcher(e164).matches()) {
            return null;
        }
        return PHONE_PREFIX + " •• ••• •• " + e164.substring(e164.length() - INVITE_VISIBLE_TAIL);
    }

    /** {@code olena.k@gmail.com} → {@code o***@gmail.com}; null / no local part → {@code null}. */
    public static String email(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return null;
        }
        return email.charAt(0) + HIDDEN + email.substring(at);
    }
}
