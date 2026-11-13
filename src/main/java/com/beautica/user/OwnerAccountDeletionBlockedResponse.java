package com.beautica.user;

/**
 * Phase 488 — {@code data} of the 409 for {@code OwnerAccountDeletionBlockedException}:
 * {@code {"code":"OWNER_HAS_ACTIVE_SALONS"}} or {@code {"code":"OWNER_SALON_HAS_STAFF"}}.
 */
public record OwnerAccountDeletionBlockedResponse(String code) {}
