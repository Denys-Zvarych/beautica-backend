package com.beautica.catalog.admin.dto;

import com.beautica.service.controller.DecisionReason;
import jakarta.validation.constraints.Size;

/**
 * B503 — body of {@code POST /api/v1/internal/catalog/requests/{kind}/{id}/reject}; the body itself is optional.
 *
 * <p>{@code reason} is optional free text shown to the requester in the decision e-mail. The {@code @Size} here only
 * bounds the raw payload (the email form's raw hard cap); the real rule is {@link DecisionReason#parse} in the service
 * — line breaks fold, control characters drop, blank means "no reason", and more than
 * {@value DecisionReason#MAX_LENGTH} code points (the {@code decision_reason} column) is a 400.
 */
public record RejectRequestRequest(
        @Size(max = DecisionReason.RAW_HARD_CAP) String reason) {
}
