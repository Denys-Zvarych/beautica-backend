package com.beautica.client.provider.repository;

import static com.beautica.client.provider.repository.ClientListSql.MASTER_WHERE;
import static com.beautica.client.provider.repository.ClientListSql.SALON_MASTER_WHERE;
import static com.beautica.client.provider.repository.ClientListSql.SALON_WHERE;

/**
 * B526 — the viewer's own visible ratings of one client (CLI-59); B546 adds the guest (phone-keyed) arm. Scope = B507's WHERE blocks verbatim;
 * {@code client_reviews.booking_id} is unique (uq_client_reviews_booking), so the join is an index read from the
 * scope's bookings. The own-ratings page selects only {@code cr.rating} — never the comment, author or review id
 * (REV-36). B565 adds the anonymous cross-provider comments page (REV-44): review id, visit month, stars, text and a
 * per-viewer {@code own} flag; never the author, salon, master, service or exact date.
 */
final class ClientRatingSql {

    private ClientRatingSql() {
    }

    /** B565 — the review-to-visit join alone; the comments page needs no service (REV-44: no service, no date). */
    private static final String FROM_REVIEWS = """
            FROM bookings b
              JOIN client_reviews cr ON cr.booking_id = b.id
            """;

    /** The one join shared by the page and the count (the three scope WHEREs + FILTER follow it). */
    private static final String FROM = FROM_REVIEWS + """
              JOIN master_services ms ON ms.id = b.master_service_id
              JOIN service_definitions sd ON sd.id = ms.service_def_id
            """;

    private static final String PAGE_SELECT =
            "SELECT b.id AS bookingId, b.starts_at AS visitAt, sd.name AS serviceName, cr.rating AS stars\n" + FROM;

    private static final String FILTER = """
               AND cr.subject_client_id = :userId AND cr.moderation_status = 'VISIBLE'
            """;

    /**
     * B546 (REV-38) — the guest arm: the viewer's scope's visible ratings keyed to the anchor's E.164 phone. The phone
     * is resolved from the anchor booking under the existence guard first (never taken from the client, D1).
     */
    private static final String FILTER_GUEST = """
               AND cr.subject_guest_phone = :phone AND cr.moderation_status = 'VISIBLE'
            """;

    private static final String PAGE_TAIL = """
             ORDER BY b.starts_at DESC, b.id DESC
             LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT_SELECT = "SELECT COUNT(*) " + FROM;

    static final String PAGE_SALON = PAGE_SELECT + SALON_WHERE + FILTER + PAGE_TAIL;
    static final String PAGE_SALON_MASTER = PAGE_SELECT + SALON_MASTER_WHERE + FILTER + PAGE_TAIL;
    static final String PAGE_MASTER = PAGE_SELECT + MASTER_WHERE + FILTER + PAGE_TAIL;

    // Existence check (D6 uniform 404): a booking of that identity in scope. The guest form mirrors the card's anchor
    // guard (ClientCardSql GUEST_CLOSE_ANCHOR): in scope, client_id IS NULL, phone present, not anonymised (GST-51).
    private static final String EXISTS_OPEN = "SELECT EXISTS (SELECT 1 FROM bookings b\n";
    private static final String EXISTS_USER_CLOSE = "   AND b.client_id = :userId)";
    private static final String GUEST_ANCHOR_GUARD = """
               AND b.id = :bookingId AND b.client_id IS NULL AND b.guest_phone IS NOT NULL
               AND b.guest_pii_anonymised_at IS NULL""";
    private static final String EXISTS_GUEST_CLOSE = GUEST_ANCHOR_GUARD + ")";

    // B564 — the removal's guest gate + identity in ONE read: the anchor's phone, under the exact guard of the
    // existence check above (empty = the uniform 404). Removal keys the LIST scope, so the salon form checks the
    // anchor salon-wide (a removal never carries masterId).
    private static final String ANCHOR_PHONE_SELECT = "SELECT b.guest_phone FROM bookings b\n";
    static final String ANCHOR_PHONE_SALON = ANCHOR_PHONE_SELECT + SALON_WHERE + GUEST_ANCHOR_GUARD;
    static final String ANCHOR_PHONE_MASTER = ANCHOR_PHONE_SELECT + MASTER_WHERE + GUEST_ANCHOR_GUARD;
    // B546 audit-fix — the own-ratings ?masterId= gate + identity in ONE read: the anchor's phone under the EXACT
    // predicate of EXISTS_GUEST_SALON_MASTER below (anchor salon-wide, then a guest booking of that phone with the
    // master), so the ratings read skips the separate EXISTS without widening who gets a 200.
    static final String ANCHOR_PHONE_SALON_MASTER = "WITH anchor AS (SELECT b.guest_phone FROM bookings b\n"
            + SALON_WHERE + GUEST_ANCHOR_GUARD + ")\n"
            + "SELECT a.guest_phone FROM anchor a WHERE EXISTS (SELECT 1 FROM bookings b\n" + SALON_MASTER_WHERE + """
               AND b.client_id IS NULL AND b.guest_phone = a.guest_phone
               AND b.guest_pii_anonymised_at IS NULL AND b.client_detached_at IS NULL)""";

    static final String EXISTS_USER_SALON = EXISTS_OPEN + SALON_WHERE + EXISTS_USER_CLOSE;
    static final String EXISTS_USER_SALON_MASTER = EXISTS_OPEN + SALON_MASTER_WHERE + EXISTS_USER_CLOSE;
    static final String EXISTS_USER_MASTER = EXISTS_OPEN + MASTER_WHERE + EXISTS_USER_CLOSE;
    static final String EXISTS_GUEST_SALON = EXISTS_OPEN + SALON_WHERE + EXISTS_GUEST_CLOSE;
    // ?masterId= mode mirrors the card (ClientCardSql NARROW_TO_MASTER): the anchor is checked salon-wide, then a guest
    // booking with the anchor's phone must exist with that master; an anchor outside the salon is the uniform 404.
    static final String EXISTS_GUEST_SALON_MASTER = "WITH anchor AS (SELECT b.guest_phone FROM bookings b\n"
            + SALON_WHERE + """
               AND b.id = :bookingId AND b.client_id IS NULL AND b.guest_phone IS NOT NULL
               AND b.guest_pii_anonymised_at IS NULL)
            """ + EXISTS_OPEN + SALON_MASTER_WHERE + """
               AND b.client_id IS NULL AND b.guest_phone = (SELECT guest_phone FROM anchor)
               AND b.guest_pii_anonymised_at IS NULL AND b.client_detached_at IS NULL)""";
    static final String EXISTS_GUEST_MASTER = EXISTS_OPEN + MASTER_WHERE + EXISTS_GUEST_CLOSE;

    static final String PAGE_GUEST_SALON = PAGE_SELECT + SALON_WHERE + FILTER_GUEST + PAGE_TAIL;
    static final String PAGE_GUEST_SALON_MASTER = PAGE_SELECT + SALON_MASTER_WHERE + FILTER_GUEST + PAGE_TAIL;
    static final String PAGE_GUEST_MASTER = PAGE_SELECT + MASTER_WHERE + FILTER_GUEST + PAGE_TAIL;
    static final String COUNT_GUEST_SALON = COUNT_SELECT + SALON_WHERE + FILTER_GUEST;
    static final String COUNT_GUEST_SALON_MASTER = COUNT_SELECT + SALON_MASTER_WHERE + FILTER_GUEST;
    static final String COUNT_GUEST_MASTER = COUNT_SELECT + MASTER_WHERE + FILTER_GUEST;

    // ── B565 (REV-44, REV-D10): every visible non-blank comment about the client, from ANY provider ──
    // Cross-provider on purpose: no scope WHERE here — the caller first passes the card's own gate (EXISTS / anchor
    // phone). Anonymous: the author columns feed only the per-viewer `own` predicate, never a selected column.
    // Non-blank = at least one non-whitespace character (the write path stores the text as sent, so a
    // newline-only comment exists); `\S` instead of `[[:space:]]`, whose colon would parse as a named parameter.

    private static final String COMMENTS_SELECT_OPEN = """
            SELECT cr.id AS reviewId,
                   to_char(b.starts_at AT TIME ZONE 'Europe/Kyiv', 'YYYY-MM') AS visitMonth,
                   cr.rating AS stars, cr.comment AS comment,
            """;
    private static final String OWN_SALON = "       (cr.salon_id IS NOT NULL AND cr.salon_id = :salonId) AS own\n";
    private static final String OWN_INDEPENDENT =
            "       (cr.author_master_id = :masterId AND cr.salon_id IS NULL) AS own\n";
    private static final String OWN_SALON_MASTER = "       (cr.author_master_id = :masterId) AS own\n";

    private static final String COMMENTS_VISIBLE = """
               AND cr.moderation_status = 'VISIBLE' AND cr.comment IS NOT NULL AND cr.comment ~ '\\S'
            """;
    private static final String COMMENTS_USER_WHERE = " WHERE cr.subject_client_id = :userId\n" + COMMENTS_VISIBLE;
    private static final String COMMENTS_GUEST_WHERE = " WHERE cr.subject_guest_phone = :phone\n" + COMMENTS_VISIBLE;
    private static final String COMMENTS_TAIL = """
             ORDER BY cr.created_at DESC, cr.id
             LIMIT :limit OFFSET :offset
            """;

    static final String COMMENTS_USER_SALON =
            COMMENTS_SELECT_OPEN + OWN_SALON + FROM_REVIEWS + COMMENTS_USER_WHERE + COMMENTS_TAIL;
    static final String COMMENTS_USER_INDEPENDENT =
            COMMENTS_SELECT_OPEN + OWN_INDEPENDENT + FROM_REVIEWS + COMMENTS_USER_WHERE + COMMENTS_TAIL;
    static final String COMMENTS_USER_SALON_MASTER =
            COMMENTS_SELECT_OPEN + OWN_SALON_MASTER + FROM_REVIEWS + COMMENTS_USER_WHERE + COMMENTS_TAIL;
    static final String COMMENTS_GUEST_SALON =
            COMMENTS_SELECT_OPEN + OWN_SALON + FROM_REVIEWS + COMMENTS_GUEST_WHERE + COMMENTS_TAIL;
    static final String COMMENTS_GUEST_INDEPENDENT =
            COMMENTS_SELECT_OPEN + OWN_INDEPENDENT + FROM_REVIEWS + COMMENTS_GUEST_WHERE + COMMENTS_TAIL;
    static final String COMMENTS_GUEST_SALON_MASTER =
            COMMENTS_SELECT_OPEN + OWN_SALON_MASTER + FROM_REVIEWS + COMMENTS_GUEST_WHERE + COMMENTS_TAIL;
    // The count reads client_reviews alone: every predicate is a cr column and booking_id is a NOT NULL FK (V128),
    // so the bookings join of the page (needed only for the visit month) cannot change the row count.
    private static final String COUNT_COMMENTS_FROM = "SELECT COUNT(*)\nFROM client_reviews cr\n";
    static final String COUNT_COMMENTS_USER = COUNT_COMMENTS_FROM + COMMENTS_USER_WHERE;
    static final String COUNT_COMMENTS_GUEST = COUNT_COMMENTS_FROM + COMMENTS_GUEST_WHERE;

    static final String COUNT_SALON = COUNT_SELECT + SALON_WHERE + FILTER;
    static final String COUNT_SALON_MASTER = COUNT_SELECT + SALON_MASTER_WHERE + FILTER;
    static final String COUNT_MASTER = COUNT_SELECT + MASTER_WHERE + FILTER;
}
