package com.beautica.moderation.dto;

import com.beautica.moderation.repository.ClientBlockListRow;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 397 — one row of {@code GET /api/v1/blocks} (also the {@code POST} body). Never carries a raw phone, the
 * phone hash or the blocked user's id.
 *
 * <p>{@code maskedPhone} is populated only for a {@code GUEST} row, whose phone the provider already sees on the
 * booking (phase 386). A registered client's phone never reaches a provider, not even its last digits.
 */
public record BlockResponse(
        UUID id,
        BlockKind kind,
        String displayName,
        @Schema(types = {"string", "null"}, nullable = true,
                description = "Live avatar of the blocked account. CLIENT rows only; null for GUEST.")
        String avatarUrl,
        @Schema(types = {"string", "null"}, nullable = true, example = "+380 •• ••• 12 34",
                description = "Masked guest phone, last four digits only. GUEST rows only; null for CLIENT.")
        String maskedPhone,
        Instant createdAt
) {

    static final String MASK_PREFIX = "+380 •• ••• ";

    /** From the list projection — the live account name wins over the snapshot for a CLIENT row. */
    public static BlockResponse from(ClientBlockListRow row) {
        if (row.isRegisteredClient()) {
            String liveName = BlockDisplayNames.shortName(row.userFirstName(), row.userLastName());
            return new BlockResponse(row.id(), BlockKind.CLIENT,
                    liveName != null ? liveName : row.displayName(),
                    row.userAvatarUrl(), null, row.createdAt());
        }
        return new BlockResponse(row.id(), BlockKind.GUEST, row.displayName(), null,
                maskPhone(row.phoneLast4()), row.createdAt());
    }

    /** «+380 •• ••• 12 34» from the last four digits; null when unknown. */
    public static String maskPhone(String last4) {
        if (last4 == null || last4.length() != 4) {
            return null;
        }
        return MASK_PREFIX + last4.substring(0, 2) + " " + last4.substring(2);
    }
}
