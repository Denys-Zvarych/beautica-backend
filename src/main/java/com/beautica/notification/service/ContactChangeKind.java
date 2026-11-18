package com.beautica.notification.service;

/** B516 (AUTH-D19) — which account contact a security-notice email is about; selects its subject and copy. */
public enum ContactChangeKind {
    /** The verified phone was changed (sent to the account's confirmed email). */
    PHONE,
    /** The notifications email was changed (sent to the PREVIOUS confirmed email). */
    EMAIL
}
