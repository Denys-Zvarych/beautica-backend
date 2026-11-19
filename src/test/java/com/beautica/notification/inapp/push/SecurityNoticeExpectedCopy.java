package com.beautica.notification.inapp.push;

/**
 * The ONE shared expected-copy fixture for the B516 (AUTH-D19) account security notice, per channel: push title + body
 * and the email subject + support line. The masked value never reaches the lock screen (NTF-D01). Tests take expected copy from here, never a re-typed literal, so a copy change
 * breaks in one place. Values are written out on purpose (not read from the renderers): an assertion against the
 * renderer's own constant would pass whatever the renderer says. Titles mirror the mobile ARB (M517).
 */
public final class SecurityNoticeExpectedCopy {

    /** Push / feed title of {@code SECURITY_PHONE_CHANGED}. */
    public static final String PUSH_TITLE_PHONE = "Номер телефону змінено";

    /** Push / feed title of {@code SECURITY_EMAIL_CHANGED}. */
    public static final String PUSH_TITLE_EMAIL = "Email для сповіщень змінено";

    /** The AUTH-D19 line closing every channel's notice. */
    public static final String SUPPORT_LINE = "Якщо це не ви — зверніться в підтримку.";

    /** Push / feed title of {@code SECURITY_PASSWORD_RESET} (AUTH-D26 a). */
    public static final String PUSH_TITLE_PASSWORD = "Пароль змінено";

    /** Lock-screen body of {@code SECURITY_PASSWORD_RESET}: it names no contact, so the full sentence fits. */
    public static final String PUSH_BODY_PASSWORD =
            "Пароль до вашого акаунта змінено. Якщо це не ви — зверніться в підтримку.";

    /** Email subject after a password reset (sent to the confirmed email). */
    public static final String EMAIL_SUBJECT_PASSWORD = "Beautica: пароль змінено";

    /** Email subject after a phone change (sent to the confirmed email). */
    public static final String EMAIL_SUBJECT_PHONE = "Beautica: номер телефону змінено";

    /** Email subject after an email change (sent to the PREVIOUS confirmed email). */
    public static final String EMAIL_SUBJECT_EMAIL = "Beautica: email для сповіщень змінено";

    private SecurityNoticeExpectedCopy() {
    }

    /** Lock-screen body of both types: the support line only (NTF-D01 — no phone / email, masked or not). */
    public static final String PUSH_BODY = SUPPORT_LINE;
}
