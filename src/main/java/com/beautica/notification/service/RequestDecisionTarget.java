package com.beautica.notification.service;

import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * Phase 491 — the live state of a decided request, re-read at drain time (D2): the name shown to the
 * requester, the admin's stored reason, and the CURRENT requester column (nulled by an account delete,
 * V65 / V76 {@code ON DELETE SET NULL}).
 *
 * @param name              the category's display name or the suggested service name
 * @param reason            {@code decision_reason}, already cleaned by {@code DecisionReason.parse}
 * @param requestedByUserId the row's current {@code requested_by_user_id}
 */
public record RequestDecisionTarget(String name, @Nullable String reason, @Nullable UUID requestedByUserId) {
}
