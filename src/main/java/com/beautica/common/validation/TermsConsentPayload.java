package com.beautica.common.validation;

/**
 * Phase 442 — the consent triple carried by every account-creating request ({@code RegisterRequest},
 * {@code RegisterIndependentMasterRequest}, {@code InviteAcceptRequest}). Checked as a whole by
 * {@link TermsConsentComplete}; each field's own format rules sit on the record components.
 */
public interface TermsConsentPayload {

    Boolean termsAccepted();

    String termsVersion();

    String privacyPolicyVersion();

    /** {@code true} when the client sent any consent field at all (a legacy build sends none). */
    default boolean hasAnyConsentField() {
        return termsAccepted() != null || termsVersion() != null || privacyPolicyVersion() != null;
    }
}
