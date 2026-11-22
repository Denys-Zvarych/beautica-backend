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

    // The two first_value picks run over the scope rows (one sort per window, no per-group array build): `latest` =
    // the client's newest booking (the list row's anchor), `named` = the newest booking that carries a guest name, so
    // first name and surname always come from the SAME booking (never a mixed «Марія Петренко»). Both are constant per
    // partition (the default frame starts at UNBOUNDED PRECEDING), so grouping by them adds no groups.
    private static final String SCOPED_SELECT = """
            WITH scoped AS (
              SELECT b.id, COALESCE(b.appointment_id, b.id) AS visit_key, b.client_id,
                     CASE WHEN b.client_id IS NULL THEN b.guest_phone END AS guest_phone,
                     b.status, b.starts_at,
                     first_value(b.id) OVER latest AS anchor_booking_id,
                     first_value(b.guest_name) OVER named AS guest_name,
                     first_value(b.guest_surname) OVER named AS guest_surname
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
              WINDOW latest AS (PARTITION BY b.client_id, CASE WHEN b.client_id IS NULL THEN b.guest_phone END
                                ORDER BY b.starts_at DESC, b.id DESC),
                     named AS (PARTITION BY b.client_id, CASE WHEN b.client_id IS NULL THEN b.guest_phone END
                               ORDER BY (b.guest_name IS NULL AND b.guest_surname IS NULL), b.starts_at DESC, b.id DESC)
            ), grouped AS (
              SELECT client_id, guest_phone, anchor_booking_id, guest_name, guest_surname,
                     MAX(starts_at) AS last_activity_at,
                     MAX(starts_at) FILTER (WHERE starts_at <= :now AND status IN ('COMPLETED','CONFIRMED')) AS last_visit_at,
                     MIN(starts_at) FILTER (WHERE starts_at >  :now AND status = 'CONFIRMED') AS next_visit_at,
                     COUNT(DISTINCT visit_key) FILTER (WHERE status = 'COMPLETED') AS completed_visits
                FROM scoped
               GROUP BY client_id, guest_phone, anchor_booking_id, guest_name, guest_surname
            )
            """;

    // CLI-41 search: a registered row matches its ACCOUNT name, a guest row its (same-booking) guest name.
    // `filtered` is MATERIALIZED (computed once) and carries COUNT(*) OVER (): the total rides on the page rows, no
    // second aggregate statement. A page past the end has no row to carry it: only then does COALESCE reach the
    // lazily-run InitPlan COUNT(*) over the already-materialised `filtered` (same shape as ClientCardSql visits).
    private static final String FILTERED_AND_PAGE = """
            , filtered AS MATERIALIZED (
              SELECT g.client_id, g.guest_phone, g.last_activity_at, g.last_visit_at, g.next_visit_at,
                     g.completed_visits, g.anchor_booking_id, g.guest_name, g.guest_surname,
                     u.first_name, u.last_name, u.avatar_url, u.avg_rating, u.review_count,
                     CASE WHEN u.phone_verified_at IS NOT NULL THEN u.phone_number END AS verified_phone,
                     COUNT(*) OVER () AS total
                FROM grouped g
                LEFT JOIN users u ON u.id = g.client_id
               WHERE (CAST(:q AS text) IS NULL
                      OR CASE WHEN g.client_id IS NOT NULL THEN concat_ws(' ', u.first_name, u.last_name)
                              ELSE concat_ws(' ', g.guest_name, g.guest_surname) END
                         ILIKE '%' || CAST(:q AS text) || '%' ESCAPE '\\')
            )
            SELECT COALESCE(p.total, (SELECT COUNT(*) FROM filtered)) AS total,
                   p.client_id AS clientId, p.guest_phone AS guestPhone, p.last_visit_at AS lastVisitAt,
                   p.next_visit_at AS nextVisitAt, p.completed_visits AS completedVisits,
                   p.anchor_booking_id AS anchorBookingId, p.guest_name AS guestName,
                   p.guest_surname AS guestSurname, p.first_name AS firstName, p.last_name AS lastName,
                   p.avatar_url AS avatarUrl, p.avg_rating AS avgRating, p.review_count AS reviewCount,
                   p.verified_phone AS verifiedPhone
              FROM (SELECT 1) one
              LEFT JOIN (SELECT * FROM filtered
                          ORDER BY last_activity_at DESC, anchor_booking_id DESC
                          LIMIT :limit OFFSET :offset) p ON TRUE
             ORDER BY p.last_activity_at DESC, p.anchor_booking_id DESC
            """;

    // Compile-time constants (annotation values): plain concatenation, no helper method. Always >= 1 row: an empty
    // page is ONE row with anchorBookingId null carrying the total.
    static final String PAGE_SALON = SCOPED_SELECT + SALON_WHERE + IDENTITY_FILTER_AND_GROUP + FILTERED_AND_PAGE;
    static final String PAGE_SALON_MASTER =
            SCOPED_SELECT + SALON_MASTER_WHERE + IDENTITY_FILTER_AND_GROUP + FILTERED_AND_PAGE;
    static final String PAGE_MASTER = SCOPED_SELECT + MASTER_WHERE + IDENTITY_FILTER_AND_GROUP + FILTERED_AND_PAGE;
}
