package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Typed invite-token failure, replacing the generic {@link BusinessException} /
 * {@link NotFoundException} throws in {@code InviteService#previewInvite} and
 * {@code InviteService#acceptInvite} (phase 285).
 *
 * <p>Unlike {@link VerificationException}, which always answers 400, the invite-token failure
 * space genuinely spans multiple statuses: {@code previewInvite}'s "token not found" is 400
 * ({@code BusinessException}, historical) while {@code acceptInvite}'s "token not found" is 404
 * ({@code NotFoundException}, historical) — a deliberate asymmetry phase 285 preserves rather than
 * "tidying" (see the phase doc's backward-compatibility gate; changing either status to make the
 * table symmetrical would break every existing client and test). {@code INVITE_SALON_INACTIVE} is
 * 409 (phase 286). So each {@link Code} constant carries its own default {@link HttpStatus} via
 * the two-arg constructor, and the three-arg constructor lets a throw site override it for the one
 * code ({@code INVITE_NOT_FOUND}) whose status is call-site-dependent rather than code-dependent.
 *
 * <p>{@code EMAIL_ALREADY_REGISTERED} is deliberately NOT a {@link Code} here. That 409 already
 * has its own type ({@link EmailAlreadyRegisteredException}) and handler
 * ({@code GlobalExceptionHandler#handleEmailAlreadyRegistered}) — {@code acceptInvite}'s
 * duplicate-email branch throws that existing exception directly instead of minting a second
 * spelling of the same wire code (phase 285 decision: one code, one handler, no divergence).
 */
public class InviteTokenException extends BusinessException {

    public enum Code {
        INVITE_NOT_FOUND(HttpStatus.BAD_REQUEST),
        INVITE_EXPIRED(HttpStatus.BAD_REQUEST),
        INVITE_USED(HttpStatus.BAD_REQUEST),
        INVITE_REVOKED(HttpStatus.BAD_REQUEST),
        INVITE_SALON_INACTIVE(HttpStatus.CONFLICT);

        private final HttpStatus defaultStatus;

        Code(HttpStatus defaultStatus) {
            this.defaultStatus = defaultStatus;
        }

        public HttpStatus getDefaultStatus() {
            return defaultStatus;
        }
    }

    private final Code code;

    /** Uses {@link Code#getDefaultStatus()} — every throw site except accept's not-found case. */
    public InviteTokenException(Code code, String message) {
        this(code, code.getDefaultStatus(), message);
    }

    /**
     * Overrides the code's default status. Used only by {@code acceptInvite}'s
     * {@code INVITE_NOT_FOUND} throw, which must stay 404 — see the class javadoc.
     */
    public InviteTokenException(Code code, HttpStatus status, String message) {
        super(status, message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }

    /**
     * Stack-trace capture is suppressed — like its siblings in this ordering block
     * ({@link EmailAlreadyRegisteredException}, {@link ClientBookingConflictException},
     * {@link BookingElapsedException}, {@link DuplicateServiceException}), this is a flow-control
     * exception translated directly to an HTTP response; the full trace is never logged or
     * inspected, so the per-throw allocation cost is pure waste.
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
