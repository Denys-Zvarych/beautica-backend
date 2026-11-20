package com.beautica.user;

import com.beautica.auth.Role;

/** B516 (AUTH-45) — role and phone-verified flag of one account, for the verified-phone gate. */
public record PhoneGateRow(Role role, boolean phoneVerified) {
}
