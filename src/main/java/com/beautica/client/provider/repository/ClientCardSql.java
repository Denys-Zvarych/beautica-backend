package com.beautica.client.provider.repository;

import static com.beautica.client.provider.repository.ClientListSql.MASTER_WHERE;
import static com.beautica.client.provider.repository.ClientListSql.SALON_MASTER_WHERE;
import static com.beautica.client.provider.repository.ClientListSql.SALON_WHERE;

import com.beautica.review.repository.GuestRatingSql;

/**
 * B508 — the native statements behind the provider client card and visit history (CLI-42..45). The scope variants
 * reuse B507's {@link ClientListSql} WHERE blocks verbatim (no nullable-UUID OR bind); the identity variants are:
 * <ul>
 *   <li><b>user</b>: {@code client_id = :userId};</li>
 *   <li><b>guest</b>: the anchor booking ({@code :bookingId}) must itself be in scope, {@code client_id IS NULL},
 *       carry a phone and not be anonymised (GST-51) — its {@code guest_phone} (read once by the {@code anchor} CTE)
 *       then selects "all scope bookings with that phone and {@code client_id IS NULL}", served by
 *       {@code idx_bookings_guest_phone_open} (V199; V263 dropped V259's identical copy). The
 *       {@code client_detached_at IS NULL} term is a no-op by {@code chk_bookings_guest_fields} (a detached row has no
 *       phone) and exists only so the predicate implies that partial index's. An invalid anchor yields an empty {@code scoped} set, which the service turns
 *       into the one uniform 404.</li>
 * </ul>
 * A visit is {@code COALESCE(appointment_id, id)} over the rows in scope (a SALON_MASTER's scope holds only their own
 * rows of a multi-master visit). {@code :now} is the injected Clock's instant. Three counts only (D3).
 */
final class ClientCardSql {

    private ClientCardSql() {
    }

    private static final String SCOPED_COLS = """
            SELECT b.id, COALESCE(b.appointment_id, b.id) AS visit_key, b.status, b.starts_at,
                   b.guest_name, b.guest_surname, b.master_id
              FROM bookings b
            """;

    // ── identity: registered user ──
    private static final String USER_OPEN = "WITH scoped AS (" + SCOPED_COLS;
    private static final String USER_CLOSE = "   AND b.client_id = :userId)\n";

    // ── identity: guest by anchor booking ──
    private static final String GUEST_OPEN_ANCHOR = "WITH anchor AS (SELECT b.guest_phone FROM bookings b\n";
    private static final String GUEST_ANCHOR_END = """
               AND b.id = :bookingId AND b.client_id IS NULL AND b.guest_phone IS NOT NULL
               AND b.guest_pii_anonymised_at IS NULL),
            """;
    private static final String GUEST_CLOSE_ANCHOR = GUEST_ANCHOR_END + "scoped AS (" + SCOPED_COLS;
    private static final String GUEST_CLOSE = """
               AND b.client_id IS NULL AND b.guest_phone = (SELECT guest_phone FROM anchor)
               AND b.guest_pii_anonymised_at IS NULL AND b.client_detached_at IS NULL)
            """;

    // ── SALON scope narrowed by ?masterId= (owner/admin master mode): the identity rows are read salon-wide into
    //    `salon_scoped` (the chip row's source) and `scoped` narrows them to the master, so the card aggregate sees
    //    exactly the rows SALON_MASTER_WHERE selects. The guest anchor is checked SALON-wide (CLI-74: the list's
    //    guestBookingId is the latest salon booking, any master; owner/admin see the salon anyway, no new disclosure);
    //    an anchor outside the salon is still the uniform 404, and a guest with no booking of that master is too
    //    (empty `scoped`), exactly like the registered variant. ──
    private static final String NARROW_TO_MASTER = """
            , scoped AS (SELECT id, visit_key, status, starts_at, guest_name, guest_surname, master_id
                           FROM salon_scoped WHERE master_id = :masterId)
            """;
    private static final String USER_OPEN_SALON_WIDE = "WITH salon_scoped AS (" + SCOPED_COLS;
    private static final String GUEST_SALON_WIDE = GUEST_ANCHOR_END + "salon_scoped AS (" + SCOPED_COLS;

    // ── card ──
    // The anchor = the newest scope row; the guest name + surname come from ONE row, the newest that carries a name
    // (never a mixed «Марія Петренко»). Two top-1 reads of the (single-client, multiply-referenced hence materialised)
    // `scoped` CTE instead of three per-aggregate array builds.
    // B530 (CLI-89): nextVisitKey = the visit of the earliest CONFIRMED row starting after :now (tie: lowest booking
    // id, the nextVisitAt order); a scalar top-1 over the same `scoped` CTE, so ?masterId= narrowing holds.
    private static final String CARD_AGG = """
            SELECT agg.*, lt.id AS anchorBookingId, nm.guest_name AS guestName, nm.guest_surname AS guestSurname,
                   (SELECT s2.visit_key FROM scoped s2
                     WHERE s2.status = 'CONFIRMED' AND s2.starts_at > :now
                     ORDER BY s2.starts_at, s2.id LIMIT 1) AS nextVisitKey
              FROM (SELECT COUNT(DISTINCT visit_key) AS totalVisits,
                           COUNT(DISTINCT visit_key) FILTER (WHERE status = 'COMPLETED') AS completedVisits,
                           COUNT(DISTINCT visit_key) FILTER (WHERE status = 'CONFIRMED' AND starts_at > :now)
                               AS upcomingVisits,
                           MIN(starts_at) AS firstVisitAt,
                           MAX(starts_at) FILTER (WHERE starts_at <= :now AND status IN ('COMPLETED','CONFIRMED'))
                               AS lastVisitAt,
                           MIN(starts_at) FILTER (WHERE starts_at >  :now AND status = 'CONFIRMED') AS nextVisitAt
                      FROM scoped) agg
              LEFT JOIN LATERAL (SELECT id FROM scoped ORDER BY starts_at DESC, id DESC LIMIT 1) lt ON TRUE
              LEFT JOIN LATERAL (SELECT guest_name, guest_surname FROM scoped
                                  WHERE guest_name IS NOT NULL OR guest_surname IS NOT NULL
                                  ORDER BY starts_at DESC, id DESC LIMIT 1) nm ON TRUE
            """;

    // ── served-by masters (CLI-73), one extra column of the SALON card statements (no statement of its own: it
    //    reads the identity CTE the aggregate already reads; referenced twice, Postgres materialises it once).
    //    Salon-wide whatever ?masterId= (source `salon_scoped` in master mode). Only masters still on this salon's
    //    roster (m.salon_id) — the membership ProviderClientScopeResolver accepts for ?masterId=, so every chip is a
    //    filter that resolves. Name = the visit items' rule (live account, else the V157 detach snapshot); avatar only
    //    from a live account. Any booking status counts as "served" (the card's totalVisits rule). JSON text, most
    //    recent booking first then master id; '[]' when none. Bounded by the roster; LIMIT is a backstop. ──
    static final int SERVED_BY_MASTERS_LIMIT = 50;

    private static final String SERVED_HEAD = """
            (SELECT CAST(COALESCE(json_agg(json_build_object('masterId', c.master_id, 'firstName', c.first_name,
                                                            'lastName', c.last_name, 'avatarUrl', c.avatar_url)
                                           ORDER BY c.last_at DESC, c.master_id), '[]') AS text)
                      FROM (SELECT m.id AS master_id, COALESCE(mu.first_name, m.detached_first_name) AS first_name,
                                   COALESCE(mu.last_name, m.detached_last_name) AS last_name,
                                   mu.avatar_url AS avatar_url, s.last_at
                              FROM (SELECT master_id, MAX(starts_at) AS last_at FROM\s""";
    private static final String SERVED_TAIL = " " + """
             GROUP BY master_id) s
                              JOIN masters m ON m.id = s.master_id AND m.salon_id = :salonId
                              LEFT JOIN users mu ON mu.id = m.user_id
                             ORDER BY s.last_at DESC, m.id
                             LIMIT\s""" + SERVED_BY_MASTERS_LIMIT + ") c) AS servedByMasters";
    private static final String SERVED_FROM_SCOPED = SERVED_HEAD + "scoped" + SERVED_TAIL;
    private static final String SERVED_FROM_SALON_SCOPED = SERVED_HEAD + "salon_scoped" + SERVED_TAIL;
    private static final String SERVED_NONE = "CAST(NULL AS text) AS servedByMasters";

    private static final String CARD_GUEST_HEAD = "SELECT a.*, (SELECT guest_phone FROM anchor) AS guestPhone, "
            + """
            CAST(NULL AS text) AS verifiedPhone, CAST(NULL AS text) AS firstName,
                   CAST(NULL AS text) AS lastName, CAST(NULL AS text) AS avatarUrl,
                   gr.avg_rating AS avgRating, gr.review_count AS reviewCount,
                   """;
    // B546 (CLI-43, REV-36) — the guest's cross-provider rating rides the card statement: the shared
    // GuestRatingSql aggregate keyed on the anchor's phone (always one row; NULL / 0 when unrated or the anchor is
    // invalid, which the service turns into the uniform 404 anyway).
    private static final String CARD_GUEST_FROM = "\n  FROM (" + CARD_AGG + ") a\n"
            + "  CROSS JOIN LATERAL (" + GuestRatingSql.AGGREGATE
            + "   AND cr.subject_guest_phone = (SELECT guest_phone FROM anchor)) gr\n";

    // B518 (GST-80): the registered phone rides the existing users join (no extra statement) and is NULL unless the
    // number is SMS-verified (V244 phone_verified_at) - the unverified number never leaves the database.
    private static final String CARD_USER_HEAD = "SELECT a.*, CAST(NULL AS text) AS guestPhone, "
            + """
            CASE WHEN u.phone_verified_at IS NOT NULL THEN u.phone_number END AS verifiedPhone,
                   u.first_name AS firstName, u.last_name AS lastName, u.avatar_url AS avatarUrl,
                   u.avg_rating AS avgRating, u.review_count AS reviewCount,
                   """;
    private static final String CARD_USER_FROM = "\n  FROM (" + CARD_AGG + """
            ) a
              LEFT JOIN users u ON u.id = :userId
            """;

    static final String CARD_USER_SALON =
            USER_OPEN + SALON_WHERE + USER_CLOSE + CARD_USER_HEAD + SERVED_FROM_SCOPED + CARD_USER_FROM;
    static final String CARD_USER_SALON_MASTER = USER_OPEN_SALON_WIDE + SALON_WHERE + USER_CLOSE + NARROW_TO_MASTER
            + CARD_USER_HEAD + SERVED_FROM_SALON_SCOPED + CARD_USER_FROM;
    static final String CARD_USER_MASTER =
            USER_OPEN + MASTER_WHERE + USER_CLOSE + CARD_USER_HEAD + SERVED_NONE + CARD_USER_FROM;

    static final String CARD_GUEST_SALON = GUEST_OPEN_ANCHOR + SALON_WHERE + GUEST_CLOSE_ANCHOR + SALON_WHERE
            + GUEST_CLOSE + CARD_GUEST_HEAD + SERVED_FROM_SCOPED + CARD_GUEST_FROM;
    static final String CARD_GUEST_SALON_MASTER = GUEST_OPEN_ANCHOR + SALON_WHERE + GUEST_SALON_WIDE
            + SALON_WHERE + GUEST_CLOSE + NARROW_TO_MASTER + CARD_GUEST_HEAD + SERVED_FROM_SALON_SCOPED
            + CARD_GUEST_FROM;
    static final String CARD_GUEST_MASTER = GUEST_OPEN_ANCHOR + MASTER_WHERE + GUEST_CLOSE_ANCHOR + MASTER_WHERE
            + GUEST_CLOSE + CARD_GUEST_HEAD + SERVED_NONE + CARD_GUEST_FROM;

    // ── visits page: one row per page entry; an empty page still returns ONE row (visitKey null) carrying the total.
    //    `grouped` is MATERIALIZED (computed exactly once) and carries COUNT(*) OVER () on every row, so the total
    //    rides on the page rows with no second aggregate pass. A page past the end has no row to carry it: only then
    //    does COALESCE reach the lazily-run InitPlan COUNT(*) over the already-materialised `grouped`. ──
    private static final String VISITS_PAGE_SELECT = """
            , grouped AS MATERIALIZED (SELECT visit_key, MIN(starts_at) AS starts_at, COUNT(*) OVER () AS total
                                         FROM scoped GROUP BY visit_key)
            SELECT COALESCE(p.total, (SELECT COUNT(*) FROM grouped)) AS total, p.visit_key AS visitKey,
                   p.starts_at AS startsAt
              FROM (SELECT 1) one
              LEFT JOIN (SELECT visit_key, starts_at, total FROM grouped
                          ORDER BY starts_at DESC, visit_key DESC LIMIT :limit OFFSET :offset) p ON TRUE
             ORDER BY p.starts_at DESC, p.visit_key DESC
            """;

    static final String VISITS_USER_SALON = USER_OPEN + SALON_WHERE + USER_CLOSE + VISITS_PAGE_SELECT;
    static final String VISITS_USER_SALON_MASTER = USER_OPEN + SALON_MASTER_WHERE + USER_CLOSE + VISITS_PAGE_SELECT;
    static final String VISITS_USER_MASTER = USER_OPEN + MASTER_WHERE + USER_CLOSE + VISITS_PAGE_SELECT;

    static final String VISITS_GUEST_SALON =
            GUEST_OPEN_ANCHOR + SALON_WHERE + GUEST_CLOSE_ANCHOR + SALON_WHERE + GUEST_CLOSE + VISITS_PAGE_SELECT;
    // Anchor salon-wide, rows narrowed to the master (see NARROW_TO_MASTER).
    static final String VISITS_GUEST_SALON_MASTER = GUEST_OPEN_ANCHOR + SALON_WHERE + GUEST_CLOSE_ANCHOR
            + SALON_MASTER_WHERE + GUEST_CLOSE + VISITS_PAGE_SELECT;
    static final String VISITS_GUEST_MASTER =
            GUEST_OPEN_ANCHOR + MASTER_WHERE + GUEST_CLOSE_ANCHOR + MASTER_WHERE + GUEST_CLOSE + VISITS_PAGE_SELECT;

    // ── visit items: the page's visit keys, in scope only. appointment_id / id are separate index paths (an OR of two
    //    indexable predicates, not COALESCE(...) IN, which would scan the whole scope). ──
    private static final String ITEMS_SELECT = """
            SELECT COALESCE(b.appointment_id, b.id) AS visitKey, b.id AS bookingId, sd.name AS serviceName,
                   b.status AS status, b.starts_at AS startsAt, b.salon_id AS salonId, b.master_id AS masterId,
                   COALESCE(mu.first_name, m.detached_first_name) AS masterFirstName,
                   COALESCE(mu.last_name, m.detached_last_name) AS masterLastName,
                   b.price_at_booking AS priceAtBooking, b.price_max_at_booking AS priceMaxAtBooking
              FROM bookings b
              JOIN master_services ms ON ms.id = b.master_service_id
              JOIN service_definitions sd ON sd.id = ms.service_def_id
              JOIN masters m ON m.id = b.master_id
              LEFT JOIN users mu ON mu.id = m.user_id
            """;
    // The anonymised guard mirrors GUEST_CLOSE: a row anonymised after the page was keyed (GST-51) can share a visit
    // (appointment_id) with an in-scope row and must not surface its placeholder identity in the items. The identity
    // (client_id / phone) is deliberately not re-bound here: the keys already come from the identity-resolved page.
    private static final String ITEMS_KEYS = """
               AND b.guest_pii_anonymised_at IS NULL
               AND (b.appointment_id IN (:visitKeys) OR b.id IN (:visitKeys))
             ORDER BY b.starts_at, b.id
            """;

    static final String ITEMS_SALON = ITEMS_SELECT + SALON_WHERE + ITEMS_KEYS;
    static final String ITEMS_SALON_MASTER = ITEMS_SELECT + SALON_MASTER_WHERE + ITEMS_KEYS;
    static final String ITEMS_MASTER = ITEMS_SELECT + MASTER_WHERE + ITEMS_KEYS;
}
