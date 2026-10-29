package com.beautica.review.invite.service;

import java.util.Objects;
import java.util.UUID;

/**
 * Phase 400 D4 — one invite SMS ready to go out, produced by {@link ReviewInviteSendService#claimAndPrepare} after its
 * transaction marked the row {@code SENT}. Holds the raw phone and the text carrying the raw token: the only place
 * the token exists outside the SMS itself, in memory, for one job tick. {@link #toString} redacts both.
 *
 * @param inviteId  the invite id — the only identifier that may be logged
 * @param phoneE164 recipient
 * @param text      rendered SMS body (contains the raw token)
 */
public record PreparedInviteSms(UUID inviteId, String phoneE164, String text) {

    public PreparedInviteSms {
        Objects.requireNonNull(inviteId, "inviteId");
        Objects.requireNonNull(phoneE164, "phoneE164");
        Objects.requireNonNull(text, "text");
    }

    @Override
    public String toString() {
        return "PreparedInviteSms[inviteId=" + inviteId + ", phoneE164=<redacted>, text=<redacted>]";
    }
}
