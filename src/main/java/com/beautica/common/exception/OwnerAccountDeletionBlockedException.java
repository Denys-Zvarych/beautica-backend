package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase 488 (SUP-11) — a {@code SALON_OWNER}'s {@code DELETE /api/v1/users/me} was refused before any write.
 *
 * <p>Surfaces as {@code 409 Conflict} carrying the stable {@link Code} under {@code data.code}:
 * {@link GlobalExceptionHandler#handleBusiness} genericises every CONFLICT body to {@code {"data": null}},
 * so the mobile client (M489) could never tell "delete your salons first" apart from any other 409.
 * Same shape as {@link PortfolioLimitReachedException}; stack-trace capture is suppressed (flow control).
 */
public class OwnerAccountDeletionBlockedException extends BusinessException {

    /** Stable error codes echoed under {@code data.code}; the client maps them to localised copy. */
    public enum Code {
        /** The owner still has at least one ACTIVE salon (SUP-11 precondition). */
        OWNER_HAS_ACTIVE_SALONS,
        /** Another account is still linked to one of the owner's salons (unreachable after SAL-21). */
        OWNER_SALON_HAS_STAFF
    }

    private final Code code;

    public OwnerAccountDeletionBlockedException(Code code) {
        super(HttpStatus.CONFLICT, "Owner account deletion blocked: " + code.name());
        this.code = code;
    }

    public Code getCode() {
        return code;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
