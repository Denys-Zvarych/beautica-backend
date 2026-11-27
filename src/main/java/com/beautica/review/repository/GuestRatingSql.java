package com.beautica.review.repository;

/**
 * B546 (REV-36, REV-D10, CLI-43) — the ONE computation of a guest's (phone-keyed, REV-38) rating, shared verbatim by
 * the provider client card, the provider client list and the provider booking detail, so all three always show the
 * same number and count. A guest has no stored aggregate, so it is computed on read: visible ratings only, average
 * rounded half-up to 2 places (Postgres {@code round(numeric)} rounds half away from zero; ratings are positive), the
 * scale {@code users.avg_rating} NUMERIC(3,2) stores for an account. An aggregate with no GROUP BY always yields
 * exactly one row: {@code avg_rating} NULL and {@code review_count} 0 when the phone is unrated.
 *
 * <p>The caller appends the phone predicate ({@code AND cr.subject_guest_phone = <phone>}), served by
 * {@code idx_client_reviews_subject_guest_phone} (V269). Only the number and the count leave the table — never the
 * comment, author or review id (REV-36).
 */
public final class GuestRatingSql {

    private GuestRatingSql() {
    }

    /** {@code avg_rating} NUMERIC (nullable) + {@code review_count} INTEGER; append the phone predicate. */
    public static final String AGGREGATE = """
            SELECT ROUND(CAST(AVG(cr.rating) AS numeric), 2) AS avg_rating,
                   CAST(COUNT(*) AS integer) AS review_count
              FROM client_reviews cr
             WHERE cr.moderation_status = 'VISIBLE'
            """;
}
