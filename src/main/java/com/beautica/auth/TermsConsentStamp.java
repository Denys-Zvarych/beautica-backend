package com.beautica.auth;

import com.beautica.common.validation.TermsConsentPayload;
import com.beautica.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Phase 442 — the single write path for account-creation consent, shared by {@code AuthService.register*} and
 * {@code InviteService.acceptInvite}. The request was already checked by {@code @TermsConsentComplete}, so it
 * carries either the full triple or nothing (legacy build, tolerant window). The timestamp is always the
 * server's; the versions are stored verbatim. Logs the role only — never the email or the versions.
 */
final class TermsConsentStamp {

    private static final Logger log = LoggerFactory.getLogger(TermsConsentStamp.class);

    private TermsConsentStamp() {
    }

    static void apply(User user, TermsConsentPayload consent, Instant now) {
        if (Boolean.TRUE.equals(consent.termsAccepted())) {
            user.recordTermsConsent(now, consent.termsVersion(), consent.privacyPolicyVersion());
            return;
        }
        log.info("register.consent.legacy role={}", user.getRole());
    }
}
