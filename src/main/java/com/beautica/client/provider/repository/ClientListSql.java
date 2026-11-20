package com.beautica.client.provider.repository;

/**
 * B507 — the shared text blocks of the provider client list (D1, CLI-39..41). Three scope variants differ ONLY in
 * {@link #SALON_WHERE} / {@link #SALON_MASTER_WHERE} / {@link #MASTER_WHERE}; there is no nullable-UUID OR bind.
 *
 * <p>Identity: a registered client is {@code client_id}; a guest is the raw E.164 {@code guest_phone} of rows with
 * {@code client_id IS NULL}. The {@code CASE} keeps a registered row's (always null) phone out of the grouping key and
 * the {@code guest_pii_anonymised_at IS NULL} conjunct drops anonymised guests (GST-51); a detached account (CLI-24)
 * has neither a client_id nor a phone and falls out of the first filter. {@code :now} is the injected Clock's instant.
 *
 * <p>{@code :q} is passed already trimmed with {@code % _ \} escaped (ESCAPE '\'), or NULL for no filter.
 */
final class ClientListSql {

    private ClientListSql() {
    }

    private static final String SCOPED_SELECT = """
            WITH scoped AS (
              SELECT b.id, COALESCE(b.appointment_id, b.id) AS visit_key, b.client_id,
                     CASE WHEN b.client_id IS NULL THEN b.guest_phone END AS guest_phone,
                     b.guest_name, b.guest_surname, b.status, b.starts_at
                FROM bookings b
            """;

    static final String SALON_WHERE = """
               WHERE b.salon_id = :salonId
            """;

    static final String SALON_MASTER_WHERE = """
               WHERE b.salon_id = :salonId AND b.master_id = :masterId
            """;

    static final String MASTER_WHERE = """
               WHERE b.master_id = :masterId
            """;

    private static final String IDENTITY_FILTER_AND_GROUP = """
                 AND (b.client_id IS NOT NULL
                      OR (b.guest_phone IS NOT NULL AND b.guest_pii_anonymised_at IS NULL))
            ), grouped AS (
              SELECT client_id, guest_phone,
                     MAX(starts_at) AS last_activity_at,
                     MAX(starts_at) FILTER (WHERE starts_at <= :now AND status IN ('COMPLETED','CONFIRMED')) AS last_visit_at,
                     MIN(starts_at) FILTER (WHERE starts_at >  :now AND status = 'CONFIRMED') AS next_visit_at,
                     COUNT(DISTINCT visit_key) FILTER (WHERE status = 'COMPLETED') AS completed_visits,
                     (ARRAY_AGG(id ORDER BY starts_at DESC, id DESC))[1] AS anchor_booking_id,
                     (ARRAY_AGG(guest_name ORDER BY starts_at DESC)
                          FILTER (WHERE guest_name IS NOT NULL))[1] AS guest_name,
                     (ARRAY_AGG(guest_surname ORDER BY starts_at DESC)
                          FILTER (WHERE guest_surname IS NOT NULL))[1] AS guest_surname
                FROM scoped
               GROUP BY client_id, guest_phone
            )
            """;

    private static final String NAME_FILTER = """
             WHERE (CAST(:q AS text) IS NULL
                    OR concat_ws(' ', COALESCE(u.first_name, g.guest_name), COALESCE(u.last_name, g.guest_surname))
                       ILIKE '%' || CAST(:q AS text) || '%' ESCAPE '\\')
            """;

    private static final String PAGE_SELECT = """
            SELECT g.client_id AS clientId, g.guest_phone AS guestPhone, g.last_visit_at AS lastVisitAt,
                   g.next_visit_at AS nextVisitAt, g.completed_visits AS completedVisits,
                   g.anchor_booking_id AS anchorBookingId, g.guest_name AS guestName,
                   g.guest_surname AS guestSurname, u.first_name AS firstName, u.last_name AS lastName,
                   u.avatar_url AS avatarUrl, u.avg_rating AS avgRating, u.review_count AS reviewCount
              FROM grouped g
              LEFT JOIN users u ON u.id = g.client_id
            """;

    private static final String PAGE_TAIL = """
             ORDER BY g.last_activity_at DESC, g.anchor_booking_id DESC
             LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT_SELECT = """
            SELECT COUNT(*)
              FROM grouped g
              LEFT JOIN users u ON u.id = g.client_id
            """;

    // Compile-time constants (annotation values): plain concatenation, no helper method.
    static final String PAGE_SALON =
            SCOPED_SELECT + SALON_WHERE + IDENTITY_FILTER_AND_GROUP + PAGE_SELECT + NAME_FILTER + PAGE_TAIL;
    static final String PAGE_SALON_MASTER =
            SCOPED_SELECT + SALON_MASTER_WHERE + IDENTITY_FILTER_AND_GROUP + PAGE_SELECT + NAME_FILTER + PAGE_TAIL;
    static final String PAGE_MASTER =
            SCOPED_SELECT + MASTER_WHERE + IDENTITY_FILTER_AND_GROUP + PAGE_SELECT + NAME_FILTER + PAGE_TAIL;
    static final String COUNT_SALON =
            SCOPED_SELECT + SALON_WHERE + IDENTITY_FILTER_AND_GROUP + COUNT_SELECT + NAME_FILTER;
    static final String COUNT_SALON_MASTER =
            SCOPED_SELECT + SALON_MASTER_WHERE + IDENTITY_FILTER_AND_GROUP + COUNT_SELECT + NAME_FILTER;
    static final String COUNT_MASTER =
            SCOPED_SELECT + MASTER_WHERE + IDENTITY_FILTER_AND_GROUP + COUNT_SELECT + NAME_FILTER;
}
