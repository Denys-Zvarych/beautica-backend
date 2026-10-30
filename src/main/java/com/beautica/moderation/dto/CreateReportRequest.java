package com.beautica.moderation.dto;

import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code POST /api/v1/reports} body (phase 395). {@code comment} is optional except for
 * {@link ReportReason#OTHER}, which needs at least {@value #MIN_OTHER_COMMENT_LENGTH} non-blank characters —
 * enforced by {@code ContentReportService} (400).
 *
 * <p>{@code comment} rejects control characters other than {@code \n} (C0, DEL, C1) and the bidirectional
 * embedding/override/isolate characters U+202A–U+202E and U+2066–U+2069 (400): the text is shown verbatim to a
 * moderator in phase 396, where a bidi override could disguise what the reporter wrote ("Trojan Source").
 * Phase 396 also rejects the implicit direction marks LRM/RLM/ALM (U+200E, U+200F, U+061C) and the line/paragraph
 * separators U+2028/U+2029, now that the moderator queue renders the text. The same pattern guards support's
 * resolve note.
 */
public record CreateReportRequest(
        @NotNull
        ReportTargetType targetType,

        @NotNull
        UUID targetId,

        @NotNull
        ReportReason reason,

        @Schema(description = "Optional; required (min 5 characters) when reason is OTHER", maxLength = 500)
        @Size(max = MAX_COMMENT_LENGTH)
        @Pattern(regexp = COMMENT_PATTERN, message = "must not contain control or bidirectional-override characters")
        String comment
) {
    public static final int MAX_COMMENT_LENGTH = 500;
    public static final int MIN_OTHER_COMMENT_LENGTH = 5;

    /** Any text without control characters (except {@code \n}), bidi controls/marks, or U+2028/U+2029. */
    public static final String COMMENT_PATTERN =
            "^[^\\x00-\\x09\\x0B-\\x1F\\x7F-\\x9F"
                    + "\\x{061C}\\x{200E}\\x{200F}\\x{2028}\\x{2029}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}]*$";
}
