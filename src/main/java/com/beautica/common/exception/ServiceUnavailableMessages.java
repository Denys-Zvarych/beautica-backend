package com.beautica.common.exception;

import java.util.Set;

/**
 * Fixed, non-sensitive client-facing strings for {@code 503} {@link BusinessException}s.
 * {@link GlobalExceptionHandler} echoes a 503 message only when it is in {@link #ALLOWED};
 * any other 503 message is replaced by {@link #GENERIC}. Throw sites must use these constants so
 * the allow-list and the strings never drift apart.
 */
public final class ServiceUnavailableMessages {

    public static final String MEDIA_STORAGE_NOT_CONFIGURED = "Media storage is not configured";
    public static final String SERVICE_SETUP_BUSY = "Service setup is busy for this master, please retry";
    public static final String SUPPORT_NOT_CONFIGURED = "Support channel is not configured";
    public static final String OTP_SEND_FAILED = "Could not send the verification code";

    /** Returned for any 503 whose message is not on the allow-list. */
    public static final String GENERIC = "Service temporarily unavailable";

    public static final Set<String> ALLOWED = Set.of(
            MEDIA_STORAGE_NOT_CONFIGURED,
            SERVICE_SETUP_BUSY,
            SUPPORT_NOT_CONFIGURED,
            OTP_SEND_FAILED);

    private ServiceUnavailableMessages() {
    }

    /** The message to show a client: the original when allow-listed, otherwise {@link #GENERIC}. */
    public static String safe(String message) {
        return message != null && ALLOWED.contains(message) ? message : GENERIC;
    }
}
