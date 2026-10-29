package com.beautica.notification.inapp.dto;

import com.beautica.auth.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Display parameters resolved AT READ TIME from the referenced booking/appointment/salon/user —
 * the feed row on disk stores ids only, never a name or copy (phase 332/333). Mobile fills these
 * into an ARB-keyed template chosen by {@link NotificationResponse#type}.
 *
 * <p>Every field is nullable: a notification whose referent is gone or no longer visible to this
 * recipient (a detached client/master, a deleted salon, an admin removed from the salon) renders
 * with {@code params = null} entirely — see {@link NotificationResponse}.
 *
 * <p>Never carries a booking note ({@code clientComment}/{@code providerComment}/
 * {@code clientCancellationNote}) — booking notes are never logged and never enter a notification
 * payload (locked track-25 rule); this record has no field for one.
 *
 * @param counterpartName the client's display name for a provider recipient (their guest name for
 *                         a LINK booking, or the detached-client sentinel «Видалений клієнт»); the
 *                         salon's or master's display name for a client recipient. Null for
 *                         {@code INVITE_ACCEPTED}.
 * @param serviceName     the first service performed, in visit order. Null for
 *                         {@code INVITE_ACCEPTED}.
 * @param serviceCount     how many services the visit chained — 1 for a single-service booking;
 *                         mobile appends "+N" for anything above 1.
 * @param startsAt         the start of the (visit) booking. Null for {@code INVITE_ACCEPTED}.
 * @param salonName        the salon's name, or null for an independent-master booking or an
 *                          {@code INVITE_ACCEPTED} row.
 * @param masterName       the performing master's display name, only for a salon-staff recipient
 *                         (owner/admin) of a salon {@code BOOKING_CREATED},
 *                         {@code BOOKING_CANCELLED_BY_CLIENT} or {@code BOOKING_RESCHEDULED} who is
 *                         not themselves the performing master. Null otherwise.
 * @param subjectName      {@code INVITE_ACCEPTED} only — the new teammate's display name.
 * @param subjectRole      {@code INVITE_ACCEPTED} only — {@code SALON_ADMIN} or
 *                          {@code SALON_MASTER}.
 * @param categoryName     phase 372, {@code CATEGORY_REQUEST_*} and {@code SERVICE_TYPE_SUGGESTION_*}
 *                         only — the category's display name. Null otherwise.
 * @param decisionReason   phase 372, {@code *_REJECTED} only — the optional reason the platform admin
 *                         typed. Null on every approve, on a reject without a reason, and on every
 *                         other type. For suggestion items {@code serviceName} carries the suggested
 *                         service-type name.
 * @param contentKind      phase 415, moderation-outcome types only — which kind of content the resolved report was
 *                         about ({@code REVIEW} / {@code CLIENT_REVIEW} / {@code PORTFOLIO_PHOTO} / {@code PROFILE}),
 *                         so the client picks the copy variant. Null on every other type.
 */
public record NotificationParams(
        @Schema(types = {"string", "null"}, nullable = true) String counterpartName,
        @Schema(types = {"string", "null"}, nullable = true) String serviceName,
        int serviceCount,
        @Schema(types = {"string", "null"}, format = "date-time", nullable = true) Instant startsAt,
        @Schema(types = {"string", "null"}, nullable = true) String salonName,
        @Schema(types = {"string", "null"}, nullable = true) String masterName,
        @Schema(types = {"string", "null"}, nullable = true) String subjectName,
        @Schema(types = {"string", "null"}, nullable = true) Role subjectRole,
        @Schema(types = {"string", "null"}, nullable = true) String categoryName,
        @Schema(types = {"string", "null"}, nullable = true) String decisionReason,
        @Schema(types = {"string", "null"}, nullable = true) ModeratedContentKind contentKind
) {

    /** Pre-phase-415 shape: every item type except the moderation outcomes. */
    public NotificationParams(
            String counterpartName, String serviceName, int serviceCount, Instant startsAt,
            String salonName, String masterName, String subjectName, Role subjectRole,
            String categoryName, String decisionReason) {
        this(counterpartName, serviceName, serviceCount, startsAt, salonName, masterName,
                subjectName, subjectRole, categoryName, decisionReason, null);
    }

    /** Phase 415 — a moderation-outcome item: only {@code contentKind} is set. */
    public static NotificationParams moderationOutcome(ModeratedContentKind contentKind) {
        return new NotificationParams(null, null, 0, null, null, null, null, null, null, null, contentKind);
    }

    /** Pre-phase-372 shape: every booking/invite item, with no request-decision fields. */
    public NotificationParams(
            String counterpartName, String serviceName, int serviceCount, Instant startsAt,
            String salonName, String masterName, String subjectName, Role subjectRole) {
        this(counterpartName, serviceName, serviceCount, startsAt, salonName, masterName,
                subjectName, subjectRole, null, null, null);
    }
}
