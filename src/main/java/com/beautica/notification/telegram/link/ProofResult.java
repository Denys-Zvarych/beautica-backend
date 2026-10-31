package com.beautica.notification.telegram.link;

/** Phase 405 D7 — outcome of {@link TelegramLinkService#proveContact}. */
public enum ProofResult {
    LINKED,
    /** Phone differed or did not parse; the token is terminal. */
    MISMATCH,
    /** A forwarded contact card (or no Telegram user id on it); the token stays claimed. */
    NOT_OWN_CONTACT,
    /** This chat holds no unexpired claimed token. */
    NO_PENDING
}
