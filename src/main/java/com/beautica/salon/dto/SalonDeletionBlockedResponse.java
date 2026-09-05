package com.beautica.salon.dto;

import com.beautica.common.exception.SalonDeletionBlockedException;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for the {@code 409 SALON_DELETION_BLOCKED} returned when Phase 289's salon-scoped
 * staff-as-client safety audit finds a violation for the salon {@code DELETE /salons/{salonId}}
 * was about to scrub (Phase 290).
 *
 * <p>{@code code} carries the stable {@link SalonDeletionBlockedException#ERROR_CODE} the mobile
 * client branches on — never the top-level {@code message} string. {@code affectedStaffCount} is
 * the count of DISTINCT staff user ids implicated, not a violation-row count: a single offending
 * booking can trip more than one reference-type check (its own {@code client_id} plus any
 * review/client-review authored against it), and those are reported as ONE incident per staff
 * member, not several. No user id, role, or reference type is echoed — this 409 is a
 * "contact support" signal, not something the owner can self-resolve from the mobile app.
 *
 * <p>Reachable only from {@code GlobalExceptionHandler}, which springdoc does not scan, so this
 * type and the {@code DELETE /salons/{salonId}} endpoint both carry explicit {@code @Schema}/
 * {@code @ApiResponse} declarations — without them no model reaches {@code /api-docs} and the
 * mobile Dio client has no branch for this response.
 */
@Schema(name = "SalonDeletionBlockedResponse",
        description = "Payload under `data` of the 409 SALON_DELETION_BLOCKED response. Branch on "
                + "`code`; direct the owner to contact support rather than retrying.")
public record SalonDeletionBlockedResponse(

        @Schema(description = "Stable machine-readable error code.",
                example = SalonDeletionBlockedException.ERROR_CODE)
        String code,

        @Schema(description = "Count of DISTINCT staff accounts implicated by the audit — never a "
                + "per-violation-row count.")
        int affectedStaffCount) {

    public static SalonDeletionBlockedResponse from(SalonDeletionBlockedException ex) {
        return new SalonDeletionBlockedResponse(
                SalonDeletionBlockedException.ERROR_CODE, ex.getAffectedStaffCount());
    }
}
