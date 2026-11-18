package com.beautica.common.util;

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
