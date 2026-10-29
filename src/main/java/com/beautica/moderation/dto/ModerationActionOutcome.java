package com.beautica.moderation.dto;

import org.springframework.http.HttpStatus;

/**
 * Phase 414 D10 — what an e-mailed moderation link did, rendered as a friendly Ukrainian page (never JSON). The HTTP
 * status is part of the contract: 200 for a done action, 410 for an unusable link, 409 when there was nothing left to
 * act on. {@link #FAILED} is the controller's last-resort page for an unexpected error (500, no details).
 */
public enum ModerationActionOutcome {
    DONE_REMOVED(HttpStatus.OK),
    DONE_DISMISSED(HttpStatus.OK),
    DONE_RESTORED(HttpStatus.OK),
    /** Unknown, expired, used or revoked link, or a stale / mismatched confirm nonce. */
    INVALID(HttpStatus.GONE),
    /** No OPEN report left on the target, or the phase-396 resolve answered 409. */
    ALREADY_RESOLVED(HttpStatus.CONFLICT),
    /** The target no longer exists (resolution {@code TARGET_GONE}). */
    CONTENT_GONE(HttpStatus.CONFLICT),
    FAILED(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ModerationActionOutcome(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    /** The success outcome for an action that resolved a live target. */
    public static ModerationActionOutcome doneFor(ModerationAction action) {
        return switch (action) {
            case REMOVE -> DONE_REMOVED;
            case DISMISS -> DONE_DISMISSED;
            case RESTORE -> DONE_RESTORED;
        };
    }
}
