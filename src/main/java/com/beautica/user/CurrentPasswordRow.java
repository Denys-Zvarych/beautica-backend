package com.beautica.user;

/**
 * B516 (AUTH-D19) — the column-only, NON-locking pre-read for the current-password check of a phone change / email
 * add-change: BCrypt runs on it before the row lock is taken. Never logged ({@code toString} hides the hash).
 */
public record CurrentPasswordRow(String passwordHash, boolean phoneVerified) {

    @Override
    public String toString() {
        return "CurrentPasswordRow[phoneVerified=" + phoneVerified + "]";
    }
}
